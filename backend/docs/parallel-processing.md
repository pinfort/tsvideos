# Bounded parallel recording processing

`process` uses one bounded batch for all positional paths. Defaults remain one recording worker and one permit for each expensive stage. Set environment variables (or equivalent Spring properties) to opt in:

| Environment | Property | Default | Range |
| --- | --- | --- | --- |
| PROCESSOR_PARALLEL_WORKERS | processor.parallel.workers | 1 | 1..32 |
| PROCESSOR_PARALLEL_QUEUE_CAPACITY | processor.parallel.queue-capacity | 0 (twice workers) | 0=automatic, otherwise 1..1024 |
| PROCESSOR_PARALLEL_SPLIT | processor.parallel.split | 1 | 1..32 |
| PROCESSOR_PARALLEL_COMPRESS | processor.parallel.compress | 1 | 1..32 |
| PROCESSOR_PARALLEL_UPLOAD | processor.parallel.upload | 1 | 1..32 |
| PROCESSOR_PARALLEL_ENCODER_SUBMIT | processor.parallel.encoder-submit | 1 | 1..32 |

These are limits per CLI/JVM, not a distributed scheduler. The encoder permit bounds submission calls, not jobs already queued in the external encoder. Choose limits for local disk, CPU and NAS throughput; a stage never exceeds either its permit count or the recording worker count. Callback invocations have their own process-local upload limit.

The fixed execution queue blocks its producer when full. It neither stores an unbounded future list nor executes work on the producer to evade worker limits. Input directory enumeration still materializes a sorted list of filenames: bounded execution does not claim streaming directory enumeration or bounded memory for that list. One failure is reported once to Slack using its exception class without expanding exception messages, while other files continue. Per-recording progress and completion lines contain the canonical input path; writes are synchronized and no concurrent carriage-return bar is shared. Completion lines arrive in completion order. Any failed input makes the process exit 1 after the batch; cancellation exits 130 (native signals may use 143 as described in graceful-shutdown.md).

`ProcessingCancellation` now tracks nested producer scopes and worker owners atomically. Stop closes admission first, interrupts every registered owner once, and rejects later worker registrations. Queued tasks that never entered their owner scope do not claim jobs. Semaphore waits are interruptible and acquired permits are always released. The shutdown deadline covers all owners together; owned attempt history and ambiguous encoder submission receipts retain the #70 guarantees.

Splits and gzip outputs use an exclusively created `tssplitter/job-<jobId>-attempt-<attemptNumber>` directory beneath the recording directory. Rollback only inspects that owned directory. Original files and neighboring attempts (including identical basenames in different source directories) are never removed by prefix scanning. NAS paths derive from the durable job's original recording directory, so extra workspace depth does not change remote placement. Callback and reset use registered split paths, including their sibling succeeded directory. Interrupted unregistered split outputs remain for manual inspection as documented for #70; stale attempt directories are never silently reused. Durable #64 claims remain the authority for duplicate input paths, which may produce a failed duplicate task but never a second owned attempt or encoder submission.

Integration with #71: its QUARANTINED pipeline outcome should extend BatchResult and take exit 4 precedence over other failures. Keep this branch's processPaths batch entry point rather than reverting to independent per-path queues, and retain quality checks through history.stage so cancellation is observed.
