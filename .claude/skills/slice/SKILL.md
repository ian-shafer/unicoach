---
name: slice
description: >-
  Runs one already-specified product slice from its ID to landed code and
  updated product docs: resolves the slice in product/NNNN-*/spec.md, gates on
  its `Needs:` dependency edges (BLOCKS refuses, PREFER asks once, CONFLICTS
  only warns), assembles a /work instruction from the slice text plus the
  standing product decisions, runs /work, and then writes the brief ledger
  line and product/STATUS.md. Use when Ian names a slice ID — "start work on
  search/04/similar-colleges", "do money/02", "run family/06" — asks what can
  be started, or invokes /slice. Sits between /chart and /work: /chart decides
  what the slice is, /slice dispatches it, /work lands it. The Claude Code
  successor to Prime Agent's /slice.
---

# slice

One slice ID in; landed code AND updated product docs out. The slice text was
already written by /chart and already approved at its gate 2 — this skill does
not re-decide it. It resolves it, checks that it may run now, hands /work a
complete instruction, and closes the books afterwards.

    chart  — decides WHAT to build, writes slices        (product judgement)
    slice  — decides WHETHER a named slice may run now,  (dispatch judgement)
             and dispatches it
    work   — builds and lands it                          (engineering)

/chart's EXECUTE phase is _satisfied by_ calling this skill once per slice. That
is delegation between peers, not containment: this skill runs inside its own
phases, never opens a brief, never runs a chart gate, never writes a spec.

Three script homes, all **in the original checkout** and used in place (each
script has `-h`):

    $BOARD  .prime/agent/skills/slice/scripts/slice-board
    $DOCS   .prime/agent/skills/chart/scripts        (docs-claim, docs-land)
    $SHIP   .prime/agent/skills/ship/scripts         (ship-state, ship-status)

## Talking to Ian

Every message follows
[`.prime/agent/skills/shared/operator-comms.md`](../../../.prime/agent/skills/shared/operator-comms.md).
This skill talks almost entirely in identifiers — slice IDs, decision numbers,
RFC numbers, the three dependency kinds — so rule 1 applies to nearly every
sentence it emits: say what the name IS the first time it reaches him, then use
the short form. The gate questions in [`references/gate.md`](references/gate.md)
are written in the required shapes; copy them.

## Non-negotiables

1. **Never edit this skill, its references, `$BOARD`, `$DOCS/**` or `$SHIP/**`
   from inside a run.** Fix the workflow between runs, through /work.
2. **The slice text is not rewritten here.** A slice that is wrong goes back to
   /chart as a new numbered decision or a new slice. This skill may only
   _assemble_ — slice text, standing decisions — never re-specify. (Same reason
   /chart holds product judgement at SPEC & SLICE: 0001 D12.)
3. **A BLOCKS edge refuses. A PREFER edge never refuses.** See GATE.
4. **Numbers are claimed at run time, never copied from a doc.** /work's claim
   phase reserves the RFC number (`ship-claim -l rfc`) when its spec level needs
   one; this skill never computes one into the instruction. Migrations have no
   number at all (RFC 180) — a slug filename and a line in `db/schema/ORDER`.
5. **A run that lands code without updating `product/**` is NOT finished.** The
   ledger and `product/STATUS.md` are part of the deliverable, not paperwork.
6. **This skill never lands code itself.** No RFC, no code commit — that is
   /work's contract, including its approval stop and the pre-commit hook. It
   does claim one worktree of its own, for the close-out DOCS, and that worktree
   can land nothing but `product/**`.
7. **The close-out is never written in the root checkout.** `docs-claim` ->
   write -> commit -> `docs-land` (RFC 201). `bin/pre-commit` refuses a commit
   in the root checkout, so a run that forgets is refused rather than silently
   doing it.

## Slice IDs

    <brief>/<milestone>.<step>/<name>        e.g. search/04/similar-colleges

The full grammar, handles and the never-renumber rule are in
[`/chart`'s slice-instruction reference](../chart/references/slice-instruction.md).
**The number never grants or denies permission to start — only the `Needs:` line
does.** Adjacency is a plan, not a dependency.

**Refuse a bare old letter.** "S4" is ambiguous — brief 0001's S4 is the
admissions layer, brief 0004's S4 is similar colleges, and 0003 numbers with
`M`. Answer with the candidates in a `YOUR DECISION REQUIRED` block and wait
(the shape is in `references/gate.md`). Do not guess from context: guessing here
spends a whole /work run on the wrong thing.

## No ID? Print the board.

Invoked with no slice ID — "what can I work on?" — run the board and stop:

    $BOARD

It computes READY / IN FLIGHT / BLOCKED / DEFERRED / LANDED from the `Needs:`
lines in every `spec.md`, the LANDED rows in the briefs, and live runs from
`ship-status`. It is the answer to "what may I kick off", and it is **computed,
never remembered** — do not summarise it from `STATUS.md` or from memory of an
earlier turn. Exit 1 means it found a doc defect; report the defect rather than
the board. `-p` is porcelain for scripting; `-q` prints defects only.

## Phases

```
RESOLVE -> GATE -> ASSEMBLE -> WORK -> CLOSE OUT
```

### 1. RESOLVE

Run `$BOARD` first — it answers where the slice stands in one line, and whether
the docs are clean. Then read the slice itself:

    grep -rn "search/04\|similar-colleges" product/0004-*/spec.md product/0004-*/brief.md

Collect four things, verbatim: the **slice text** (intent, what to build, the
foundations it names); its **acceptance criteria**, including the named door;
its **`Needs:` line** (kinds + targets + reasons); and the brief's **gate
decisions** it cites by number, read from the same brief.

Then read the **ledger** in `brief.md` for the states of its `Needs:` targets —
the ledger is ground truth, `STATUS.md` is the view. If the slice is already
LANDED, stop and say so. If no `Needs:` line exists, or an entry has no reason,
that is a doc defect: say so, state the edges you can read off the slice's own
foundations text, and get them written into `spec.md` (through /chart's docs
path) before the run. Never carry a dependency that lives only in a session note
— `.scratch/` is transient, and an unwritten edge is how folklore starts.

### 2. GATE

| Kind          | Meaning                              | Action                                |
| ------------- | ------------------------------------ | ------------------------------------- |
| **BLOCKS**    | technical; cannot proceed without it | **refuse** while unmet                |
| **PREFER**    | product judgement                    | **ask once**, one block, then proceed |
| **CONFLICTS** | same files, rebase risk              | **warn**; never an order              |
| **DEFERRED**  | parked by intent, not by an edge     | **confirm once**, like a PREFER       |

The message shapes for each are in [`references/gate.md`](references/gate.md). A
BLOCKS refusal names the target and its reason and claims nothing. A PREFER or
DEFERRED question defaults to not starting; record the answer verbatim — it goes
in the report and, if Ian overrode, in the ledger line. A CONFLICTS warning is
checked against live runs first (`$SHIP/ship-status`, `git worktree list`); with
no live run on the other slice, say "clear to run — rebase risk only".

### 3. ASSEMBLE

Build one /work instruction from three sources. Nothing else belongs in it.

1. **A first line `Slice: <id>`**, so /work stamps its run with the ID at claim
   time (that is what makes `$BOARD` say IN FLIGHT instead of listing an
   unattributed run — guessing attribution is how `pipeline/rfc-147` was once
   recorded as the wrong slice).
2. **The slice's own spec text and acceptance criteria**, quoted, not
   paraphrased — including the door, and what the spec leaves to /work.
3. **The standing decisions it inherits.** Not optional, not re-litigated (chart
   non-negotiables 4–6; brief 0001 D10/D11/D12; brief 0003):
   - **Reachability named** — the door a real user walks through;
   - **Value before ask** — invite, allow start/stop/resume/later, degrade
     gracefully on decline, never gate on completion;
   - **DDL at the approval stop (0001 D10)** — a slice that adds any table says
     so, and /work presents the DDL explicitly to Ian;
   - **Guided, not gated (0001 D11)** — a profile or flow can be started,
     stopped, resumed or skipped, and every surface downstream degrades to a
     labelled answer;
   - **The money vocabulary (brief 0003)** — _tuition and fees_, _housing and
     food_, _the published price_, _a financial aid offer_; never subtract loans
     from a price; no bare source code reaches a tool result.
4. **The gate answer** from phase 2, if a PREFER or DEFERRED was overridden.

State the dials you expect /work to choose (spec level and scrutiny, in its own
vocabulary: a slice that leaves design open is spec 3–4 / `full`; one whose
shape the spec fixes may be 2) and let /work veto them in its first message.
Nothing about product value, and no number of any kind.

### 4. WORK

Invoke `/work` with the assembled instruction, in this session. /work owns the
worktree, the RFC number, its approval stop, implementation, tiered review, the
`bin/pre-commit` gate and the fast-forward land. Do not duplicate any of it.

Check the stamp once /work has claimed: `$SHIP/ship-state -s <rs> get SLICE`
must print the ID; if it does not, set it. Ian is /work's approval gate; a
product question surfacing mid-run is a spec defect and goes back to /chart
(0001 D12) — do not answer it on your own authority. If /work stops at the hook,
this skill stops too: report, do not work around it. While the run is open, read
its state with `ship-status` / `ship-state`, never from memory.

### 5. CLOSE OUT — required

The run is not finished when the code lands. It is finished when the product
docs say what the product now is. The full recipe, the ledger line formats (with
and without an RFC) and the five `STATUS.md` edits are in
[`references/close-out.md`](references/close-out.md). In one line: claim
`docs-claim -s rfc-<n>-docs` (or a slug of your own for a close-out covering two
slices), write the ledger line and `STATUS.md` in that worktree, commit,
`docs-land`, and re-check the next slice against what actually landed.

## End-of-run checklist

- [ ] Full slice ID resolved from `product/`, not guessed from a letter.
- [ ] `$BOARD` clean (exit 0) before the run and again after the close-out, and
      it agrees with what landed.
- [ ] Every `Needs:` edge evaluated: BLOCKS met, PREFER/DEFERRED asked and
      answered, CONFLICTS checked against live runs.
- [ ] Instruction opened with `Slice: <id>` and carried the slice text, the
      acceptance criteria, the door, the standing decisions and the gate answer.
- [ ] Run stamped: `ship-state get SLICE` printed the ID while the run was live.
- [ ] /work landed: code SHA in hand, `PHASE=complete`, its `DONE` report seen.
- [ ] Close-out written in a claimed `docs/` worktree, not the root checkout.
- [ ] `docs-land` run: doc SHA in hand, worktree and branch gone.
- [ ] Brief ledger line appended, in the house format.
- [ ] `product/STATUS.md`: TL;DR, `Updated:` line, work table, user-manual entry
      with its door, wave board.
- [ ] Next slice re-checked against what landed; drift reported to /chart.
- [ ] Report to Ian: opens with `DONE` and one sentence a non-engineer could
      repeat, then slice ID, RFC number (or "no RFC, spec N"), both SHAs, gate
      answers, open items, and the archive paths
      (`.scratch/ship-archive/<run-id>/`, `.scratch/docs-archive/<run-id>/`).

Any unchecked box means the run is still open. Say so plainly rather than
declaring done.
