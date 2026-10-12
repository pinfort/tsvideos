# Processing identity and reconciliation (#64)

Before upgrading an existing installation, stop processor commands and apply
`core/src/main/resources/db/migrations/064_processing_job.sql` to its application database.
The script creates only the new table; it does not change existing registrations.
New deployments must also apply it after creating the original tables. Testcontainers
uses the same table definition in its initialization script.

A job key is SHA-256 of canonical absolute input path, a NUL separator, byte length,
another NUL separator and last-modified milliseconds. It is a version fingerprint,
not a content hash: identical size and timestamp cannot detect changed contents.
Use the same filesystem path spelling and preserve metadata when restoring files.
A changed fingerprint is a new job, but existing path/name registrations require an
explicit reset; they are never silently reused or overwritten. Inspect/drop-check
failures create no job. A proven rollback before any upload permits RETRYABLE claims.
Crashes and uncertain external effects are never automatically reclaimed.

Job states: CLAIMED (active or interrupted), RETRYABLE (known safe retry),
SUBMISSION_UNKNOWN (intent committed before encoder call), SUBMITTED (call returned
zero), COMPLETED (callback completed), REJECTED (operator verified no encoder task),
RESETTING (cleanup reserved). A zero return is acknowledgement, not exactly-once proof.
Callbacks claim the job atomically and persist a hash of encoder item ID plus sorted
canonical output paths. Changed manifests are rejected. Existing created_file rows
are the artifact ledger: ENCODE_SUCCESS is pending, FILE_MOVED is durably uploaded.
Pending uploads verify the exact video NAS target's length before deletion; a retry
may find an upload that completed before its DB update. A target with no ledger row
is rejected. Completed artifacts tolerate missing local files. Keep NAS destination
configuration unchanged while callbacks remain incomplete. Length is not a checksum;
stronger publication verification is follow-up #72.

## Operator procedure (no implicit resubmission)

Stop all `process`, `after-encode`, `reset`, and encoder post-process invocations for
the affected recording before editing claims. Check the encoder queue/history and
local/NAS artifacts. Never infer rejection from elapsed time, network failure or an
empty queue when history is unavailable. Back up the affected DB rows first.
Use the numeric ID found in `SELECT * FROM processing_job ORDER BY id` below.
Every UPDATE is compare-and-set: require exactly one affected row and re-read state.

For SUBMISSION_UNKNOWN, positively identify the encoder task by its input path:

```sql
SET @job_id = 123; -- replace with inspected ID
-- Only after confirming the encoder accepted the task:
UPDATE processing_job SET state='SUBMITTED', updated_at=UTC_TIMESTAMP(6)
WHERE id=@job_id AND state='SUBMISSION_UNKNOWN' AND callback_claim IS NULL;
-- Alternative: only after confirming it was NOT accepted and cannot still run:
UPDATE processing_job SET state='REJECTED', updated_at=UTC_TIMESTAMP(6)
WHERE id=@job_id AND state='SUBMISSION_UNKNOWN' AND callback_claim IS NULL;
```

Accepted jobs wait for/replay their original callback. Rejected jobs can use the
existing confirmed `reset` command, then `process`. Do not change UNKNOWN directly
to RETRYABLE: its registrations and uploads may already exist. For acknowledged
SUBMITTED jobs cancelled by the operator, verify the encoder has terminated before
using the same guarded transition from SUBMITTED to REJECTED, then reset.

For an interrupted callback, verify no callback process is alive, record its exact
`callback_claim` value, then release only that claim and replay the original callback:

```sql
UPDATE processing_job SET callback_claim=NULL, updated_at=UTC_TIMESTAMP(6)
WHERE id=@job_id AND callback_claim='the-inspected-owner-token';
```

Do not clear callback_manifest or delete FILE_MOVED rows to force a changed callback.
Preserve local artifacts if a pending upload is missing or NAS verification fails.
For CLAIMED with no possible encoder task, inspect all registrations and generated
artifacts. With an executed_file row, transition the inspected CLAIMED job to REJECTED
under stopped workers, then reset. If a crash occurred between registration and job linkage, first set the inspected job's executed_file_id to that verified row ID using a guarded UPDATE requiring state='CLAIMED' and executed_file_id IS NULL; never guess from a basename. Without a row, remove any owned partial artifacts
only after checking provenance; when no effects remain, transition CLAIMED to RETRYABLE.
Never reset active CLAIMED jobs. Failed RESETTING cleanup can be replayed while its program/executed_file rows still exist. If those rows were already deleted, reset exits without clearing the job: under stopped workers, inspect and finish removal of the owned remaining local split files, then delete only the inspected RESETTING job row with callback_claim IS NULL. Do not run
callbacks concurrently with that cleanup.

## Existing in-flight encodes (legacy adoption)

For each pre-upgrade encode, stop its callbacks, verify the executed_file and
splitted_file rows refer to that encoder task and inspect existing created_file
rows. Existing FILE_MOVED rows must genuinely represent completed uploads; pending
ENCODE_SUCCESS rows must retain their output files. Do not adopt unrelated targets.
After confirming those facts, create a callback-only legacy job:

```sql
SET @executed_id = 456; -- inspected executed_file ID
INSERT INTO processing_job(identity_key,input_path,input_size,modified_millis,state,executed_file_id)
SELECT SHA2(CONCAT('legacy:',e.id),256),e.file,e.size,-1,'SUBMITTED',e.id
FROM executed_file e WHERE e.id=@executed_id
AND NOT EXISTS (SELECT 1 FROM processing_job j WHERE j.executed_file_id=e.id);
```

Require one insert or verify the already existing job; replay the original callback.
The first replay freezes the manifest and claims ownership like a new job. The legacy
key never claims to reconstruct unavailable original timestamps. A later `process`
requires reset of the existing registration. This deliberate adoption prevents an
upgrade from guessing that unrelated pre-existing NAS targets belong to a callback.

## Shared job command transition contract

`ProcessingJobCommand.claim` catches only duplicate-key insertion failures. Initial
INSERT and retry CAS run in separate REQUIRES_NEW transactions through
ProcessingJobClaimCommand: each claim commits before work begins, and duplicate
index locks are released before attempting the retry update. Other DB errors propagate.
`link` accepts only a CLAIMED row without an executed-file link. `transition` permits
only CLAIMED -> RETRYABLE or CLAIMED -> SUBMISSION_UNKNOWN using expected-state CAS.
`acknowledgeSubmission` advances UNKNOWN -> SUBMITTED while preserving an already
COMPLETED callback; `complete` accepts only UNKNOWN/SUBMITTED/already COMPLETED.
Retry, manifest persistence, reconciliation and reset reservations update activity time.
