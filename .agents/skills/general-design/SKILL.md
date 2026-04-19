---
name: general-design
description: Opinionated philosophical constraints to apply when writing code.
---

# General Code and Design

Establishes structural code design constraints emphasizing abstraction,
reusability, and compile-time safety.

## Generalization Before Implementation

- **Exhaustive Generalization**: Evaluate if a problem can be abstracted into
  shared infrastructure before implementing a specific solution.
- **Primitive Extraction**: Centralize generic interactions (e.g., CRUD entity
  ID management) into broad parent abstractions rather than duplicating logic
  per domain.

## Solve Problems Completely

- **Comprehensive Resolution**: Address core issues completely upon
  identification to prevent iterative patching.
- **Scope Alignment**: Do not conflate completeness with speculative
  engineering. Solve the validated, identified problem comprehensively; do not
  invent new problems.

## Common Infrastructure Abstraction

- **Domain-Agnostic Centralization**: Extract domain-agnostic components (e.g.,
  `Validator<T>`, hashing mechanisms) into a shared `common` module accessible
  globally across the codebase.

## Target Scale Restraints

- **De-prioritize Load Engineering**: Assume a maximum peak load of 1 query per
  second (QPS) unless otherwise specified. Synchronous database writes per HTTP
  request are the default standard.
- **YAGNI Enforcement**: Do not introduce caching layers, message buses, or
  async optimizations for hypothetical load mitigation. Maintain clean, modular
  architecture without premature scaling complexity.

## Structurally Impossible Misuse

- **Compile-Time Validation**: Design APIs where invalid states cannot compile.
  Runtime state checks or documentation warnings are failure conditions.
  - Use `oneof`/unions to mutually exclude representable states.
  - Use database `CHECK` constraints to enforce schema invariants.
  - Use `sealed` types to force exhaustive handling at compile time.
  - Use specific state transition methods (e.g., `claimJob(lockDuration)`)
    instead of generic property modifiers (e.g.,
    `updateStatus(status, lockDuration?)`).

## Error Actionability

- **Zero-Lookup Errors**: Error messages must encapsulate all identifiers,
  state, and context required for root-cause analysis directly in the payload.
  Do not require secondary lookups to gather missing context. Ideally, an agent
  could read the logs and automatically fix the issue without human
  intervention.
- **JSON Logging**: Prefer JSON formatting for all log outputs. Actionable state
  and identifiers must be natively parseable properties, not concatenated
  strings.
