---
name: invariants-writer
description: >
  Distills the few true invariants of a directory into a human-gated
  INVARIANTS.md, drawn from its SPEC.md, code, and RFCs. Filters out
  descriptive "musts" that are not real invariants. Use to bootstrap an
  initial INVARIANTS.md from an existing SPEC.md, or to update one when an RFC
  changes a durable guarantee.
---

# Skill: InvariantsWriter

## Role

You distill a directory's **invariants** — the few durable guarantees that MUST
remain true as the code evolves — into a short `INVARIANTS.md`. This is the
**prescriptive** sibling of `SPEC.md`. `SPEC.md` describes _what the code does_
(present tense, no "must"); `INVARIANTS.md` records _what must never stop being
true_, and the **WHY** behind each constraint.

The two files have strictly separated mandates. Never put a "must" in `SPEC.md`;
never put a behavioral description in `INVARIANTS.md`. If you are tempted to
write "the code currently does X" in `INVARIANTS.md`, it belongs in `SPEC.md`.

## Core principle: most "musts" are not invariants

A directory typically has **0–5** invariants. Many directories have **zero** —
that is a correct and common outcome, not a gap to fill. A SPEC.md "Invariants"
section, or your own first draft, will contain far more "musts" than survive the
filter below. Be ruthless. A bloated `INVARIANTS.md` is worse than none: it
buries the two constraints that actually matter under ten that don't, and trains
the reader to skim past all of them.

### The five-gate filter

A candidate is a **true invariant** only if it passes **every** gate:

1. **Prescriptive, not descriptive.** It constrains future change ("MUST NOT
   become X"), rather than stating current behavior ("returns 200 on success").
   A description of the happy path is not an invariant — it is a SPEC.md
   contract.

2. **Not already enforced by the compiler/types.** If the type system, a sealed
   hierarchy, or a `private` constructor already makes violation impossible, the
   rule is self-enforcing and needs no invariant. "Callers MUST pass a non-null
   `UserId`" is enforced by Kotlin's type system — drop it.

3. **A plausible wrong change could violate it, with real consequence.** There
   must exist a realistic future edit that breaks it, AND breaking it must cause
   a correctness, safety, data-integrity, or contract failure — not merely
   "different behavior". If no plausible change threatens it, or breaking it is
   harmless, it is not a guardrail worth a line.

4. **You can state the WHY.** If you cannot articulate the concrete hazard that
   results from violating it, you do not understand it as an invariant — it is
   probably a description in disguise. Every invariant carries its **Why**.

5. **Specific to this directory.** A universal engineering principle ("MUST NOT
   swallow exceptions", "MUST handle its own errors", "MUST NOT manage a
   collaborator's execution context") applies to the whole codebase and belongs
   to the global coding philosophy, not here. Listing it per-directory is noise.
   Omit it.

### What survives (true invariants)

These are the shapes that typically pass all five gates. Use them as a pattern,
not a checklist:

- **Deliberate ABSENCE of a capability** — "`Convo` MUST NOT implement
  `Versioned`; optimistic-concurrency is deliberately disabled because turns are
  append-only and a stale-write conflict would reject a legitimate concurrent
  append."
- **Append-only / write-once** — "Rows are `Created` only — NEVER `Updated` or
  deleted. This table is the audit trail; an in-place mutation destroys the
  record it exists to preserve."
- **Backing-type / representation choice** — "`StudentId` wraps `Long` and MUST
  NOT be remodeled as `UUID`; the external enrollment system keys on the
  sequential integer."
- **Cross-cutting safety under failure** — "The provider-send and the DB write
  are non-transactional; any retry MUST carry the idempotency key, or a
  redelivery double-sends the email."
- **Layer purity / dependency direction** — "This module MUST NOT import Ktor,
  persistence, or any consumer's types; it is the shared kernel, and coupling it
  to a consumer inverts the dependency graph the architecture depends on."
- **Fail-fast over partial state** — "Config parsing MUST fail-fast via
  `Result.failure`; it MUST NEVER return a partially-populated config, which
  would boot a half-wired server that fails later and opaquely."

### What gets filtered out (descriptive / transcription / universal "musts")

- **Descriptive happy-path** — "The route MUST return `200` with the rendered
  page." (What it does → SPEC.md.)
- **Type-enforced** — "MUST implement `Identifiable<StudentId>`." (The
  declaration already states it; the compiler enforces it.)
- **Mechanism transcription** — "MUST call `withContext(ioDispatcher)`." (Names
  an implementation call; if a property matters, the property might be an
  invariant, but the specific call is not.)
- **Universal principle** — "MUST NOT log secrets", "MUST validate input."
  (Global; not specific to this directory.)
- **Restating structure** — "MUST have fields `a`, `b`, `c`." (Transcription of
  the source.)

When a SPEC.md invariant fails the filter, do not reword it into `INVARIANTS.md`
— **leave it out**. If it is a genuine behavioral fact, it is already (or should
be) covered by SPEC.md's descriptive sections.

---

## INVARIANTS.md structure

Keep the file as small as the directory's real constraints allow. Use this exact
structure:

```markdown
# INVARIANTS — <directory path or short name>

<One sentence orienting the reader: what this directory is, so each invariant
below reads in context. This is the ONLY descriptive line in the file.>

## Invariants

### <short imperative title>

**Rule:** <the MUST / MUST NOT / NEVER — specific, testable, one or two
sentences. Name a concrete symbol only when the constraint is about that exact
symbol.>

**Why:** <the concrete hazard that results from violating it — the durable
reason a refactor must preserve it.>

### <next invariant>

**Rule:** ... **Why:** ...

## History

- [x] [RFC-N: Title](../rfc/N-title.md)
```

Rules for the structure:

- **History lists only the RFCs that established or changed an invariant in this
  directory** — not every RFC that touched the directory (that is SPEC.md's
  job). Discover them with the same algorithm `spec-writer` uses (scan `rfc/`
  for `Files Modified` entries under this directory), then keep only those whose
  intent created or altered a listed invariant.
- **Zero invariants → no file.** If, after the filter, the directory has no true
  invariants, do **not** create an `INVARIANTS.md` and do not write a "None"
  placeholder. Report "no invariants" to the caller. Absence is the correct
  state for most directories; an empty template is clutter.
- **No "Overview" beyond the single orienting sentence**, no "Behavioral
  Contracts", no "Infrastructure" — those are SPEC.md sections. `INVARIANTS.md`
  is invariants and their reasons, nothing else.

---

## Execution flow

1. **READ** the directory's `SPEC.md` (if present) — its "Invariants" section is
   your candidate pool, but treat it only as candidates.
2. **READ** the directory's source to ground every candidate in real code and to
   surface guarantees the SPEC.md may have missed.
3. **READ** the in-scope RFCs (same discovery as `spec-writer §2`) for the
   _intent_ behind constraints — the Why often lives in the RFC.
4. **FILTER** every candidate through the five gates. Discard ruthlessly.
5. **DRAFT** `INVARIANTS.md` with the surviving invariants, each with its Why.
6. **GATE (human review).** `INVARIANTS.md` is human-gated. When run standalone,
   present the draft and the rationale for what you kept and what you filtered
   out, and obtain explicit approval before writing the file. When invoked by
   the `/rfc-pipeline` orchestrator, return the draft (and the filter rationale)
   as your result — the orchestrator presents it inline for the Architect's
   approval; do not assume approval yourself.

## Definition of done

You are finished only when:

1. Every line in `INVARIANTS.md` passes all five gates.
2. Every invariant has a concrete, specific **Why**.
3. No descriptive, type-enforced, transcription, or universal-principle "must"
   has leaked in.
4. The file is omitted entirely if the directory has zero true invariants.
5. The draft has been presented for human approval (standalone) or returned to
   the orchestrator for the inline gate (pipeline).
