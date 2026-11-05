package org.smartgurukul.chhaon.wallet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.db.UserScopedSql;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * The Reports wallet (migration 0029) through the backend: the records run AS the signed-in caregiver ({@link UserScopedSql}), so
 * the database's own rules still decide everything (Row Level Security = family circle only, the author stamp, the folder check
 * on every file path, the plan's number of reports, 10 pages, 300 MB). Files go through {@link WalletStorage} with the
 * service-role key, and only for paths inside the person's own folder that this backend chose or that the family can see.
 *
 * <p>Why (docs/TODO.md, "Reports tab and the login token"): the web app read the wallet straight from Supabase with the login token our backend mints.
 * Supabase accepts that token only when it was signed with the project's own (legacy) JWT secret, so any difference in that secret
 * (the local demo) or a project that no longer accepts it gave "Could not load the reports". Now nothing in the wallet depends on it.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    public static final List<String> CATEGORIES = List.of("lab", "prescription", "scan", "discharge", "vaccination", "insurance", "bill", "other");
    static final int MAX_PAGES = 10;
    static final int LINK_SECONDS = 300;
    static final int MAX_LINKS = 200;
    private static final Pattern HINT = Pattern.compile("(?mi)^\\s*hint:\\s*(\\S+)\\s*$");
    private static final Set<String> HINTS = Set.of("plan_documents", "too_many_documents", "too_many_files", "storage_full", "bad_path");
    private static final Map<String, String> EXT = Map.of("pdf", "pdf", "jpg", "jpg", "jpeg", "jpg", "png", "png", "webp", "webp");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record FileDto(String id, String path, String thumbPath, String mime, int sizeBytes, int position) {}

    public record DocumentDto(String id, String recipientId, String title, String category, LocalDate docDate, String doctor,
                              String notes, OffsetDateTime createdAt, List<FileDto> files) {}

    public record Fields(String title, String category, LocalDate docDate, String doctor, String notes) {}

    public record NewFile(String path, String thumbPath, String mime, Integer sizeBytes) {}

    public record CreateRequest(String title, String category, LocalDate docDate, String doctor, String notes, List<NewFile> files) {}

    public record PagesRequest(List<NewFile> files) {}

    public record UploadRequest(String kind) {}

    /** Upload addresses are relative to {@code <Supabase URL>/storage/v1}; thumb* are null for a PDF. */
    public record UploadSlot(String path, String uploadUrl, String thumbPath, String thumbUploadUrl) {}

    public record PathsRequest(List<String> paths) {}

    public record Usage(int used, Integer max) {}

    public record Saved(String id) {}

    private final UserScopedSql db;
    private final WalletStorage storage;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public WalletService(UserScopedSql db, WalletStorage storage) {
        this.db = db;
        this.storage = storage;
    }

    // ---------------------------------------------------------------- read

    private static final String SELECT = """
            select d.id, d.recipient_id, d.title, d.category, d.doc_date, d.doctor, d.notes, d.created_at,
                   coalesce((select jsonb_agg(jsonb_build_object('id', f.id, 'path', f.path, 'thumbPath', f.thumb_path, 'mime', f.mime,
                                                                 'sizeBytes', f.size_bytes, 'position', f.position)
                                              order by f.position, f.created_at)
                             from document_files f where f.document_id = d.id), '[]'::jsonb)::text as files
            from documents d
            """;

    public List<DocumentDto> list(UUID userId, UUID recipientId) {
        requireRecipient(userId, recipientId);
        return db.asUser(userId, jdbc -> jdbc.query(SELECT + " where d.recipient_id = ? order by d.doc_date desc nulls last, d.created_at desc limit 200",
                (rs, i) -> toDto(rs), recipientId));
    }

    public DocumentDto get(UUID userId, UUID documentId) {
        List<DocumentDto> rows = db.asUser(userId, jdbc -> jdbc.query(SELECT + " where d.id = ?", (rs, i) -> toDto(rs), documentId));
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Report not found");
        return rows.get(0);
    }

    public Usage usage(UUID userId, UUID recipientId) {
        requireRecipient(userId, recipientId);
        String raw = db.asUser(userId, jdbc -> jdbc.queryForObject("select public.wallet_usage(?)::text", String.class, recipientId));
        try {
            Map<String, Object> m = json.readValue(raw, new TypeReference<>() {});
            Object max = m.get("max");
            return new Usage(((Number) m.get("used")).intValue(), max == null ? null : ((Number) max).intValue());
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_GATEWAY, "usage_unreadable");
        }
    }

    private DocumentDto toDto(java.sql.ResultSet rs) throws SQLException {
        List<FileDto> files;
        try {
            files = json.readValue(rs.getString("files"), new TypeReference<List<FileDto>>() {});
        } catch (Exception e) {
            throw new SQLException("files unreadable", e);
        }
        Date d = rs.getDate("doc_date");
        return new DocumentDto(rs.getObject("id").toString(), rs.getObject("recipient_id").toString(), rs.getString("title"),
                rs.getString("category"), d == null ? null : d.toLocalDate(), rs.getString("doctor"), rs.getString("notes"),
                rs.getObject("created_at", OffsetDateTime.class), files);
    }

    // ---------------------------------------------------------------- files

    /** A fresh path in the person's folder (chosen here, never by the browser) and a one-time upload address for it. */
    public UploadSlot uploadSlot(UUID userId, UUID recipientId, UploadRequest in) {
        requireRecipient(userId, recipientId);
        requireStorage();
        String ext = in == null || in.kind() == null ? null : EXT.get(in.kind().trim().toLowerCase());
        if (ext == null) throw new ResponseStatusException(BAD_REQUEST, "kind_invalid");
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String name = HexFormat.of().formatHex(bytes);
        String path = recipientId + "/" + name + "." + ext;
        String thumb = "pdf".equals(ext) ? null : recipientId + "/" + name + "-t.jpg";
        try {
            return new UploadSlot(path, storage.uploadUrl(path), thumb, thumb == null ? null : storage.uploadUrl(thumb));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("wallet upload address not made ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(BAD_GATEWAY, "storage_failed");
        }
    }

    /** Short-lived read links, only for files of this person's reports that the family can see. */
    public Map<String, String> links(UUID userId, UUID recipientId, PathsRequest in) {
        requireRecipient(userId, recipientId);
        requireStorage();
        List<String> wanted = in == null || in.paths() == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(in.paths().stream().filter(p -> p != null && !p.isBlank()).toList()));
        if (wanted.size() > MAX_LINKS) throw new ResponseStatusException(BAD_REQUEST, "too_many_paths");
        if (wanted.isEmpty()) return Map.of();
        String[] arr = wanted.toArray(String[]::new);
        List<String> known = db.asUser(userId, jdbc -> jdbc.queryForList("""
                        select p from (select f.path p from document_files f join documents d on d.id = f.document_id where d.recipient_id = ?
                                       union select f.thumb_path from document_files f join documents d on d.id = f.document_id where d.recipient_id = ?) x
                        where p = any(?)
                        """, String.class, recipientId, recipientId, arr));
        if (known.isEmpty()) return Map.of();
        try {
            return storage.viewUrls(known, LINK_SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("wallet links not made ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(BAD_GATEWAY, "storage_failed");
        }
    }

    /** Uploaded files that were never saved into a report (the family cancelled, or the save failed) are removed. */
    public void discard(UUID userId, UUID recipientId, PathsRequest in) {
        requireRecipient(userId, recipientId);
        List<String> paths = in == null || in.paths() == null ? List.of() : in.paths();
        if (paths.size() > 2 * MAX_PAGES * 2) throw new ResponseStatusException(BAD_REQUEST, "too_many_paths");
        storage.remove(unreferenced(recipientId, paths));
    }

    /** Only well-formed paths in this person's own folder that no report row (or old report-reading row) points to. */
    private List<String> unreferenced(UUID recipientId, List<String> paths) {
        Pattern own = Pattern.compile("^" + recipientId + "/[A-Za-z0-9._-]{1,120}$");
        List<String> ok = paths.stream().filter(p -> p != null && own.matcher(p).matches() && !p.contains("..")).distinct().toList();
        if (ok.isEmpty()) return List.of();
        String[] arr = ok.toArray(String[]::new);
        List<String> used = db.asService(jdbc -> jdbc.queryForList("""
                select p from (select path p from document_files union select thumb_path from document_files
                               union select file_url from reports) x where p = any(?)
                """, String.class, (Object) arr));
        return ok.stream().filter(p -> !used.contains(p)).toList();
    }

    // ---------------------------------------------------------------- write

    public Saved create(UUID userId, UUID recipientId, CreateRequest in) {
        requireRecipient(userId, recipientId);
        if (in == null) throw new ResponseStatusException(BAD_REQUEST, "invalid");
        List<NewFile> files = in.files() == null ? List.of() : in.files();
        if (files.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "files_required");
        if (files.size() > MAX_PAGES) {
            discardQuietly(recipientId, files);
            throw new ResponseStatusException(CONFLICT, "too_many_files");
        }
        Fields f = clean(new Fields(in.title(), in.category(), in.docDate(), in.doctor(), in.notes()));
        try {
            UUID id = db.asUser(userId, jdbc -> {
                UUID docId = jdbc.queryForObject("""
                                insert into documents (recipient_id, title, category, doc_date, doctor, notes)
                                values (?, ?, ?, ?, ?, ?) returning id
                                """, UUID.class, recipientId, f.title(), f.category(),
                        f.docDate() == null ? null : Date.valueOf(f.docDate()), f.doctor(), f.notes());
                insertFiles(jdbc, docId, 0, files);
                return docId;
            });
            return new Saved(id.toString());
        } catch (DataAccessException e) {
            discardQuietly(recipientId, files);
            throw translate(e);
        }
    }

    public Saved addPages(UUID userId, UUID documentId, PagesRequest in) {
        DocumentDto doc = get(userId, documentId);
        UUID recipientId = UUID.fromString(doc.recipientId());
        List<NewFile> files = in == null || in.files() == null ? List.of() : in.files();
        if (files.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "files_required");
        if (doc.files().size() + files.size() > MAX_PAGES) {
            discardQuietly(recipientId, files);
            throw new ResponseStatusException(CONFLICT, "too_many_files");
        }
        int start = doc.files().stream().mapToInt(FileDto::position).max().orElse(-1) + 1;
        try {
            db.asUser(userId, jdbc -> {
                insertFiles(jdbc, documentId, start, files);
                return null;
            });
            return new Saved(documentId.toString());
        } catch (DataAccessException e) {
            discardQuietly(recipientId, files);
            throw translate(e);
        }
    }

    public DocumentDto update(UUID userId, UUID documentId, Fields in) {
        Fields f = clean(in);
        int n;
        try {
            n = db.asUser(userId, jdbc -> jdbc.update("update documents set title = ?, category = ?, doc_date = ?, doctor = ?, notes = ? where id = ?",
                    f.title(), f.category(), f.docDate() == null ? null : Date.valueOf(f.docDate()), f.doctor(), f.notes(), documentId));
        } catch (DataAccessException e) {
            throw translate(e);
        }
        if (n != 1) throw new ResponseStatusException(NOT_FOUND, "Report not found");
        return get(userId, documentId);
    }

    /** Removes one page (never the last one: delete the report instead), then its stored files. */
    public void removePage(UUID userId, UUID documentId, UUID fileId) {
        DocumentDto doc = get(userId, documentId);
        FileDto file = doc.files().stream().filter(x -> x.id().equals(fileId.toString())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Page not found"));
        if (doc.files().size() <= 1) throw new ResponseStatusException(CONFLICT, "last_page");
        int n = db.asUser(userId, jdbc -> jdbc.update("delete from document_files where id = ? and document_id = ?", fileId, documentId));
        if (n != 1) throw new ResponseStatusException(NOT_FOUND, "Page not found");
        storage.remove(paths(List.of(file)));
    }

    /** Deletes the record first (its pages go with it), then the stored files. */
    public void delete(UUID userId, UUID documentId) {
        DocumentDto doc = get(userId, documentId);
        int n = db.asUser(userId, jdbc -> jdbc.update("delete from documents where id = ?", documentId));
        if (n != 1) throw new ResponseStatusException(NOT_FOUND, "Report not found");
        storage.remove(paths(doc.files()));
    }

    private static List<String> paths(List<FileDto> files) {
        List<String> out = new ArrayList<>();
        for (FileDto f : files) {
            out.add(f.path());
            if (f.thumbPath() != null) out.add(f.thumbPath());
        }
        return out;
    }

    private void insertFiles(JdbcTemplate jdbc, UUID documentId, int start, List<NewFile> files) {
        int position = start;
        for (NewFile f : files) {
            if (f == null || f.path() == null || f.mime() == null || f.sizeBytes() == null) throw new ResponseStatusException(BAD_REQUEST, "file_invalid");
            jdbc.update("insert into document_files (document_id, path, thumb_path, mime, size_bytes, position) values (?, ?, ?, ?, ?, ?)",
                    documentId, f.path(), f.thumbPath(), f.mime(), f.sizeBytes(), position++);
        }
    }

    private void discardQuietly(UUID recipientId, List<NewFile> files) {
        List<String> all = new ArrayList<>();
        for (NewFile f : files) {
            if (f == null) continue;
            if (f.path() != null) all.add(f.path());
            if (f.thumbPath() != null) all.add(f.thumbPath());
        }
        try {
            storage.remove(unreferenced(recipientId, all));
        } catch (Exception e) {
            log.warn("wallet files not cleaned up ({})", e.getClass().getSimpleName());
        }
    }

    private static Fields clean(Fields in) {
        if (in == null) throw new ResponseStatusException(BAD_REQUEST, "invalid");
        String title = in.title() == null ? "" : in.title().trim();
        if (title.isEmpty() || title.length() > 120) throw new ResponseStatusException(BAD_REQUEST, "title_invalid");
        String category = in.category() == null ? "other" : in.category();
        if (!CATEGORIES.contains(category)) throw new ResponseStatusException(BAD_REQUEST, "category_invalid");
        String doctor = in.doctor() == null || in.doctor().isBlank() ? null : in.doctor().trim();
        String notes = in.notes() == null || in.notes().isBlank() ? null : in.notes().trim();
        return new Fields(title, category, in.docDate(), doctor, notes);
    }

    // ---------------------------------------------------------------- helpers

    /** 404 unless this user can see the person (the same rule as the database's Row Level Security). */
    private void requireRecipient(UUID userId, UUID recipientId) {
        Boolean ok = db.asUser(userId, jdbc -> jdbc.queryForObject("select public.can_access_recipient(?)", Boolean.class, recipientId));
        if (!Boolean.TRUE.equals(ok)) throw new ResponseStatusException(NOT_FOUND, "Profile not found");
    }

    private void requireStorage() {
        if (!storage.isConfigured()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "storage_off");
    }

    /** The database's limits come back as their hint (plan_documents, too_many_documents, too_many_files, storage_full, bad_path). */
    private static ResponseStatusException translate(DataAccessException e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        Matcher m = HINT.matcher(String.valueOf(t.getMessage()));
        if (m.find() && HINTS.contains(m.group(1))) return new ResponseStatusException(CONFLICT, m.group(1));
        log.warn("wallet save refused ({})", e.getClass().getSimpleName());
        return new ResponseStatusException(BAD_REQUEST, "invalid");
    }
}
