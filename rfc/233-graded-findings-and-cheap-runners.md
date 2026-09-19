# RFC 233: The lens grades its findings, and the runner is cheap

## Summary

Two changes to the `/ship` contract, both to what the run SPENDS, neither to
what it CHECKS. Every lens still runs on every tier it applies to.

1. A review lens dispatched by `/ship` grades its report — `CLEAN`, `ADVISORY`,
   `SHOULD-FIX` or `BLOCKING` — and tags each finding the same way. A fix child
   is spawned for `SHOULD-FIX` and `BLOCKING` findings only. `ADVISORY` findings
   go to Ian in the report, unapplied. And a Tier 0 finding whose resolution
   would rewrite a decision Ian approved at the gate goes back to him, instead
   of being settled by the run.
2. A child whose whole job is to run a command and report its result verbatim —
   the suite, the hook, the land sequence, `installDist` — is spawned on the
   cheapest model that has done that job correctly, named in the skill.

The corpus under `.claude/**` is untouched. The findings template already says a
caller's output instructions win over its own; this RFC uses that clause.

## Motivation: measured, not estimated

Every child session logs per-call usage with a dollar cost. Read across the
1,828 child sessions on disk on 2026-09-18 (`~/.prime/agent/session-artifacts/`,
nearly all on `claude-opus-5`):

| child kind                 | n     | total  | median | p90    |
| -------------------------- | ----- | ------ | ------ | ------ |
| review lens                | 1,347 | $1,862 | $1.17  | $2.21  |
| fix (a tier's fix batch)   | 153   | $967   | $3.85  | $14.57 |
| suite / hook / land runner | 117   | $361   | $1.41  | $4.73  |

Three facts in that table decide this RFC.

**The verdict is binary, so it cannot steer the spend.** Of ~2,900 lens reports
in `.scratch/ship-archive/*/findings/`, 60–75% carry `🔴 FAIL`. Tier 3 was
all-`PASS` in 1 run of 88; Tier 1 in 0 of 93. But the reports themselves say
what the verdict cannot: "4 deviations (1 MEDIUM, 3 LOW); nothing regressed",
"one Low finding; all five specified cases exist", "one should-fix, one
consider; nothing structural". The template
(`.claude/skills/findings-output.template.md`) allows only `PASS`, `FAIL`,
`N/A`, so any finding at all is `FAIL`, and `FAIL` is what dispatches a fix
child — the unit that costs three times a lens. Twenty-one Tier 3 fix children
ran at $5–19 each, for one-to-four-line renames.

**Runners are paid for judgement they do not use.** 108 opus-5 children ran
`nix develop -c bin/test` or the hook and copied the exit code, the executed
count and the failing text: $337, median $1.38. Four `deepseek-v4-pro` children
did the same job (the `rfc-230-suite` runs): $0.03 in total, median $0.01, same
report contract, same record file. The job is "run this, copy that".

**The cost is turns × context, not output.** Lens children read 1,335M cached
tokens and wrote 17M, over a median of 15 calls each. Fix children: 1,513M read,
6M written, 65 calls each. A cheaper model cuts the price per token; a graded
verdict cuts the number of fix children; neither touches what the lenses look
at. A third lever — fewer turns per lens — is real and is NOT in this RFC, so
that its effect can be measured on its own.

## What is given up, honestly

- An `ADVISORY` finding is not applied by the run. It is a line in the report.
  If Ian never reads those lines, the corpus's taste on naming and wording stops
  reaching the code until a later lens finds the same thing as `SHOULD-FIX`. The
  finding is not lost — it is in the archive and the report — but it is no
  longer free.
- A lens now makes one more judgement: the grade. The grade is the lens's own
  call, and a lens that grades everything `SHOULD-FIX` restores today's cost
  exactly. That is the correct failure: a grade that is wrong is visible in the
  report, and a lens that grades badly is evidence for `/principle`.
- A cheap runner that misreports is worse than an expensive one that misreports,
  only in that it is trusted the same. The runner's report was never the
  authority: the record file (`suite-green.txt`, RFC 211) is written by the
  shell, not the model, and the hook re-runs the suite at land. The model in the
  loop can change what it SAYS, not what the shell measured.

## Decisions

### D1 — every `/ship`-dispatched lens report carries a grade

The dispatch prompt for every lens, on every tier, adds one output instruction
on top of the template. The template's own precedence clause makes this the
sanctioned path: "A caller's prompt may specify its own output instructions, and
those win."

Directly under `**Verdict:**`, one line:

    **Grade:** CLEAN | ADVISORY | SHOULD-FIX | BLOCKING

And each `### <finding>` heading ends with its own tag in brackets:
`### The`[n]`marker is formatted twice [SHOULD-FIX]`. The report's grade is the
highest tag it contains. `**Verdict:**` stays exactly as the template defines
it, so the Claude Code chains, which read only the verdict, see nothing new.

The four grades, by what happens next rather than by importance:

| grade        | means                                                                                          | the run does          |
| ------------ | ---------------------------------------------------------------------------------------------- | --------------------- |
| `CLEAN`      | no findings (`🟢 PASS`), or the lens does not apply (`⚪ N/A`)                                 | nothing               |
| `ADVISORY`   | a preference: a name, a phrasing, a comment, a "consider". Leaving it is not a defect.         | reports it, unapplied |
| `SHOULD-FIX` | a rule in the corpus is broken, or a defect whose fix is contained. Must be fixed before land. | fix batch             |
| `BLOCKING`   | a correctness, safety or data defect; or, Tier 0 only, the code is right and the RFC is wrong. | fix batch, or D2      |

A `🔴 FAIL` verdict with no tags is malformed. The coordinator does not guess;
it re-dispatches that one lens with the instruction quoted. A tag the
coordinator disagrees with is a judgement call: it may promote an `ADVISORY` to
the batch or hold a `SHOULD-FIX` back, with a one-line reason in the tier's fix
plan. Both directions are recorded, because a silent demotion is how a real
defect becomes an unread line.

### D1b — a fix child is spawned for `SHOULD-FIX` and `BLOCKING` only

The tier's fix plan (`<rs>/findings/tier<N>-fix-plan.md`) lists three sets: the
findings dispatched, the `ADVISORY` findings held, and any promotions or holds
with their reasons. A tier whose reports are all `CLEAN` or `ADVISORY` spawns no
fix child and the tier closes at once. The next tier still runs; the tree did
not move, so it reviews the same tree.

The phase 7 report gains a section, **Advisory findings (not applied)**: every
held finding, one line each — lens, file:line, the finding's title. It is a
queue for Ian, like learning candidates, and `None` is a real result.

### D2 — a Tier 0 finding may not silently rewrite an approved decision

Tier 0 asks whether the implementation matches the RFC, so its characteristic
outcome is "one of these two is wrong". Per `rfc/README.md` the code wins, and
the run amends the RFC. That is right, and it stays.

What has no check on it is the narrow case where the RFC line being amended is
one **Ian approved at the gate**. Then the run is not correcting a stale
sentence; it is editing the agreement. So: when resolving a Tier 0 finding would
change a decision taken at the approval gate, the coordinator stops and asks, in
the `YOUR DECISION REQUIRED` block defined in
`.prime/agent/skills/shared/operator-comms.md`, quoting the approved text and
the code's behaviour. The default, taken in silence, is to amend the RFC to
match the code. Every other Tier 0 finding is resolved by the run exactly as
today.

**Measured, because the first draft of this decision was wrong.** Across the 82
archived runs carrying Tier 0 reports, 156 reports in 67 runs propose amending
the RFC — but nearly all are prose corrections (a wrong filename, a plan step
the code improved on), and only 10 reports in 6 runs use blocker-level language.
The case this decision is about — a proposed amendment that touches a
gate-approved decision — appears in **7 reports across 6 runs, about 7%**:

- rfc-155 — "D-B replaced the random token with `HMAC(secret, row id)` at the
  gate, and the code follows D-B. The plan step and the file list were never
  re-written, so the approved RFC now describes something else."
- rfc-148 — "This is second because it changes an approved decision to match
  code rather than the reverse."
- rfc-141 — the seeded paragraph is byte-identical to the file "which Ian
  approved after the table was written. Something has to give."
- rfc-177 — "the RFC has already been approved at gate 2."

**What this decision does NOT claim.** The first draft said a blocking Tier 0
finding must stop verify before Tier 1, to avoid paying ~35 lenses to review
code about to be reshaped. That motivation was false and is withdrawn. Tier 0
already resolves before Tier 1: the tiers are sequential by
`.prime/agent/skills/ship/references/review.md`, and in every archived run where
both a Tier 0 fix child and a Tier 1 lens child exist, the fix child finished
first. A rule restating behaviour the skill already has would read later as a
guarantee while checking nothing. This decision is therefore only the
escalation.

### D3 — runner children are spawned on a named cheap model

A child is a **runner** when its task is to execute a command the coordinator
names and report the outcome under the existing contract (exit code, executed
counts, failing text verbatim, a log under `<rs>/`). The suite,
`bin/test
check`, the hook, the land sequence, `installDist`, `bin/format`,
`bin/shell-
tests -n <name>`, a compile. A runner is spawned with

    model='deepseek/deepseek-v4-pro'

That is the model with a measured record of doing this job (four runs, correct
records, $0.01 each). Nothing else changes model: lenses, fix children,
implementers and research inherit the coordinator's model as they do today.

Two failure modes, stated:

- **The model is unavailable.** `rlm()` fails the spawn. The coordinator retries
  once with no `model` argument (inherits) and says so in the report. A runner
  is never skipped because the cheap model was down.
- **The runner's prose is wrong.** Phase 5 already reads the record file, not
  the sentence (RFC 211: "Emitted, not REPORTED"), and re-derives the executed
  count from the JUnit XML. Nothing in this RFC loosens that; it is the reason a
  cheap runner is safe.

A child that must DECIDE anything — which test to re-run, whether a failure
reproduces, what a stack trace means — is not a runner. If a runner's task grows
a decision, it inherits the model. The line is "run and copy" versus "look and
judge".

### D4 — `.claude/**` and `tiers.md` are untouched

No lens SKILL.md changes; the template changes nothing; `review-fix/tiers.md`
keeps its rows. The grade lives in `/ship`'s dispatch prompt, exactly where the
template says a caller's instructions go. If the grade proves its worth, moving
it into the template is a `/principle` conversation, not this RFC.

### D5 — what is measured next

The next five lane A runs record, in their reports: grade counts per tier, fix
children spawned per tier, and the runner children's model and cost. That is the
evidence a follow-up RFC uses to decide whether lenses also move model, and
whether the turns-per-lens lever is worth pulling. This RFC makes no claim about
either.

## Detailed Design

### `references/review.md` — a new section, "Dispatch output contract"

After "Applicability filter". It states the `**Grade:**` line and the
per-finding tag (D1), the four grades with their table, the malformed-report
rule (re-dispatch, never guess), and the fix-dispatch rule with the three sets
in the fix plan (D1b). It closes with the Tier 0 stop (D2).

### `SKILL.md` phase 5

The tier-order paragraph gains a short paragraph, not one sentence: a run reads
`SKILL.md` before it reads `references/review.md`, so the dispatch rule (D1) and
the fix-dispatch rule (D1b) are stated there too, pointing at `review.md` for
the grades themselves. It closes with D2 — resolving a Tier 0 finding that would
change a gate-approved decision is Ian's call, not the run's. The "one file per
lens" persistence paragraph gains: the fix plan records dispatched, held,
promoted and demoted.

### `SKILL.md` "Long commands run in a subagent" — the runner model

The "Default: give a long command to a child" paragraph gains the runner
definition and the `model=` argument (D3), the unavailable-model retry, and the
"run and copy, not look and judge" line. The four existing conditions are
unchanged.

### `SKILL.md` phase 7 — one new report section

"Advisory findings (not applied)", immediately after the sentence that names the
per-tier findings and open items — that sentence is one unit, so there is no
slot inside it (D1b). Plus, for the next five runs, the D5 counts, including the
grade-completeness and re-dispatch lines.

### What a lens dispatch prompt now contains

The rule file, the diff (or the tree), the template reference, and the text
below — which `references/review.md` carries verbatim under "The instruction,
verbatim", so a re-dispatch has a tracked source to quote:

    Output per `.claude/skills/findings-output.template.md`, plus: directly
    under **Verdict:** emit `**Grade:** CLEAN | ADVISORY | SHOULD-FIX |
    BLOCKING`, and end every finding heading with its tag in brackets.
    ADVISORY = a preference whose absence is not a defect. SHOULD-FIX = a
    corpus rule broken, or a contained defect. BLOCKING = correctness, safety
    or data; or, for Tier 0, the code is right and the RFC is wrong. The
    report's grade is its highest tag.

## Files Modified

- `rfc/233-graded-findings-and-cheap-runners.md` — new (this file).
- `.prime/agent/skills/ship/references/review.md` — new section "Dispatch output
  contract" (D1, D1b, D2).
- `.prime/agent/skills/ship/SKILL.md` — phase 5 (D2 stop; fix-plan sets), the
  subagent section (D3 runner model), phase 7 (advisory section, D5 counts).

Nothing under `.claude/**`. No script under `bin/` or `scripts/`. No Kotlin.

## Implementation Plan

1. Add the "Dispatch output contract" section to `references/review.md`.
2. Edit the three `SKILL.md` passages named above; no other prose moves.
3. `nix develop -c bin/format -c` on the worktree (Markdown is the only gate
   input here).
4. Phase 5 of THIS run dispatches its lenses with the new contract and spawns
   its own suite runner on `deepseek/deepseek-v4-pro`, so the RFC is exercised
   before it lands. The report records the grade counts and the runner's cost as
   the first D5 sample.

## Tests

None automated. Every changed file is prose read by an agent; the only
mechanical check is `bin/format -c`, which the hook runs. A test that greps a
skill file for a sentence is the static-analysis test Ian has ruled out, and
would pin wording rather than behaviour.

The behavioural check is step 4 of the plan: this run's own verify phase is the
first execution of the contract. Its report states, per tier, how many reports
carried a grade line (must be all), how many fix children ran, and the runner's
model and cost. A verify phase that produced an untagged `🔴 FAIL` would have
tripped the re-dispatch rule, and the report says whether it did.
