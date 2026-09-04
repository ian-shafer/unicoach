-- RFC 160: widen the commitment lens vocabulary with 'share_report' -- the
-- deterministic share-nudge commitment the synthesis pass writes when a student
-- becomes eligible to be invited to share the Family Cost Report (RFC 155).
-- The lens is code-written, never LLM-proposed: the synthesis forced-tool
-- schema and validator restrict the LLM to the proposable subset
-- (gap|timing|contradiction), so this value can only arrive from the nudge
-- step. Same drop-and-re-add shape a trigger_kind widening would use
-- (0025's comment names it): the CHECK is the closed enum's one home in SQL,
-- and CommitmentLens.SHARE_REPORT is its one home in Kotlin.

ALTER TABLE commitments DROP CONSTRAINT commitments_lens_check;
ALTER TABLE commitments ADD CONSTRAINT commitments_lens_check
  CHECK (lens IN ('gap','timing','contradiction','share_report'));
