import { compressImage } from "./image";
import { supabase } from "./supabase";

/** Reports wallet (migration 0029): a parent's reports, prescriptions and other papers, stored as photos or PDFs. No reading or analysis. */
export const CATEGORIES = ["lab", "prescription", "scan", "discharge", "vaccination", "insurance", "bill", "other"] as const;
export type Category = (typeof CATEGORIES)[number];

export type WalletFile = { id: string; path: string; thumbPath: string | null; mime: string; sizeBytes: number; position: number };
export type WalletDoc = { id: string; recipientId: string; title: string; category: Category; docDate: string | null; doctor: string | null; notes: string | null; createdAt: string; files: WalletFile[] };
export type DocFields = { title: string; category: Category; docDate: string | null; doctor: string | null; notes: string | null };
export type Uploaded = { path: string; thumbPath: string | null; mime: string; sizeBytes: number };

export const BUCKET = "reports";          // private; the app only ever uses short-lived signed links
export const MAX_FILE_BYTES = 15 * 1024 * 1024;
export const MAX_PAGES = 10;
const ALLOWED = ["image/jpeg", "image/png", "image/webp", "application/pdf"];

type Row = {
  id: string; recipient_id: string; title: string; category: string; doc_date: string | null; doctor: string | null; notes: string | null; created_at: string;
  document_files: { id: string; path: string; thumb_path: string | null; mime: string; size_bytes: number; position: number }[];
};
const SELECT = "id, recipient_id, title, category, doc_date, doctor, notes, created_at, document_files(id, path, thumb_path, mime, size_bytes, position)";

const toDoc = (r: Row): WalletDoc => ({
  id: r.id, recipientId: r.recipient_id, title: r.title,
  category: (CATEGORIES as readonly string[]).includes(r.category) ? (r.category as Category) : "other",
  docDate: r.doc_date, doctor: r.doctor, notes: r.notes, createdAt: r.created_at,
  files: [...r.document_files].sort((a, b) => a.position - b.position).map((f) => ({ id: f.id, path: f.path, thumbPath: f.thumb_path, mime: f.mime, sizeBytes: f.size_bytes, position: f.position })),
});

export async function loadDocuments(recipientId: string): Promise<WalletDoc[]> {
  const { data, error } = await supabase.from("documents").select(SELECT).eq("recipient_id", recipientId)
    .order("doc_date", { ascending: false, nullsFirst: false }).order("created_at", { ascending: false }).limit(200);
  if (error) throw error;
  return (data as unknown as Row[]).map(toDoc);
}

export async function loadDocument(id: string): Promise<WalletDoc | null> {
  const { data, error } = await supabase.from("documents").select(SELECT).eq("id", id).maybeSingle();
  if (error) throw error;
  return data ? toDoc(data as unknown as Row) : null;
}

/** Short-lived links for private files, keyed by storage path. A path that cannot be signed is simply missing. */
export async function signedUrls(paths: string[], seconds = 300): Promise<Record<string, string>> {
  const wanted = [...new Set(paths)].filter(Boolean);
  if (!wanted.length) return {};
  const { data } = await supabase.storage.from(BUCKET).createSignedUrls(wanted, seconds);
  const out: Record<string, string> = {};
  for (const item of data ?? []) if (item.path && item.signedUrl) out[item.path] = item.signedUrl;
  return out;
}

const randomName = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, "0")).join("");
const kindOf = (file: File) => file.type || (file.name.toLowerCase().endsWith(".pdf") ? "application/pdf" : "");

export type UploadResult = { ok: true; items: Uploaded[] } | { ok: false; reason: "type" | "size" | "upload" | "auth" };

/** Uploads the chosen photos / PDFs. Photos are shrunk first (and get a small preview); a failure removes whatever was already uploaded. */
export async function uploadFiles(recipientId: string, files: File[], onProgress?: (done: number) => void): Promise<UploadResult> {
  const { data: auth } = await supabase.auth.getSession();
  if (!auth.session) return { ok: false, reason: "auth" };
  const items: Uploaded[] = [];
  const cleanup = async () => { await supabase.storage.from(BUCKET).remove(items.flatMap((i) => [i.path, ...(i.thumbPath ? [i.thumbPath] : [])])); };
  for (const [index, file] of files.entries()) {
    const kind = kindOf(file);
    if (!kind.startsWith("image/") && kind !== "application/pdf") { await cleanup(); return { ok: false, reason: "type" }; }
    const isPdf = kind === "application/pdf";
    const body: Blob = isPdf ? file : await compressImage(file, 2000, 0.85);
    const mime = isPdf ? "application/pdf" : body.type;
    if (!ALLOWED.includes(mime)) { await cleanup(); return { ok: false, reason: "type" }; }   // e.g. a photo format this browser cannot shrink
    if (body.size > MAX_FILE_BYTES) { await cleanup(); return { ok: false, reason: "size" }; }
    const name = randomName();
    const ext = isPdf ? "pdf" : mime === "image/png" ? "png" : mime === "image/webp" ? "webp" : "jpg";
    const path = `${recipientId}/${name}.${ext}`;
    const main = await supabase.storage.from(BUCKET).upload(path, body, { contentType: mime });
    if (main.error) { await cleanup(); return { ok: false, reason: "upload" }; }
    let thumbPath: string | null = null;
    if (!isPdf) {
      try {
        const thumb = await compressImage(file, 360, 0.7);
        const candidate = `${recipientId}/${name}-t.jpg`;
        const up = await supabase.storage.from(BUCKET).upload(candidate, thumb, { contentType: "image/jpeg" });
        if (!up.error) thumbPath = candidate;
      } catch { /* a missing preview is fine: the list shows an icon */ }
    }
    items.push({ path, thumbPath, mime, sizeBytes: body.size });
    onProgress?.(index + 1);
  }
  return { ok: true, items };
}

export type SaveResult = { ok: true; id: string } | { ok: false; reason: "limit" | "pages" | "save" };

const removeObjects = async (items: { path: string; thumbPath: string | null }[]) => {
  const paths = items.flatMap((i) => [i.path, ...(i.thumbPath ? [i.thumbPath] : [])]);
  if (paths.length) await supabase.storage.from(BUCKET).remove(paths);
};

export async function createDocument(recipientId: string, fields: DocFields, items: Uploaded[]): Promise<SaveResult> {
  const { data: doc, error } = await supabase.from("documents")
    .insert({ recipient_id: recipientId, title: fields.title, category: fields.category, doc_date: fields.docDate, doctor: fields.doctor, notes: fields.notes })
    .select("id").single();
  if (error || !doc) { await removeObjects(items); return { ok: false, reason: (error as { hint?: string } | null)?.hint === "too_many_documents" ? "limit" : "save" }; }
  const { error: filesError } = await supabase.from("document_files")
    .insert(items.map((i, position) => ({ document_id: doc.id, path: i.path, thumb_path: i.thumbPath, mime: i.mime, size_bytes: i.sizeBytes, position })));
  if (filesError) { await supabase.from("documents").delete().eq("id", doc.id); await removeObjects(items); return { ok: false, reason: "save" }; }
  return { ok: true, id: doc.id };
}

export async function addPages(documentId: string, existing: number, items: Uploaded[]): Promise<SaveResult> {
  if (existing + items.length > MAX_PAGES) { await removeObjects(items); return { ok: false, reason: "pages" }; }
  const { error } = await supabase.from("document_files")
    .insert(items.map((i, k) => ({ document_id: documentId, path: i.path, thumb_path: i.thumbPath, mime: i.mime, size_bytes: i.sizeBytes, position: existing + k })));
  if (error) { await removeObjects(items); return { ok: false, reason: (error as { hint?: string }).hint === "too_many_files" ? "pages" : "save" }; }
  return { ok: true, id: documentId };
}

export async function updateDocument(id: string, fields: DocFields): Promise<boolean> {
  const { data, error } = await supabase.from("documents")
    .update({ title: fields.title, category: fields.category, doc_date: fields.docDate, doctor: fields.doctor, notes: fields.notes }).eq("id", id).select("id");
  return !error && !!data?.length;
}

export async function removePage(file: WalletFile): Promise<boolean> {
  const { data, error } = await supabase.from("document_files").delete().eq("id", file.id).select("id");
  if (error || !data?.length) return false;
  await removeObjects([file]);
  return true;
}

/** Deletes the record first (its pages go with it), then the stored files. */
export async function deleteDocument(doc: WalletDoc): Promise<boolean> {
  const { data, error } = await supabase.from("documents").delete().eq("id", doc.id).select("id");
  if (error || !data?.length) return false;
  await removeObjects(doc.files);
  return true;
}
