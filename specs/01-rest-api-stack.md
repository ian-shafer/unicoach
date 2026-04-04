# REST API Technology Stack Specification (Foundational MVP)

## Overview

This document establishes the definitive technology stack, infrastructure rules,
and development lifecycle for the application's backend REST API. This file
serves as the strict architectural blueprint for the initial scaffolding of the
Kotlin backend codebase.

---

## 1. Core Stack & Infrastructure

- **Language**: Kotlin (JVM)
- **Web Framework**: Ktor
  - _Rationale_: Idiomatic, coroutine-native, and highly performant.
- **JSON Serialization**: `kotlinx.serialization`
  - _Rationale_: Official Kotlin library, entirely idiomatic and native to
    Ktor's content negotiation plugin.
- **Database Engine**: PostgreSQL
  - _Rationale_: Robust relational integrity with advanced JSONB, blob, cache,
    and queue support.
- **Data Access (ORM)**: Exposed
  - _Rationale_: JetBrains' native SQL framework, ensuring database queries
    remain strictly typed in Kotlin.
  - _CRITICAL THREADING RULE_: Exposed relies on blocking JDBC IO. Because Ktor
    is non-blocking and coroutine-centric, every single database query or
    transaction MUST be explicitly wrapped in `Dispatchers.IO` (or a dedicated
    blocking thread pool). Failure to do this will starve Ktor's event loop and
    halt the server.
- **Database Migrations**: shell scripts
  - _Rationale_: Easy to build and maintain, and full control over
    functionality. Detailed constraints (including `schema_history` state
    tracking) will be defined in a separate spec.
- **Dependency Injection**: Koin
  - _Rationale_: Lightweight, idiomatic Kotlin DI framework.

## 2. API Contract & Integration

- **Specification Standard**: OpenAPI 3.x
- **Workflow**: Spec-first (Contract-Driven) API design. The `openapi.yaml` will
  physically exist in the repository as the absolute source of truth. Ktor
  routing logic will be hand-written for idiomatic clarity, but MUST be strictly
  **contract-tested** against this definition. Integration tests (using tools
  like Swagger Request Validator) will intercept HTTP traffic and fail the build
  if responses deviate from the YAML in any way. Server-side Ktor stub
  generation is explicitly banned due to JVM tooling immaturity. Client SDKs
  (iOS, Web) will still be auto-generated exclusively from this YAML file.

## 3. Build & Containerization (The "Docker Rule")

- **Build Tool**: Gradle (Kotlin DSL)
- **Containerization Standard**: Docker & Docker Compose
- **The Golden Environment Rule**: "The execution and compilation runtime is
  exclusively Docker." Both the infrastructure (Postgres, Redis) and the Ktor
  application MUST operate identically in local development and production
  inside Docker. All Gradle compilation, unit testing, and application execution
  must occur strictly within a containerized build environment. Host-machine
  JDK/Gradle resolution is explicitly banned to guarantee identical build
  outcomes across all environments.

## 4. Quality Assurance & Testing Strategy

- **Unit Testing Framework**: JUnit 5. We will NOT mock. We will use fakes if
  need be. If unit tests are not practical or possible without mocking, we will
  implement full functional tests using real infrastructure instead.
- **Integration Testing**: Testcontainers. During test execution, a real,
  ephemeral Postgres instance will be spun up via Docker to test Exposed queries
  against a genuine database.
- **E2E API Testing**: Ktor's `testApplication` engine for internal HTTP request
  simulation.
- **API Contract Fuzzing**: Schemathesis. The OpenAPI YAML is the absolute
  source of truth. A local orchestration script MUST run the Schemathesis fuzzer
  against a fully booted, containerized instance of the Ktor API to aggressively
  fuzz all endpoints. This validates compliance and guarantees that malformed
  inputs trigger structured HTTP 400s rather than unhandled Kotlin
  `SerializationException` 500s.
- **Linting & Formatting**: Ktlint. Integrated firmly into the Gradle build
  process to mandate uniform Kotlin styling.

## 5. Version Control & CI/CD

- **VCS**: git / GitHub.
- **Branching Strategy**: Lightweight feature-branching off `main`. All changes
  pushed directly to main -- this is a single-engineer project.
- **Continuous Integration / Pre-Commit Validations**: We will forgo formal
  GitHub Actions. Instead, a local `pre-commit` shell script hook is strictly
  mandated. This script MUST successfully compile the code, execute all tests,
  and pass `ktlint` formatting before Git allows a commit. This is the sole
  safety net against LLM-generated breakages.

## 6. Observability & Configuration

- **Configuration Management**: HOCON (`rest-server.conf`, `service.conf`,
  `common.conf`).
  - _CRITICAL NAMING RULE_: The `.conf` filename MUST exactly match its base
    module directory (e.g., the `postgres-exposed/` module mandates
    `postgres-exposed.conf`). This guarantees resource uniqueness and prevents
    silent configuration overwrites when Gradle merges the classpath across
    modules. Configuration heavily utilizes environment variable overrides
    supplied by Docker Compose.
  - _AGGREGATION RULE_: Ktor only auto-loads the primary entry-point file
    (`rest-server.conf`). This file MUST explicitly aggregate the underlying
    module configurations using the HOCON directive
    `include classpath("postgres-exposed.conf")`. Without this explicit
    inclusion syntax, module-specific configurations will be silently ignored
    and the server will fail to start.
- **Application Logging**: SLF4J + Logback. Configured for structured JSON
  logging output directly to `stdout`, optimizing it for Docker's native log
  aggregation.

## 7. Directory Structure Pattern

The project codebase will employ a **Multi-Module Architecture** at the root
directory to enforce strict boundaries between shared infrastructure, pure
domain operations, database adapters, and the HTTP transport layer.

- _Note on Persistence_: The core domain `service` layer will define
  database-agnostic interfaces (Ports). The actual Exposed Data Access Objects
  (DAOs) will implement these interfaces in a distinct `postgres-exposed` module
  (Adapters). This compilation boundary prevents accidental classpath leakage of
  ORM dependencies into the pure domain logic.
  - **CRITICAL MAPPING MANDATE**: The `postgres-exposed` module MUST explicitly
    instantiate and return pure Kotlin data classes (Domain Models defined in
    the `service` module). Under no circumstances is an Exposed `Entity` or
    `ResultRow` permitted to cross the interface boundary back into the
    `service` or `rest-server` modules. All database mapper functions must
    reside strictly within `postgres-exposed`.

```text
/
├── common/             (Shared utilities and base types used across all modules)
│   ├── src/main/kotlin/ed/unicoach/common/
│   └── src/main/resources/common.conf
├── service/            (The pure core business domain, including DB-agnostic interfaces/Ports)
│   ├── src/main/kotlin/ed/unicoach/service/
│   └── src/main/resources/service.conf
├── postgres-exposed/   (The database adapters implementing domain interfaces via Exposed)
│   │                   -> DEPENDS ON: `common`, `service`
│   ├── src/main/kotlin/ed/unicoach/exposed/
│   └── src/main/resources/postgres-exposed.conf
└── rest-server/        (The Ktor application, HTTP endpoints, OpenAPI setup)
    │                   -> DEPENDS ON: `common`, `service`, `postgres-exposed`
    ├── src/main/kotlin/ed/unicoach/rest/
    └── src/main/resources/
        └── rest-server.conf    (Entry configuration overriding `common`, `service` & `postgres-exposed`)
```

## 8. Files

- `specs/01-rest-api-stack.md`: The foundational architecture blueprint for the
  Kotlin backend.

---

_Note: Cross-cutting concerns such as Authentication, Global Error Handling,
JSON Serialization strategies, and Pagination will be strictly detailed in
subsequent, separate specifications._
