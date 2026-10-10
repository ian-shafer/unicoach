-- RFC 243: widen the commitment lens vocabulary with 'psat_window' -- the
-- deterministic PSAT/NMSQT nudge the synthesis pass writes for a junior when
-- the one qualifying October window is near (slice test/08/the-psat-is-money,
-- brief 0010). The qualifying sitting happens once in a student's life, so the
-- nudge is raised once and never repeated.
-- The lens is code-written, never LLM-proposed: LLM_PROPOSABLE_LENSES is built
-- by an exhaustive `when` over CommitmentLens.entries, so this member does not
-- compile until its proposability is decided, and it is decided false -- the
-- synthesis forced-tool schema never offers it and the validator rejects it.
-- Same drop-and-re-add shape as 0080's share_report widening: the CHECK is the
-- closed enum's one home in SQL, and CommitmentLens.PSAT_WINDOW is its one home
-- in Kotlin.
--
-- THE RE-ADDED LIST CARRIES EVERY VALUE THE BASE ALREADY HAS, INCLUDING
-- 'invite_supporter' (RFC 240), WHICH THIS RUN DID NOT ADD. That is the hazard
-- of a drop-and-re-add: the statement is not "add a value", it is "replace the
-- vocabulary", so a list written from the author's own memory of the enum SILENTLY
-- DELETES whatever landed while the branch was open. This file was written when
-- the base had four lenses and rebased onto a base with five; the rebase
-- conflicted on the Kotlin enum, which is what made the missing value visible
-- here. A run adding the next lens must re-read this list from the base at rebase
-- time, not from the last version of it that it wrote.

ALTER TABLE commitments DROP CONSTRAINT commitments_lens_check;
ALTER TABLE commitments ADD CONSTRAINT commitments_lens_check
  CHECK (lens IN ('gap','timing','contradiction','share_report','invite_supporter','psat_window'));
