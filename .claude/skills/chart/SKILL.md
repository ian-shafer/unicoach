---
name: chart
description: >-
  Runs the unicoach product layer: takes a product question from Ian and
  carries it through FRAME -> PRIORITISE -> DISCOVER -> SPEC & SLICE ->
  EXECUTE -> LEARN, producing a numbered brief under product/, parallel cited
  research reports, exactly two human gates (prioritisation and spec), a set
  of /work-ready slice instructions, and a ledger mapping slices to landed
  SHAs. Use when Ian raises a product question, asks what to build next, asks
  to run or resume a product brief, or invokes /chart. Sits strictly above
  /slice and /work: it decides WHAT to build and writes instructions; /slice
  dispatches one and /work builds it. The Claude Code successor to Prime
  Agent's /chart. Worked example: product/0014-parents-in-the-room (brief +
  spec + research, ten slices landed as RFCs 221-254).
---

# chart

A product question in; landed, user-reachable value out. Two human touchpoints:
the prioritisation gate and the spec gate. Everything else is this skill's job.

The layer reads:

    chart  — decides WHAT to build, writes slices        (product judgement)
    slice  — decides WHETHER a named slice may run now,  (dispatch judgement)
             and dispatches it
    work   — builds and lands it                          (engineering)

`$DOCS` below is `.prime/agent/skills/chart/scripts` **in the original
checkout** — /chart uses Prime /chart's two landing scripts in place (each has
`-h`), the same way /work uses /ship's. They name branches `docs/<slug>` and
archive to `.scratch/docs-archive/`.

## Non-negotiables

1. **Never edit this skill, its references, `$DOCS/**` or `slice-board` from
   inside a run.** Fix the workflow between runs. A lesson that changes how this
   skill works is proposed to Ian and landed by /work: a skill edit is code,
   gated like code, and `docs-land` refuses a branch carrying one by
   construction (`.claude/**` is not under `product/`).
2. **Exactly two gates, never zero, never three.** Gate 1 (prioritisation)
   decides the bet; gate 2 (spec) decides the slices. Every gate is a numbered
   decision list (`D1, D2, ...`) with **defaults pre-chosen** — approving costs
   one word, amending one line. Between and after the gates, do not ask; act on
   the recorded decisions. Each decision is presented in the
   `YOUR DECISION REQUIRED` block: options as outcomes, one recommendation, one
   reason, the silent default.
3. **Product judgement is spent at SPEC & SLICE, not delegated to /work.** Each
   slice instruction is written so its acceptance criteria already embody the
   product ethos and the gate decisions. /work's job is production-quality
   implementation of the instruction; it is never asked to reason about product
   value. If a /work run surfaces a product question, it comes back here — that
   is a spec defect, not an engineering decision. (Brief 0001, D12.)
4. **Reachability is a standing acceptance criterion.** For every slice ask:
   _can a real user actually reach this?_ Name the door — the chat tool, the
   screen, the link — in the slice. Brief 0001 shipped three slices (RFCs
   133-135) of cost truth keyed off the student's college list while no
   student-facing door to that list existed; S3.5 had to be added after the
   fact. A slice whose value is unreachable is not done, whatever its tests say.
5. **Value before ask, in every slice.** Ian's standing ethos (brief 0001, D12):
   never force a user through a step whose value they don't yet understand.
   Invite, allow start/stop/resume/later, degrade gracefully on decline, never
   gate on completion. This goes into the **acceptance criteria of every slice
   that touches a user-facing flow**.
6. **Ian approves every new database table, with visible DDL at the gate.**
   (Brief 0001, D10 — standing.) A slice that adds tables says so in its
   instruction; /work's approval stop then presents the DDL explicitly, which
   its dials already require for any `db/schema/**` change.
7. **Committed gate decisions are immutable.** Like RFCs: a changed decision is
   a new numbered decision (or a new brief), never an edit of the old one. The
   brief's `Status:` ledger and post-gate slice additions (e.g. S3.5) are the
   living, append-only parts.
8. **`product/**` is never written in the root checkout.** Every write goes in a
   worktree this run claimed with `$DOCS/docs-claim`, and reaches `main` by
   fast-forward only through `$DOCS/docs-land` (RFC 201). `bin/pre-commit`
   refuses a commit in the root checkout, so this is enforced rather than
   remembered. Full recipe:
   [`references/landing-docs.md`](references/landing-docs.md).

## Talking to Ian

Every message follows
[`.prime/agent/skills/shared/operator-comms.md`](../../../.prime/agent/skills/shared/operator-comms.md):
a triage label on the first line, the outcome first, internal names expanded the
first time, every stop in a `YOUR DECISION REQUIRED` block with a default. The
two gates are where it bites: a gate is the one moment the run stops, so its
decisions take that block, and nothing in it is named by a bare identifier. This
skill also MINTS the identifiers everyone downstream has to explain — decision
numbers and slice IDs — so it carries one extra duty, under PRIORITISE below:
every committed decision gets a short label.

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

    family/10/claim-from-the-report LANDED as RFC 254 (main@0c9bfb73 + 3f53ae15,
      2026-10-11) — one-line what.

/slice appends the ledger line after each run lands. **Resume a brief by reading
`Status:`, never from session memory** — after compaction or a restart, read
`product/STATUS.md`, then the brief's `Status:` block, then any live docs run
under `$DOCS`'s worktrees (`git worktree list`, branches `docs/*`).

**`product/STATUS.md` is the cross-brief tracker** — a glanceable TL;DR of next
steps (rewritten on every update, never "see below"), a user manual of shipped
features (each entry names its door), the prioritised work table with honest
partial-progress state, a backlog of unscheduled ideas (/chart promotes backlog
items into the table or a brief; Ian appends freely), and Ian's paste-ready
kickoff prompts. Read it FIRST when this skill is invoked, and update it
whenever a slice lands, a feature's reachability changes, or priorities move.
Briefs' ledgers are the ground truth; `STATUS.md` is the view — when they
disagree, fix `STATUS.md`. Readiness is **not** read from it: `slice-board`
computes that from the specs.

## Phases

```
FRAME -> PRIORITISE (gate 1) -> DISCOVER -> SPEC & SLICE (gate 2) -> EXECUTE -> LEARN
```

Each phase that writes `product/**` runs inside a claimed docs worktree and
lands before the run stops for a gate, so a gate never holds an unlanded tree.

### FRAME

Write `brief.md`: the question in one paragraph, the candidates (a table, with
each candidate's **existing foundation in the repo** — grep before you
speculate), and explicit success criteria for the decision. If Ian handed you
the answer rather than a question (brief 0002: "we should just build this"),
FRAME still runs — it captures the why, what the repo does today, and the
decisions Ian must make — and PRIORITISE collapses to gate 1 over those
decisions.

### PRIORITISE — gate 1

Fan out **parallel research subagents**, one `Agent` (`general-purpose`, with
web search) per genuinely independent question, all dispatched in one message
(brief 0001 used three: competitor landscape, data feasibility, user value).
Each child writes `research/<topic>.md` in the docs worktree and must state its
**method and cite every load-bearing claim** — URLs inline, primary sources
preferred, and an honest note when a tool was unavailable. Children write files;
the coordinator reads the files — no answers through message paraphrase.

Synthesise the reports into a ranked table in the brief (rank, bet, verdict
across reports), then present **gate 1**: the numbered decisions with defaults.
Record Ian's answers in the brief verbatim, including amendments — an amendment
(0001: "report-as-wedge, invite-parent first-class"; 0014: "sharing the link IS
the inviter's consent") often IS the product insight; carry it into every
downstream slice.

**Every committed decision gets a short label** — three to six words, written
beside its number in the brief:

    D33 — no loan subtraction. <the full decision text, verbatim>

The label is the gloss every later message uses so `D33` can be said cheaply
after its first full expansion. The verbatim text stays the binding version; the
label never replaces it in an argument about what was decided.

### DISCOVER

Ground the chosen bet against the codebase before slicing: what tables, tools,
and surfaces actually exist, and where the brief's assumptions were wrong.
Delegate bounded reading to `Explore` agents; keep the deciding here. Write the
corrections into `spec.md`'s preamble (0001: "colleges carries only overall
net_price; NO income-band prices"). Slices written against imagined schema waste
a /work spec phase.

### SPEC & SLICE — gate 2

Write `spec.md`: an ordered list of slices, **each one a self-contained /work
instruction** with acceptance criteria, in the shape
[`references/slice-instruction.md`](references/slice-instruction.md) fixes — a
permanent ID, a `Needs:` line with a kind and a reason on every edge, the door,
what is decided here versus left to /work's spec, and the first-session test.
`slice-board` parses those lines; a malformed one is a hard error there, not a
guess.

Order slices so each lands user-visible value or directly enables the next; name
the slice where the aha becomes live end-to-end. Then present **gate 2**: slice
order + the numbered decisions with defaults. Record verbatim, including
standing rules Ian declares (D10/D12 pattern) — mark those `standing` so future
briefs inherit them without re-asking.

### EXECUTE

One run per slice, dispatched by **/slice** — "start work on
`search/04/similar-colleges`" — which resolves the ID, enforces the `Needs:`
edges, hands /work a complete instruction, and writes the ledger and `STATUS.md`
back. This phase is _satisfied by_ invoking /slice once per slice; /chart does
not drive /work itself. Slices in the same wave of the board are safe to run in
parallel; a CONFLICTS pair may still share a wave, it just rebases. In each run,
Ian is /work's approval gate; product questions that surface mid-run come back
to the brief as appended decisions or new slices. After each land, re-check the
next slice's instruction against what actually landed (the code wins over the
spec).

### LEARN

After the beat ships (or a pause): record in the brief what the runs taught —
gaps that became slices (S3.5), operational lessons, and follow-on work that
deserves its own brief (0001's testing pain framed 0002). A lesson that changes
how THIS skill works is a skill edit, proposed to Ian (non-negotiable 1).

## Relationship to slice and work

This skill never writes RFCs and never lands code — that is /work's contract,
including its approval stop and the pre-commit hook. The interface is one
artifact: the slice instruction. If /work needs product context beyond its
instruction, the instruction was underwritten; fix it in `spec.md`.

It DOES claim a worktree, for its own documentation. That is the same instrument
/work uses and not the same contract: a docs worktree has no RFC number, no
approval stop and no review tiers, and `docs-land` will not let it carry a path
outside `product/`.
