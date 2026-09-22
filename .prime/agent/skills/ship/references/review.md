# Review: dispatching the shared rule corpus

## Where the rules live (read-only)

    .claude/skills/impl-review-*/SKILL.md      Tier 0 — RFC conformance
    .claude/skills/code-review-*/SKILL.md      code lenses
    .claude/skills/design-review-*/SKILL.md    design lenses
    .claude/skills/review-fix/tiers.md         the ordering manifest
    .claude/skills/findings-output.template.md the output contract

`ship` **reads** these and never writes them. The `*-chain` and `review-fix`
skills beside them are Claude Code orchestration (Agent tool, depth-1 fan-out,
worktree-per-fix) and are deliberately not used — this skill dispatches the same
rules with its own machinery.

## Discovery and the accounting invariant

The live lens set is the DIRECTORY NAMES on disk, and `scripts/ship-lens` reads
them: it globs the three patterns, reads `tiers.md`, and halts when discovery
finds no lens, when a discovered lens has no row in the manifest, or when the
manifest cannot be read (RFC 234). This is the same assertion `/review-fix`
makes, and it exists because the dangerous failure is silent: a review that
quietly runs with fewer lenses looks exactly like a clean one.

**Do not re-derive that set by hand.** Run `ship-lens -s <rs> missing -t <N>`
before dispatching the tier: with no ledger line written yet, every lens the
tier assigns comes back `UNDISPATCHED`, and that list IS the dispatch set. It is
the same computation the tier closes on, so the set you dispatched and the set
you are held to cannot disagree. A hand-run copy of the glob and the halts is a
second implementation of one invariant, and only one of two copies is ever
exercised: the other's errors stay invisible until a review runs short, which is
the failure this assertion is for.

Two rules stay in the script because it is what applies them: the `*-chain`
skills are orchestrators and are never discovered, and a `tiers.md` row whose
skill directory is gone warns and is dropped, so a retired lens cannot hold a
tier open.

## Do not merge the rules

The rules look overlapping and are not. `design-review-minimum-context` is about
implicit string-parsing contracts and global memory, not decomposition.
`design-review-scale-restraints` is narrowly about load engineering at 1 QPS,
not general YAGNI. `code-review-concrete-names` (drop filler) and
`code-review-no-smurf-naming` (drop redundant type info) push the same
direction, not opposite ones.

The narrow scoping is engineered — `no-smurf-naming` carries a medium-relative
exception and a carve-out for `<Operation><Entity>Result`, which are the
fingerprints of a rule sharpened against real false positives. That precision is
what makes a single-lens adversarial reviewer accurate, and `code-review-chain`
records that a generalist applying all lenses at once **under-finds**.

**One rule, one reviewer.** If cost bites, skip lenses that cannot fire on the
changed file types — filter, never merge.

## Tier order is load-bearing

From `tiers.md`: every rule is assigned a tier by **the blast radius of the fix
its findings induce**, not by importance. Tiers are triaged in order so a
structural finding is decided before the nits its fix would delete.

| Tier | Shape                   | Note                                                         |
| ---- | ----------------------- | ------------------------------------------------------------ |
| 0    | RFC conformance         | The only tier whose triage may conclude **the RFC is wrong** |
| 1    | multi-file / structural |                                                              |
| 2    | single-file             |                                                              |
| 3    | 1–4 lines               | Renames, wording, comments                                   |

Run tiers **sequentially**; the tier base moves as each tier's accepted fixes
integrate, so Tier 2 must see the tree Tier 1 left. Parallelise within a tier.

Tier 0 findings are special: prefer amending the code, but when the
implementation is right and the RFC was wrong, say so — that is the
reconciliation point between intent and reality, and per `rfc/README.md` the
code wins.

## Applicability filter

Skip a lens whose subject cannot appear in the change. A bash-only change does
not need `code-review-structured-payloads`; a markdown-only change needs almost
none. A skipped lens must be visible, never silent.

Where that visibility lives is `<rs>/findings/lens-ledger.tsv` — append-only,
one line per event, every line written by the act it records. The skill's
`scripts/ship-lens` appends them and answers one question (RFC 234):

    ship-lens -s <rs> start   -t <N> -l <lens> -c <child-id>
    ship-lens -s <rs> done    -t <N> -l <lens>
    ship-lens -s <rs> skip    -t <N> -l <lens> -r <reason>
    ship-lens -s <rs> missing -t <N>

**`start` is appended AT DISPATCH** — when the child is spawned, before it does
any work. Not when it replies, not when the tier is written up. That order is
the mechanism: after it, "claimed and no report on disk" can only mean the child
really failed, which is a fact to act on rather than a reconstruction to
distrust. The plan it replaces was written from memory after the review, and the
archive carries claims that a lens ran where no report exists; RFC 234 counts
them.

`missing -t <N>` compares NAMES to FILENAMES and prints one line per fault, of
five kinds: `STALLED` — a `start` with no `tier<N>-<lens>.md` on disk, or an
empty one; `UNDISPATCHED` — a lens in this tier with neither a `start` nor a
`skip`; `UNDECLARED` — a `tier<N>-*.md` on disk that no `start` line names, the
batched review; `SHARED-CHILD` — two `start` lines claiming one child id; and
`MALFORMED` — a ledger record this script did not write: the verb, the field
count and the non-emptiness of every field must all agree, so a truncated record
is caught as well as an unknown verb. It never opens a report, so it is not a
second reviewer. The list is closed, and each kind is named here because a kind
this contract never declared names no repair — but what each one DETECTS is the
script's to say: read `ship-lens -h`, and where the two disagree the script is
the answer, not this file (RFC 234).

Four names in that directory are the RUN's own writing rather than a lens
report, and are never `UNDECLARED`: `tier<N>-fix-plan.md`,
`tier<N>-fix-report.md`, `tier<N>-outcomes.jsonl` and `tier<N>-missing.txt`, the
VERDICT `missing` leaves behind — three answers, never a transcript of stdout:
the faults, nothing at all when the tier is clean, or `INCOMPLETE` lines
carrying the reason when the check could not finish. The third state is why it
is not a copy of stdout: phase 7 and the workflow audit read the file and never
see the exit status, so a transcript is structurally incapable of saying "I
could not check". Nothing else is exempt. The exemption is an enumerated list
and never a filename shape — a shape nothing writes is a hole in the only check
this script exists for, because a batched review can be given any name at all.
Add a run-written artifact, add it here first, and enumerate it in the script
even when today's glob cannot reach it: an exempt set that leans on the glob's
shape has stopped being a list. A carve-out in the script for a name no document
defines is the defect this rule removed once already.

**The tier closes only when `missing` exits 0.** Not the next tier, not the
suite, not the land — and empty stdout is not the test, because a corpus
`ship-lens` could not read prints nothing either and halts with its own status
(RFC 234). Each line it does print names its own repair: re-dispatch that lens,
or append a `skip` carrying the real reason — "child stalled twice" is a real
reason, and the archive shows runs batching as a recovery from a stalled
fan-out, so a rule that only forbids batching breaks at the next stall. **The
skip IS the override**, which is why there is no override flag.

State the reason **once** for the whole skipped set when it is one reason — the
same `-r` text on each of those `skip` lines — rather than inventing a fresh
sentence per lens. One shared justification is easier to challenge than twenty
identical ones, and a lens that needs its own reason is exactly the one worth
reading. Phase 7 reads the skipped set and its reasons from the ledger, not from
a written-up summary.

## Dispatch output contract

`.claude/skills/findings-output.template.md` is the output contract, and it says
a caller's own output instructions **win**. This skill uses that clause; it does
not edit the template, and it does not edit a lens. Every lens dispatch adds one
instruction on top of the template (RFC 233).

Directly under `**Verdict:**`, one line:

    **Grade:** CLEAN | ADVISORY | SHOULD-FIX | BLOCKING

and every finding heading ends with its own tag in brackets —
`### The [n] marker is formatted twice [SHOULD-FIX]`. The report's grade is the
highest tag it carries. `**Verdict:**` is unchanged, so anything reading only
the verdict sees exactly what it saw before.

| Grade        | Means                                                                                      | The run then       |
| ------------ | ------------------------------------------------------------------------------------------ | ------------------ |
| `CLEAN`      | no findings (`🟢 PASS`), or the lens does not apply (`⚪ N/A`)                             | nothing            |
| `ADVISORY`   | a preference — a name, a phrasing, a comment, a "consider". Leaving it is not a defect.    | reports, unapplied |
| `SHOULD-FIX` | a corpus rule is broken, or a defect whose fix is contained. Fix before land.              | fix batch          |
| `BLOCKING`   | correctness, safety or data loss; or, Tier 0 only, the code is right and the RFC is wrong. | fix batch          |

The grade exists because the verdict cannot steer the spend: any finding at all
makes a report `🔴 FAIL`, and `FAIL` is what dispatches a fix child — the unit
that costs several times a lens. RFC 233 carries the measurement; it is not
repeated here, because a figure in a file edited this often goes stale silently
and an RFC is immutable. The lens already knows the difference ("one should-fix,
one consider"); the grade is it saying so where the caller can read it.

### Out of lens

A lens that sees a real defect **outside its own rule** reports it under a final
heading `## Out of lens` — `file:line`, what it saw, and its own grade tag,
exactly as for an in-lens finding (RFC 234). The coordinator then dispatches it
to that tier's fix batch by that grade, like any other finding. A defect is a
defect; which rule owns it is a fact about the corpus, not about the defect. RFC
234 counts the archived hand-offs that went to a lens with no inbox and were
fixed by nobody.

The lens still does not fix it itself. One rule, one reviewer, and the tree is
frozen while a tier's lenses read it — a lens that edits invalidates its
siblings' reads. The heading exists so the source stays traceable, not so the
finding can be parked.

One limit: **only within this run's own diff.** A defect in code the run did not
touch goes to the phase 7 report as an open item, named, with its `file:line`.
Fixing it would make the landed change something Ian did not approve, and the
approval gate is what that would defeat.

### The instruction, verbatim

This is the text a dispatch appends, and the text a re-dispatch quotes. It lives
here because a rule that says "quote the instruction" must name something that
still exists after the run's scratch directory is deleted.

```text
Output per `.claude/skills/findings-output.template.md`, PLUS: directly under
`**Verdict:**` emit a line

    **Grade:** CLEAN | ADVISORY | SHOULD-FIX | BLOCKING

and end EVERY finding heading with its own tag in brackets, e.g.

    ### The plan step names a file that does not exist [SHOULD-FIX]

ADVISORY   = a preference; leaving it is not a defect (naming, wording, a "consider").
SHOULD-FIX = a corpus rule is broken, or a contained defect. Fix before land.
BLOCKING   = correctness, safety or data loss; or (Tier 0 only) the code is right
             and the RFC is wrong.

The report's Grade is the highest tag it carries.

AND: a real defect you see that your own rule does not cover goes under a final
heading

    ## Out of lens

one tagged `###` finding each, with its `file:line`, what it is, and its own
grade tag. Report it there; do not fix it, and do not judge whose rule it is.
```

**A `🔴 FAIL` with no tags is malformed.** Re-dispatch that one lens with the
instruction quoted. Never infer the grade — inferring it is the caller grading
the code, which is the thing one-rule-one-reviewer exists to prevent.

### What gets a fix child

`SHOULD-FIX` and `BLOCKING` findings only. A tier whose reports are all `CLEAN`
or `ADVISORY` spawns no fix child and closes; the next tier runs against the
same tree, which it would have anyway.

The tier's fix plan (`<rs>/findings/tier<N>-fix-plan.md`) records four sets:
dispatched, held as advisory, **promoted or demoted with a one-line reason**,
and **contradicted** — each contradicted entry naming both sides and pointing at
its line in `tier<N>-outcomes.jsonl` (RFC 234). The coordinator may overrule a
tag in either direction — that is a judgement call it is entitled to make — but
a silent demotion is how a real defect becomes an unread line, so both
directions are written down.

Held findings reach Ian in the phase 7 report under **Advisory findings (not
applied)**, one line each: lens, `file:line`, title. `None` is a real result.

**A fix child runs its build, test and format commands through
[`scripts/ship-run`](../scripts/ship-run), never bare** (RFC 235). `rfc-155`'s
archive carries five `bin-test` logs inside ONE fix pass: each was paid into
that child's context and then re-read on every turn it had left. What the child
reads is the record and the failing excerpt; the whole log stays on disk, named
in the record, and it opens that only when the excerpt is not enough. The suite
runs ONCE per batch, at the end — never once per finding — while per-item
compiles are encouraged, through the same script, with only their error lines
read back. A long command is started in the background and the turn ENDS; the
completion follow-up wakes the child, so it neither sits blocking nor needs a
child of its own to wait for it. A suite run phase 5 will reuse still emits RFC
211's three-line `<rs>/suite-green.txt` record from the same shell, projected
out of the record `ship-run` just wrote by
[`scripts/ship-suite-green`](../scripts/ship-suite-green) — `ship-run`'s own
record does not replace it, the projection is one command that writes three
lines or halts, and the recipe is in [`../SKILL.md`](../SKILL.md), where the RFC
211 rule lives. Copy that recipe whole: it takes the invocation's instant BEFORE
`ship-run` and hands it to the projection as `-a`, which refuses any record that
started earlier. A REFUSED `ship-run` writes no file and leaves the previous
run's record standing, green, to be projected as this one's; `ship-run`'s exit
status cannot show that, because a wrapped command's own usage exit lands in the
same reserved band a refusal does, and `started=` can.

This is the one statement of that rule; `SKILL.md`'s phase 5 points here for it
rather than restating it.

### Tier 0 may not silently rewrite an approved decision

Tier 0's characteristic outcome is "the code and the RFC disagree", and per
`rfc/README.md` the code wins — the run amends the RFC and carries on. That is
unchanged, and it is most of them.

The exception is narrow: when the RFC line to be amended is one **Ian approved
at the gate**, the run is editing the agreement rather than correcting a stale
sentence. Stop and ask, in the `YOUR DECISION REQUIRED` block defined in
[`../../shared/operator-comms.md`](../../shared/operator-comms.md), quoting the
approved text and what the code does. The default taken in silence is to amend
the RFC to match the code.

This is not an ordering rule. Tier 0 already resolves before Tier 1 — the tiers
are sequential and each tier's fixes move the tree the next one reviews. It is
only the escalation.

## When two rules disagree

A **contradiction** is a finding that cannot be applied without breaking a
different rule the run is also bound by (RFC 234). Four counterparties, and the
archive carries all four. The record carries the kind and the reference as two
fields, because the kind is the axis the run is asked to count by and a kind
packed into a prefix makes that count a parse (RFC 234):

| `contradicts_kind` | `contradicts_ref`             | what it is                                                                         |
| ------------------ | ----------------------------- | ---------------------------------------------------------------------------------- |
| `lens`             | the lens directory name       | another rule in the corpus                                                         |
| `house`            | the doc path                  | a convention in `CLAUDE.md` or another doc                                         |
| `rfc`              | the RFC number                | a decision in the approved RFC for this run                                        |
| `tier`             | the lens whose fix it applied | a fix an earlier tier already applied, with `contradicts_tier` carrying which tier |

In the `CONTRADICTION` line below — prose, read by a human — the two are written
together as `<kind>:<ref>`, and the cross-tier one as `tier<N>:<lens>`.

**Synthesis first.** If one change satisfies both rules, make it. That is an
ordinary `ACCEPTED` outcome with the synthesis named in its reason, and it is
what most of the archive's recorded lens-versus-lens cases did (RFC 234) —
`listViolations()` satisfies both "use a verb" and "do not repeat the type". A
contradiction is only the case where both cannot hold at once.

**Otherwise halt THAT ITEM, not the batch.** Every other finding in the dispatch
is still worked. The fix child writes, in its report:

```text
CONTRADICTION: <lens>#<id> (<file>:<line>) vs <counterparty>
```

then both rules **quoted verbatim, each with the path it was read from**, and
one line saying why no change satisfies both. The quoting is the guard. Nothing
mechanical can refuse a plausible but wrong citation, and "this contradicts
another rule" is a cheaper sentence than doing the work — so a decline has to be
confirmed, not merely asserted.

**The fix child does not pick a side, and does not need to know who will.** It
records and moves on. A cross-tier case is the same case: a later tier that
would undo an earlier tier's applied fix records the `tier` kind, the tier and
the lens whose fix it is, rather than silently reverting it. Tiers are ordered
by the blast radius of a fix, not by importance, so holding the pen later is not
winning the argument. Which rule wins is a decision about the corpus, and the
corpus has one writer, [`/principle`](../../principle/SKILL.md).

A contradicted `SHOULD-FIX` finding is recorded, reported, and left — an unfixed
finding with two rules named is worth more than a fixed one whose losing rule
nobody can name. **A contradicted `BLOCKING` finding stops the run and goes to
Ian**, in the `YOUR DECISION REQUIRED` block defined in
[`../../shared/operator-comms.md`](../../shared/operator-comms.md), quoting both
rules and the code. That is the same reasoning as Tier 0 above: where nothing
downstream catches the mistake, the run does not settle it alone, and a
`BLOCKING` finding dropped because two rules disagreed is a correctness defect a
review looked straight at and cleared. The default taken in silence is to leave
the code as the implementation has it and record the contradiction, because that
is the state the run is already in.

## The outcome record

The fix child appends one JSON object per line to
`<rs>/findings/tier<N>-outcomes.jsonl`, **one record per lens finding — never
per merged triage item** (RFC 234). One edit answering findings from three
lenses writes three records with the same reason. Collapsing them is what
destroys the per-lens rate: an archived run declines a single item that three
named lenses raised, and no rate can attribute that decline.

```text
{"lens":"code-review-error-bubbling","finding_id":1,"grade":"SHOULD-FIX",
 "file":"public-web/src/main/kotlin/Routing.kt","line":396,"outcome":"CONTRADICTED",
 "contradicts_kind":"lens","contradicts_ref":"code-review-no-leaks",
 "out_of_lens":false,
 "reason":"carry the cause vs do not leak it; no change satisfies both"}
```

One record is ONE line; the example is wrapped to fit the page. Every column
below earns its place from a count of the archive that RFC 234 carries; the
figures are not repeated here, for the reason the grade table gives above.

| field              | required                               | why                                                                         |
| ------------------ | -------------------------------------- | --------------------------------------------------------------------------- |
| `lens`             | always                                 | the canonical directory name — most archived declines name no lens          |
| `finding_id`       | always                                 | archived ids are private per run, so no two reports join                    |
| `grade`            | always                                 | echoed from the finding's tag                                               |
| `file`, `line`     | always                                 | a location almost never sits next to an archived decline                    |
| `outcome`          | always, closed enum                    | archived outcomes are free prose as often as not — `DONE`, `left untouched` |
| `decline_class`    | only when `DECLINED`                   | a decline rate means nothing without knowing whether the rule was wrong     |
| `contradicts_kind` | only when `CONTRADICTED`, closed enum  | the axis phase 7 counts by, so it is a field of its own and never a prefix  |
| `contradicts_ref`  | only when `CONTRADICTED`               | the lens, doc path or RFC number that kind points at                        |
| `contradicts_tier` | only when `contradicts_kind` is `tier` | which earlier tier's applied fix the finding would have undone              |
| `out_of_lens`      | always, `true` or `false`              | makes the hand-off countable, and a later tier's duplicate visible          |
| `reason`           | always                                 | one line; a named cell stops prose leaking into the outcome                 |

**Only** means only: a field that does not apply is **absent**, never `null`,
never `""`, never `"none"`, and a field that does apply is never omitted. A
placeholder is a value, so `jq 'select(.contradicts_kind)'` would count records
rather than contradictions; and an optional `out_of_lens` cannot tell a finding
that was in lens from a child that forgot the field, which is the one thing the
flag exists to count. The key's presence IS the fact — the same promise the
ledger keeps by refusing an empty column instead of writing one (RFC 234).

**The record is written by an encoder, never typed as a literal.** `reason` and
`file` are free text: a reason that quotes a rule — `"use a verb"` — or a path
carrying a backslash produces a line that is not JSON, and the damage is silent,
because nothing downstream reads this file (`ship-lens` compares names to
filenames and never opens it, RFC 234). Build the record in `python3` and let
`json.dumps` spell it:

```text
python3 -c 'import json, sys
record = {"lens": sys.argv[1], "finding_id": int(sys.argv[2]), "grade": sys.argv[3],
          "file": sys.argv[4], "line": int(sys.argv[5]), "outcome": sys.argv[6],
          "out_of_lens": sys.argv[7] == "true", "reason": sys.argv[8]}
print(json.dumps(record))' \
  code-review-error-bubbling 1 SHOULD-FIX public-web/src/main/kotlin/Routing.kt 396 \
  ACCEPTED false "carry the cause: Routing.kt now names it" \
  >> "$RS/findings/tier$N-outcomes.jsonl"
```

`decline_class` and the `contradicts_*` fields are added to that dict on their
own arm and on no other. `finding_id` and `line` are numbers; every other value
is a string.

**A line that does not parse is not a record.** It is a finding with no verdict,
and no counter can say which one — so the child that wrote the file is the only
thing that can catch it. Before it reports, it reads its own file back: every
line parses, and every conditional field is present exactly on its own arm.

```text
python3 -c 'import json, sys
ARM = {"decline_class": "DECLINED", "contradicts_kind": "CONTRADICTED",
       "contradicts_ref": "CONTRADICTED"}
for number, line in enumerate(open(sys.argv[1]), 1):
    try:
        record = json.loads(line)
    except ValueError as bad:
        raise SystemExit("line %d does not parse: %s" % (number, bad))
    for field, arm in ARM.items():
        if (field in record) != (record["outcome"] == arm):
            raise SystemExit("line %d: %s on a %s record" % (number, field, record["outcome"]))
' "$RS/findings/tier$N-outcomes.jsonl"
```

Rewrite the offending line and read back again; do not hand-edit around it. This
is the writer checking its own writing, not a second reviewer and not a second
close condition: the tier still closes on `missing` exiting 0, and `missing`
neither opens this file nor judges it (RFC 234).

A finding's `finding_id` is **its position in its own report** — the 1-based
index of tagged `###` headings in file order, counting the `## Out of lens` ones
with the rest. It is the only id an auditor can recompute from the report
itself.

`outcome` is closed:

| value          | means                                                    |
| -------------- | -------------------------------------------------------- |
| `ACCEPTED`     | applied                                                  |
| `PARTIAL`      | applied in part                                          |
| `DECLINED`     | not applied, and not agreed                              |
| `DEFERRED`     | agreed, not here — it becomes an open item in the report |
| `CONTRADICTED` | halted with both rules named, per the section above      |

`decline_class` is closed too, and the corpus owns it: take the class from the
rejection-class table in `.claude/skills/review-fix/SKILL.md`, read when the
record is written, and spell it as that table spells it. The names are not
copied here. A copy is an unchecked one — a class added or renamed there would
leave these records spelling a vocabulary the corpus has moved on from, and the
decline rates would stop joining across runs without anything saying so (RFC
234). This skill takes the names and not the per-finding operator conversation
that surrounds them there — it has one approval gate and does not reopen it per
finding.

`CONTRADICTED` is deliberately NOT the corpus's `discarded-conflict`. That value
records a side that WAS chosen over a conflicting fix; this one records that no
side was chosen. They must not be collapsed.

**The fix report keeps the narrative.** The fix child writes it to
`<rs>/findings/tier<N>-fix-report.md` — the name the ledger check exempts, which
is why it is fixed here rather than chosen by the child (RFC 234) — and it says
what the child DID; the record carries the per-finding verdicts, and the reason
text lives there, once. **One report per tier.** A tier that dispatches two fix
children has two narratives, and a second name the exemption does not carry is
`UNDECLARED`, so the tier does not close until they are that one file. The check
is the enforcement, and it is cheaper to concatenate two files than to exempt a
filename shape that every batched report could then be given. JSON Lines because
a tier may spawn two or more fix children and appending lines from several is
safe where editing one table is not, because counting across runs is `cat`, and
because the findings directory is under `.scratch/`, so no formatter ever
rewrites it.
