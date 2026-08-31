---
name: slice
description: >-
  Runs one already-specified product slice from its ID to landed code and
  updated product docs: resolves the slice in product/NNNN-*/spec.md, gates on
  its `Needs:` dependency edges (BLOCKS refuses, PREFER asks once, CONFLICTS
  only warns), assembles a /ship instruction from the slice text plus the
  standing product decisions plus RFC and migration numbers claimed live from
  the repo, runs /ship, and then writes the brief ledger line and
  product/STATUS.md. Use when Ian names a slice ID — "start work on
  search/04/similar-colleges", "do money/02", "run first-value/05" — or invokes
  /skill:slice. Sits between chart and ship: chart decides what the slice is,
  slice dispatches it, /ship lands it.
---

# slice

One slice ID in; landed code AND updated product docs out. The slice text was
already written by /chart and already approved at its gate 2 — this skill does
not re-decide it. It resolves it, checks that it may run now, hands /ship a
complete instruction, and closes the books afterwards.

## Peer of chart and ship, not an entry point of chart

**Verdict: peer.** It reads chart's artifacts and writes back to chart's ledger,
but that is a data interface, exactly as /ship's interface to /chart is the
slice instruction. What makes something an entry point is that it runs _inside_
another skill's contract and phases; this runs inside its own. Ian invokes it by
name with an ID, not by resuming a brief; it never opens a brief, never runs a
chart gate, never writes a spec; and it _drives_ /ship, which /chart's EXECUTE
phase describes but does not itself do. So the layer reads:

    chart  — decides WHAT to build, writes slices        (product judgement)
    slice  — decides WHETHER a named slice may run now,  (dispatch judgement)
             and dispatches it
    ship   — builds and lands it                          (engineering)

/chart's EXECUTE phase is _satisfied by_ calling this skill once per slice. That
is delegation between peers, not containment.

## Non-negotiables

1. **Never modify `.claude/**`.** Same rule as chart and ship.
2. **The slice text is not rewritten here.** A slice that is wrong goes back to
   /chart as a new numbered decision or a new slice. This skill may only
   _assemble_ — slice text, standing decisions, live numbers — never re-specify.
   (Same reason chart holds product judgement at SPEC & SLICE: 0001 D12.)
3. **A BLOCKS edge refuses. A PREFER edge never refuses.** See the gate below.
4. **Numbers are claimed at run time, never copied from a doc.** RFC numbers and
   migration numbers move under you while parallel runs are open.
5. **A run that lands code without updating `product/**` is NOT finished.** The
   ledger and `product/STATUS.md` are part of the deliverable, not paperwork.
6. **This skill never lands code itself.** No worktree, no RFC, no commit — that
   is /ship's contract, including its approval gate and the pre-commit hook.

## Slice IDs

    <brief>/<milestone>.<step>/<name>        e.g. search/04/similar-colleges

Brief short names map to directories: `first-value` = `product/0001-*`,
`deletion` = `0002-*`, `money` = `0003-*`, `search` = `0004-*`. Step decimals
mark slices inserted after the fact (`first-value/03.5/college-list-in-chat`); a
slice split during /ship's design phase takes a letter instead
(`first-value/04a`, `first-value/04b`). IDs are permanent; never renumber. **The
number never grants or denies permission to start — only the `Needs:` line
does.** Adjacency is a plan, not a dependency.

**Refuse a bare old letter.** "S4" is ambiguous — brief 0001's S4 is the
admissions layer, brief 0004's S4 is similar colleges, and 0003 numbers with
`M`. Answer with the candidates and ask for a full ID:

> "S4" is ambiguous across briefs — 0001 S4 (admissions, landed RFCs 140/148)
> and 0004 S4 (similar colleges). Say `search/04/similar-colleges` or
> `first-value/04b/admissions-in-chat`.

Do not guess from context. Guessing here spends a whole /ship run on the wrong
thing.

## No ID? Print the board.

Invoked with no slice ID — "what can I work on?" — run the board and stop:

    .prime/agent/skills/slice/scripts/slice-board

It computes READY / IN FLIGHT / BLOCKED / DEFERRED / LANDED from the `Needs:`
lines in every `spec.md`, the LANDED rows in the briefs, and live runs from
`ship-status`. It is the answer to "what may I kick off", and it is **computed,
never remembered** — do not summarise it from `STATUS.md` or from your own
memory of an earlier turn. Exit 1 means it found a doc defect; report the defect
rather than the board.

`-p` is porcelain for scripting; `-q` prints defects only.

## Phases

```
RESOLVE -> GATE -> ASSEMBLE -> SHIP -> CLOSE OUT
```

### 1. RESOLVE

Run `slice-board` first — it answers where the slice stands in one line, and
whether the docs are clean. Then read the slice itself:

    grep -rn "search/04\|similar-colleges" product/0004-*/spec.md product/0004-*/brief.md

Headings may still carry the old letter (`### S4 — ...`) with the ID in
parentheses on first mention. Collect four things, verbatim:

- the **slice text** — intent, what to build, the repo foundations it names;
- its **acceptance criteria**, including the named door;
- its **`Needs:` line** (kinds + targets + reasons);
- the brief's **gate decisions** it cites by number (D8, D20, ...), read from
  the same brief.

Then read the **ledger** in `brief.md` for the states of its `Needs:` targets —
the ledger is ground truth, `STATUS.md` is the view. If the slice is already
LANDED, stop and say so. If no `Needs:` line exists in the doc, that is a doc
defect: say so, state the edges you can read off the slice's own "Build on"
text, and get them written into `spec.md` before the run. Never carry a
dependency that lives only in a session note — `.scratch/` is gitignored and
transient, and an unwritten edge is how folklore starts.

A `Needs:` entry with **no reason is not a dependency** — reject it and report
it as a doc defect rather than blocking on it.

### 2. GATE

| Kind          | Meaning                              | Action                               |
| ------------- | ------------------------------------ | ------------------------------------ |
| **BLOCKS**    | technical; cannot proceed without it | **refuse** while unmet               |
| **PREFER**    | product judgement                    | **ask once**, one line, then proceed |
| **CONFLICTS** | same files, rebase risk              | **warn**; never an order             |

**BLOCKS unmet → refuse, and name the target and its reason.** Do not
half-start, do not claim a worktree:

> `search/04/similar-colleges` BLOCKS on `search/03/the-index` — weighted
> distance runs over the index's percentile columns, and the index does not
> exist yet. Run `search/03/the-index` first.

**`Status: DEFERRED` → confirm once, like a PREFER.** A deferred slice is parked
by intent, not blocked by anything. It never appears under READY on the board.
Ask plainly — "`search/06/unattended-refresh` is marked DEFERRED. Start it
anyway?" — and if Ian says yes, remove the `Status:` line as part of the run's
close-out, because the board must match the decision.

**PREFER → a one-line override question, with the reason, defaulting to defer.**
It never refuses:

> `first-value/05/family-cost-report` PREFERs `money/02/component-split` (the
> parent-facing artifact should show the component split, not one blended
> number) and `money/03/comparison-contract` (the report compares schools, so it
> should carry the five assumption lines). Both are product judgement, neither
> is technical. Run it now anyway? [default: wait for M2/M3]

Record his answer verbatim — it goes in the report and, if he overrode, in the
ledger line for that slice.

**CONFLICTS → warn only.** It says two slices edit the same files, so the later
one rebases. It is **not** an ordering: a CONFLICTS pair may share a wave and
run in parallel in **separate** worktrees — expect a rebase, not a wait. A stale
conflict note must never harden into a blocker: `money/02/component-split` was
recorded for weeks as blocked on `search/03/the-index` and never was. Check for
live runs before warning:

    .prime/agent/skills/ship/scripts/ship-status
    git worktree list

If the conflicting slice has no live run, say "clear to run — rebase risk only".

### 3. ASSEMBLE

Build one /ship instruction from four sources. Nothing else belongs in it.

1. **The slice's own spec text and acceptance criteria**, quoted, not
   paraphrased — including the door.
2. **The standing decisions it inherits.** These are not optional and are not
   re-litigated (chart non-negotiables 4–6; brief 0001 D10/D12):
   - **Reachability named** — the instruction states the door a real user walks
     through. A slice whose value is unreachable is not done, whatever its tests
     say (0001 shipped three slices of cost truth with no door; S3.5 had to be
     added after the fact).
   - **Value before ask** — for any user-facing flow: invite, allow
     start/stop/resume/later, degrade gracefully on decline, never gate on
     completion.
   - **DDL at the /ship gate (0001 D10)** — if the slice adds any table, the
     instruction directs /ship to present the proposed DDL _explicitly at its
     approval gate_, not buried in the RFC. Ian approves every new table.
   - **Guided, not gated (0001 D11)** — a profile or flow can be started,
     stopped, resumed, or skipped, and every surface downstream of it degrades
     to a labelled answer. No feature is locked behind completion.
   - **The money vocabulary (brief 0003)** — any slice that talks about cost
     says _tuition and fees_, _housing and food_, _the published price_, _a
     financial aid offer_; never subtracts loans from a price; and lets no bare
     source code reach a tool result.
3. **Live numbers** (below).
4. **The gate answer** from phase 2, if a PREFER was overridden.

State the expected lane (A — rfc for anything design-bearing; B — quick only for
a genuinely mechanical slice) and let /ship veto it.

#### Live numbers — claimed at run time, twice

Compute these at the **start** of the run, and **again immediately before the
commit**, because parallel /ship runs claim numbers while yours is open. A
number read once at kickoff and used at commit is a collision.

    # next free RFC number: highest RFC file, and any in-flight rfc branch
    ls rfc | grep -Eo '^[0-9]+' | sort -n | tail -1
    git branch -a --format='%(refname:short)' | grep -Eo 'rfc-[0-9]+' | sort -t- -k2 -n
    git worktree list

    # next free migration number
    ls db/schema | grep -Eo '^[0-9]+' | sort -n | tail -1

Next free RFC = **one more than the highest of** the RFC files and any
`pipeline/rfc-<n>` branch or worktree (a claimed branch has no file yet). Next
free migration = highest file + 1, zero-padded to four digits. /ship's
`ship-claim` claims the RFC branch atomically and will bump on a collision — the
number in the instruction is a starting point, and the report states the number
that was actually claimed.

> Worked example, **as of 2026-08-30 only** — recompute, never copy: highest RFC
> file 148, branch `pipeline/rfc-147` live → next free RFC **149**; highest
> migration `0059` → next free **0060**.

### 4. SHIP

Invoke `/skill:ship` with the assembled instruction.

**Stamp the run with the slice ID, as soon as /ship has a run scratch.** This is
required, and it is one command:

    .prime/agent/skills/ship/scripts/ship-state -s <run-scratch> set SLICE <slice-id>

Without it the run is invisible to `slice-board` as a slice: the board lists it
under "unattributed live runs" and **refuses to guess** which slice it belongs
to, because guessing attribution is exactly how a wrong claim reached
`STATUS.md` before (`pipeline/rfc-147` was recorded as `search/03/the-index`; it
was the substrate slice `search/03a/published-codebooks`). The stamp is what
makes the board say IN FLIGHT instead of READY, and what makes a CONFLICTS
warning fire for anyone else. /ship owns the worktree, the RFC, its single
approval gate, implementation, tiered review, the `bin/pre-commit` gate, and the
fast-forward land. Do not duplicate any of it and do not answer product
questions inside the run on your own authority — a product question surfacing
mid-run is a spec defect and goes back to /chart (0001 D12).

While the run is open, stay out of its worktree. Read its state with
`ship-status` / `ship-state`, never from memory.

If /ship stops at the hook, this skill stops too. Report; do not work around it.

### 5. CLOSE OUT — required

The run is not finished when the code lands. It is finished when the product
docs say what the product now is.

**a. The brief's ledger** (`product/NNNN-*/brief.md`), one appended line in the
house format — slice ID, RFC number, code SHA + doc SHA, date, one-line what:

    search/04/similar-colleges (S4) LANDED as RFC 149 (main@<code> + <doc>,
       2026-08-31) — query-time similar_colleges tool: weighted distance over
       the index percentile columns, axes and constraints named in every
       response.

Append; never edit a landed line. Record a PREFER override here too ("run early
on Ian's call, 2026-08-31").

**b. `product/STATUS.md`**, five edits, all of them:

- the **TL;DR** — rewritten, most important first, never "see below";
- the **`Updated:` line** — date, what landed, and the new next-free RFC and
  migration numbers;
- the **work table** — this slice's row, and the next slice's honest state;
- the landed feature's **user-manual entry** under "The product today", which
  **names its door** (how a real user reaches it), what it does, how it
  degrades, and any rollback knob;
- the **wave board** — the landed slice leaves it, and any slice it unblocked
  moves to the ready wave.

When `STATUS.md` and a brief ledger disagree, the ledger wins and `STATUS.md`
gets fixed.

**c. Re-check the next slice** against what actually landed. The code wins over
the spec; a drifted next-slice instruction is a defect to report to /chart now,
not a surprise at its kickoff.

## End-of-run checklist

- [ ] Full slice ID resolved from `product/`, not guessed from a letter.
- [ ] Run stamped: `ship-state -s <run-scratch> set SLICE <slice-id>`.
- [ ] `slice-board` re-run at the end and it agrees with what landed (exit 0).
- [ ] Every `Needs:` edge evaluated: BLOCKS met, PREFER asked and answered,
      CONFLICTS checked against live runs.
- [ ] Instruction carried the slice text, acceptance criteria, the door, the
      standing decisions (reachability, value-before-ask, DDL-at-gate), and live
      numbers.
- [ ] RFC and migration numbers recomputed immediately before the commit.
- [ ] /ship landed: code SHA and doc SHA in hand, `PHASE=complete`.
- [ ] Brief ledger line appended.
- [ ] `product/STATUS.md`: TL;DR, `Updated:` line, work table, user-manual entry
      with its door, wave board.
- [ ] Next slice re-checked against what landed; drift reported.
- [ ] Report to Ian: slice ID, RFC number claimed, SHAs, gate answers, open
      items, and the `.scratch/ship-archive/<run-id>/` path.

Any unchecked box means the run is still open. Say so plainly rather than
declaring done.
