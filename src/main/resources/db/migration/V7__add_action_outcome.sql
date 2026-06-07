-- ADR 0015 alignment: give action_audit an explicit EXECUTING->SUCCESS/FAILED outcome.
-- New rows are written EXECUTING before the upstream call, then completed with a terminal
-- outcome_code, so the audit never reports success for a call that actually failed.
ALTER TABLE action_audit ADD COLUMN outcome_code    VARCHAR(32);
ALTER TABLE action_audit ADD COLUMN outcome_message TEXT;
ALTER TABLE action_audit ADD COLUMN completed_at    TIMESTAMP(6) WITH TIME ZONE;
