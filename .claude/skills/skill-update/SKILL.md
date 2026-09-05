---
name: skill-update
description: >-
  Moved. Repairing or creating a review principle is now /principle. Use that
  instead; this entry exists only so older prompts and archived run reports do
  not dead-end.
---

# skill-update — moved to `/principle`

This skill was replaced by **`/principle`**
(`.prime/agent/skills/principle/SKILL.md`), which owns every change to the
review-principle corpus.

`skill-update` could only repair a rule that had **fired wrongly**. It had no
answer for the opposite and more common failure: a real defect that shipped
because no principle spoke. `/principle` keeps the original four classifications
— overreach, wrong rule, missing exception, reviewer error — and adds **gap**
and **new** for that false-negative half, plus the tier-row registration and
`bin/compile-skills.py` recompile that a corpus edit needs to be complete.

Run `/principle` with the same evidence: the finding verbatim, the subject
verbatim.
