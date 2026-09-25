-- RFC 240: widen the commitment lens vocabulary with 'invite_supporter' -- the
-- deterministic invite-nudge commitment the synthesis pass writes when a
-- student who has already shared the family cost report becomes eligible to be
-- offered a proper supporter account (RFC 232).
-- The lens is code-written, never LLM-proposed: the synthesis forced-tool
-- schema and validator restrict the LLM to the subset
-- SynthesisService.LLM_PROPOSABLE_LENSES names -- an exhaustive when over
-- CommitmentLens, so a new member must state its proposability there -- and
-- this value is deliberately outside it, so it can only arrive from the nudge
-- step. The members are NOT restated here: no SQL comment can be made to track
-- that set. Same drop-and-re-add shape 0080 used for 'share_report': the CHECK is
-- the closed enum's one home in SQL, and CommitmentLens.INVITE_SUPPORTER is its
-- one home in Kotlin.

ALTER TABLE commitments DROP CONSTRAINT commitments_lens_check;
ALTER TABLE commitments ADD CONSTRAINT commitments_lens_check
  CHECK (lens IN ('gap','timing','contradiction','share_report','invite_supporter'));
