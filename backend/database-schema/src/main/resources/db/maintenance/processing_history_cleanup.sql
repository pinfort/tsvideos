-- Explicit operator cleanup; run only after reviewing the cutoff. No scheduled deletion is enabled.
SET @history_cutoff_utc = UTC_TIMESTAMP(6) - INTERVAL 90 DAY;
START TRANSACTION;
DELETE s FROM processing_stage_event s JOIN processing_attempt a ON a.id=s.attempt_id
WHERE a.outcome IN ('COMPLETED','FAILED','INTERRUPTED') AND a.ended_at < @history_cutoff_utc
AND s.outcome <> 'ACTIVE';
DELETE a FROM processing_attempt a
WHERE a.outcome IN ('COMPLETED','FAILED','INTERRUPTED') AND a.ended_at < @history_cutoff_utc
AND NOT EXISTS (SELECT 1 FROM processing_stage_event s WHERE s.attempt_id=a.id);
COMMIT;
-- Keep processing_history_identity so attempt numbering is never reused.
