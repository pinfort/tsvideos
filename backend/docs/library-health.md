# Library health and saved verification

Apply `core/src/main/resources/db/migrations/051_library_health.sql` to the application database before deploying this version (new and existing installations). This repository does not automatically run schema migrations. It creates only the namespaced `health_verification_run` and `health_verification_finding` tables; it does not alter recordings.

`GET /api/v1/health/library` reads failed (`ERROR`) programs, known positive dropped-frame counts, and saved verification state. Orphan programs remain visible. It does not access NAS storage. `POST /api/v1/health/library/verify` explicitly invokes the same `LibraryVerificationCommand` as CLI verify and returns refreshed state. The web `/health` page uses a same-origin Next route for browser requests; the backend request remains in `frontend/lib/api/health`. No broad CORS permission is added.

The scan performs existence/size reads only, never file repair/removal. Its five statuses are unchanged: VERIFIED, MISSING, SIZE_MISMATCH, INACCESSIBLE and UNCHECKED. An inaccessible NAS is not proof that a file is missing. Transfer-pending records and programs without artifacts are unchecked. These individual findings are valid completed scan results; a whole-run failure means verification or persistence could not finish.

Run start and completion timestamps and per-finding timestamps are stored explicitly in UTC with microsecond precision. A scan first commits a RUNNING claim, performs NAS I/O outside the database transaction, and atomically commits the immutable findings and COMPLETED state. Only one active scan is permitted across processes (unique active marker). A simultaneous POST returns 409. Whole-run failure records only the safe `VERIFICATION_FAILED` code and retains the last completed snapshot. The cause is written to the manager:api log, not the database; `POST` still returns 200 with `latestRun.state` = `FAILED`. If marking the run failed itself fails (retried once), the run stays RUNNING and needs the manual recovery below; the log names the run ID. GET never synthesizes a healthy result when no completed scan exists: summary and lastCompletedRun remain null. Failed or in-progress newer runs are shown separately from the last completed result.

The page displays UTC completion time, elapsed age, and per-file checked time. It labels findings from deleted programs and omits their detail link, while retaining the historical findings/counts. Results are historical and do not reflect later filesystem changes. Reloading the page/process does not discard them. The action is synchronous; if a request times out or the browser disconnects, use status refresh to determine whether the server is still running instead of assuming failure.

A crashed process may leave RUNNING with its active marker. It is deliberately not reclaimed merely because it is old. First stop/verify termination of the process that owns the scan, inspect the run ID, then an operator may explicitly mark that exact run failed:

```sql
-- Replace 123 with the inspected interrupted run ID, only after its process is stopped.
UPDATE health_verification_run
SET state = 'FAILED', completed_at = UTC_TIMESTAMP(6),
    failure_code = 'VERIFICATION_FAILED', active_marker = NULL
WHERE id = 123 AND state = 'RUNNING';
```

This does not replace the last completed snapshot. Historical runs/findings are retained; there is no automatic pruning. Optional processing-stall detection is not included because this branch has no durable processor activity timestamps.
