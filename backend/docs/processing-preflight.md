# Processing preflight

`tvpcli preflight [--json] [--dry-run | --check-write] <file-or-directory>...` aggregates shared doctor configuration/database/NAS findings plus input, tool, encoder and capacity checks. Directories expand regular `.m2ts` files (non-recursive, case-sensitive: `.M2TS` is skipped, as in the pipeline) in filename order. No schema migration is needed.

Normal packaged `process` runs this batch check before the regular Spring processor graph, including dry-run. FAIL stops inspection/registration/split/compression/submission. Database/NAS checks run once per invocation. Embedded callers of the low-level pipeline/runner must call `ProcessingPreflightCommand` themselves. Checks are point-in-time observations, not locks or guarantees against later changes.

Inputs must be canonical readable regular files; missing/unreadable files and empty directories fail. Tool settings (`TS_SPLITTER_PATH`, `AMATSUKAZE_ADD_TASK_PATH`, `FFPROBE_PATH`) identify readable files; bare names resolve on PATH. ffprobe needs executable permission on Unix. TsSplitter/Amatsukaze are Windows-only: on another platform accessibility can pass, but execution is explicitly UNCHECKED. Tools are never launched by preflight. Windows executable validity and remote profile existence are not inferred.

Encoder host/port and default/AT-X profile names are validated locally; the host is a DNS name or IPv4 literal (underscores allowed for LAN/Windows names) or a bracketed IPv6 literal. A three-second DNS+TCP caller deadline checks connectivity only, not encoder protocol/task acceptance. DNS uses a daemon worker because some native resolvers cannot be forcibly interrupted; a stalled resolver cannot keep the CLI alive.

Local temporary-space estimate is **3 × total input bytes per filesystem** by default. Set `processor-preflight.space-multiplier` in external Spring YAML or `PROCESSOR_PREFLIGHT_SPACE_MULTIPLIER`; it must be finite and positive. Canonical aliases count once; directories sharing a FileStore share one combined estimate. Actual zero space fails for nonempty inputs, API failure is UNCHECKED, and overflow fails explicitly. The estimate rounds up; it is not a reservation or actual compression/encoding prediction. Check external encoder output volumes separately.

Default checks are read-only. Explicit `--check-write` reuses doctor's unique exclusive NAS create/write/read/delete probe. It is forbidden with dry-run and on normal process. Cleanup failure is reported; an ambiguous unowned create is never deleted automatically.

JSON stdout is UTF-8 with `schemaVersion: 1` and `checks` containing `id`, `status` (PASS/FAIL/UNCHECKED), and safe actionable `reason`. Credential-bearing settings and raw exceptions are not echoed. Canonical user input paths are shown. Environment-only Boot config loading diagnoses invalid datasource/tool config before the regular bean graph; use `--spring.config.additional-location=<file>` for external YAML (only the `--spring.key=value` form is a property; `--spring.key value` is not, and arguments after `--` are always operands). Exit: 0 no FAIL (UNCHECKED may remain), 1 failed checks, 2 invalid CLI, 130 interruption. Shared DB/NAS timeouts are three seconds per operation; total time includes every endpoint.

Tool PASS for Windows-only tools (and every tool on Windows) means the file is readable; execution permission is not checked. The space estimate uses the filesystem holding each input because TsSplitter and compression write their outputs next to it.

`--json`/`--help` are recognised only before a `--` separator, so `tvpcli process -- --json` treats `--json` as a path. When preflight itself fails unexpectedly, stderr names only the exception class (never its message) so bugs stay diagnosable without leaking settings.
