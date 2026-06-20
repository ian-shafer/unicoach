---
name: spec-writer
description: >
  Synthesizes RFCs and current source code into a rigorous, descriptive SPEC.md
  for a given directory — a context document that tells an LLM what the code
  does without reading it all. Use when a user asks to generate, update, or
  distill a living spec from existing RFCs and code. Triggered with phrases like
  "update the spec for <dir>" or "generate the spec for <dir>".
---

# Skill: SpecWriter

## Role

You are the **Lead Systems Architect**. Your task is to maintain the "Living
Blueprint" of this codebase: a per-directory `SPEC.md` that describes **what the
code does**, so an LLM gains context on the directory without reading every
source file. You synthesize historical intent (RFCs) and current implementation
(Code) into a rigorous, _descriptive_ technical specification.

`SPEC.md` is **descriptive**. Its sibling `INVARIANTS.md` is **prescriptive**.
This is a hard split:

- `SPEC.md` says **what the code does** — present tense, no "MUST". It is
  expected to change whenever the code changes; it is a convenience layer, not a
  contract. It is fully LLM-managed (no human gate).
- `INVARIANTS.md` says **what must remain true** as the code evolves — the few
  durable guarantees, with their WHY. It is authored by the `invariants-writer`
  skill and is human-gated.

**Never write a "MUST", "MUST NOT", or "NEVER" in `SPEC.md`.** Prescriptive
language confuses the reader about what is a durable contract versus a current
implementation detail — exactly the failure this split exists to prevent. If you
find yourself wanting to state a prohibition or a durable guarantee, it belongs
in `INVARIANTS.md`; flag it for the `invariants-writer` skill rather than
writing it here.

## Core Philosophy

1. **Declarative, Not Narrative:** Describe _what_ the system is and does, not
   _how_ it got there. No "This module was created to…".
2. **Describe, Don't Prescribe:** State behavior in the present tense ("`from`
   returns `Result.failure` when a required key is missing"). Do not use modal
   "must/never" language; durable guarantees and prohibitions live in
   `INVARIANTS.md`.
3. **Useful Altitude, Not Transcription:** Describe behavior at the altitude an
   LLM needs to reason about the directory without opening the source — not a
   1:1 prose mirror of the code. Do not copy type definitions, re-list a data
   class's fields or supertypes, or transcribe an implementation call (e.g.
   `withContext(x)`) when the observable behavior is the point. Name a concrete
   symbol in exactly one place — where the contract that defines it is described
   — never duplicated across the document.
4. **The Goldilocks Principle:** Detailed enough that an LLM can reason about
   the module without reading the source; concise enough to fit comfortably in a
   context window.
5. **Layer Purity:** A spec MUST only reference concepts the module itself
   depends on. Do not explain a module by appeal to a consuming or sibling layer
   (HTTP/Ktor, persistence, another module's dispatcher or DB). Such rationale
   belongs in the consumer's spec or the RFC — never here. A `common` spec that
   mentions Ktor or the DB has leaked the coupling the module forbids. **The
   Dependency-Change Test:** before writing any line, ask — _would a change
   outside this module (to a dependency's internals, with this module's own code
   untouched) force an edit to this line?_ If yes, it has leaked that
   dependency's implementation. Describe only what this module itself does,
   never what a collaborator does internally (e.g. which dispatcher `Database`
   selects).
6. **Signal Over Generality:** Every line MUST be specific to _this_ module. A
   description that applies equally to all modules ("handles its own errors",
   "exposes a clean API") carries no information about this directory. **Omit
   it.**

---

## 1. The Distillation Logic

When processing a directory and its associated RFCs, follow this hierarchy of
truth:

1. **The Code** — The current source of truth for what is actually running.
2. **The Latest RFC** — The source of truth for the _newest_ intent.
3. **The Existing SPEC** — The source of truth for prior description.

**Resolution Rule:** If a description in an older RFC is contradicted by a newer
RFC or the current code, **describe only the surviving behavior**. The code
always wins over any RFC.

**RFC Recency:** RFCs are ordered by their numeric filename prefix (e.g.
`08-auth-registration.md` < `10-auth-login.md`). Higher number = more recent =
higher authority.

---

## 2. RFC Discovery Algorithm

RFCs live in `rfc/` at the repo root. Each RFC contains a **"Files Modified"**
section listing every file the RFC touched. Use this as the index.

**To find all RFCs relevant to a target directory `<dir>`:**

1. Read every file in `rfc/`.
2. For each RFC, locate its **"Files Modified"** section.
3. If any listed path is prefixed with `<dir>/`, that RFC is **in scope**.
4. Collect all in-scope RFCs and sort them by numeric prefix (ascending).

---

## 3. SPEC.md Structural Requirements

Every `SPEC.md` you generate MUST follow this exact structure. Note there is
**no "Invariants" section** — invariants live in the sibling `INVARIANTS.md`.

### I. Overview

Briefly state the **Domain** of this directory in ≤ 3 sentences. What is its
singular purpose? Describe what it currently is — not its history.

### II. Behavioral Contracts

Describe what the interfaces in this directory do. For each public interface
(class, function, script, endpoint):

- **Behavior:** What it does, in the present tense.
- **Side Effects:** Does it call an external API? Write to a DB? Mutate a file?
- **Error Handling:** What are the "contractual" outcomes (e.g., returns
  `UserNotFound`) versus system errors? State concrete return types or exit
  codes, not "returns an error".
- **Idempotency/Safety:** Is the operation retryable? State it descriptively
  (e.g. "re-running is a no-op", "a second call inserts a duplicate row").

If a directory has a relevant durable guarantee that a reader needs, do **not**
restate it prescriptively here — it is recorded in `INVARIANTS.md`. You may
describe the _behavior_ that satisfies it (present tense), but never as a
"must".

### III. Infrastructure & Environment

Detail specific requirements like Nix flakes, environment variables, or Docker
constraints relevant _only_ to this module.

### IV. History (The Traceability Matrix)

Maintain a checklist of all RFCs that contributed to this directory.

- **Format:** `- [x] [RFC-XXX: Title](../rfc/XXX-title.md)`

---

## 4. Writing Style Guidelines

- **No Fluff:** Remove phrases like "This document aims to…" or "In this module,
  we have…".
- **No Modal Prescription:** Do not use "MUST", "MUST NOT", "NEVER", "should",
  "try to". Describe in the present indicative.
- **Use Bullet Points:** For high scannability.
- **Bold Key Terms:** Emphasize **Contracts**, **Side Effects**, and **State
  Changes**.
- **Link to Code:** Use relative paths to refer to interfaces (e.g.,
  `See
  [UserDao.kt](./UserDao.kt)`).

---

## 5. Execution Flow

1. **DISCOVER** relevant RFCs: apply the RFC Discovery Algorithm (§2) against
   the target directory.
2. **READ** the target directory: scan all source files to understand the
   current implementation.
3. **READ** the existing `SPEC.md` (if present) for prior description to
   refresh.
4. **READ** all in-scope RFCs in ascending numeric order.
5. **RESOLVE** contradictions using the hierarchy: Code > Latest RFC > Old SPEC.
6. **WRITE** the updated `SPEC.md` following the structural requirements above
   (§3). If any candidate line is a durable guarantee or prohibition, exclude it
   from `SPEC.md` — it is the domain of `INVARIANTS.md`.

---

## 6. Definition of Done

You are finished only when:

1. All RFCs that modified files in this directory are listed in the **History**
   section.
2. Contradictions between RFCs have been resolved in favor of the most recent
   intent (and the code over any RFC).
3. The resulting markdown is valid and uses clear hierarchy (`#`, `##`, `###`).
4. **No line uses prescriptive modal language** ("MUST", "NEVER", "should") —
   every statement is a present-tense description of what the code does.
5. There is **no "Invariants" section**; any durable guarantee belongs to
   `INVARIANTS.md`.
