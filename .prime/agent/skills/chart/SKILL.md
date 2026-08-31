---
name: chart
description: >-
  Runs the unicoach product layer: takes a product question from Ian and
  carries it through FRAME -> PRIORITISE -> DISCOVER -> SPEC & SLICE ->
  EXECUTE -> LEARN, producing a numbered brief under product/, parallel cited
  research reports, exactly two human gates (prioritisation and spec), a set
  of /ship-ready slice instructions, and a ledger mapping slices to landed
  SHAs. Use when Ian raises a product question, asks what to build next, asks
  to run or resume a product brief, or invokes /skill:chart. Sits strictly
  above the ship skill: it decides WHAT to build and writes instructions;
  /ship builds them. Worked examples: product/0001-v1-differentiator (brief +
  spec + research, S1-S3 landed as RFCs 133-135) and
  product/0002-account-data-deletion (framed brief awaiting gate 1).
---

# chart

A product question in; landed, user-reachable value out. Two human touchpoints:
the prioritisation gate and the spec gate. Everything else is this skill's job.

## Non-negotiables

1. **Never modify `.claude/**`.** Same rule as ship.
2. **Exactly two gates, never zero, never three.** Gate 1 (prioritisation)
   decides the bet; gate 2 (spec) decides the slices. Every gate is a numbered
   decision list (`D1, D2, ...`) with **defaults pre-chosen** — approving costs
   one word, amending one line. Between and after the gates, do not ask; act on
   the recorded decisions.
3. **Product judgement is spent at SPEC & SLICE, not delegated to /ship.** Each
   slice instruction is written so its acceptance criteria already embody the
   product ethos and the gate decisions. /ship's job is production-quality
   implementation of the instruction; it is never asked to reason about product
   value. If a /ship run surfaces a product question, it comes back here — that
   is a spec defect, not a /ship decision. (Brief 0001, D12.)
4. **Reachability is a standing acceptance criterion.** For every slice ask:
   _can a real user actually reach this?_ Name the door — the chat tool, the
   screen, the link — in the slice. Brief 0001 shipped three slices (RFCs
   133-135) of cost truth keyed off the student's college list while no
   student-facing door to that list existed; S3.5 had to be added after the
   fact. A slice whose value is unreachable is not done, whatever its tests say.
5. **Value before ask, in every slice.** Ian's standing ethos (brief 0001, D12):
   never force a user through a step whose value they don't yet understand.
   Invite, allow start/stop/resume/later, degrade gracefully on decline, never
   gate on completion. This sentence-level policy goes into the **acceptance
   criteria of every slice that touches a user-facing flow** — the money profile
   (S2) is the pattern, not the exception.
6. **Ian approves every new database table, with visible DDL at the gate.**
   (Brief 0001, D10 — standing.) A slice that adds tables says so in its
   instruction, and the instruction directs /ship to present the proposed DDL
   explicitly at its approval gate, not buried in the RFC.
7. **Committed gate decisions are immutable.** Like RFCs: a changed decision is
   a new numbered decision (or a new brief), never an edit of the old one. The
   brief's `Status:` ledger and post-gate slice additions (e.g. S3.5) are the
   living, append-only parts.

## The artifact: `product/NNNN-slug/`

    product/
      STATUS.md                      # cross-brief tracker: manual, priorities, prompts
      NNNN-slug/
        brief.md                     # framing, candidates, gate-1 Ds, ledger
        research/*.md                # DISCOVER reports, one per subagent
        spec.md                      # slices + gate-2 Ds (created at SPEC & SLICE)

Number = max existing + 1, zero-padded to four digits. The brief opens with a
`Status:` block that is the run's state on disk — phase, gate outcomes, and the
**ledger**: one line per landed slice mapping it to its RFC and SHAs, e.g.

    S2 LANDED as RFC 134 (main@583c108a + d550012d, 2026-08-25) — one-line what.

Update the ledger immediately after each /ship run lands (code SHA + doc SHA).
Resume a brief by reading `Status:`, never from session memory.

**`product/STATUS.md` is the cross-brief tracker** — a glanceable TL;DR of next
steps (rewritten on every update, never "see below"), a user manual of shipped
features (each entry names its door), the prioritised work table with honest
partial-progress state, a backlog of unscheduled ideas (/chart promotes backlog
items into the table or a brief; Ian appends freely), and Ian's paste-ready
kickoff prompts. Read it FIRST when this skill is invoked (it answers "where are
we?" before any brief does), and update it whenever a slice lands, a feature's
reachability changes, or priorities move. Briefs' ledgers are the ground truth;
STATUS.md is the view — when they disagree, fix STATUS.md.

## Phases

```
FRAME -> PRIORITISE (gate 1) -> DISCOVER -> SPEC & SLICE (gate 2) -> EXECUTE -> LEARN
```

### FRAME

Write `brief.md`: the question in one paragraph, the candidates (a table, with
each candidate's **existing foundation in the repo** — grep before you
speculate), and explicit success criteria for the decision. If Ian handed you
the answer rather than a question (brief 0002: "we should just build this"),
FRAME still runs — it captures the why (legal/store/testing), what the repo does
today, and the decisions Ian must make — and PRIORITISE collapses to gate 1 over
those decisions.

### PRIORITISE — gate 1

Fan out **parallel research subagents**, one per genuinely independent question
(brief 0001 used three: competitor landscape, data feasibility, user value).
Each child writes `research/<topic>.md` and must state its **method and cite
every load-bearing claim** — URLs inline, primary sources preferred, and an
honest note when a tool was unavailable. Children write files; the coordinator
reads the files — no answers through message paraphrase.

Synthesise the reports into a ranked table in the brief (rank, bet, verdict
across reports), then present **gate 1**: the numbered decisions with defaults.
Record Ian's answers in the brief verbatim, including amendments — an amendment
(0001: "report-as-wedge, invite-parent first-class") often IS the product
insight; carry it into every downstream slice.

### DISCOVER

Ground the chosen bet against the codebase before slicing: what tables, tools,
and surfaces actually exist, and where the brief's assumptions were wrong. Write
the corrections into `spec.md`'s preamble (0001: "colleges carries only overall
net_price; NO income-band prices"). Slices written against imagined schema waste
a /ship design phase.

### SPEC & SLICE — gate 2

Write `spec.md`: an ordered list of slices, **each one a self-contained /ship
instruction** with acceptance criteria. A good slice instruction contains:

- a **permanent slice ID**, `<brief>/<milestone>.<step>/<name>` — e.g.
  `search/03/the-index`, `money/02/component-split`. Brief handles:
  `first-value` = 0001, `deletion` = 0002, `money` = 0003, `search` = 0004; a
  new brief declares its handle here. Two digits so listings sort. An ID is
  assigned once and **never renumbered** — a slice inserted later takes a
  decimal step (`money/01.2`), and a slice whose scope changes that much is a
  new slice, not a renamed one;
- a **`Needs:` line, with a KIND and a REASON**, on every slice without
  exception:
  - **BLOCKS** — technical. Same table, same contract, same output consumed.
    Cannot proceed. Not overridable.
  - **PREFER** — product judgement. Could proceed; we choose not to. Overridable
    by Ian, and naming it PREFER makes the override visible instead of hidden.
  - **CONFLICTS** — both edit the same files. A rebase risk, **not** an order.
  - **`Needs: —`** — genuinely nothing.

  **A `Needs:` entry with no reason is not a dependency and must be rejected.**
  A slice parked by intent — valuable but deliberately not scheduled — carries
  `**Status:** DEFERRED` beside its `Needs:` line, because "we chose not to yet"
  is not a dependency and must not be written as one. Remove the line when it is
  scheduled.

  These lines are read by `.prime/agent/skills/slice/scripts/slice-board`, which
  computes what is startable. A malformed line is a hard error there, not a
  guess, so keep the shape exact. Verify every claimed edge against the binding
  decision text (the numbered Ds), never against a summary line in `STATUS.md` —
  summaries drift, decisions do not. And note that **adjacency in an ID never
  grants or denies permission to start**: `03` following `02` is a plan, not a
  dependency. This rule exists because a CONFLICTS edge on `bin/ingest-colleges`
  was written into a status doc as "waits on", with no reason, and hardened over
  three restatements into a blocker that held `money/02` for weeks;
- what to build and why it exists (one sentence of product intent);
- the repo foundations to build on, by RFC/table/tool name;
- what is decided here vs. **explicitly left to /ship's design phase** (0001 D6:
  "money-profile mechanism decided inside S2's RFC, not here");
- acceptance criteria that already embody the ethos (non-negotiables 4-6):
  reachability named, value-before-ask spelled out, DDL-at-gate flagged for any
  slice adding tables;
- the first-session test: what a new user sees, in which session.

Order slices so each lands user-visible value or directly enables the next; name
the slice where the aha becomes live end-to-end. Then present **gate 2**: slice
order + the numbered decisions with defaults. Record verbatim, including
standing rules Ian declares (D10/D12 pattern) — mark those `standing` so future
briefs inherit them without re-asking.

### EXECUTE

One run per slice, dispatched by the **`slice` skill** — "start work on
`search/04/similar-colleges`" — which resolves the ID, enforces the `Needs:`
edges, claims the live RFC/migration numbers, runs /ship, and writes the ledger
and `STATUS.md` back. Slices in the **same wave of `STATUS.md`'s wave board are
safe to run in parallel**; a CONFLICTS pair may still share a wave, it just
rebases. The kickoff is the slice instruction from `spec.md`, dispatched by ID
or pasted verbatim. In each run, Ian (or this session) is /ship's approval gate;
product questions that surface mid-run come back to the brief as appended
decisions or new slices. After each land: update the ledger line and
`product/STATUS.md` (work table + the landed feature's manual entry), and
re-check the next slice's instruction against what actually landed (the code
wins over the spec).

### LEARN

After the beat ships (or a pause): record in the brief what the runs taught —
gaps that became slices (S3.5), operational lessons worth a work-queue note (the
silent no-op ingest), and follow-on work that deserves its own brief (0001's
testing pain framed 0002). A lesson that changes how THIS skill works is a skill
edit, proposed to Ian — this file is code, gated like code.

## Relationship to ship

This skill never claims worktrees, writes RFCs, or lands code — that is /ship's
contract, including its own approval gate and the pre-commit hook. The interface
is one artifact: the slice instruction. If /ship needs product context beyond
its instruction, the instruction was underwritten; fix it in `spec.md`. Product
files (`product/**` and this skill) land on `main` through the normal gate like
any other change.
