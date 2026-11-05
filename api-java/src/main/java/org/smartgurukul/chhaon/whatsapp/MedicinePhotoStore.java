package org.smartgurukul.chhaon.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads a medicine's photo from the PRIVATE Supabase Storage bucket {@code medicine-photos} so the dose reminder can carry it
 * (read-only: one GET per photo through the Storage REST API with the service-role key). The photo is made small enough for
 * WhatsApp (JPEG, at most {@link #MAX_BYTES}).
 *
 * Never throws: any problem (switched off, no photo, a path outside the person's folder, 404, too big, not an image, timeout)
 * gives an empty result, and the reminder goes out as plain text. A reminder is never lost because of a photo.
 *
 * Config: app.storage.supabase-url (SUPABASE_URL) + app.storage.service-role-key (SUPABASE_SERVICE_ROLE_KEY). Either blank =
 * photos off. The key is only ever sent to that Supabase address; it is never logged.
 */
@Service
public class MedicinePhotoStore {

    private static final Logger log = LoggerFactory.getLogger(MedicinePhotoStore.class);

    public static final String BUCKET = "medicine-photos";
    /** The photo sent on WhatsApp is at most this size. */
    public static final int MAX_BYTES = 1024 * 1024;
    /** A file bigger than the bucket's own limit (5 MB) is not read at all. */
    static final int MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024;
    /** Refuse images with more pixels than this before decoding (a tiny file can unpack into gigabytes). */
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int[] SIDES = {1280, 960, 640};
    private static final float[] QUALITIES = {0.8f, 0.65f};

    private final String baseUrl;
    private final String serviceKey;
    private final boolean configured;
    private final HttpClient http;

    public MedicinePhotoStore(@Value("${app.storage.supabase-url:}") String supabaseUrl,
                              @Value("${app.storage.service-role-key:}") String serviceKey) {
        String u = supabaseUrl == null ? "" : supabaseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.baseUrl = u;
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        boolean safeAddress = u.startsWith("https://")
                || u.matches("http://(localhost|127\\.0\\.0\\.1|host\\.docker\\.internal)(:[0-9]+)?");   // the key never travels unencrypted
        this.configured = !u.isEmpty() && !this.serviceKey.isEmpty() && safeAddress;
        this.http = configured
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
                : null;
        if (!configured) {
            log.info(!u.isEmpty() && !this.serviceKey.isEmpty()
                    ? "medicine photos in reminders: off (SUPABASE_URL must start with https://), reminders are sent as text"
                    : "medicine photos in reminders: off (storage not configured), reminders are sent as text");
        }
    }

    /** true when the Supabase address (https) and the key are both set. */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * The photo of a medicine of this person, ready for WhatsApp (JPEG, at most 1 MB), or empty when there is none or anything
     * goes wrong. Never throws.
     */
    public Optional<byte[]> reminderJpeg(UUID recipientId, String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        if (!isConfigured()) return Optional.empty();
        if (!allowedPath(recipientId, path)) {
            log.warn("medicine photo not used: the path is not in this person's folder");
            return Optional.empty();
        }
        try {
            byte[] raw = download(path);
            if (raw == null) {
                log.info("medicine photo not used: not found in storage");
                return Optional.empty();
            }
            byte[] small = shrinkToJpeg(raw, MAX_BYTES);
            if (small == null) log.info("medicine photo not used: not a readable image or too big ({} bytes)", raw.length);
            return Optional.ofNullable(small);
        } catch (Exception | LinkageError e) {
            log.warn("medicine photo not used: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Only a file inside this person's own folder ("recipientId/...") is read: the path is typed by a caregiver and read with
     * elevated rights (same rule as the Python reference).
     */
    static boolean allowedPath(UUID recipientId, String path) {
        if (recipientId == null || path == null) return false;
        if (!path.startsWith(recipientId + "/")) return false;
        if (path.contains("..") || path.contains("\\") || path.contains("?") || path.contains("#") || path.contains("//")) return false;
        return path.length() <= 300;
    }

    /**
     * The raw file, or null when Storage says it is not there (400/404). Throws on anything else (the caller turns that into
     * "no photo"). Protected so tests can put a fake storage in its place: tests never call the real Supabase.
     */
    protected byte[] download(String path) throws IOException, InterruptedException {
        String encoded = Arrays.stream(path.split("/"))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/storage/v1/object/" + BUCKET + "/" + encoded))
                .timeout(Duration.ofSeconds(15))
                .header("apikey", serviceKey)
                .header("Authorization", "Bearer " + serviceKey)
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            int status = resp.statusCode();
            if (status == 404 || status == 400) return null;
            if (status != 200) throw new IOException("storage_http_" + status);
            byte[] body = in.readNBytes(MAX_DOWNLOAD_BYTES + 1);
            if (body.length > MAX_DOWNLOAD_BYTES) throw new IOException("photo_too_big");
            return body;
        }
    }

    /**
     * A JPEG of at most maxBytes: a JPEG already small enough is kept as it is (the app already shrinks photos to 1280 px JPEG);
     * anything else is decoded, scaled down and saved again as JPEG. null when it is not a readable image or cannot get small enough.
     */
    public static byte[] shrinkToJpeg(byte[] raw, int maxBytes) {
        if (raw == null || raw.length == 0) return null;
        if (raw.length <= maxBytes && isJpeg(raw)) return raw;
        try {
            BufferedImage img = readBounded(raw);
            if (img == null) return null;
            for (int side : SIDES) {
                BufferedImage scaled = scale(img, side);
                for (float q : QUALITIES) {
                    byte[] out = writeJpeg(scaled, q);
                    if (out != null && out.length <= maxBytes) return out;
                }
            }
            return null;
        } catch (Exception | OutOfMemoryError | LinkageError e) {
            return null;
        }
    }

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static BufferedImage readBounded(byte[] raw) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS) return null;
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scale(BufferedImage img, int maxSide) {
        int w = img.getWidth(), h = img.getHeight();
        double f = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * f)), nh = Math.max(1, (int) Math.round(h * f));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);   // transparent PNG areas become white, not black
            g.fillRect(0, 0, nw, nh);
            g.drawImage(img, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] writeJpeg(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    @Override
    public String toString() {
        return "MedicinePhotoStore[" + (isConfigured() ? "on" : "off") + "]";   // never the key
    }
}
