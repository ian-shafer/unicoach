# RFC 234: A contradiction is an outcome, and a decline is a record

## Summary

Three changes to the `/ship` review contract, all to what a run RECORDS and who
decides, none to what the lenses look at. `.claude/**` stays untouched, as in
RFC 233.

1. A fix child that cannot apply a finding without breaking a different rule
   **halts that item** and reports `CONTRADICTION:`, quoting both sides. It does
   not pick a side. A fix that satisfies both rules is not a contradiction — it
   is an ordinary accept, and it stays the preferred answer.
2. `contradicted` becomes a fourth set in the tier's fix plan, beside
   dispatched, held and promoted/demoted, and contradictions get their own
   heading in the phase 7 report — distinct from learning candidates, because
   the two are opposite failures: a learning candidate is _no rule covered
   this_, a contradiction is _two rules covered it and disagreed_.
3. Every dispatched finding gets a **structured outcome record** — one line of
   JSON per lens finding, in a closed enum — so accept/decline rate per lens
   becomes countable without a regex. And the lens plan stops being a document
   written from memory after the review: a new `ship-lens` script makes it an
   append-only ledger, written at the moment each lens is dispatched, so a lens
   claimed as run with no report is a fact rather than a reconstruction.
4. A defect a lens sees **outside its own rule** is fixed by that tier's fix
   child like any other finding of its grade, instead of being handed to a lens
   with no inbox. A defect is a defect; which rule owns it is a fact about the
   corpus, not about the defect.

## Motivation: measured, not estimated

Everything here is counted over the 148 archived runs in
`.scratch/ship-archive/`. The sweeps and their exact regexes are in this run's
research files, which are archived with it.

### The contract for this already exists next door, and `/ship` never inherited it

`.claude/skills/review-fix/SKILL.md` — the Claude Code sibling `/ship` shares a
rule corpus with but no code — carries the whole vocabulary:
`discarded-conflict` ("a statement about the two lenses, not about either fix"),
`correct-declined`, `overrode_recommendation`, and the speak-up trigger "**Two
findings conflict.** Name both ids and the lines they contend for".

`.prime/agent/skills/ship/SKILL.md` uses the word `conflict` on seven lines and
**all seven are about git worktrees**. There is no per-finding accept/decline
contract in `/ship`, no contradiction vocabulary, and no required place to write
either. This RFC imports names that already exist rather than inventing new
ones, so evidence from the two workflows stays comparable.

### Contradictions happen, and every record of one is a different shape

Seventeen verified cases in sixteen runs — 10.8% of 148 — where applying a
finding would have broken another rule. Eleven are lens versus lens; five are a
lens versus a house rule in `CLAUDE.md` or a decision in an approved RFC; three
are cross-tier, where a later tier undid an earlier tier's applied fix.

What the fixer did, in those eleven lens-versus-lens cases: composed a fix
satisfying both rules 4, picked a side and wrote why 5 (one of those also
escalating), applied neither 1, escalated 1. **Picked a side with no record: 0 —
and that zero is an artifact of the method, not a fact.** A side picked silently
leaves no text for an archive search to find. What can be measured is the
recording capacity, and it is poor:

- A fix child's reasoning is written to a file in **31 of 148 runs (21%)**. Of
  the 90 runs that ran a per-lens review, **54 (60%) wrote no fix artifact at
  all**.
- **22 of 148 runs (15%)** record no per-finding disposition anywhere — not a
  triage file, not a fix report, not a section in `report.md`.
- The findings template has fields for
  `Verdict / Findings / Assessment / RFC
  says / Code / Options`. There is **no
  field for "conflicts with lens X"** and none for the fixer's disposition, so
  all seventeen cases are phrased differently and filed in seven different kinds
  of file.

So the defensible claim is not "the run silently picks a side N times". It is
that **a silently picked side is indistinguishable from a resolved one**, and
the 21% of runs that wrote anything at all are the ones where a fixer chose,
unprompted, to write prose.

### The decline rate is not countable, and the direction of its error is unknown

The regex `\bdeclin` over the fix and triage corpus plus every `report.md`
returns 266 lines in 130 files. A hand audit of 20 of them found **7 true
per-finding declines and 13 false** — headings, pointers, negations ("Nothing
else was declined"), a student declining an income band, a JSON value
`"declined"`, a Kotlin enum case, and one of Ian's own gate decisions.

It under-counts as well: one line at `rfc-170` carries six declines, others
carry two and nine, and "3 of 4 accepted" never writes the decline at all. So
the earlier "~52% declined, likely overstated" figure is not even wrong in a
known direction.

**And the lens-to-decision edge is destroyed before the decision is made.** Only
46 of the 266 decline lines (17.3%, an upper bound — the window is generous) sit
next to exactly one canonical lens name; 173 (65%) name no lens at all. Where a
report does cite lenses, it uses ad-hoc abbreviations — `lossless`, `misuse`,
`solve`, `bubbling` — of which 9 of 105 bracket tokens are canonical. Worse,
triage **de-duplicates** findings before deciding them (13 files say so
explicitly; `rfc-179/report.md` declines one item that three named lenses
raised). A per-lens decline rate is not merely hard to extract from today's
artifacts — it is not defined by them.

Also measured: 151 fix and triage files use **46 distinct filename spellings**;
outcome lines are prose 50% of the time; `file:line` appears next to a decline
in **2 of 266 cases (0.8%)**.

### The four suspected contradictory pairs do not exist

The work plan named four pairs to resolve: `common-abstraction` vs
`feature-isolation`, `parameterize-bounds` vs `single-source-of-truth`,
`structured-payloads` vs `actionable-errors`, `generalization` vs
`scale-restraints`/`solve-completely`. Each was tested by parsing every lens
verdict and looking for runs where both fired, then reading the prose in those
runs.

| pair                                             | runs where both fired | shown disagreeing |
| ------------------------------------------------ | --------------------- | ----------------- |
| `common-abstraction` / `feature-isolation`       | 26                    | **none**          |
| `parameterize-bounds` / `single-source-of-truth` | 6                     | **none**          |
| `structured-payloads` / `actionable-errors`      | 36                    | **none**          |
| `generalization` / `scale-restraints`            | 15                    | **none**          |
| `generalization` / `solve-completely`            | 59                    | **none**          |

They co-fire constantly and **converge**; `rfc-148`'s triage states it as
doctrine — "Two lenses reaching the same conclusion from different rules settles
it". The pairs that actually contradict are different ones — the naming triangle
(`no-smurf-naming` / `standard-verbs` / `concrete-names`), error fidelity versus
containment (`error-bubbling` vs `no-leaks`), and scope width (`srp` vs
`solve-completely`) — and the two biggest families are not lens-versus-lens at
all.

**This RFC therefore encodes no pair list.** A hard-coded map of suspected pairs
would have been wrong in all four rows. The mechanism finds the pairs; it does
not assume them.

### Prose already said "one file per lens", and 37% of runs did something else

`SKILL.md` phase 5 has said "Persist as you go, one file per lens" since it was
written. Classified over the archive:

| what the run left behind                                       | runs | share |
| -------------------------------------------------------------- | ---- | ----- |
| one report file per lens                                       | 93   | 63%   |
| a per-lens fan-out for some tiers, one batched file for others | 3    | 2%    |
| batched — no per-lens file at all, one or two tier files       | 16   | 11%   |
| no persisted lens report of any kind                           | 36   | 24%   |

**37% of runs left no per-lens report set**, and of the 112 that persisted
anything, 17% batched it. The trend runs the wrong way: the oldest 74 runs are
70% per-lens; the newest 40 are 40% per-lens, 18% batched, and 40% persisted
nothing.

The declaration is no better. Only **65 of 148 runs (44%)** wrote
`findings/lens-plan.json` at all; 72 wrote some plan, under **29 different
filenames and 9 incompatible JSON shapes** — `ran` is an integer in one run and
a list in another, `file` and `findings_file` appear in the same run. Eighteen
plans disagree with the files on disk, and the dangerous direction is well
populated: **28 lens claims across 5 runs say a lens RAN and no report exists**
(`rfc-229` declares 9 and wrote 0; `rfc-225` declares the six tier-3 lenses and
wrote 0). Four plans name a `ran` set containing **no corpus lens at all** — a
fault one set intersection would have caught.

And the batched reviews are thin. Per-lens reports are flat across all four
tiers at a median of 683 words and 4 finding items each; batched reports,
normalised by the number of lenses they claim to cover, run a median of 12 words
and 0.35 items. Per run: 128 review items against 14. The cleanest control is
tier 3, where the lens set is fixed at six — a per-lens fan-out has a median of
20 items, while `rfc-225`'s single tier-3 file has 6, five of its lenses
reported as one word ("concrete-names: clean.").

Two honest caveats, both from the same sweep. This measures **volume written,
not defects escaped**, and batched runs skew towards small lane-B diffs. And
batching CAN be deep: `rfc-147` batched 13 tier-1 lenses into 17,526 words,
twice the per-lens median per lens. It is the one counterexample in 19.

### A batched review cleared a live bug

Run `rfc-232` — open, not yet archived — batched its review per tier, and that
batched review **cleared a live bug**: in `public-web`'s `Routing.kt`,
`acceptInvite` returns a `Result<AcceptInviteOutcome>` whose `InvalidToken`,
`SelfLinkProhibited` and `StudentProfileRequired` cases are SUCCESSFUL results;
both call sites `getOrThrow()` and drop the outcome, then respond
`notice=invite-accepted` — "You are now connected. Welcome to unicoach." A
refused invite is reported to the user as accepted, and nothing is logged. The
tier-1 batch praised the sealed outcomes; the tier-2 batch said
`error-bubbling`'s "`getOrThrow()` at the route edge" was fine.

That is `code-review-chain`'s documented "a generalist applying all lenses at
once under-finds", observed in this workflow, on this repo, on a bug that would
have reached users. A rule that is stated in prose and disobeyed in the run that
most needed it is not a rule yet.

### Batching is what a run does after a dispatch fails

This is the finding that shapes the mechanism. The batched runs say why, in
their own words: `rfc-225` — "two dispatch workers exited without writing
findings"; `rfc-159` — "review-t3 child stalled twice-in-a-row spawn flake";
`rfc-138` — "after child stall". They batched as a **recovery** from a failed
fan-out, not out of laziness.

So a rule that only forbids batching breaks at the next stall — it converts a
degraded review into a stuck run, and the run will route around it. What
survives a stall is a check that makes **plan versus disk** visible and names
the missing lens, so the answer is to re-dispatch that one lens rather than to
write a summary covering for it. The ledger in D5 is built that way.

### What this does NOT fix: most re-work is a plain mistake

The obvious claim — that contradiction detection cures the tier-to-tier churn in
the earlier cost measurements — was tested and is false. Across the 88 runs that
reached tier 2, a later tier undid an earlier tier's fix 13 times: **4 because
two rules disagreed, 9 because the earlier fix was simply wrong.** `rfc-156`'s
own words: "SAME BUG AS TIER 1 #30, REINTRODUCED BY MY OWN TIER 1 FIX";
`rfc-121`'s: "self-inflicted". Those nine need a better fix, not a better
record, and nothing in this RFC touches them.

There is a second reason not to oversell it: a rule disagreement usually costs
nothing. Five of the archived ones were settled at triage, before any code
moved. The expensive case is the narrow one where the two lenses sit in
**different tiers and never meet** — which is what a per-lens file and a named
field address.

(The words "ping-pong" and "oscillat" appear zero times in 148 archived runs.
The churn is real and the runs describe it; the vocabulary for it is not shared,
which is its own argument for a named field.)

## What is given up, honestly

- **A halted item is an unfixed finding.** Today a contradiction is resolved
  somehow — well in eleven recorded cases, unknowably in the rest. After this
  RFC, a `SHOULD-FIX` finding in contradiction is _recorded and reported_, not
  fixed. That is a deliberate trade: an unfixed finding with two rules named is
  worth more than a fixed one whose losing rule nobody can name.
- **The halt is a lever a lazy fix child can pull.** "This contradicts another
  rule" is a cheaper sentence than doing the work. The guard is that the record
  must quote **both** rules verbatim with their source, and name the
  counterparty as a canonical lens or a real file. Nothing mechanical can refuse
  a _plausible but wrong_ citation, and this RFC does not pretend otherwise.
  That residual is real, and it is visible: a contradiction is reported to Ian
  by name, where a silent decline is not.
- **Synthesis must not be crowded out.** Four of the eleven recorded cases
  dissolved under a fix satisfying both rules (`listViolations()` satisfying
  both "use a verb" and "do not repeat the type"). If this RFC turned those four
  into escalations it would be a net loss. D1 therefore states synthesis first
  and the halt second.
- **Five outcome values and a decline class are more bookkeeping.** One line of
  JSON per finding, against fix children that already run a median of 65 calls.
  The cost is small; the honest risk is a child that fills the fields
  carelessly, which produces confidently wrong counts rather than no counts.
- **A new script can fail a tier for a bookkeeping reason** while the code is
  fine. It reports every fault in one pass, never touches code, and every fault
  it raises is mechanically fixable. Set against it: today a review that runs
  with fewer lenses looks exactly like a clean one.

## Decisions

### D1 — a fix child halts on contradiction and names both sides

A **contradiction** is a finding that cannot be applied without breaking a
different rule that the run is also bound by. Four kinds, all measured in the
archive:

| `contradicts_kind` | `contradicts_ref`             | what it is                                  | archive                         |
| ------------------ | ----------------------------- | ------------------------------------------- | ------------------------------- |
| `lens`             | the lens directory name       | another rule in the corpus                  | 11 cases                        |
| `house`            | the doc path                  | a convention in `CLAUDE.md` or another doc  | `rfc-144` (raw-source rule)     |
| `rfc`              | the RFC number                | a decision in the approved RFC for this run | `rfc-139`, `rfc-154`            |
| `tier`             | the lens whose fix it applied | a fix an earlier tier already applied       | `rfc-161`, `rfc-222`, `rfc-164` |

The kind and the reference are two fields of the outcome record, not one packed
token: D7 asks for the contradiction count BY KIND, and a kind carried as a
prefix — `tier<N>:<lens>` worst of all, since the tier digit sits inside the
token too — makes that count a parse of a free string. The cross-tier case
carries `contradicts_tier` beside them. The `CONTRADICTION` line below is prose
for a human and writes the pair together, `<kind>:<ref>`.

**Synthesis first.** If one change satisfies both rules, make it. That is an
ordinary `ACCEPTED` outcome with the synthesis named in its reason, and it is
what four of the eleven archived cases did. A contradiction is only the case
where both cannot hold at once — `rfc-185`'s "Skip and refuse cannot both be
satisfied" is the archetype.

**Otherwise, halt that item — not the batch.** Every other finding in the
dispatch is still worked. The fix child writes, in its report:

    CONTRADICTION: <lens>#<id> (<file>:<line>) vs <counterparty>

followed by the two rules **quoted verbatim**, with the path each was read from,
and one line saying why no change satisfies both. `rfc-148` invented this
ceremony unaided and named why — a decline must be "CONFIRMED, not merely
asserted".

**The fix child does not pick a side, and does not need to know who will.** It
reports and moves on.

**A cross-tier contradiction is the same case, and today the later tier wins by
default.** Tiers are ordered by the blast radius of the fix, not by importance —
`review.md` says so — yet a later tier holds the pen, so it silently overturns
an earlier tier's applied fix. `rfc-164` names the gap exactly: "A tier-0
conformance finding was overturned by a tier-1 correctness finding, and nothing
said which wins ... The corpus has no rule for this collision." Under D1 that is
a `CONTRADICTED` record whose kind is `tier`, naming the tier and the lens whose
fix it would undo, not a silent revert.

**This RFC does not add a precedence rule.** Which rule wins is a decision about
the corpus, and the corpus has one writer — `/principle`, with Ian. One pair
recurs across 148 runs (a visibility-or-DRY-shaped rule against
`code-review-no-leaks`: `rfc-148`, `rfc-153`, `rfc-161`); everything else is a
one-off. That is too thin to legislate from and exactly the evidence D7 is
designed to thicken.

### D2 — `contradicted` is the fourth set in the fix plan, and its own heading in the report

RFC 233's tier fix plan (`<rs>/findings/tier<N>-fix-plan.md`) records three
sets: dispatched, held as advisory, and promoted or demoted with a reason. It
gains a fourth: **contradicted**, each entry naming both sides and pointing at
the outcome record.

Phase 7 gains a section, **Contradictions**, separate from **Learning
candidates**. The two are opposite failures and collapsing them loses the
distinction that decides what `/principle` does next:

- a **learning candidate** is a call made with _no rule covering it_ — the
  corpus is missing something;
- a **contradiction** is a call where _two rules covered it and disagreed_ — the
  corpus has something too wide.

`None` is a real result for both.

### D3 — a contradicted `BLOCKING` finding is Ian's call, not the run's

A contradicted `SHOULD-FIX` finding is recorded, reported, and left. A
contradicted `BLOCKING` finding — correctness, safety or data loss — stops the
run and goes to Ian in the `YOUR DECISION REQUIRED` block defined in
`.prime/agent/skills/shared/operator-comms.md`, quoting both rules and the code.

This is the same reasoning the skill already applies to a Tier 0 finding that
would rewrite a gate-approved decision (RFC 233 D2): where nothing downstream
catches the mistake, the run does not settle it alone. A `BLOCKING` finding that
is dropped because two rules disagreed is exactly the `rfc-232` shape — a
correctness defect that a review looked straight at and cleared.

The default taken in silence is to leave the code as the implementation has it
and record the contradiction, because that is the state the run is already in.

### D4 — every dispatched finding gets a structured outcome record

The fix child appends one JSON object per line to
`<rs>/findings/tier<N>-outcomes.jsonl`, one record per **lens finding** — never
per merged triage item.

```text
{"lens":"code-review-error-bubbling","finding_id":1,"grade":"SHOULD-FIX",
 "file":"public-web/src/main/kotlin/Routing.kt","line":396,"outcome":"CONTRADICTED",
 "contradicts_kind":"lens","contradicts_ref":"code-review-no-leaks",
 "out_of_lens":false,
 "reason":"carry the cause vs do not leak it; no change satisfies both"}
```

(One record is ONE line; it is wrapped here to fit the page. The fence is `text`
and not `json` precisely so the formatter does not reflow it into the shape the
file must never contain.)

Fields, each answering a measured failure:

| field              | required                               | the failure it closes                                                              |
| ------------------ | -------------------------------------- | ---------------------------------------------------------------------------------- |
| `lens`             | always                                 | 65% of decline lines name no lens; 9 of 105 lens citations are canonical           |
| `finding_id`       | always                                 | the archive uses at least five private id schemes, so no two reports join          |
| `grade`            | always                                 | echoed from RFC 233; zero fix files carry a grade today                            |
| `file`, `line`     | always                                 | a location sits next to a decline in 2 of 266 cases                                |
| `outcome`          | always, closed enum                    | outcomes are free prose half the time — `DONE`, `FIXED`, `left untouched`          |
| `decline_class`    | only when `DECLINED`                   | a decline rate is uninterpretable without knowing whether the rule was wrong       |
| `contradicts_kind` | only when `CONTRADICTED`, closed enum  | there is no field for this anywhere today, so all 17 cases are phrased differently |
| `contradicts_ref`  | only when `CONTRADICTED`               | the lens, doc path or RFC number the kind points at (D1)                           |
| `contradicts_tier` | only when `contradicts_kind` is `tier` | which earlier tier's applied fix the finding would have undone                     |
| `out_of_lens`      | always, `true` or `false`              | the 74 hand-offs are invisible today; this is what makes them countable            |
| `reason`           | always                                 | the one thing prose does well; a named cell stops it leaking into the outcome      |

**Only** means only, and **always** means always. A field that does not apply is
ABSENT — never `null`, never `""`, never `"none"` — and a field that does apply
is never omitted. A placeholder is a value, so `contradicts_kind` would be
present on every record and the contradiction count would be a count of records;
and an `out_of_lens` written only when true cannot separate an in-lens finding
from a child that forgot the field, which is the one thing the flag is for.

The record is JSON and is written **by an encoder** — `python3`'s `json.dumps` —
never by typing the literal. `reason` and `file` are free text, and a reason
that quotes a rule (`"use a verb"`) hand-built into a literal produces a line
that is not JSON. Nothing downstream would notice: `missing` never opens this
file. So the child that wrote the file reads it back before it reports — every
line parses, every conditional field present exactly on its own arm — and
repairs any line that does not. `references/review.md` carries both commands.
That read-back is the writer checking its own writing; it is not a second close
condition, and it is not `ship-lens`, which still compares names to filenames
and judges nothing in this file (D5).

A finding's id is **its position in its own lens report** — the 1-based index of
tagged `###` headings in file order, counting the `## Out of lens` ones (D6)
with the rest. It is not a new burden on the lens, and it is the only id an
auditor can recompute from the report itself; the archive's five private schemes
(`A1`, `C1`, `N1`, `F10`, `#1`) are per-run and per-tier and join to nothing.

`outcome` is one of:

| value          | means                                                    | evidence                   |
| -------------- | -------------------------------------------------------- | -------------------------- |
| `ACCEPTED`     | applied                                                  | the corpus already uses it |
| `PARTIAL`      | applied in part                                          | 12 occurrences in 8 files  |
| `DECLINED`     | not applied, and not agreed                              | the corpus already uses it |
| `DEFERRED`     | agreed, not here — it becomes an open item in the report | 6 occurrences in 6 files   |
| `CONTRADICTED` | D1: halted, both rules named                             | 17 cases in 16 runs        |

`decline_class` reuses the corpus taxonomy in `review-fix/SKILL.md`, which owns
it: `review.md` names that table and the fix child reads the class from it when
the record is written, rather than carrying a second copy of the six names that
can silently stop joining across runs. `/ship` takes the **names** and not the
per-finding operator conversation that surrounds them there — `/ship` has one
approval gate and does not reopen it per finding.

`CONTRADICTED` is deliberately NOT the corpus's `discarded-conflict`. That value
records a side that WAS chosen in favour of a conflicting fix; this one records
that no side was chosen. Both are real outcomes and they must not be collapsed.

**One record per lens finding** is the whole point. When a fix child merges
findings from three lenses into one edit it writes three records with the same
reason. That is the duplication triage removes today, and removing it is what
destroys the per-lens rate: `rfc-179` declines one item that three lenses
raised, and no rate can attribute that decline. The measured cost of insisting
on it is small — only 3 lines in 151 files record a merge edge at all.

JSON Lines, not a table, for three reasons: 33% of (run, tier) pairs spawn two
or more fix children, and appending lines from several children is safe where
editing one table is not; counting across runs is `cat`; and the findings
directory is under `.scratch/`, which is gitignored, so no formatter ever
rewrites it.

The fix report keeps its prose. It narrates what the child DID; the outcome file
carries the per-finding verdicts. The reason text lives in the record, once. Its
name is `<rs>/findings/tier<N>-fix-report.md`, one per tier, fixed in
`review.md` rather than chosen by the child: `missing` exempts that name, and an
exempt name whose producer no document names is a hole in the check. A tier with
two fix children concatenates their narratives into it, which is cheaper than
exempting a filename shape any batched report could be given.

### D5 — the lens plan becomes an append-only ledger, written at dispatch

`lens-plan.json` is written after the review, from memory, as a document. That
is why **28 lens claims across 5 runs say a lens ran and no report exists**, and
why 72 runs produced 29 filename spellings and 9 incompatible JSON shapes.
Nothing was lying; the coordinator was reconstructing.

So the plan stops being a document. `<rs>/findings/lens-ledger.tsv` is
append-only, one line per event, and every line is written by the act it
records:

    start	1	code-review-srp	sub-a1b2c3
    skip	1	code-review-bracket-serialization	no JSON or wire payload in this diff
    done	1	code-review-srp

A new script, `.prime/agent/skills/ship/scripts/ship-lens`, appends those lines
and answers one question. It follows the verb shape `ship-lock` and
`ship-verified` already use:

    ship-lens -s <rs> start   -t <N> -l <lens> -c <child-id>
    ship-lens -s <rs> done    -t <N> -l <lens>
    ship-lens -s <rs> skip    -t <N> -l <lens> -r <reason>
    ship-lens -s <rs> missing -t <N>

**The order is the mechanism.** `start` is appended when the child is spawned,
BEFORE it does any work — not when it replies, and not when the tier is written
up. After that, "claimed and no report on disk" can only mean the child really
failed, which is a fact worth acting on rather than a reconstruction to
distrust.

`missing` prints three lists and nothing else, comparing NAMES to FILENAMES:

| list                                                                | what it is                     | archive           |
| ------------------------------------------------------------------- | ------------------------------ | ----------------- |
| a `start` line with no `tier<N>-<lens>.md` on disk, or an empty one | the stalled child              | 28 claims, 5 runs |
| a lens in this tier with neither a `start` nor a `skip`             | the lens nobody dispatched     | the silent skip   |
| a `tier<N>-*.md` on disk with no `start` line                       | the batched or undeclared file | 16 batched runs   |

It also flags two lenses sharing one child id, which is free: the ids are
already in the ledger. With `MALFORMED` — a ledger record whose first field is
not one of the three verbs — that makes five kinds on stdout: the three lists
and those two cheap checks. Only a hand edit produces a malformed record, and
silently skipping it is the single thing nothing else would notice.

Two asymmetries in corpus discovery, and they are deliberate. A lens directory
with **no row in `tiers.md`** is fatal, by name: it belongs to no tier, so no
tier ever asks for it, which is precisely the "quietly runs with fewer lenses"
failure. A `tiers.md` row with **no directory on disk** is a warning and the
lens is dropped — a retired skill must not hold a tier open with an
`UNDISPATCHED` line nobody can clear.

Exit 0 when all three lists are empty, 1 otherwise, and the reserved usage codes
(10, 11, 20, 21, 22) for a caller that invoked it wrongly. All logging to
stderr; stdout carries the lists and the help text.

**`missing` leaves a VERDICT, not a transcript.** Its answer is written to
`<rs>/findings/tier<N>-missing.txt` and has exactly three states: the faults,
nothing at all when the tier is clean, or `INCOMPLETE` lines carrying the reason
when the check could not finish. A copy of stdout would not do, and this is the
sharpest lesson of the run: phase 7 and the workflow audit read that file and
never see the exit status, so a transcript is structurally incapable of saying
"I could not check" — and an empty transcript reads as a clean tier. Five lenses
found that defect in this RFC's own first implementation.

**The tier closes only when `missing` exits 0.** The four tiers run in order
because each one's fixes move the code the next one reads; a non-zero `missing`
means this tier is not finished, so the coordinator does not start the next
tier, does not run the suite and does not land. Each line names its own repair —
re-dispatch that lens, or append a `skip` with the real reason ("child stalled
twice"). Both are honest, neither is silent, and **the skip IS the override**,
which is why there is no override flag. This matters because the archive shows
runs batching as a recovery from a stalled fan-out: a rule that only forbids
batching breaks at the next stall.

Phase 7 reads the ledger for the skipped set and its reasons, rather than a
written-up summary.

**What this deliberately does NOT do.** It does not parse a report's contents.
No `**Grade:**` line check, no counting of tagged headings, no validation of the
outcome records against the findings. Those were in an earlier draft of this RFC
and are dropped: a bash script matching `###` headings in LLM-written Markdown
is a heuristic dressed as a specification, and it would fail a tier over an
`## Out of lens` section or a `###` inside a fenced block. RFC 233 already makes
a malformed report the coordinator's job, and `ship-lens` is not a second
reviewer. It compares names to filenames, and that is all.

The honest limit is the same one this repo keeps stating: a coordinator that
appends a `start` line per lens while dispatching one child for five defeats the
child-id check, exactly as `--no-verify` defeats the pre-commit hook. The old
failure becomes impossible by DEFAULT, not impossible.

### D6 — a defect a lens sees outside its own rule is FIXED, not filed

The largest silent class in the archive is not the contradiction. It is the
hand-off: a lens sees a real defect, judges it outside its own rule, and gives
it to a lens with no inbox. **74 such phrases in 71 reports across 41 runs — 44%
of every run that had a review.** Verbatim, `rfc-145`:

> `CollegeCostChatTool.kt:192` currently reads
> `if (false) { null } else { ... }` ... That is a correctness/dead-condition
> defect for another lens; **I changed nothing.**

A defect is a defect. The lens that found it does not own the rule it breaks,
and that is a fact about the CORPUS, not about the defect.

So a lens that sees a defect outside its own rule reports it under a heading
`## Out of lens`, as an ordinary tagged `###` finding heading — `file:line`,
what it saw, and **its own grade tag**, exactly as for an in-lens finding, since
that is what keeps D4's position-based id computable — and the coordinator
dispatches it to the tier's fix batch by that grade, like any other finding.
`SHOULD-FIX` and `BLOCKING` get fixed in this run. The heading exists so the
source stays traceable, not so the finding can be parked.

Its outcome record (D4) carries `out_of_lens: true` and the lens that SAW it, so
two things become countable that are not today: how many defects arrive this
way, and how often a later lens raises the same one — which is what would
justify routing instead of fixing.

**The lens still does not fix it itself.** One rule, one reviewer, and the tree
is frozen while a tier's lenses read it; a lens that edits invalidates its
siblings' reads. The fix child for that tier is already being spawned.

**Two limits, both real.**

- **Only within this run's own diff.** A defect a lens sees in code the run did
  not touch is not fixed here: fixing it would make the landed change something
  Ian did not approve, and that is what the approval gate is for. It goes to the
  report as an open item, named, with its `file:line`.
- **It may duplicate a later tier's finding.** That is cheap and already has a
  name: the later finding is recorded `DECLINED` with class `duplicate`, which
  is exactly the evidence that says whether the owning lens would have caught it
  anyway.

There is direct evidence that off-lens findings are worth fixing rather than
filing. The Tier 3 model comparison found `gemini-flash` raising three findings
under `semantic-output` that were **off-lens — a real bug, filed under the wrong
rule**. The bug was real. The rule was wrong. Under this decision the bug gets
fixed and the misfiling is visible.

### D7 — what is measured next

The next five lane A runs record in their reports: contradiction count by
`contradicts_kind`, which is one `cut` because the kind is its own field (D4);
outcome counts by value and by lens; decline classes; the `Out of lens` count;
and what `ship-lens missing` printed at each tier close, including "nothing".
RFC 233's D5 counts continue.

That is the evidence a `/principle` session needs to narrow the rules that
actually contradict — and the archive has already shown that the four pairs
everyone suspected are not among them.

## Detailed Design

### `references/review.md` — a new section, "When two rules disagree"

After "Dispatch output contract". It defines a contradiction and its four
counterparty kinds (D1) as the two record fields that carry them, states
synthesis-first, gives the
`CONTRADICTION: <lens>#<id> (<file>:<line>) vs <counterparty>` line verbatim so
a dispatch has a tracked source to quote, and states the halt-this-item-not-the-
batch rule. It closes with D3 — a contradicted `BLOCKING` finding is Ian's.

### `references/review.md` — "The outcome record"

A second new section carrying D4: the file, the one-record-per-lens-finding
rule, the field table with its absent-not-`null` rule, the closed `outcome`
enum, the pointer to the corpus table that owns `decline_class`, the two
commands that write a record with an encoder and read the file back, and the
sentence that keeps prose in the fix report and verdicts in the record.

### `references/review.md` — "What gets a fix child" gains the fourth set

The existing paragraph names three sets in the fix plan. It gains `contradicted`
(D2) and a pointer to the outcome file.

### `references/review.md` — "Applicability filter" becomes the ledger

The skipped set is no longer prose in a plan document. The section states the
four `ship-lens` verbs, the rule that `start` is appended AT DISPATCH, and that
a tier closes only when `missing` exits 0 (D5). The one-shared-reason guidance
for a skipped set survives — it is now the `-r` text on the `skip` lines, and
phase 7 reads them from the ledger.

### `references/review.md` — "Discovery and the accounting invariant"

The section told the coordinator to glob the corpus, read `tiers.md` and assert
the two agree — by hand, in prose. `missing` now performs that assertion, so the
section points at it and gives the coordinator the read it needs: `missing` run
before any `start` line returns the tier's whole lens set as `UNDISPATCHED`, so
the dispatch set and the set the tier is held to are one computation (D5). Two
implementations of one invariant drift in the direction nobody notices, and the
copy nobody exercises is the one that is wrong.

### `SKILL.md` phase 5

The "Persist as you go" paragraph gains the ledger: `start` at dispatch, `done`
on reply, `skip` with a reason, and `missing` before the tier closes (D5). The
dispatch paragraph gains one sentence: a fix child that cannot apply a finding
without breaking another rule halts that item and records it, and never picks a
side (D1), pointing at `review.md`. The `BLOCKING` escalation (D3) sits beside
the Tier 0 escalation already there, since it is the same rule about the same
gate.

### `SKILL.md` phase 7

Two new sections named: **Contradictions** (D2) after Advisory findings, and
**Seen but not owned** (D6). The D7 counts join RFC 233's D5 list rather than
starting a second list.

### `SKILL.md` — the script index

The `## Phases` block lists every ship script; `ship-lens` joins it. An index
that omits one script is how the next run fails to find it.

### `scripts/ship-lens`

A new bash script beside the other twelve, following their conventions: a verb
as the first argument (`ship-lock` and `ship-verified` precedent), `getopts`
with short options only, its own inlined exit-code values with the comment the
siblings carry — these scripts must run outside the dev shell, so they do not
source `bin/functions` — and all logging to stderr.

`start`, `done` and `skip` append one tab-separated line each and exit 0.
`missing` reads the ledger, the corpus directory names, `tiers.md` for the tier
assignment, and the findings directory listing; it prints its three lists to
stdout and exits 1 if any is non-empty. It never opens a report file. A reason
containing a tab or a newline is refused at append time, because the file's one
structural promise is one record per line.

### `bin/ship-scripts-tests`

New assertions against a throwaway run scratch and a fabricated corpus of lens
directories, in the harness's existing style. They are behavioural: each builds
a ledger and a findings directory in a known state and asserts `missing`'s exit
code and which list it names. None greps a skill file for a sentence.

## Files Modified

- `rfc/234-contradiction-is-an-outcome.md` — new (this file).
- `.prime/agent/skills/ship/references/review.md` — two new sections (D1, D3,
  D4); the fix-plan paragraph gains a fourth set (D2); the applicability filter
  becomes the ledger and the discovery section points at the script that
  performs its assertion (D5); the dispatch contract gains `## Out of lens`
  (D6).
- `.prime/agent/skills/ship/SKILL.md` — phase 5 (ledger, halt rule, `BLOCKING`
  escalation), phase 7 (two sections, the D7 counts).
- `.prime/agent/skills/ship/scripts/ship-lens` — new.
- `bin/ship-scripts-tests` — new assertions for the script above.
- `features/continuous-workflow-audit.md` — one table row: the question "did
  every discovered lens run or get skipped?" now points at the ledger, not at
  the file this RFC replaces.

Nothing under `.claude/**`. No Kotlin, no migration, no `db/schema/ORDER` line.
The staged set spans `bin/`, `.prime/`, `rfc/` and `features/`, so
`bin/gate-scope` classifies it `full` and the commit runs the whole gate — which
is the right answer, since the diff edits a harness.

## Implementation Plan

1. Write `scripts/ship-lens` — four verbs, three lists, no report parsing.
2. Add its assertions to `bin/ship-scripts-tests`; run
   `nix develop -c bin/ship-scripts-tests` until green.
3. Add the two new sections to `references/review.md`, the fourth set to the
   fix-plan paragraph, rewrite the applicability filter around the ledger, and
   point the discovery section at the script that asserts it.
4. Edit the two `SKILL.md` passages.
5. `nix develop -c bin/format -c` — Markdown plus one new shell script.
6. Phase 5 of THIS run dispatches under the new contract: a `start` line per
   lens at dispatch, one report file per lens, an outcome record per dispatched
   finding, and `ship-lens missing` before each tier closes. The report carries
   the D7 counts as the first sample.

## Tests

`ship-lens` is a script, so it is tested like the other ship scripts —
behaviourally, in `bin/ship-scripts-tests`, against a throwaway run scratch and
a fabricated corpus of lens directories:

- `start`, `done` and `skip` each append exactly one line, in order, and leave
  the earlier lines byte-identical — the file is append-only;
- a `skip` reason containing a tab or a newline is refused, and the ledger is
  unchanged;
- a ledger whose `start` lines all have a report on disk, with every other lens
  in the tier skipped, exits 0 and prints no list;
- a `start` with no `tier<N>-<lens>.md` exits 1 and names that lens under the
  stalled-child list — the 28-claims case — and so does an EMPTY one, which is
  what a child that created its file and died leaves behind;
- a lens in the tier with neither `start` nor `skip` exits 1 and names it;
- a `tier1-structural.md` on disk with no `start` line exits 1 and names the
  file — the `rfc-232` batched-review case, which is why this script exists;
- two `start` lines sharing one child id exits 1;
- a fixture that trips all three lists at once reports all three in one run;
- a re-dispatched lens — two `start` lines for the same lens, one report on disk
  — exits 0;
- `missing -t 2` ignores tier-1 ledger lines and tier-1 files;
- a ledger record whose first field is not a verb is reported `MALFORMED`;
- a `start` naming a lens the tier manifest does not assign declares NOTHING, so
  the report file beside it is still `UNDECLARED` — the fabricated-line bypass;
- a halt leaves `INCOMPLETE` and its reason in `tier<N>-missing.txt`, never the
  previous invocation's faults and never an empty file, so the archived verdict
  can always be told apart from a clean tier;
- an unreadable ledger halts rather than reading as an absent one;
- a final record with no trailing newline is judged, not dropped;
- a repeated option is refused rather than silently taking the last value;
- a corpus with no lens directories, and an unreadable `tiers.md`, each HALT
  rather than reporting a clean tier;
- the two asymmetries above, from both sides: a lens directory with no row in
  `tiers.md` HALTS and names that lens, while a `tiers.md` row with no directory
  warns and the lens is dropped, leaving the tier able to close;
- `tier<N>-fix-plan.md`, `tier<N>-fix-report.md`, `tier<N>-outcomes.jsonl` and
  `tier<N>-missing.txt` — the four names the run itself writes — are not
  undeclared reports, and NOTHING else is exempt: any other `tier<N>-*.md` with
  no `start` line is undeclared however its name reads;
- a lens name shaped like a path (`-l ../../escape`) is refused and no ledger is
  written — the name becomes a filename;
- the usage grammar: an unknown verb, an unknown option, `-s` with no value, a
  surplus positional, a missing `-s`, and a `-t` that is not a tier number each
  exit with their own reserved code;
- `-h` writes to stdout, and `start`, `done` and `skip` write nothing to stdout
  at all.

The prose changes are not tested. A test that greps `SKILL.md` for a sentence is
the static-analysis test this repo has ruled out: it pins wording, not
behaviour. The behavioural check on the prose is step 6 of the plan — this run's
own verify phase is the first execution of the contract, and its report says
what `ship-lens missing` returned at each tier close.
