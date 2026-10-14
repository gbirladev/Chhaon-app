# Reports wallet (migration 0029)

The Reports tab is now a **wallet**: it keeps a parent's reports, prescriptions, scans and other papers (photos or PDFs) in one place for the whole family circle. No reading or analysis. The older AI report reading stays switched off (`VITE_REPORTS_ENABLED=false`); if that flag is turned on, the old screen replaces the wallet.

## What it does
- **Add:** take a photo or choose photos / a PDF (several pages per report), then give it a name, a type, the date on the paper, an optional doctor or hospital and notes.
- **Find:** search by name or doctor, filter by type (Lab report, Prescription, Scan or X-ray, Discharge summary, Vaccination, Insurance, Bill, Other), thumbnails in the list.
- **View:** pages open full size; PDFs open or download. Edit details, add or remove pages, delete (confirmation first).
- Works in English and Hindi.

## Limits by plan (reports per parent)
| Plan | Reports per parent |
|---|---|
| Free | 5 |
| Plus | 25 |
| Family | 100 |
| Ultra | unlimited (system ceiling 200) |

The numbers live in `plans.max_documents` (change with one `update`). A trial counts as Plus. The limit follows the plan of whoever created the family circle, like medicines.
**Over the limit nothing is hidden or deleted**: families can still open, download and export every report; they just cannot add more (this also applies after a downgrade). The Reports screen shows "3 of 5 reports used", turns amber when one is left, and shows a full / over-limit card with a link to Plans.

## Limits and safety
- 10 pages per report, 15 MB per file, and a hidden cost guard of 300 MB of files per parent on every plan. Photos are shrunk (about 2000 px) before upload and get a small preview.
- Files are in the private `reports` bucket at `<parent id>/<name>`; the app only uses short-lived (5 minute) links. Only the family circle can read or write them (database rules, also for direct writes).
- A file path must be inside that parent's own folder (no `..`, no other parent's folder); only JPEG, PNG, WebP and PDF are accepted.
- Deleting a report removes its files from storage. Deleting a parent or an account purges the whole folder (existing behaviour). "Download my data" now includes the `documents` and `document_files` records (not the files themselves).

## Not built (ideas)
- Sharing a report with a doctor, a combined "all pages as one PDF" download, reading values from a report (the old AI code), reminders for old reports.

Tests: `supabase/tests/documents.test.sql` (19), `supabase/tests/documents_limits.test.sql` (12: per plan, trial, downgrade, 300 MB cap), `api/tests/test_privacy.py` (export and erasure include the wallet).
