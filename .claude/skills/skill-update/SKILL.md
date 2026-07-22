---
name: skill-update
description: >-
  Interactively revise one skill using a verbatim finding it produced as the
  evidence. Takes the target skill and the finding, works out with the operator
  what the rule should have said, and edits the skill only once they agree. Use
  when a reviewer produced a bad or unwanted finding and the reviewer itself
  needs fixing, or when a user invokes /skill-update.
---

# Skill Update

Repairs a **skill**, using one finding as the evidence for what is wrong with
it. Invoked with the operator present, in a clean context.

## Invocation Parameters

- **Skill**: the skill to revise.
- **Finding**: the verbatim finding it produced — description, options, and
  recommendation, unedited.
- **Subject**: the code or text the finding was raised against, quoted exactly.

If any is missing, stop and ask. Do not infer the skill from the finding's
subject matter; a finding about naming may have come from any of several lenses.
Do not reconstruct the subject from the finding's description either — the
reviewer's paraphrase of the code is part of what is being diagnosed.

## Why both are passed verbatim

The finding and the subject are the two halves of the evidence, and the
diagnosis needs both.

The **finding** is a symptom of the rule's text — read it as what the rule told
the reviewer to do, not as a claim about the code. A paraphrase loses the
specific wording that reveals the rule's actual instruction.

The **subject** is the ground truth the finding was measured against. Without
it, a rule that is simply wrong looks identical to a rule correctly stated and
misapplied to one case, and those want opposite edits: narrow the rule's scope,
or sharpen its wording so it is applied correctly next time.

## Execution

1. **Read the skill, the finding, and the subject together.** Locate the
   sentence or clause the finding is an application of. Name it to the operator
   explicitly: _"this finding follows from this line, applied to this code."_
2. **Diagnose before proposing.** Hold the rule against the subject and classify
   what went wrong:
   - **overreach** — the rule is right but its scope is too wide; the subject is
     a place it should never have fired;
   - **wrong rule** — the rule states something the codebase does not want, and
     the subject is evidence of the convention it contradicts;
   - **missing exception** — the rule is right and the subject is a case it
     never anticipated;
   - **reviewer error** — the rule is fine and the subject does not actually
     violate it; the fix is a clearer statement, not a changed rule.

   Say which, and why, citing the subject. If the finding turns out to be
   correct and the subject really does violate a rule worth keeping, say that
   too — "the skill needs no change" is a valid diagnosis.
3. **Propose the edit as text.** Offer at least two concrete rewordings of the
   affected clause — the literal replacement text — and recommend one. Prefer a
   **scoped exclusion** ("does not apply to …") over a vaguer instruction to be
   selective: selectivity by judgement does not reproduce across runs, a
   narrowed rule does.
4. **Discuss.** The operator may accept, amend, or reject. Do not edit until
   they agree. If they reject every option, say so plainly and stop — leaving
   the skill unchanged is a valid outcome.
5. **Apply the agreed edit** to the skill, and nothing else. Do not tidy
   neighbouring rules, restructure the file, or fix unrelated wording.
6. **Check the downstream consumers.** In this repo the `code-review-*` /
   `design-review-*` micro-skills are compiled into `skills/coding/SKILL.md` and
   `skills/general-design/SKILL.md` by `bin/compile-skills.py`. If the edited
   skill is one of them, re-run `nix develop -c bin/compile-skills.py` so the
   implementation-time guidance matches the reviewer. A reviewer and the
   guidance that writes the code must not drift apart.
7. **Report** the diff to the operator, and whether a recompile ran.

## Scope

One skill per invocation. If the discussion reveals a second skill also needs
changing, note it and let the operator start a separate pass — a context holding
two rules argues for merging them, which is rarely what is wanted.
