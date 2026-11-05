import { compressImage } from "./image";
import { supabase } from "./supabase";
import { authToken } from "./auth";
import { apiFetch, ApiError } from "./http";
import { resolveLocalUrl } from "./hosts";

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

/*
 * Everything goes through our Java API (docs/TODO.md, "Reports tab and the login token"). The records are read and written by the backend AS the signed-in
 * caregiver, so the database's own rules still apply; files go to the private bucket through one-time signed addresses the backend
 * makes. Nothing here needs Supabase to accept our login token (that was why the Reports tab said "Could not load the reports").
 * Fallback: a backend whose storage is not configured answers 503 "storage_off"; then files use the direct Storage calls as before.
 */
type FileDto = { id: string; path: string; thumbPath: string | null; mime: string; sizeBytes: number; position: number };
type DocDto = { id: string; recipientId: string; title: string; category: string; docDate: string | null; doctor: string | null; notes: string | null; createdAt: string; files: FileDto[] };

const toDoc = (r: DocDto): WalletDoc => ({
  id: r.id, recipientId: r.recipientId, title: r.title,
  category: (CATEGORIES as readonly string[]).includes(r.category) ? (r.category as Category) : "other",
  docDate: r.docDate, doctor: r.doctor, notes: r.notes, createdAt: r.createdAt,
  files: [...(r.files ?? [])].sort((a, b) => a.position - b.position),
});

export async function loadDocuments(recipientId: string): Promise<WalletDoc[]> {
  return (await apiFetch<DocDto[]>(`/api/v1/recipients/${recipientId}/documents`)).map(toDoc);
}

export async function loadDocument(id: string): Promise<WalletDoc | null> {
  try {
    return toDoc(await apiFetch<DocDto>(`/api/v1/documents/${id}`));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

/** Storage addresses from the backend are relative to <Supabase URL>/storage/v1: this app's own Supabase address goes in front. */
const storageUrl = (relative: string) => `${resolveLocalUrl(import.meta.env.VITE_SUPABASE_URL)}/storage/v1${relative}`;
const storageOff = (e: unknown) => e instanceof ApiError && e.status === 503 && /storage_off/.test(e.message);

/** Short-lived links to a report's files (thumbnails or pages), keyed by storage path. A path that cannot be signed is missing. */
export async function walletLinks(recipientId: string, paths: string[]): Promise<Record<string, string>> {
  const wanted = [...new Set(paths)].filter(Boolean);
  if (!wanted.length) return {};
  try {
    const links = await apiFetch<Record<string, string>>(`/api/v1/recipients/${recipientId}/documents/links`, { method: "POST", body: { paths: wanted } });
    return Object.fromEntries(Object.entries(links).map(([path, url]) => [path, storageUrl(url)]));
  } catch (e) {
    if (storageOff(e)) return signedUrls(wanted);
    throw e;
  }
}

/** Short-lived links for private files, keyed by storage path. A path that cannot be signed is simply missing. */
export async function signedUrls(paths: string[], seconds = 300, bucket = BUCKET): Promise<Record<string, string>> {
  const wanted = [...new Set(paths)].filter(Boolean);
  if (!wanted.length) return {};
  const { data } = await supabase.storage.from(bucket).createSignedUrls(wanted, seconds);
  const out: Record<string, string> = {};
  for (const item of data ?? []) if (item.path && item.signedUrl) out[item.path] = item.signedUrl;
  return out;
}

export const randomName =() => Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, "0")).join("");
const BY_EXTENSION: Record<string, string> = { pdf: "application/pdf", jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", webp: "image/webp", heic: "image/heic", heif: "image/heif" };
/** The file's type; some phones and browsers leave it empty or say "octet-stream", so the extension is the fallback. */
export const kindOf =(file: File) => {
  if (file.type && file.type !== "application/octet-stream") return file.type;
  return BY_EXTENSION[file.name.toLowerCase().split(".").pop() ?? ""] ?? "";
};

export type UploadResult = { ok: true; items: Uploaded[] } | { ok: false; reason: "type" | "heic" | "size" | "upload" | "auth" };

/** Uploads the chosen photos / PDFs. Photos are shrunk first (and get a small preview); a failure removes whatever was already uploaded. */
/** PUTs one file to a one-time signed upload address (no login token needed: the address itself is the permission). */
async function putSigned(relative: string, body: Blob, contentType: string): Promise<boolean> {
  try {
    const key = String(import.meta.env.VITE_SUPABASE_ANON_KEY);
    const response = await fetch(storageUrl(relative), { method: "PUT", body, headers: { apikey: key, "Content-Type": contentType, "x-upsert": "false" } });
    return response.ok;
  } catch {
    return false;
  }
}

type Slot = { path: string; uploadUrl: string; thumbPath: string | null; thumbUploadUrl: string | null };

/** Where the backend wants this file (null = backend storage is off: upload directly as before). Throws on any other failure. */
async function uploadSlot(recipientId: string, kind: string): Promise<Slot | null> {
  try {
    return await apiFetch<Slot>(`/api/v1/recipients/${recipientId}/documents/uploads`, { method: "POST", body: { kind } });
  } catch (e) {
    if (storageOff(e)) return null;
    throw e;
  }
}

export async function uploadFiles(recipientId: string, files: File[], onProgress?: (done: number) => void): Promise<UploadResult> {
  if (!authToken()) return { ok: false, reason: "auth" };
  const items: Uploaded[] = [];
  const cleanup = async () => { await discardUploads(recipientId, items); };
  for (const [index, file] of files.entries()) {
    const kind = kindOf(file);
    if (file.size === 0 || (!kind.startsWith("image/") && kind !== "application/pdf")) { await cleanup(); return { ok: false, reason: "type" }; }
    const isPdf = kind === "application/pdf";
    // compressImage only touches files whose own type says "image/*": give it the type we worked out (some phones leave it empty)
    const typed = file.type === kind ? file : new File([file], file.name, { type: kind });
    const body: Blob = isPdf ? file : await compressImage(typed, 2000, 0.85);
    const mime = isPdf ? "application/pdf" : body.type;
    if (!ALLOWED.includes(mime)) { await cleanup(); return { ok: false, reason: /hei[cf]/i.test(`${mime} ${kind} ${file.name}`) ? "heic" : "type" }; }   // a photo format this browser cannot shrink (HEIC from some phones)
    if (body.size > MAX_FILE_BYTES) { await cleanup(); return { ok: false, reason: "size" }; }
    const ext = isPdf ? "pdf" : mime === "image/png" ? "png" : mime === "image/webp" ? "webp" : "jpg";
    let slot: Slot | null;
    try { slot = await uploadSlot(recipientId, ext); } catch (e) {
      await cleanup();
      return { ok: false, reason: e instanceof ApiError && e.status === 401 ? "auth" : "upload" };
    }
    const name = randomName();
    const path = slot?.path ?? `${recipientId}/${name}.${ext}`;
    const saved = slot ? await putSigned(slot.uploadUrl, body, mime) : !(await supabase.storage.from(BUCKET).upload(path, body, { contentType: mime })).error;
    if (!saved) { await cleanup(); return { ok: false, reason: "upload" }; }
    let thumbPath: string | null = null;
    if (!isPdf) {
      try {
        const thumb = await compressImage(typed, 360, 0.7);
        const candidate = slot ? slot.thumbPath : `${recipientId}/${name}-t.jpg`;
        const up = !candidate ? false : slot?.thumbUploadUrl ? await putSigned(slot.thumbUploadUrl, thumb, "image/jpeg")
          : !(await supabase.storage.from(BUCKET).upload(candidate, thumb, { contentType: "image/jpeg" })).error;
        if (up) thumbPath = candidate;
      } catch { /* a missing preview is fine: the list shows an icon */ }
    }
    items.push({ path, thumbPath, mime, sizeBytes: body.size });
    onProgress?.(index + 1);
  }
  return { ok: true, items };
}

export type SaveResult = { ok: true; id: string } | { ok: false; reason: "plan" | "limit" | "storage" | "pages" | "save" };

/** Uploaded files that were not saved into a report: the backend removes them (only unsaved files in this person's folder). */
async function discardUploads(recipientId: string, items: { path: string; thumbPath: string | null }[]) {
  const paths = items.flatMap((i) => [i.path, ...(i.thumbPath ? [i.thumbPath] : [])]);
  if (!paths.length) return;
  try { await apiFetch<void>(`/api/v1/recipients/${recipientId}/documents/discard`, { method: "POST", body: { paths } }); }
  catch { /* a file left behind costs a little space, never data */ }
}

/** The database's refusal (its hint, sent back by the backend as the reason) in the words the screen uses. */
const saveReason = (e: unknown): Exclude<SaveResult, { ok: true }>["reason"] => {
  const reason = e instanceof ApiError ? e.message : "";
  return reason === "plan_documents" ? "plan" : reason === "too_many_documents" ? "limit" : reason === "too_many_files" ? "pages"
    : reason === "storage_full" ? "storage" : "save";
};
const files = (items: Uploaded[]) => items.map((i) => ({ path: i.path, thumbPath: i.thumbPath, mime: i.mime, sizeBytes: i.sizeBytes }));

/** Saves the report with its pages in one step; on a refusal the backend also removes the uploaded files. */
export async function createDocument(recipientId: string, fields: DocFields, items: Uploaded[]): Promise<SaveResult> {
  try {
    const saved = await apiFetch<{ id: string }>(`/api/v1/recipients/${recipientId}/documents`, { method: "POST", body: { ...fields, files: files(items) } });
    return { ok: true, id: saved.id };
  } catch (e) {
    return { ok: false, reason: saveReason(e) };
  }
}

export async function addPages(documentId: string, existing: number, items: Uploaded[], recipientId?: string): Promise<SaveResult> {
  if (existing + items.length > MAX_PAGES) { if (recipientId) await discardUploads(recipientId, items); return { ok: false, reason: "pages" }; }
  try {
    await apiFetch<{ id: string }>(`/api/v1/documents/${documentId}/files`, { method: "POST", body: { files: files(items) } });
    return { ok: true, id: documentId };
  } catch (e) {
    return { ok: false, reason: saveReason(e) };
  }
}

export async function updateDocument(id: string, fields: DocFields): Promise<boolean> {
  try { await apiFetch(`/api/v1/documents/${id}`, { method: "PUT", body: fields }); return true; } catch { return false; }
}

/** Removes one page; the backend deletes its stored files. */
export async function removePage(documentId: string, file: WalletFile): Promise<boolean> {
  try { await apiFetch<void>(`/api/v1/documents/${documentId}/files/${file.id}`, { method: "DELETE" }); return true; } catch { return false; }
}

/** Deletes the record first (its pages go with it), then the backend deletes the stored files. */
export async function deleteDocument(doc: WalletDoc): Promise<boolean> {
  try { await apiFetch<void>(`/api/v1/documents/${doc.id}`, { method: "DELETE" }); return true; } catch { return false; }
}

/** How many reports this parent has and how many the plan allows (max null = no plan limit). */
export async function loadWalletUsage(recipientId: string): Promise<{ used: number; max: number | null } | null> {
  try { return await apiFetch<{ used: number; max: number | null }>(`/api/v1/recipients/${recipientId}/documents/usage`); } catch { return null; }
}
