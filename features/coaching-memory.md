# Feature Brief — Coaching Memory & Reflection

> **Status:** design brief. This document is the north-star vision that drives
> RFC generation (via `rfc-pipeline`); it is **not** an RFC and carries no
> implementation authority. The per-RFC files under `rfc/`, and ultimately the
> code + applied migrations, are the source of truth. The RFC index below is a
> **coordination aid** that will drift — trust the code, not this table.

## What this is

Today the coach is reactive: it answers within a conversation and forgets the
student between them. This feature makes the coach **accumulate an evolving
understanding of each student and work on it between sessions** — the thing that
separates a chatbot with college knowledge from a real coach.

The student-facing promise: a coach that remembers what you told it, holds a
plan across months, notices when you change your mind, and occasionally comes
back with "I was thinking about what you said…". The north-star test for every
decision is: **would a good human coach do this?**

## The model

Four concepts, three of them a layered spine plus one shared knowledge base.

### Spine

```
observations   — evidence    — "what the student said"        (immutable)
claims         — belief      — "what Unicoach believes"       (revisable)
commitments    — intention   — "what Unicoach owes / will do" (resolvable)
```

- **Observations** — immutable records of what was said: the quote span, the
  date uttered, and a pointer back to the conversation/message. Append-only.
  They are true forever — the student _did_ say it.
- **Claims** — the coach's current beliefs, distilled from observations and/or
  reasoned from other claims. Revisable: a claim gains confidence, gets
  superseded, or is retracted, while the observations behind it stay true.
  Claims are what get injected into context and shown on a profile.
- **Commitments** — coach-owned intentions to follow up: "let me think about
  this," or "check in before the November deadline." They resolve (open →
  fulfilled/dropped), they carry a trigger, and they are surfaced to the
  student.

The relationship is **many-to-many**, not a pipeline: one observation can feed
several claims; one claim can be reinforced by several observations (recurrence
→ confidence); most observations support no claim at all; and some claims have
no observation behind them — they are the coach reasoning across other claims.

### Shared knowledge base

- **College-knowledge layer** — a real college dataset (College Scorecard /
  IPEDS: programs, size, location, selectivity, cost/aid, deadlines) plus
  retrieval over it. This is what lets the coach name real schools instead of
  hallucinating them. It is consumed by **both** live chat and reflection, so it
  is shared infrastructure, not a reflection detail.

### Student-facing artifact

- **College list** — a dedicated entity (status: considering / applying /
  admitted / rejected; reasons; key dates), **not** rows in the generic claims
  table. It references supporting observations for provenance. The list is the
  co-owned deliverable; the spine is the substrate beneath it.

## Design principles

1. **Provenance is the whole game.** Every derived fact points back to what the
   student actually said, in which conversation. Fabricated memory ("you said
   you wanted pre-med") destroys trust faster than no memory — especially with
   teenagers who will notice. Provenance is what makes the coach correctable and
   what makes "I was thinking about what you said about X" possible.

2. **Separate the utterance from the belief.** The quote is immutable; the
   belief is revisable. Never overwrite an observation when a student changes
   their mind — flip the claim's status and keep the history. The history is
   what lets the coach _notice the change_, which is premium coaching.

3. **The LLM is a reasoner over retrieved knowledge, not a knowledge source.**
   Reflection quality is bounded by what you assemble into it. Reflection over a
   raw transcript produces horoscopes; reflection over distilled claims + a real
   college dataset + calendar context produces recommendations.

4. **Disclosure is a coaching choice, not a data-hiding mechanism.** Commitments
   declare `disclosure = explicit | internal`. Internal ≠ secret: it means "not
   announced as a promise," not "a place to stash inferences the student
   shouldn't see." Anything stored should survive the student asking to see
   their full record.

5. **Promises must be kept.** An _explicit_ commitment is a delivery obligation:
   when it resolves, the coach surfaces it referencing the original. This is
   measurable — `fulfilled / explicit` is a real quality metric (promise-kept
   rate). Internal commitments are not promises and do not count against it.

6. **Student agency is a feature, not an afterthought.** The student can see and
   correct what the coach believes. Corrections act on claims, never on
   utterances, and the correction log is the single best eval signal we have.

## Key decisions

- **Two async passes, not one.** _Extraction_ runs per-conversation
  (observations → claims; high-fidelity, transcript-only). _Synthesis_ runs
  periodically over the accumulated model (new ideas, gaps, candidates; needs
  outside knowledge). The "think about it later" feature is synthesis.
- **Delivery channel (push vs pull) is resolved at fulfillment**, from the
  insight's urgency — not fixed when the commitment is created. A quiet idea
  surfaces on next visit (pull); a 3-days-to-deadline finding notifies (push).
- **Reflection is a set of lenses**, not one prompt. Internal lenses
  (contradiction, gap, timing) need no external data and reason over what we
  already have. The fit/discovery lens needs the college-knowledge layer and is
  the marquee piece.
- **The college-knowledge layer is foundational and shared** with live chat, so
  it is sequenced as its own root RFC.
- **Tool use in chat is staged into one shared loop.** Individual tool
  _contracts_ (`CollegeSearchTool`, and later extraction's inline-action writer)
  are built with their owning feature, but the chat turn's **tool-use loop** —
  structured content blocks on `ChatMessage`, a typed `tools` field on
  `ChatRequest`, and `stop_reason == "tool_use"` → dispatch → second call — is a
  single **`chat-tool-use`** RFC that lands the loop once so every tool
  registers into it. It imports `CollegeSearchTool` as its first consumer and
  unblocks extraction's deferred inline actions. RFCs 66 and 67 both explicitly
  defer to it: 67 ships the college tool without wiring it into a turn, and 66
  keeps extraction worker-only until this loop exists.
- **No standalone schema RFC.** The obs/claim/claim_support tables ship inside
  `extraction` (their first consumer); the `commitments` table ships inside
  `synthesis` (its first consumer), not `extraction` — extraction never writes a
  commitment, so defining it there would be a writerless schema. Each lands as a
  vertical slice, per YAGNI.
- **Every token spent on a student is recorded.** No LLM call — chat,
  extraction, or later reflection passes — may consume tokens without a
  persisted usage row tied to that student, including failed/retried calls
  (record at the call boundary, not on success). `extraction` logs this in
  `extraction_runs` per billed call; chat already logs it in `convo_responses`.
  A future cross-feature token ledger unifies them into one source of truth for
  per-user spend.

## Open forks

- **Substrate-as-its-own-RFC?** Currently the schema is folded into
  `extraction`. Reconsider if the schema proves heavy enough to design
  independently.
- **Extraction timing — partly resolved.** Extraction shipped **worker-only**
  (RFC 66): no inline memory actions on the chat turn, because there is no
  tool-use dispatch in the chat path yet and inline writes would land on the
  request coroutine, violating the request-path constraint (RFC 43). The hybrid
  lean (structured high-confidence actions inline, fuzzy claims in the worker
  pass) is not abandoned but **deferred to the `chat-tool-use` RFC**; the
  extraction schema precludes nothing, since a future inline writer targets the
  same `observations`/`claims` tables.
- **Visibility policy** — what is student-visible by default vs. internal
  coaching note, and how that interacts with minors/parents/counselors.
- **Confidence & decay model** — how recurrence raises confidence and how stale
  one-off observations lose weight.

## RFCs (living index)

Status axis:
`planned → drafting → designed (RFC committed) → implemented (code
merged)`.
"Implemented" means code merged, not RFC written. Slugs are the stable handles;
`rfc/NN-*.md` numbers are assigned at design time and backfilled here.

| slug                | description                                                                                                                                                                       | status      | rfc          |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- | ------------ |
| `extraction`        | per-conversation pass: observations → claims; defines the obs / claim / claim_support schema                                                                                      | implemented | 66           |
| `college-knowledge` | college dataset (Scorecard/IPEDS) + `CollegeSearchTool` retrieval; shared by live chat and reflection                                                                             | implemented | 67 (+78, 82) |
| `chat-tool-use`     | generic chat tool-use loop: content blocks + typed `tools` on `ChatRequest` + `tool_use` dispatch → second call; imports `CollegeSearchTool`, unblocks extraction's inline writer | planned     | —            |
| `college-list`      | student-facing list entity (status / reasons / dates), references supporting observations                                                                                         | planned     | —            |
| `synthesis`         | reflection loop; defines the commitments schema; internal lenses (gap / timing / contradiction), pull delivery                                                                    | planned     | —            |
| `fit-lens`          | "I found a school you'd love" — fit/discovery reflection over the college dataset                                                                                                 | planned     | —            |
| `push-delivery`     | notifications + scheduled ticklers (calendar-triggered commitments)                                                                                                               | planned     | —            |
| `token-ledger`      | cross-cutting per-user LLM token/cost ledger (one usage row per call); unifies chat + extraction + reflection spend                                                               | planned     | —            |

> **`token-ledger` is cross-cutting infrastructure, not a coaching-memory
> node.** It was surfaced by `extraction`'s per-user token requirement but is
> independent of the spine and **not** part of the dependency tree below. Sketch
> for the future RFC: a single append-only `token_usage` table — one row per LLM
> call (`student_id`, `source` ∈ {chat, extraction, synthesis, …}, `source_ref`,
> `model`, input/output/cache tokens, `created_at`) — written at the
> `ChatProvider` chokepoint so no call site can bill a student without
> recording. Existing per-feature token columns (`convo_responses`,
> `extraction_runs`) backfill or dual-write into it, then become read-throughs.
> The chokepoint enforces _"no `ChatProvider` call bills a student without a
> recorded usage row"_ by construction; the only residual is the
> non-transactional bill-vs-record window (provider billed but the local write
> lost to a crash), which the RFC must close by reconciling against provider
> usage / request ids.

## Dependency tree

```
extraction ──► college-list ──► synthesis ──► fit-lens
  (66,done)                        │             ▲
                                   ▼             │
                              push-delivery      │
college-knowledge ───────────────────────────────┘
  (67,done)  │
             └──► chat-tool-use ──► live college search in chat;
                                    unblocks extraction's inline actions
```

- `extraction` and `college-knowledge` are **parallel roots** — neither blocks
  the other. **Both are now implemented** (RFC 66; RFC 67 + hardening 78, 82),
  so the two roots are done.
- `chat-tool-use` is a **separate track off `college-knowledge`**, not part of
  the reflection spine. It wires the live chat turn to actually _call_
  `CollegeSearchTool`, and lands the shared tool-use loop that extraction's
  deferred inline writer later registers into. `fit-lens` can reach the dataset
  through the worker (via `CollegeSearchService`) without it; `chat-tool-use` is
  what the dataset needs to show up **live in a conversation**.
- `synthesis` is the integration point (needs claims + the list). Its **internal
  lenses ship without `college-knowledge`** — this is the MVP cut.
- `fit-lens` is the only reflection piece needing _both_ synthesis and the
  dataset, so it is correctly the last marquee step.
- `push-delivery` extends `synthesis` once the pull loop feels real.

## Sequencing

1. ~~**`college-knowledge`** and **`extraction`** in parallel — the two roots.~~
   **Done** (RFC 66; RFC 67 + hardening 78, 82).
2. **`college-list`** on top of extraction. ← **next on the docket.**
3. **`synthesis`** (internal lenses, pull-only) — the smallest honest "I thought
   about this" loop: a gap/timing observation surfaced as a next-session opener,
   needing only the internal model + a calendar. Proves the loop end-to-end with
   no notification infra.
4. **`fit-lens`** once `college-knowledge` is earning its keep in chat.
5. **`push-delivery`** last.

`chat-tool-use` sits **off this spine**: it can be sequenced any time after
`college-knowledge` (done), independent of `college-list`/`synthesis`. Do it
when live college search in chat — or extraction's inline actions — becomes the
priority; it is the prerequisite for both.
