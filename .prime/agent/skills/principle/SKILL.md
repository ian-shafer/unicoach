---
name: principle
description: >-
  Owns every change to the unicoach review-principle corpus. Takes one piece of
  evidence -- a defect that shipped, a finding that fired wrongly, or a taste
  call a review had to make unaided -- classifies what the corpus got wrong, and
  edits it only once Ian agrees. Use when a principle should be created,
  widened, narrowed, reworded, or retired, when a /ship report nominates a
  learning candidate, or when Ian invokes /skill:principle.
---

# Principle

The review corpus under `.claude/skills/{code,design,impl}-review-*/` is the
project's durable engineering judgement. Every run reads it; no run writes it.
**This skill is the only writer**, and it runs with Ian present, in a clean
context.

An agent that edits its own review rules mid-run stops being a check on itself.
That is why codification is a separate, human-gated conversation rather than a
step at the end of `/ship`.

## What the corpus is

    .claude/skills/design-review-*/SKILL.md   design principles
    .claude/skills/code-review-*/SKILL.md     code principles
    .claude/skills/impl-review-*/SKILL.md     RFC-conformance principles
    .claude/skills/review-fix/tiers.md        the ordering manifest
    .claude/skills/findings-output.template.md the shared output contract
    bin/compile-skills.py                     folds them into implementer guidance

They are `SKILL.md` files because Claude Code requires that shape. They are
**principles**, and several say so in their own titles. The content is plain
markdown with YAML frontmatter and stays readable with `cat` if every agent
runtime here is replaced.

## Invocation parameters

Ask for all three. Do not proceed on two.

- **Evidence** -- what actually happened, in Ian's words. A defect that reached
  production, a finding that should not have fired, a review that argued from
  taste.
- **Subject** -- the code or text the evidence is about, **quoted verbatim**.
  Not a paraphrase.
- **Principle** -- the existing corpus entry in play, if Ian names one. Often
  there is none; that is itself a finding, not a blocker.

Do not infer the principle from the evidence's subject matter. A complaint about
naming may have come from any of several lenses. Do not reconstruct the subject
from the description either -- the reviewer's paraphrase of the code is part of
what is being diagnosed.

## The classification

Read the evidence, the subject, and the nearest existing principles together,
then say which of these it is and why, citing the subject. Announce the
classification **before** proposing any text.

| Outcome               | The corpus said                            | The edit                          |
| --------------------- | ------------------------------------------ | --------------------------------- |
| **overreach**         | right rule, scope too wide                 | narrow with a scoped exclusion    |
| **wrong rule**        | something this codebase does not want      | change or retire the rule         |
| **missing exception** | right rule, unanticipated case             | add the carve-out                 |
| **reviewer error**    | rule is fine, misapplied                   | sharpen the wording, not the rule |
| **gap**               | right rule, stayed silent on a real defect | widen its reach                   |
| **new**               | nothing covers this at all                 | write a new principle             |

The first four are repair: a principle fired and was wrong. **gap** and **new**
are the false-negative half -- a defect shipped and no principle spoke. Those
are the harder cases, because there is no finding text to diagnose; the evidence
is the defect itself.

"The corpus needs no change" is a valid sixth answer. Say it plainly and stop.

## Gate 1 -- new, or widen? (Ian decides)

Between **gap** and **new** is the highest-value call in this skill, and it is
Ian's.

Read the neighbours the evidence could plausibly fall under. Present the
closest, quote the clause that would have had to stretch, and argue which way
you read it. Then let Ian arbitrate.

Bias toward **new**. `.prime/agent/skills/ship/references/review.md` records
that the rules look overlapping and are not, and that a generalist applying all
lenses at once **under-finds**. A principle widened past its subject becomes a
generalist. `design-review-minimum-context` is about implicit string-parsing
contracts, not decomposition; `design-review-scale-restraints` is about load at
1 QPS, not general YAGNI. That precision is the asset.

Widen only when the new case is the **same defect** in different clothing.
Create when it is a different defect that happens to be nearby.

## Gate 2 -- the wording (Ian decides)

Offer **at least two literal rewordings** of the affected clause -- the
replacement text itself, not a description of it -- and recommend one.

Prefer a stated invariant over an instruction to be careful. "Does not apply to
X" reproduces across runs; "be selective here" does not.

## Gate 3 -- where it must not fire (Ian decides)

Every principle needs its carve-outs stated. A rule with no exception fires
everywhere, and a lens that cries wolf gets discarded wholesale rather than
argued with. Ask directly: _where would this be wrong?_

The fingerprints of a sharpened rule are visible in the corpus already --
`code-review-no-smurf-naming` carries a medium-relative exception and a
carve-out for `<Operation><Entity>Result`. Aim for that.

## Gate 4 -- the tier (Ian decides)

From `tiers.md`: a principle is tiered by the **blast radius of the fix its
findings induce**, not by importance. Tier 1 multi-file or structural, tier 2
single-file, tier 3 one to four lines. Tier 0 is RFC conformance and only
`impl-review-*` belongs there.

Propose one with a reason. `tiers.md` states the operator's judgement beats the
author's here, so take Ian's answer.

## Execution -- the mechanical half

No judgement left in this part. Do not start it before all four gates pass.

1. **Write the file** at `.claude/skills/<kind>-review-<slug>/SKILL.md`. Follow
   the house shape of its neighbours: key definitions, the invariant, the cost,
   the remedy, BAD and GOOD examples in the language the defect occurred in, an
   adversarial-posture section naming what a reviewer should grep for, and a
   closing pointer to `findings-output.template.md` written exactly as its
   neighbours write it --
   `[\`findings-output.template.md\`](../findings-output.template.md)`, relative
   to the new file's own directory. **Never restate the output contract** -- it
   has 40 dependents and has drifted twice.
2. **Frontmatter.** `name`, a one-sentence `description` of what it reviews, and
   an `implementation_summary` written as an instruction to the person _writing_
   the code, not to the reviewer. That field is what gets compiled.
3. **Origin.** Add a single line under the title recording the evidence this
   principle came from -- the incident, and the run or date. A principle with an
   `Origin:` was paid for; one without is inherited text nobody has tested.
4. **Register it.** Add the row to the correct tier table in
   `.claude/skills/review-fix/tiers.md`. An unregistered principle **halts**
   every `/ship` review, by design. Add a skill, add a row.
5. **Recompile.** `nix develop -c bin/compile-skills.py`, which folds the
   `implementation_summary` into `.claude/skills/coding/SKILL.md` and
   `general-design/SKILL.md`. Skipping this leaves a principle that catches the
   bug at review but never stops it being written. A reviewer and the guidance
   that writes the code must not drift apart.
6. **Land it.** Corpus edits are code and go through the normal gate:
   `nix develop -c bin/format -c` then `nix develop -c git commit`. Commit the
   corpus change **alone** -- a principle whose commit also carries a feature is
   unreviewable and unrevertable.
7. **Report** the diff to Ian, whether the recompile ran, and which tier row was
   added.

## How the evidence reaches this skill

Three routes, all ending in the same conversation.

- **Ian says so.** The main route. Needs no machinery and is the one that
  catches the principles worth having.
- **A `/ship` report nominates.** Phase 7 lists findings where a reviewer had to
  reason from taste because no principle covered the case. It is a queue to read
  later, not an action -- `/ship` never edits the corpus, and only Ian can tell
  a one-off taste call from a principle.
- **A finding fired wrongly.** The repair route. `/review-fix` and
  `/rfc-review-fix` emit a prompt for a fresh conversation rather than running
  this skill inline, for the same reason: a run must not edit the rules it is
  being judged by.

## Scope

**One principle per invocation.** If the discussion reveals a second also needs
changing, note it and let Ian start a separate pass -- a context holding two
rules argues for merging them, which is rarely what is wanted.

**This skill does not review code.** It never opens the diff to look for other
violations of the principle it just wrote. Landing the principle is the end; the
next `/ship` run applies it.

**Retirement is in scope.** A principle that fires often and is always discarded
is a bad rule, and deleting it -- row and all -- is a legitimate outcome of a
**wrong rule** classification.
