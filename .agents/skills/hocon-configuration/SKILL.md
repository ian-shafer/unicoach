---
name: hocon-configuration
description: >-
  Standardize HOCON configuration for multi-module Kotlin/Ktor applications.
  Use when creating, modifying, or reviewing `.conf` files, environment variable overrides,
  or dealing with Gradle module resource handling.
---

# HOCON Configuration Standards

This skill enforces strict safety boundaries when dealing with `.conf` files in a multi-module application.

## Core Philosophical Rule: Prevent Classpath Collisions
In a multi-module Gradle/Ktor project, the `src/main/resources` directories are merged onto a unified classpath when the final `rest-server` application runs. If multiple modules define a generic `application.conf` or `database.conf`, the classloader will silently overwrite them. This leads to catastrophic runtime bugs where one module uses the config meant for another.

## 1. The Naming Mandate
**A HOCON configuration file MUST bear the exact same name as its parent module directory.**

- **Bad**: `postgres-exposed/src/main/resources/database.conf` (Generic name invites collision)
- **Bad**: `postgres-exposed/src/main/resources/postgres.conf` (Close, but doesn't mirror the directory)
- **Good**: `postgres-exposed/src/main/resources/postgres-exposed.conf` (Zero chance of collision across modules)

**Validation**: Before creating or modifying a `.conf` file, always check its base module directory name. Refuse to create configurations that violate this strict naming standard.

## 2. 12-Factor App / Multi-Environment Overrides
Configuration payloads MUST act strictly as an immutable schema mapping for environment variables. You are explicitly banned from shipping separate `.conf` files per environment (e.g., no `dev.conf` vs `prod.conf`).

**Build Once, Deploy Everywhere**:
- The Docker image built in CI is perfectly immutable. The exact same `.conf` files move identically through `qa` -> `staging` -> `prod`.
- All environment-specific behaviors (Database URLs, API Keys, Feature Flags) are injected from the outside at runtime via Environment Variables (e.g., `url = ${?POSTGRES_URL}`).
- **Fail-Fast Defaults**: Omit fallback defaults for critical production secrets (e.g., `jwt_secret = ${JWT_SECRET}`). The application should crash instantly on boot if misconfigured, rather than starting silently with an insecure default string.
- **Local Dev**: Overrides must be supplied locally through Docker Compose (via a `.env` file).
