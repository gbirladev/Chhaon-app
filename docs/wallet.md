# Reports wallet (migration 0029)

The Reports tab is now a **wallet**: it keeps a parent's reports, prescriptions, scans and other papers (photos or PDFs) in one place for the whole family circle. No reading or analysis. The older AI report reading stays switched off (`VITE_REPORTS_ENABLED=false`); if that flag is turned on, the old screen replaces the wallet.

## What it does
- **Add:** take a photo or choose photos / a PDF (several pages per report), then give it a name, a type, the date on the paper, an optional doctor or hospital and notes.
- **Find:** search by name or doctor, filter by type (Lab report, Prescription, Scan or X-ray, Discharge summary, Vaccination, Insurance, Bill, Other), thumbnails in the list.
- **View:** pages open full size; PDFs open or download. Edit details, add or remove pages, delete (confirmation first).
- Works in English and Hindi.

## Limits and safety
- 10 pages per report, 200 reports per parent, 15 MB per file. Photos are shrunk (about 2000 px) before upload and get a small preview.
- Files are in the private `reports` bucket at `<parent id>/<name>`; the app only uses short-lived (5 minute) links. Only the family circle can read or write them (database rules, also for direct writes).
- A file path must be inside that parent's own folder (no `..`, no other parent's folder); only JPEG, PNG, WebP and PDF are accepted.
- Deleting a report removes its files from storage. Deleting a parent or an account purges the whole folder (existing behaviour). "Download my data" now includes the `documents` and `document_files` records (not the files themselves).

## Not built (ideas)
- Plan limits for the wallet (today the same for every plan), sharing a report with a doctor, a combined "all pages as one PDF" download, reading values from a report (the old AI code), reminders for old reports.

Tests: `supabase/tests/documents.test.sql` (19), `api/tests/test_privacy.py` (export and erasure include the wallet).
