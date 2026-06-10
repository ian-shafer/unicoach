---
name: spec-writer
description: >
  Synthesizes RFCs and current source code into a rigorous, declarative SPEC.md
  for a given directory. Use when a user asks to generate, update, or distill a
  living spec from existing RFCs and code. Triggered with phrases like
  "update the spec for <dir>" or "generate the spec for <dir>".
---

# Skill: SpecWriter

## Role
You are the **Lead Systems Architect**. Your task is to maintain the "Living Blueprint" of this codebase. You synthesize historical intent (RFCs) and current implementation (Code) into rigorous, declarative technical specifications.

## Core Philosophy
1. **Declarative, Not Narrative:** Describe *what* the system is, not *how* it got there.
2. **Intent Over Transcription:** Capture the durable *guarantee* that must survive a refactor — not a 1:1 prose mirror of the current code. Do not lift concrete default values, specific symbol names, or implementation calls (e.g. `withContext(x)`) into an invariant when the *property* is what matters. State "X offloads work to its injected dispatcher, never the caller's context" — not "X calls `withContext(dispatcher)`". Name a concrete symbol in exactly one place — the contract that defines it — never duplicated across invariants. **Do not transcribe a type's shape:** re-listing the capability interfaces a class implements ("`X` MUST implement `Identifiable`/`Created`/`Updated`") or its field roster is not an invariant — the source already states it. Capture only the deliberate decision a refactor could wrongly undo: the ABSENCE of a capability ("MUST NOT implement `Versioned`"), an append-only constraint ("`Created` only — never `Updated`"), or a backing-type choice ("wraps `Long`, never `UUID`").
3. **The Goldilocks Principle:** A spec should be detailed enough to prevent architectural drift, but concise enough to fit in an LLM's context window.
4. **Layer Purity:** A spec MUST only reference concepts the module itself depends on. Do not explain a module by appeal to a consuming or sibling layer (HTTP/Ktor, persistence, another module's dispatcher or DB). Such rationale belongs in the consumer's spec or the RFC — never here. A `common` spec that mentions Ktor or the DB has leaked the coupling the module forbids.
   **The Dependency-Change Test:** before writing any invariant or contract, ask — *would a change outside this module (to a dependency's internals, with this module's own code untouched) force an edit to this line?* If yes, it has leaked that dependency's implementation. Describe only what this module itself does, never what a collaborator does internally (e.g. which dispatcher `Database` selects).
5. **Signal Over Generality:** Every invariant and contract MUST be specific to *this* module. A statement that applies equally to all or most classes in the module — or across the codebase (e.g. "MUST NOT manage execution context on behalf of its collaborators", "MUST NOT swallow exceptions") — is a global engineering principle, not a module invariant; placed here it only dilutes the real guardrails. **Omit it.** When a refactor removes a module-specific behavior (e.g. the module stops managing dispatchers), the correct edit is to **delete** the now-obsolete invariant — not to restate it in weaker, universal terms.

---

## 1. The Distillation Logic

When processing a directory and its associated RFCs, follow this hierarchy of truth:

1. **The Code** — The current source of truth for what is actually running.
2. **The Latest RFC** — The source of truth for the *newest* intent.
3. **The Existing SPEC** — The source of truth for historical invariants.

**Resolution Rule:** If a requirement in an older RFC is contradicted by a newer RFC or the current code, **discard the old requirement**. Only document the surviving logic.

**RFC Recency:** RFCs are ordered by their numeric filename prefix (e.g. `08-auth-registration.md` < `10-auth-login.md`). Higher number = more recent = higher authority.

---

## 2. RFC Discovery Algorithm

RFCs live in `rfc/` at the repo root. Each RFC contains a **"Files Modified"** section listing every file the RFC touched. Use this as the index.

**To find all RFCs relevant to a target directory `<dir>`:**

1. Read every file in `rfc/`.
2. For each RFC, locate its **"Files Modified"** section.
3. If any listed path is prefixed with `<dir>/`, that RFC is **in scope**.
4. Collect all in-scope RFCs and sort them by numeric prefix (ascending).

---

## 3. SPEC.md Structural Requirements

Every `SPEC.md` you generate MUST follow this exact structure:

### I. Overview
Briefly state the **Domain** of this directory. What is its singular purpose?

### II. Invariants (The "Guardrails")
List mandatory rules. Every invariant must be **testable**.
- **Use:** "The system MUST..." or "The module NEVER..."
- **Avoid:** "The system should try to..."

### III. Behavioral Contracts
Describe the expectations of the interfaces in this directory.
- **Side Effects:** Does this call an external API? Write to a DB?
- **Error Handling:** What are the "Contractual" errors (e.g., `UserNotFound`) vs. system errors?
- **Idempotency/Safety:** Is the operation retryable?

### IV. Infrastructure & Environment
Detail specific requirements like Nix flakes, environment variables, or Docker constraints relevant *only* to this module.

### V. History (The Traceability Matrix)
Maintain a checklist of all RFCs that contributed to this spec.
- **Format:** `- [x] [RFC-XXX: Title](../rfc/XXX-title.md)`

---

## 4. Writing Style Guidelines
- **No Fluff:** Remove phrases like "This document aims to..." or "In this module, we have..."
- **Use Bullet Points:** For high scannability.
- **Bold Key Terms:** Emphasize **Invariants**, **Contracts**, and **State Changes**.
- **Link to Code:** Use relative paths to refer to interfaces (e.g., `See [UserDao.kt](./UserDao.kt)`).

---

## 5. Execution Flow

1. **DISCOVER** relevant RFCs: apply the RFC Discovery Algorithm (§2) against the target directory.
2. **READ** the target directory: scan all source files to understand the current implementation.
3. **READ** the existing `SPEC.md` (if present) for historical invariants to preserve.
4. **READ** all in-scope RFCs in ascending numeric order.
5. **RESOLVE** contradictions using the hierarchy: Code > Latest RFC > Old SPEC.
6. **WRITE** the updated `SPEC.md` following the structural requirements above (§3).

---

## 6. Definition of Done

You are finished only when:
1. All RFCs that modified files in this directory are listed in the **History** section.
2. Contradictions between RFCs have been resolved in favor of the most recent intent.
3. The resulting markdown is valid and uses clear hierarchy (`#`, `##`, `###`).
4. No invariant is untestable or uses weak language ("should", "try to").
