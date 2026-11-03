#!/usr/bin/env node
// Copies the FILES in Supabase Storage (medicine photos, reports, feedback pictures) from the OLD project to the NEW one, same bucket, same path,
// same file type. A database copy does not bring storage over, and the database rows already point at the right paths, so copying the files with
// the same names is all that is needed for old photos and reports to show again.
//
// SAFE BY DEFAULT: without --apply it only LISTS what is in the old project and what is already in the new one, and changes nothing.
// It never deletes anything, never overwrites a file that already exists in the new project, and refuses to run when old and new are the same project.
//
// Needs Node 20 or newer (nothing to install). Keys are read from environment variables and are never printed:
//   OLD_SUPABASE_URL   https://<old project ref>.supabase.co
//   OLD_SERVICE_KEY    the OLD project's service role (secret) key   (Dashboard > Project Settings > API)
//   NEW_SUPABASE_URL   https://<new project ref>.supabase.co
//   NEW_SERVICE_KEY    the NEW project's service role (secret) key
//   BUCKETS            optional, default "medicine-photos,reports,feedback"
// Put them in your own terminal (for example: export OLD_SERVICE_KEY='...'); do NOT paste them into a chat, a file in the repository or a commit.
//
//   node supabase/maintenance/copy_storage.mjs            # look only
//   node supabase/maintenance/copy_storage.mjs --apply    # copy what is missing
//
// Exit code 0 = nothing left to copy (or copied everything); 1 = some files failed or are still missing.

const args = new Set(process.argv.slice(2));
const APPLY = args.has("--apply");
const need = (name) => { const v = process.env[name]; if (!v) { console.error(`Missing environment variable ${name} (see the top of this file).`); process.exit(2); } return v; };
const OLD = { url: need("OLD_SUPABASE_URL").replace(/\/+$/, ""), key: need("OLD_SERVICE_KEY") };
const NEW = { url: need("NEW_SUPABASE_URL").replace(/\/+$/, ""), key: need("NEW_SERVICE_KEY") };
const BUCKETS = (process.env.BUCKETS || "medicine-photos,reports,feedback").split(",").map((b) => b.trim()).filter(Boolean);
if (OLD.url === NEW.url) { console.error("OLD_SUPABASE_URL and NEW_SUPABASE_URL are the same project: nothing to copy, stopping."); process.exit(2); }

const WORKERS = 4, TRIES = 3, PAGE = 100;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const headers = (side, extra = {}) => ({ apikey: side.key, ...(side.key.startsWith("eyJ") ? { Authorization: `Bearer ${side.key}` } : {}), ...extra });
const encode = (path) => path.split("/").map(encodeURIComponent).join("/");

async function retry(label, fn) {
  let last;
  for (let attempt = 1; attempt <= TRIES; attempt++) {
    try { return await fn(); } catch (e) { last = e; if (e.permanent) break; await sleep(400 * attempt); }
  }
  throw new Error(`${label}: ${last?.message ?? last}`);
}

/** Every file in a bucket, as {path, size, type}. Folders (no id) are walked into. */
async function listBucket(side, bucket) {
  const files = [];
  const walk = async (prefix) => {
    for (let offset = 0; ; offset += PAGE) {
      const items = await retry(`list ${bucket}/${prefix}`, async () => {
        const r = await fetch(`${side.url}/storage/v1/object/list/${bucket}`, {
          method: "POST", headers: headers(side, { "Content-Type": "application/json" }),
          body: JSON.stringify({ prefix, limit: PAGE, offset, sortBy: { column: "name", order: "asc" } }),
        });
        if (!r.ok) { const e = new Error(`HTTP ${r.status} ${(await r.text()).slice(0, 120)}`); e.permanent = r.status === 401 || r.status === 403 || r.status === 404; throw e; }
        return r.json();
      });
      for (const it of items) {
        const full = prefix ? `${prefix}${it.name}` : it.name;
        if (it.id === null || it.id === undefined) await walk(`${full}/`);
        else files.push({ path: full, size: it.metadata?.size ?? null, type: it.metadata?.mimetype ?? "application/octet-stream" });
      }
      if (items.length < PAGE) break;
    }
  };
  await walk("");
  return files;
}

/** Copies one file. Returns "copied" or "already there". Throws on a real failure. */
async function copyOne(bucket, f) {
  const bytes = await retry(`download ${bucket}/${f.path}`, async () => {
    const r = await fetch(`${OLD.url}/storage/v1/object/${bucket}/${encode(f.path)}`, { headers: headers(OLD) });
    if (!r.ok) { const e = new Error(`HTTP ${r.status}`); e.permanent = r.status === 404; throw e; }
    return Buffer.from(await r.arrayBuffer());
  });
  return retry(`upload ${bucket}/${f.path}`, async () => {
    const r = await fetch(`${NEW.url}/storage/v1/object/${bucket}/${encode(f.path)}`, {
      method: "POST", headers: headers(NEW, { "Content-Type": f.type, "x-upsert": "false", "cache-control": "max-age=3600" }), body: bytes,
    });
    if (r.ok) return "copied";
    const text = await r.text();
    if (r.status === 409 || /duplicate|already exists/i.test(text)) return "already there";   // never overwrite
    const e = new Error(`HTTP ${r.status} ${text.slice(0, 120)}`); e.permanent = r.status === 401 || r.status === 403 || r.status === 413; throw e;
  });
}

console.log(`${APPLY ? "COPYING" : "LOOKING ONLY (add --apply to copy)"}  ${new URL(OLD.url).host}  ->  ${new URL(NEW.url).host}`);
let anyMissing = false, anyFailed = false;
for (const bucket of BUCKETS) {
  let oldFiles, newFiles;
  try { [oldFiles, newFiles] = await Promise.all([listBucket(OLD, bucket), listBucket(NEW, bucket)]); }
  catch (e) { console.error(`\n[${bucket}] cannot list: ${e.message}`); anyFailed = true; continue; }
  const have = new Set(newFiles.map((f) => f.path));
  const todo = oldFiles.filter((f) => !have.has(f.path));
  const bytes = todo.reduce((n, f) => n + (f.size ?? 0), 0);
  console.log(`\n[${bucket}] old: ${oldFiles.length} files | new: ${newFiles.length} files | missing in new: ${todo.length} (${(bytes / 1048576).toFixed(1)} MB)`);
  if (todo.length) anyMissing = true;
  if (!APPLY || !todo.length) continue;
  const counts = { copied: 0, "already there": 0, failed: 0 }; const failures = []; let next = 0, done = 0;
  await Promise.all(Array.from({ length: WORKERS }, async () => {
    while (next < todo.length) {
      const f = todo[next++];
      try { counts[await copyOne(bucket, f)]++; } catch (e) { counts.failed++; failures.push(`${f.path}: ${e.message}`); }
      if (++done % 25 === 0 || done === todo.length) process.stdout.write(`  ${done}/${todo.length}\r`);
    }
  }));
  console.log(`  copied ${counts.copied}, already there ${counts["already there"]}, failed ${counts.failed}          `);
  if (failures.length) { anyFailed = true; console.log("  failures (first 10):\n   " + failures.slice(0, 10).join("\n   ")); }
  // Check the result by listing the new bucket again
  const after = new Set((await listBucket(NEW, bucket)).map((f) => f.path));
  const still = oldFiles.filter((f) => !after.has(f.path)).length;
  console.log(`  check: ${still === 0 ? "every old file is now in the new project" : `${still} old files are STILL missing in the new project`}`);
  if (still) anyFailed = true;
}
console.log(anyFailed ? "\nDone with problems (see above)." : APPLY ? "\nDone." : anyMissing ? "\nFiles are missing in the new project. Run again with --apply to copy them." : "\nNothing to copy: the new project already has every file.");
process.exit(anyFailed || (!APPLY && anyMissing) ? 1 : 0);
