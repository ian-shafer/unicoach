---
name: hexagonal-architecture
description: >-
  Scaffold, implement, and validate code using the Hexagonal Architecture
  (Ports and Adapters) pattern. Use when a user asks to create a new service,
  add a repository, structure folders, refactor code into the domain, implement
  adapters for external APIs, or check imports for dependency rule violations.
  Do not use for general bug fixing or basic API setup unless architectural
  structure is specifically mentioned.
---

# Hexagonal Architecture

This skill provides a strictly bounded generator and guardrail for working
within the Hexagonal Architecture (Ports and Adapters) pattern.

## Core Philosophy

1.  **Domain First, External Details Last**: The `domain/` layer contains pure
    business rules and entities. It knows nothing of the outside world—not HTTP,
    not the database, not messaging queues.
2.  **Ports as Contracts**: Output capabilities are declared as interfaces
    (Ports) inside the domain or near it, preventing direct dependencies on
    specific tech.
3.  **Adapters Implement Ports**: Outside of the domain, the `adapters/` layer
    implements those ports using specific libraries (e.g., SendGrid,
    PostgreSQL).
4.  **Strict Dependency Rule**: Source code dependencies must point inwards.
    Nothing in `domain/` should import from `adapters/`, `ports/`, or any
    external framework/ORM, barring specific allowlists (e.g., standard
    library).

## Recommended Directory Structure

> [!NOTE] This structure is just a recommendation. One can deviate from it and
> still comply with this architecture. The most important thing is that the
> domain code is separate from the adapter code. "Ports" are just interfaces.
> "Adapters" are implementations of those port interfaces.

-   `domain/`: Pure business logic (Entities, Value Objects).
-   `domain/services/`: Use case orchestrators.
-   `ports/inbound/`: Interfaces for driving operations (useful when strictly
    separating controllers).
-   `ports/output/`: Interfaces for driven operations (e.g., `UserRepository`,
    `EmailService`).
-   `adapters/input/`: Driving adapters (e.g., REST controllers, gRPC handlers).
-   `adapters/output/`: Driven adapters (e.g., Postgres repositories, SendGrid
    clients).

--------------------------------------------------------------------------------

## 1. Feature Scaffolding Workflow

When a user asks to build a new feature (e.g., "Add a Book Appointment
feature"), always build from the inside out:

1.  **Create the Domain Entity** (e.g., `domain/appointment.go`) and its core
    business rules. Do not include database IDs or JSON tags if they leak
    adapter logic (though standard language pragmatism applies).
2.  **Create Output Ports** (e.g., `ports/output/appointment_repository.go`) to
    define what the domain needs to store or notify.
3.  **Create the Domain Service** (e.g.,
    `domain/services/appointment_service.go`) that orchestrates the flow using
    the output ports.
4.  **Confirm with the user** before scaffolding Adapters.

## 2. Adapter Implementation

When a user says "Let's use [Tool] for the [Port]" (e.g., SendGrid for
EmailService):

1.  **Create the Adapter File** inside `adapters/output/`.
2.  **Implement the Port Interface** using the external library.
3.  Ensure the adapter maps any external error types or DTOs to internal domain
    errors/types before returning them to the domain.

## 3. Dependency Validation

When the user says "Check my work" or "Check my imports":

1.  Identify the core domain directory (e.g., `domain/`, `core/`, `app/`)
    based on the project structure, and list all files under it.
2.  For each file, closely analyze the `import`(s).
3.  **The Rule:** A file in the core domain directory MUST NOT import anything
    outside of that directory or the language's standard library.
    -   *Exception: Any user-specified allowlist libraries (e.g., logging or
        base utility types) if the user explicitly allowed them.*
4.  If you see imports for external tools (e.g., GORM, FastAPI, Stripe SDK,
    Express), you must forcefully raise a Dependency Rule Violation to the user
    and refuse validation.

## 4. Test Generation

When generating unit tests for domain services:

1.  Create the test relative to the service.
2.  Prefer using Fakes (in-memory implementations) to satisfy Port dependencies.
    Note that mocks are not recommended, but may be used as well.
3.  Never spin up a real database or real HTTP client for domain-level unit
    tests.
