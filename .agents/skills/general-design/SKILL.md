---
name: general-design
description: Opinionated philosophy to apply when writing code.
---

# 🤖 Skill: General Code and Design

This skill establishes core code design guidelines focusing on maximizing
abstraction and reusability rather than pursuing narrowly-scoped, specific
implementations.

## Solve problems as generally as possible

- Never write code until you've determined that you could not generalize the
  problem more. E.g. if you need to write a class to do CRUDL operations on a
  table, try to make it more general by finding common elements with what other
  software may be able to reuse. In this case, one could come up with the
  concept of an entity which maybe just has an ID. So every entity represented
  in the database would have a table with an ID. A generic class can be written
  that handles the generic entity stuff, like setting the ID. Entities can
  extend this interface and handle their specific concerns.

## Abstract Frameworks to Common Infrastructure

- Domain-agnostic code (e.g. `Validator<T>` functional interface, or a hashing
  library), it should be placed in the codebase in a way that is sharable across
  the codebase, e.g. in a common module.

## Avoid Over-engineering for Scale

- If a scaling solution is required, it will be specifically asked for in the
  spec. Do not concern yourself with scaling issues unless explicitly
  instructed.
- For example, writing to a database on every HTTP request is perfectly
  acceptable. We are building applications that will receive at most 1 query per
  second (1 QPS).
- This does not mean we abandon good engineering principles—clean, modular, and
  maintainable code is still required. It means we will avoid over-engineering
  systems to handle massive, hypothetical load.

## Make Misuse Structurally Impossible

- Prefer code, APIs, data structures, etc. where incorrect usage **cannot be
  expressed** over designs that require runtime checks or documentation warnings
  to prevent misuse. Examples of applying this principle:
- Use `oneof` in protos to reduce the number of representable states.
- Use database `CHECK` constraints to guarantee storage invariants at the schema
  level.
- Use sealed types to force exhaustive handling of all cases at compile time.
- Use dedicated methods to make correct use obvious and incorrect use impossible
  — prefer `claimJob(lockDuration)` over `updateStatus(status, lockDuration?)`
  where `lockDuration` is only meaningful for one transition.

## Actionable Error Messages

- Output clear, concise error messages that provide all necessary data for
  resolution.
- Eliminate the need for subsequent command executions or lookups to gather
  missing context.
