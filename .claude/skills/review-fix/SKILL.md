---
name: review-fix
description: >-
  Review and repair of an implementation, in two modes. Auto (the default) runs
  end to end unattended — applying every complete finding's recommended option
  and resolving conflicts and red gates by stated policy, never asking — while
  streaming every finding, diff, and test report so the operator can step in at
  any moment. Manual is the original human-in-the-loop triage, built to evaluate
  the reviewer skills themselves. Both modes fan out every reviewer skill against
  one fixed base per tier, build each accepted fix in its own worktree off that
  base, and integrate the tier before the next one runs. Use when a user asks
  to review and fix an implementation, or invokes /review-fix.
---

# Review / Fix

An orchestrator. It moves messages between four roles and makes no judgements of
its own.

| Role             | Who                                                        |
| ---------------- | ---------------------------------------------------------- |
| **operator**     | the human; decides everything in manual mode               |
| **reviewer**     | a skill that inspects the target and returns findings      |
| **fixer**        | a skill or prompt that applies one finding                 |
| **orchestrator** | this skill; dispatches, presents, isolates, and integrates |

In **manual** mode the operator decides every outcome. In **auto** mode (the
default) a stated policy — see **Auto mode** below — stands in for the operator
at each decision point, and the operator may step in at any moment.

**Purpose.** In manual mode this process exists to **evaluate the reviewer
skills**; repairing the code is the by-product. Auto mode inverts that trade —
the repair is the point, throughput over per-finding judgment — but keeps the
attribution machinery intact (one shared base per tier, one finding at a time,
one worktree per fix, verbatim hand-off, the ledger), so a bad outcome is still
attributable to exactly one reviewer and a retro-triage can still evaluate the
reviewers after the fact.

## Invocation Parameters

- **Mode**: `auto` (default) or `manual`. Auto applies the policy in **Auto
  mode** below at every decision point; manual asks the operator, as the phase
  sections describe.
- **Target**: the RFC under review (e.g. `rfc/107-e2e-tests.md`). Tier 0 needs
  it; the code lenses do not.
- **Evaluated Commit** (`E`): the implementation under review. Defaults to
  `HEAD`. It is where the run starts, not what every tier reviews — see **Tier
  base** below.
- **Base Revision**: what the change is measured against in the diff shown to
  reviewers. Defaults to `main`, and stays fixed for the whole run so every tier
  sees the full change rather than only the previous tier's repairs.
- **Fixer**: the skill or prompt that applies an accepted finding. Defaults to
  `/rfc-impl-fix`.
- **Model** _(optional)_: a harness model alias (`sonnet`, `opus`, `haiku`).
  When given, it is passed as the explicit `model` parameter on **every agent
  this run spawns** — reviewer leaves (where the explicit parameter overrides
  the `code-reviewer` / `design-reviewer` agent-def pins), fixers, and the Phase
  4 confirmation leaves alike. When absent, spawns carry no `model` parameter:
  leaves fall back to their agent-def pins and fixers inherit the session model
  — the pre-knob behavior.
- **Scratch Dir** _(optional)_: defaults to `.scratch/review-fix/<run-id>/`.

If Target or `E` cannot be resolved, stop and ask.

## Preconditions

`E` must be a real commit — every fix branches from it, so it cannot be a dirty
working tree. If the tree has uncommitted changes, stop and ask the operator to
commit them; that commit becomes `E`. Never commit or stash their work yourself.

## Prime directives

**Never filter.** Present every finding every reviewer returned, in the
reviewer's own words. Do not rank, merge, summarise, drop, or judge relevance.
Deciding what matters is the operator's job in manual mode and the stated
policy's in auto — never the orchestrator's. A finding the orchestrator quietly
discards is a reviewer defect nobody can see — and this run's entire output is
the record of which reviewers produce defects. Auto mode does not filter either:
every finding is presented in full, then applied or recorded as an open item —
never silently dropped.

**Never loop unasked.** No **review** iteration re-runs on its own: a lens is
fanned out once per tier, and nothing re-triages itself because something came
back red. (Phase 3's re-gate-after-discard is not that loop — it re-runs the
build gate, never a reviewer, and it strictly shrinks the tier each cycle.)

**Never let a lens vanish.** Every discovered skill ends the run with a verdict
file — real or `NOT RUN` — and every `NOT RUN` is reported as such. An omitted
lens reads as "found nothing" when it in fact never ran.

## The finding contract

The shape of a finding — assessment, `RFC says`, `Code`, ranked options — is
defined once in [`findings-output.template.md`](../findings-output.template.md),
and the leaf prompts point there. It is not restated here: it is identical for
all 39 reviewers, so a second copy is a value with two owners that will drift.

What this skill adds on top of that contract:

- **`file:line` is mandatory.** A finding whose subject cannot be located is not
  triageable, and at `/skill-update` time a rule that is simply wrong cannot be
  told apart from a rule misapplied to one case — those want opposite edits.
- **Incompleteness is a result, not a repair job.** A finding with fewer than
  two options, unranked options, a missing subject, or an out-of-range
  assessment is presented **as written**, marked `⚠ incomplete`, and recorded.
- **Never fill a gap yourself.** An orchestrator-supplied ranking, subject, or
  assessment is indistinguishable from the reviewer's own at triage time, so it
  launders a reviewer defect into a clean finding — destroying the one
  measurement this run exists to take.

## Auto mode — the policy

Auto is the default. It runs the same phases with the same machinery; the only
difference is who decides. Each manual decision point maps to a stated policy:

| Decision (manual)                 | Auto policy                                                                                                                                    |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Accept / reject / revise-RFC      | Accept every **complete** finding                                                                                                              |
| Which option to apply             | Option 1, always — the reviewer's recommendation                                                                                               |
| Keep / discard after diff + tests | Keep on green (or an explicit "nothing to run"); on red, one retry handing the fixer the failure output, then discard as `discarded-fix-fault` |
| Within-tier cherry-pick conflict  | Compose both intents if they compose; else the earlier-picked fix wins and the later one is `discarded-conflict` (**Phase 3** step 2)          |
| Red tier gate                     | Discard the implicated fix and re-gate, repeating until green — worst case the tier ends at `T`, which was already green                       |
| Unlisted skill (tiers.md gap)     | Assign a tier by blast radius, say so loudly, carry it as an open item                                                                         |
| Phase 4 re-fires                  | Report only — listed as open items, never re-triaged                                                                                           |

**What auto never applies.** A `⚠ incomplete` finding has no valid recommended
option to take, and inventing one is banned above; likewise a Tier 0 finding
whose recommendation is to revise the RFC cannot be auto-applied. Both are
presented as usual, recorded, and carried to the final report as **open items**.

**Auto never stops to ask.** Not for a conflict, not for a red gate, not for a
manifest gap — every decision point above completes under its stated policy, and
that completion is the mode's whole promise. The operator pastes the prompt and
walks away; a run that is waiting for an answer nobody is there to give has
failed at the one thing it was for.

**Surfacing is not stopping.** A within-tier conflict means two reviewers
disagree about the same lines; a gate still red after a discard means the tier's
fixes are jointly wrong. Those are exactly the results this process exists to
produce, and auto still produces them — every conflict, every gate-driven
discard, and every resolution taken is streamed as it happens, written to the
ledger, and listed in the final report. What auto drops is the **wait**, not the
evidence. The one thing that would bury them is resolving one silently, so never
do that.

**Everything still streams.** Auto prints every finding, every full colorized
diff, and every verbatim test report exactly as manual mode does — it just does
not wait for an answer. It states each decision as it takes it
(`auto: accept,
Option 1` … `auto: keep — 412 tests green`). This is what makes
stepping in informed rather than blind.

**Stepping in.** The operator may interrupt at any moment. The interrupted
finding gets full manual treatment — accept with a chosen option, reject with
the classification conversation, `/skill-update` prompt and all — and then
"continue auto" resumes the policy from the next finding. The reverse works too:
a manual run may switch to auto mid-run and finish the remainder under the
policy.

**The ledger tells the modes apart.** Every ledger line carries
`decided_by: operator | auto`. An auto decision is not evidence about the
reviewer the way an operator decision is, and the two must never blur: the final
report separates them, and a retro-triage of the auto-applied findings — walking
the ledger after the fact and feeding rejections to `/skill-update` — is how an
auto run still evaluates the reviewers when nobody was watching.

**The final report** closes every auto run: per tier, findings found / applied /
discarded with the `decided_by` split; the open items (`⚠ incomplete` findings,
rfc-revision recommendations, Phase 4 re-fires, tier assignments the policy
guessed); and every conflict resolved and gate-driven discard, with the
resolution taken. The last group is what an operator who was not watching reads
first — it is where auto exercised judgement rather than applied a
recommendation.

## Worktrees, branches & cleanup

This run creates one git worktree per accepted finding, and it may be invoked
from a linked worktree that a concurrent `rfc-pipeline` owns. **Everything it
creates is namespaced to the run, and nothing is ever deleted by pattern.**

**Run id** = `<source>-<Eshort>`, where `<source>` is the basename of the
directory this run was invoked from and `<Eshort>` is `E`'s short SHA — e.g.
`unicoach-rfc-107-a1b2c3d`. The source basename alone is not enough: the same
worktree hosts more than one run over time.

| Artefact           | Name                                              |
| ------------------ | ------------------------------------------------- |
| fix worktrees      | `<sibling-of-source>/<run-id>-fixes/<finding-id>` |
| fix branches       | `fix/<run-id>/<finding-id>`                       |
| integration branch | `integration/<run-id>`                            |
| scratch            | `.scratch/review-fix/<run-id>/`                   |
| manifest           | `<scratch>/worktrees.jsonl`                       |

All fix worktrees for a run live under **one parent directory**, so an abandoned
run is one directory to remove rather than a dozen scattered siblings.

**The manifest is what cleanup reads — not the names.** Append
`{"finding_id","path","branch"}` the moment a worktree is created, before the
fixer is spawned. Names are for humans reading `git worktree list`; the manifest
is the authority, because a run killed mid-flight leaves worktrees whose owner
cannot otherwise be established.

**Teardown removes only what this run's manifest lists**, then
`git worktree prune`. Never remove a worktree or delete a branch because its
name matches a pattern — a concurrent `rfc-pipeline` or a second `/review-fix`
owns worktrees that look just like yours, and a pattern delete takes their
in-flight work with it. If something looks orphaned but is not in the manifest,
report it and leave it alone.

## Tier base (`T`) — the run's moving reference point

A tier is reviewed against, and its fixes are branched from, the **tier base
`T`**: the current integration tip. `T` starts at `E` and advances once per
tier, when that tier's accepted fixes are integrated (**Phase 3**).

```
T₀ = E  →  Tier 0 reviewed, fixed, integrated  →  T₁
T₁      →  Tier 1 reviewed, fixed, integrated  →  T₂   … and so on
```

**Review target, fix base, and integration tip are the same commit within a
tier.** That single rule is what makes the tier ordering earn its keep: Tier 3's
naming lenses see the code as Tier 1's restructuring left it, so they never
raise a finding about a method that no longer exists, and a fix is never
authored against a base that has already moved.

It also removes two mechanisms this skill used to need. There is nothing
**stale** to flag, because no finding is ever computed against an outdated tree.
And cross-tier cherry-pick conflicts disappear, leaving only within-tier ones —
which are the interesting kind, since they mean two reviewers in the same tier
disagree about the same lines.

**The trade, stated plainly.** Later tiers judge code that fixers partly wrote
rather than the pristine implementation, so cross-tier comparability of the
review input is weaker. That is accepted: the alternative confound is worse — a
lens firing on code an earlier fix already repaired looks like a false positive
and gets the lens scored down for a finding that was correct when written.
Judging what actually exists is the more honest measurement. Attribution is
untouched either way: within a tier every fix still branches from the same `T`,
one finding per worktree.

## Depth-1 Fan-out Invariant (normative)

This skill spawns one leaf per reviewer, so it **MUST run inline in the
top-level session** — invoked with the `Skill` tool from the conversation
itself, never from inside a background subagent (an `Agent`-tool task). One
`Agent` hop above the fan-out makes every leaf a **grandchild**, which the
Claude Code harness reaps unreliably: a finished leaf can sit at `running`
indefinitely.

This binds in **both modes** — auto changes who decides, not where the leaves
are spawned from. An auto run must still be launched as a top-level
conversation, never as a background agent; the operator pastes the prompt and
walks away, which is not the same thing as backgrounding it.

## Phase 1 — Fan-out

**One fan-out per tier, against that tier's base `T`, and only when its turn
arrives.** Fan out Tier 0, triage it (Phase 2), integrate it (Phase 3) to
advance `T`, then fan out Tier 1 against the new `T`, and so on. Never spawn a
later tier's leaves before the current tier has been triaged and integrated —
they would be reviewing a tree that is about to change under them.

Fanning out all 39 up front is doubly wrong: it spends 34 leaf reviews against a
tree the earlier tiers are about to modify, and a Tier 0 finding can send the
operator back to `/rfc-design`, discarding the implementation those reviews
judged. Tier 0 gates the rest for the same reason it is triaged first — it asks
whether this is even the right implementation to be reviewing.

1. **Build the review context for this tier** at
   `<scratch>/tier-<n>/review-context.md`: the `<base>...T` diff, plus the full
   contents of every changed **non-test** file inlined whole and labelled by
   path. Name changed test files and any oversized file in a "named — `Read` on
   demand" list instead of inlining them.

   **One file per tier, not one per run.** `T` moves between tiers, so a context
   built for Tier 0 describes code Tier 1 no longer sees. Write-once **within**
   a tier (so an interrupted fan-out resumes against the same evidence); rebuilt
   from scratch for the next one.

   This file is the **evidence**, not an optimisation. It is what lets a silent
   lens be told apart from a lens that never looked, and it is the subject you
   hand to `/skill-update` later. Every leaf in the tier reads it.

2. **Resolve the skill set.** Discover live skills by glob — `impl-review-*`,
   `design-review-*`, `code-review-*`, excluding `*-chain` — and check each
   against [`tiers.md`](tiers.md). A discovered skill missing from the manifest
   **halts a manual run**; report it and ask the operator which tier it belongs
   in. In **auto** mode, assign it yourself by `tiers.md`'s own rule — the blast
   radius of the change its findings induce — announce the assignment and the
   reasoning in chat, and carry it to the final report as an open item so the
   operator can add the row. Never edit `tiers.md` to close the gap: a tier is
   the operator's judgement, and a guess written into the manifest stops looking
   like one. Do this **once**, up front, so a manifest gap surfaces before any
   leaf is spent. Report the resolved set, its per-tier counts, and the run's
   **Model** (or that spawns inherit the defaults) — a ledger read later needs
   to know which model produced the findings.

3. **Spawn one leaf per skill in the current tier only** — never a later tier —
   at most 10 in flight, refilling as each finishes. Name the tier and its skill
   count in chat before spawning, so an over-wide fan-out is visible immediately
   rather than after 39 agents are running.
   - **subagent_type**: `code-reviewer` (or `design-reviewer` for the design
     lenses) — read-only, model-pinned by default. Tier 0 skills also use
     `code-reviewer`; they read the RFC and the tree and write nothing.
   - **model**: the run's **Model**, when given — the explicit parameter is what
     overrides the agent-def pin. Omit when Model is unset.
   - **description**: `[review-fix] <skill>`
   - **run_in_background**: `true`
   - **prompt**: names the one skill to invoke, `<scratch>/review-context.md` to
     read, the Target RFC (Tier 0 only), an instruction to emit findings per
     `.claude/skills/findings-output.template.md`, and the write path
     `<scratch>/findings/<skill>.md`. State that it writes that one file, writes
     it **before** replying in chat, and changes nothing else.

4. **Drain to completion. Do not kill the fan-out mid-flight.** A leaf that
   exceeds a generous budget gets one replacement; if that also produces no
   file, write its file yourself with `NOT RUN` and the reason. Never retry
   further and never re-run the whole fan-out — completed leaf files are
   write-once and are kept.

5. **Assert completeness**, then assign every finding a stable id
   `<skill-shortname>.<n>`.

If a leaf stalls, **record what happened** — which skill, how long, whether its
file appeared — and tell the operator. Do not silently retry or restructure
around it; a stall is evidence about the harness and suppressing it destroys the
only signal.

## Phase 2 — Triage

Walk the tiers in manifest order: **0, then 1, 2, 3**. All three phases
interleave — fan out a tier (Phase 1), triage it to completion, integrate it
(Phase 3), then return to Phase 1 for the next tier against the advanced `T`.
Within a tier, order is yours. Present findings **one at a time**, never a
batch, never a summary table of several.

**Auto mode walks this same phase unchanged in everything but the wait**: each
finding is presented in full exactly as below, then instead of opening the
decision prompt the orchestrator states the policy decision (**Auto mode**
above) and proceeds. The Reject and Revise-the-RFC paths arise only when the
operator steps in.

If Tier 0 triage sends the operator back to `/rfc-design`, **stop here**. Do not
fan out Tier 1 against an implementation that is about to change.

No staleness check is needed. Every finding in a tier was computed against that
tier's `T`, and nothing has been integrated since — so no finding can be about
code another fix already changed.

### Presenting a finding

**Print the finding in the response body, then open the decision prompt.** Never
inside it. A picker's fields are a short header and a few words per option;
routing a finding through one compresses the reviewer's case to a sentence and
throws the rest away. The dialog carries the **choice** — accept / reject /
revise the RFC — and nothing else. Everything the operator reads to make that
choice is above it, in full.

Four blocks, in this order. Tier 0:

````
**<id>** <one-line title> — `<skill>`

> <the reviewer's 20–40 word assessment, verbatim>

**RFC says** — <verbatim excerpt>

**Code** — `<file>:<line>`

```<lang>
<the offending lines, verbatim>
```

<one sentence on what is wrong>

**Options**

1. **(RECOMMENDED)** — <one-line reason>

   ```<lang>
   <the literal replacement>
   ```

2. <2..n in descending preference>
````

Tiers 1–3 drop **RFC says**; **Code** carries the subject.

**Code goes in a fenced, language-tagged block — never inline in a sentence.**
`file:line` labels it; the code itself sits in a fence tagged from the file
extension (`kotlin`, `sql`, `swift`, `bash`, `yaml`) so it is syntax
highlighted. A paragraph studded with backticked fragments is unreadable at the
one moment the operator is trying to read code, and it destroys the indentation
and line breaks that make the defect visible. Fence an option's replacement the
same way whenever it is more than a short fragment.

When a finding names two sites — the usual shape for a duplication or
abstraction lens — give each its own `file:line` label and its own fence.
Merging them into one block implies a contiguity that is not there.

The **assessment leads** and is blockquoted, because it is the reviewer arguing
its case and that argument is what the operator is really judging — both about
the code now and about the lens across the run. Quote it **verbatim**. Never
rewrite, trim, or improve it: a polished assessment the orchestrator authored
tells you nothing about the skill that produced it, and this run exists to
measure exactly that.

An assessment shorter than 20 words has not made an argument; longer than 40 is
the verbosity this format exists to prevent. Present an out-of-range one **as
written** and note the length — that is a `finding-unusable` signal about the
reviewer, not a formatting job for the orchestrator.

Beyond these four blocks: no preamble, no restating the rule the lens enforces,
no summary of what the reviewer was checking, no verdict on whether it is a good
catch. The reviewer's words and the operator's decision, with nothing in
between.

The per-skill ledgers (step, declaration, traceability, test) stay in the
findings file. They are coverage accounting, not findings — surface a ledger
only when it has a row that is not a pass, since only then does it change
anything.

### Orchestrator commentary

**Default silent.** This is not "be brief" — it is a gate. Speak only when what
you would say changes the decision or its scope. Four triggers:

- **The recommended option will not build, or is wrong.** Say what breaks.
- **Two findings conflict.** Name both ids and the lines they contend for.
- **The pattern recurs outside the subject.** Grep, and report the count and
  where. Fixing one site and fixing all of them are different decisions, and the
  operator cannot make the second one without the number.
- **Something egregious sits in the diff but outside every lens's scope.**
  Report it once, then drop it.

Everything else — context, rationale, agreement, restating the finding in your
own words, noting that you checked something — is noise, and it dilutes the four
signals above until they stop being read. Stay silent.

The gate governs commentary _on findings_. It does not cover the rejection
conversation in **Reject** below, which is a defined step with its own budget:
one question and one proposed class.

The operator chooses one of:

### Accept

1. **The operator picks the option to apply.** Option 1 is the default — offer
   it as such and take silence or "yes" to mean it. Any other choice is an
   **override**, and step 5 acts on that.
2. Create the worktree at this tier's base — **not** `E` — and record it in the
   manifest **before** spawning anything:

   ```sh
   git worktree add <run-fixes-dir>/<id> -b fix/<run-id>/<id> <T>
   ```

   Append `{"finding_id","path","branch"}` to `<scratch>/worktrees.jsonl` now,
   not after the fixer returns. A fixer that hangs must still leave a cleanable
   trace.
3. Spawn the **Fixer** there, in a fresh context, one finding at a time — never
   a batch — with the run's **Model** as the spawn's `model` parameter when one
   was given. Pass the finding **verbatim**: description, all options in their
   original order, the subject. Do not paraphrase, re-order, or "clarify" it.
   Handing the fixer your restatement rather than the reviewer's own words
   destroys the attribution this run exists to produce.

   **Name the chosen option explicitly** — "apply Option 2" — rather than
   handing over the list and leaving the fixer to infer it. All options still
   travel with the finding so the fixer can see what was rejected and why, but
   which one to apply is the operator's decision, not the fixer's.

   The prompt MUST state that the fixer:
   - works only in its own worktree, and **must not commit**;
   - **owns verification** — after applying, it runs `nix develop -c bin/test`
     (the whole suite, unscoped, per CLAUDE.md) and reports the real executed
     counts. Scoping to a module would be a guess at the fix's blast radius, and
     a `:db` fix that breaks a `:rest-server` test is exactly what this run must
     not miss. The per-worktree test DB and free-port claiming make concurrent
     runs safe. If there is nothing to run, it says so explicitly rather than
     staying silent.

     **Its report is taken at face value.** The orchestrator does not re-run to
     confirm it — these are our own tools reporting their own output, and the
     tier gate re-runs everything anyway.
   - authors under `/coding` and `/general-design`. If the chosen option's
     snippet conflicts with that guidance, the fixer implements the option's
     **intent** in the conforming shape and **flags the conflict in its report**
     — never silently, and never by switching to a different option. The
     divergence is reviewer telemetry: it is the same miscalibration
     `ranking-wrong` captures, surfaced before the operator has to catch it in
     the diff, and the operator still sees the full diff and keeps or discards
     as always.

4. **Show the diff. Always, in full, colorized.** The operator MUST NOT be asked
   to keep or discard without seeing it. `git diff <T>` in that worktree plus
   any untracked files, presented inside a fenced **`diff`** block so every
   added and removed line is syntax-highlighted:

   ````
   ```diff
   <the complete output>
   ```
   ````

   Never a prose description of the diff, never an excerpt, never "the change is
   straightforward." The diff is the thing being decided on.

   Fence it as `diff` — do **not** reach for `git diff --color=always`. Its ANSI
   escapes are not rendered in the chat stream and arrive as visual garbage;
   markdown fencing is what actually colorizes here.

   Immediately after the diff, the fixer's **verbatim test report** — both
   inform the same decision, so never one without the other. If the fixer
   reported no verification, say so plainly: an unverified fix may still be
   kept, but not by accident.

5. The operator **keeps or discards** the fix.
   - _keep_: commit it in the worktree —
     `nix develop -c git commit --no-verify -am "<id>: <title>"` with trailers
     naming the reviewer, the option applied, and what was verified. The branch
     waits for Phase 3. (Intermediate commits on a work branch skip the hook per
     CLAUDE.md; the branch tip is gated in Phase 3.)
   - _discard_: `git worktree remove --force` that worktree, delete its branch,
     and mark the manifest line discarded. Nothing to roll back — the fix never
     touched the main tree, which is the point of building off `T` in a
     worktree.
   - On discard, offer a **retry**: the operator may re-run the same finding
     with extra notes, passed verbatim to a fresh fixer context alongside the
     original finding.

6. **If the operator overrode Option 1, that is a skill signal — spend it.** The
   finding was right and its options were right; the reviewer simply ranked them
   wrong. That is a defect no rejection class covers, because nothing was
   rejected, and it is invisible unless captured at the moment of the override.

   Run the same short conversation as **Reject** below, proposing class
   `ranking-wrong`, and ask the one question that decides the edit: **was Option
   1 wrong here, or wrong generally?** Locally wrong is context this codebase
   happens to impose and warrants no edit; generally wrong means the reviewer's
   preference criteria are miscalibrated and the skill should change.

   As with `correct-declined`, **record it either way**. A single override is
   weak evidence; the same lens overridden across several runs is a reviewer
   recommending the wrong thing by default, which is worse than a noisy rule —
   it is a rule the fixer will follow.

Record the outcome distinctly: `kept`, `discarded-fix-fault` (the finding was
sound, the fix was not), `discarded-finding-fault` (implementing it revealed the
finding was wrong), or `discarded-conflict` (**Phase 3** dropped it in favour of
a conflicting fix — a statement about the two lenses, not about either fix).
These are different evidence and must not be collapsed. Record `option_applied`
and `overrode_recommendation` on every accepted finding, including the ones that
took Option 1 — an override rate is only readable against the total.

### Reject

The operator declines the finding. Nothing is built.

**A rejection is not automatically a skill defect.** A rule can be correct, fire
correctly, and still be declined — the change is out of scope for now, or it
loses to a deliberate trade-off. Emitting a `/skill-update` prompt for every
rejection wastes interactive sessions on skills that are working, and worse, it
teaches the skill to stop reporting things the operator merely deferred.

So the rejection opens a **short conversation, held here with the
orchestrator**, whose only job is to decide whether the reviewer should change.

1. **Ask once, briefly:** why is this being declined? One question, not an
   interview. The queue is waiting.
2. **Classify, and say which you think it is** — the operator corrects you:

   | Class              | Meaning                                                | Update? |
   | ------------------ | ------------------------------------------------------ | ------- |
   | `rule-wrong`       | the rule itself is bad, here and generally             | yes     |
   | `misapplied`       | rule is sound, should not have fired on this subject   | yes     |
   | `finding-unusable` | rule may be right; options/subject/`file:line` are not | yes     |
   | `ranking-wrong`    | finding and options right; Option 1 was the wrong pick | yes     |
   | `correct-declined` | fired correctly; declined on scope, timing, trade-off  | no      |
   | `duplicate`        | another finding already covers it                      | no      |

   `ranking-wrong` arrives from **Accept** step 6, not from a rejection — it is
   listed here because it uses this same conversation and prompt.

   The four `yes` classes want **different edits** — `rule-wrong` changes the
   criteria, `misapplied` changes scoping and exceptions, `finding-unusable`
   changes the output contract, `ranking-wrong` changes the preference criteria
   that decide which option gets recommended. Naming the class is most of the
   work of the `/skill-update` session, so do not skip it and let that session
   re-derive it.

3. **Record the outcome in the ledger either way**, with the class and the
   operator's reason **verbatim**. This is not bookkeeping: a lens rejected five
   times as `correct-declined` across five runs is a real signal — the rule may
   be right and still not worth its noise — and that pattern is invisible unless
   every rejection is recorded, including the ones that changed nothing today.
   Likewise repeated `duplicate` against the same pair of lenses is evidence
   they overlap.

4. **Only when the class calls for an update**, print this block for the
   operator to paste into a **new conversation**. Do not run `/skill-update`
   inline — it is a full interactive editing session and would swamp the queue:

   ```
   Invoke /skill-update on skill <reviewer skill>.

   Rejection class: <rule-wrong | misapplied | finding-unusable>

   Verbatim finding:
   <the finding, in full — all options, in their original order>

   Subject (the code the finding was raised against):
   <the exact lines, quoted>

   Why it was rejected:
   <the operator's reason, verbatim>
   ```

Then **continue triaging** — do not wait for them to finish the skill edit.

**When the class is genuinely unclear, record and move on.** A ledger line is
cheap and keeps the evidence; an unnecessary `/skill-update` session costs the
operator real time and risks editing a skill that was right. But never talk the
operator out of an update they want — the orchestrator proposes a class, the
operator decides it, and this step exists to inform that decision, not to make
it.

### Revise the RFC — Tier 0 only

A Tier 0 finding often means the RFC is wrong, not the code: a "missing feature"
may be a spec the implementation correctly declined to invent. When the operator
says so, hand them the `/rfc-design` prompt for a new conversation, and mark the
finding `rfc-revision`. The RFC change re-enters through the normal pipeline,
not through this loop.

Record every outcome as a line in `<scratch>/ledger.jsonl`:
`{"id","skill","tier","outcome","decided_by","option_applied","overrode_recommendation","class","reason","skill_update"}`
— `decided_by` (`operator` | `auto`) on every line, so the two kinds of evidence
never blur; `option_applied` and `overrode_recommendation` on every accepted
finding (Option 1 included, so an override rate has a denominator); `class` and
`reason` on rejections and on overrides; `skill_update` a boolean recording
whether one was actually requested. This is the only durable record of what was
decided, and the evaluation dataset this run produced.

## Phase 3 — Integration (once per tier)

Runs at the **end of every tier**, as soon as its triage is complete — not once
at the end of the run. Its output is the next tier's base.

1. **Cherry-pick this tier's kept fixes onto `integration/<run-id>`**, in
   acceptance order. (On Tier 0, first branch it at `E`.) Only this tier's fixes
   are in flight; earlier tiers are already in.
2. **A conflict is a finding, not a chore.** Two fixes from the same tier
   contending for the same lines means two reviewers disagree about that code.
   Print both hunks and record it in the ledger as a conflict between the two
   skills — in **both** modes, before anything is resolved. That is a result
   this run was built to produce, and per-tier integration is what isolates it,
   since a conflict here can only be lens-versus-lens, never an artefact of a
   base that moved.

   Then resolve it. **Manual**: the operator does. **Auto**: you do, under this
   policy, printing the resolution you took alongside the hunks.

   1. **Compose them if they compose.** Most same-tier conflicts are two edits
      to overlapping lines whose intents are independent — a rename landing on
      the same lines as an extracted helper. Write the hunk that satisfies both
      findings; both fixes keep their attribution and both stay `kept`.
   2. **Otherwise the earlier-picked fix wins.** Cherry-picks run in acceptance
      order, so the earlier one is already on the branch: keep it, drop the
      conflicting hunk from the later fix, and record that fix
      `discarded-conflict` with the two skill names. Order is an arbitrary
      tiebreak and is meant to be — the orchestrator judging which reviewer was
      _right_ is exactly the filtering this skill never does. The discarded
      finding goes to the final report as an open item.
   3. **Never invent a third design.** The resolution is one of the two fixes or
      both of them — never a rewrite you authored. That would be a change no
      reviewer asked for, no ledger line can attribute, and no diff the operator
      saw was ever shown for.

   Step 3's gate is what checks the resolution: a composition that does not
   build fails there and unwinds like any other red gate.
3. **Gate the tip**: `nix develop -c bin/format -c` and
   `nix develop -c bin/test check`. Report the real counts.

   Per-branch greens do not imply a green integration — two fixes can merge
   cleanly and still be jointly wrong (one extracts a helper, another edits code
   that no longer exists there). This run is what catches that.

   **A red gate blocks the next tier.** Do not fan out against a base that does
   not build: every finding it produced would be suspect. In **manual** mode,
   resolve it with the operator — discard the offending fix, or fix forward —
   then re-gate.

   In **auto** mode, unwind; do not fix forward. Discard the fix the failure
   implicates — the most recently cherry-picked one when the output names none —
   re-gate, and repeat until the tip is green. Every cycle drops at least one
   fix, so this terminates: in the worst case the tier ends at `T`, which was
   already green. Record each discard `discarded-fix-fault` with the verbatim
   gate output, and report the whole sequence.

   Fixing forward is the manual-only branch on purpose. A red gate after
   per-branch greens means the tier's fixes are **jointly** wrong, and a repair
   authored here belongs to no finding and no worktree — it would be the one
   change in the run with nothing to attribute it to. Unwinding costs a fix and
   keeps the record honest; the discarded findings are open items the next run
   can raise again.

4. **Tear down this tier's worktrees — from the manifest, never by pattern.**
   For each manifest line belonging to this tier: `git worktree remove` its
   path, delete its branch, mark the line torn down. Then `git worktree prune`.
   Leave anything not in the manifest alone and say so.
5. **Advance `T`** to the integration tip and record it. That commit is what the
   next tier is reviewed against and branches from.

Gating four times rather than once costs four suite runs, and buys attribution:
a breakage is localised to the tier that introduced it, instead of surfacing at
the end against the union of every fix in the run.

## Phase 4 — Confirmation

After the last tier has been integrated, re-run the **Tier 0** skills once
against the final tip. Five read-only skills; it catches a fix that did not
actually satisfy the check it was raised against.

Tier 0 ran first, so Tiers 1–3 have since rewritten code it passed — a
restructuring fix can break RFC conformance without any lens in its own tier
noticing. This pass is the only thing that looks again.

If something re-fires, return to Phase 2 triage for those findings. The operator
decides whether to act. **This is a check, not a loop** — it does not re-run
itself, and it never re-runs Tiers 1–3. In auto mode a re-fire is **report
only**: it becomes an open item in the final report, and triage is not
re-entered.

## Cleaning up an abandoned run

A run killed mid-tier leaves fix worktrees, their branches, and an
`integration/<run-id>` branch behind. Everything needed to clean it up is in
`<scratch>/worktrees.jsonl`, which survives because `.scratch/` is gitignored
and never reset by this skill.

To wind one down — the operator's call, never automatic:

1. Read the manifest. Report each entry and whether its path still exists.
2. `git worktree remove` each listed path (`--force` if the fixer left changes),
   delete each listed branch, then `git worktree prune`.
3. Delete `integration/<run-id>` only once the operator confirms nothing on it
   is wanted — it may hold whole tiers of accepted, gated work.
4. Leave `<scratch>/` in place unless asked. It is the run's ledger, and it is
   the evaluation output this whole process exists to produce.

**Never clean up by pattern** — not `git worktree list | grep fix`, not
`git branch -D 'fix/*'`. A concurrent `rfc-pipeline` and a second `/review-fix`
create worktrees that look exactly like these, and their work is live. If a
worktree looks orphaned but appears in no manifest, report it and stop.

## What this skill never does

- Filter, rank, or summarise findings.
- Paraphrase a finding when handing it to a fixer.
- Advance an iteration the operator did not ask for.
- Apply more than one finding per fixer context.
- Ask for a keep/discard decision without showing the full colorized diff.
- Build a fix anywhere but its own worktree off the current tier base `T`.
- Fan out a tier before the previous one is triaged, integrated, and green.
- Create a worktree or branch outside this run's namespace, or remove one that
  is not in this run's manifest.
- Report a verdict for a lens that did not run.
- Stop an auto run to ask for a decision the policy already covers.
- Resolve a conflict or a red gate silently, or with a repair of its own design.
- Let the integration tip reach `main` without the full gate having run on it.
- Edit a reviewer skill itself — that is `/skill-update`, with the operator
  present.
