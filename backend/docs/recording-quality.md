# Recording quality rules

Quality checks are opt-in. With no thresholds configured there is no new stream probe or quality report, and processing retains its existing behavior. Apply `core/src/main/resources/db/migrations/071_recording_quality.sql` after migrations 064, 072 and 067 before enabling this feature on an existing database. The Testcontainers schema includes the same table.

Spring configuration example (external application YAML):

```yaml
processor-quality:
  max-dropped-frames: 0
  drops-action: QUARANTINE
  min-duration-seconds: 60
  duration-action: WARN
  required-streams: [VIDEO, AUDIO]
  streams-action: QUARANTINE
  unavailable-action: WARN
```

Equivalent environment variables are `PROCESSORQUALITY_MAXDROPPEDFRAMES=0`, `PROCESSORQUALITY_DROPSACTION=QUARANTINE`, `PROCESSORQUALITY_MINDURATIONSECONDS=60`, `PROCESSORQUALITY_DURATIONACTION=WARN`, `PROCESSORQUALITY_REQUIREDSTREAMS=VIDEO,AUDIO`, `PROCESSORQUALITY_STREAMSACTION=QUARANTINE`, and `PROCESSORQUALITY_UNAVAILABLEACTION=WARN` (Spring relaxed binding removes hyphens). Optional numeric thresholds are absent by default; actions default to WARN. `FFPROBE_PATH` selects the existing ffprobe executable. Only a configured required-streams rule invokes the additional bounded, 60-second JSON stream probe.

`max-dropped-frames` is a nonnegative integer count, inclusive. `min-duration-seconds` is finite and positive, inclusive. Each failing rule chooses CONTINUE, WARN or QUARANTINE; the highest severity wins. Negative/unknown drops, nonpositive/nonfinite duration, and unavailable stream inspection are reported as unavailable, never treated as zero or as a pass. Unavailable measurements use the explicit `unavailable-action` policy, default WARN. Reports retain configured values, observed values, reason and action per rule.

Checks run after initial inspection and before registration, split, compression, NAS upload or encoder submission. Quarantine is an **in-place hold**: the original remains at its exact canonical source path. No file is moved or deleted. The job becomes QUARANTINED, with a completed QUALITY_CHECK stage but a FAILED attempt and QUALITY_QUARANTINED error. Repeated processing remains held even if configuration is changed. Dry-run only inspects and prints the decision; it creates no job, report, file or artifact. A dry-run hold means “would quarantine,” not that a hold was persisted.

```sh
tvpcli quality report --job-id 42 --json
tvpcli quality release --job-id 42 --dry-run
tvpcli quality release --job-id 42          # confirms before changing state
tvpcli quality release --job-id 42 --yes    # explicit noninteractive confirmation
```

Report JSON has `schemaVersion: 1`, `jobId`, and `reports` with typed findings arrays, UTC timestamps, source identity and `quarantineLocation` for historical hold decisions. Reports are immutable and retained after release/reset; use `status` to determine current job state. A known job without reports returns an empty array. An unknown job returns exit 3; invalid arguments return 2 and operational failure 1.

Release verifies QUARANTINED state, no active invocation/registration/callback, and an unchanged original; it atomically changes state to RETRYABLE. Dry-run checks the same current eligibility without prompting or mutation, and actual release revalidates after confirmation. Refusal returns exit 4. The identity is the #64 canonical path + byte size + last-modified timestamp fingerprint, **not a content hash**: same-size content changes with a restored timestamp cannot be detected. A source can also change after validation; keep recordings stable during retry. Release does not automatically process the file and does not bypass a still-failing policy.

Batch processing continues after each held or failed recording. If any recording is held (including dry-run quality decisions), `process` returns exit 4; otherwise ordinary failures return 1, interruption 130 and success/skips 0. Existing completed/submitted jobs remain skips.

This branch is based on processing history (#67). The concurrency/cancellation stack (#70/#69) must integrate the QUALITY_CHECK stage and quarantine result into its own batch summary before combining branches; no concurrency behavior is introduced here.
