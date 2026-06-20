# INVARIANTS — db/models

The pure domain model layer: value types, aggregate row records, the
one-capability-each entity interfaces ([Entity.kt](./Entity.kt)), and the
creation/update input records (`New*`, `UserEdit`, `StudentEdit`).

## Invariants

### Edit records define an entity's editable surface

**Rule:** An entity's `*Edit` input record is the authoritative definition of
what may be edited through the generic `update` path: it MUST carry exactly the
caller-editable fields, plus the identity and the expected OCC `version`, and
nothing else — never a server-managed/immutable column (`createdAt`,
`deletedAt`, `updatedAt`) or an out-of-band credential (the auth method,
`password_hash`/`sso_provider_id`).

**Why:** `update` binds exactly the columns the Edit record carries, so the
record's shape _is_ the editability boundary. Exposing the auth method would let
an ordinary admin/profile update clobber credentials that change only through
dedicated auth flows; exposing an immutable column would let `update` overwrite
it.

## History

- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../../rfc/62-dao-interfaces.md)
