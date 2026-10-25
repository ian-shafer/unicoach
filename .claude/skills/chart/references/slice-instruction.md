# The slice instruction

Every slice in a `spec.md` is a self-contained /work instruction. /slice
assembles it with the standing decisions and hands it over unchanged; /work sets
its own dials from it. What it contains, in order:

## 1. A permanent slice ID

    <brief>/<milestone>.<step>/<name>        e.g. search/04/similar-colleges

The heading is `###`<id>`` (an old letter may follow in parentheses: `(S4)`).
Brief handles map to directories — `first-value` = 0001, `deletion` = 0002,
`money` = 0003, `search` = 0004, `shape` = 0006, `profile` = 0007, `soft` =
0008, `concise` = 0009, `test` = 0010, `beyond` = 0011, `fafsa` = 0012, `web` =
0013, `family` = 0014 — and a new brief declares its handle in its `Status:`
block. Two digits so listings sort. An ID is assigned once and **never
renumbered**: a slice inserted later takes a decimal step (`money/01.2`); a
slice split during /work's spec phase takes a letter (`family/04a`); a slice
whose scope changes that much is a new slice, not a renamed one. **Adjacency in
an ID never grants or denies permission to start** — `03` following `02` is a
plan, not a dependency.

## 2. A `Needs:` line, with a KIND and a REASON on every edge

    **Needs:**

    - BLOCKS family/03/the-parent-account — the parent who claims must land in
      a real parent account; there is nowhere to arrive until one exists.
    - CONFLICTS family/02/invite-and-accept — both edit the public-web entry
      pages. A rebase risk, not an order.

| Kind          | Meaning                                                   | Overridable         |
| ------------- | --------------------------------------------------------- | ------------------- |
| **BLOCKS**    | technical: same table, contract or output consumed        | no                  |
| **PREFER**    | product judgement: could proceed, we choose not to        | by Ian, visibly     |
| **CONFLICTS** | both edit the same files: a rebase risk, **not** an order | nothing to override |
| `Needs: —`    | genuinely nothing                                         |                     |

Rules `slice-board` enforces as doc defects (exit 1), so the question never
reaches a run:

- **A `Needs:` entry with no reason is not a dependency.** The shape is
  `KIND target — reason`, with a real em dash.
- **One edge per `-` bullet, never several in one paragraph.** An inline
  paragraph is parsed as ONE entry, so a second `BLOCKS` inside it vanishes into
  the first edge's reason — that is how `shape/08/drop-the-publisher-shape`
  printed READY while it still BLOCKED on `shape/05`.
- **Every target names a slice that exists** in some `spec.md`.
- **Every slice has a `Needs:` line**, even `Needs: —`.

A slice parked by intent carries `**Status:** DEFERRED` beside its `Needs:`
line, because "we chose not to yet" is not a dependency and must not be written
as one. Remove the line when it is scheduled. `DEFERRED` is the only accepted
value.

Verify every claimed edge against the binding decision text (the numbered Ds),
never against a summary line in `STATUS.md` — summaries drift, decisions do not.
This rule exists because a CONFLICTS edge on `bin/ingest-colleges` was written
into a status doc as "waits on", with no reason, and hardened over three
restatements into a blocker that held `money/02` for weeks.

## 3. What and why

One paragraph of product intent: what to build and why it exists, quoting the
decision it serves by number and label.

## 4. Foundations

The repo foundations to build on, by RFC, table, tool and file name — from
DISCOVER, not from memory.

## 5. Decided here vs. left to /work

What this spec settles, and what is **explicitly left to /work's spec phase**
(0001 D6: "money-profile mechanism decided inside S2's RFC, not here"). A slice
that leaves a design choice open says so; one that settles it says so. /work
picks its spec level from how much is still open.

## 6. Acceptance criteria

Written so they already embody the ethos; /slice adds nothing product-shaped:

- **the door named** — how a real user reaches it (the chat tool, the screen,
  the link). A slice whose value is unreachable is not done, whatever its tests
  say;
- **value before ask** spelled out for any user-facing flow — invite, allow
  start/stop/resume/later, degrade gracefully on decline, never gate on
  completion (0001 D12);
- **DDL at the approval stop** flagged for any slice adding a table (0001 D10);
- **the first-session test** — what a new user sees, in which session;
- anything the money vocabulary (brief 0003) or another standing decision binds.

## 7. The wave board

Computed from the `Needs:` edges above, never asserted beside them. A spec may
carry a wave table for the reader, but `slice-board` is the answer to "what may
I start", and when they disagree the board is right.
