# Bounded retries for verified uploads

Only `VerifiedUploadCommand.upload`, `register`, and `verifyRegistered` use the shared retry executor. Split/compression, encoder submission, local/remote deletion, explicit cleanup/reset, and the whole pipeline are never retried automatically. A dry-run performs no upload mutation or retry.

```yaml
processor-retry:
  max-attempts: 3        # total attempts including the first, range 1..10
  initial-delay-millis: 250
  max-delay-millis: 5000
```

Delays must be positive, maximum at least initial and both at most 60000 ms. Backoff doubles to the cap, with bounded jitter from half the cap to the full cap. Each wait is interruptible; cancellation is checked before attempts and before/after backoff. Retry logs contain only operation, static classification, attempt counts and delay, never exception text or configuration. Final errors propagate unchanged for existing per-recording history/Slack handling; individual retries do not notify Slack.

Only typed connection/timeouts (`SocketTimeoutException`, `ConnectException`, `NoRouteToHostException`, `SQLTransientConnectionException`, including bounded nested causes) qualify. Generic IO errors are not guessed from text. Permission/authentication, missing files, invalid input, nontransient SQL, interruption, corruption/state failures and suppressed cleanup/ownership errors do not qualify. Permanent types take precedence over a nested transient exception.

A transient error alone is insufficient. The durable upload manifest must exist, be unclaimed, and remain in a safe state both before and after backoff. Upload accepts NEW/TRANSFERRING/TEMP_VERIFIED/FINALIZED/REGISTERED; registration accepts FINALIZED/REGISTERED; receipt validation requires REGISTERED. Each new attempt claims the row again. CREATING/FINALIZING are ambiguous and require manual reconciliation; another owner's claim is never taken over.

A TRANSFERRING retry removes only the proven-owned temporary artifact, then retransfers from the unchanged source. It cannot overwrite an unrelated final path. A cleanup failure stops immediately. Claim release must affect exactly one owned row; release errors are suppressed on the primary failure and forbid retry. Successful explicit retirement deletes its token-owned manifest and therefore needs no separate release. Registration remains one transaction and repeated receipts converge to the same created-file row. Remote checks are repeated before permitted local removal; original recordings are not deleted by this retry executor.

This branch uses the verified #70/#67/#72/#64 stack. #63 recovery is independent and must preserve the same claim/release and safe-state rules when integrated. No new schema migration is required.
