# INVARIANTS — db/models

The pure domain model layer: value types, aggregate row records, the
one-capability-each entity interfaces ([Entity.kt](./Entity.kt)), and the
creation/update input records (`New*`, `UserEdit`, `StudentEdit`).

## Invariants

### Edit records define an entity's editable surface

**Rule:** An entity's `*Edit` record is the authoritative definition of what the
generic `update` may change: exactly the caller-editable fields, plus the
identity and expected OCC `version` — nothing else. It MUST NOT carry a
server-managed column (`createdAt`, `updatedAt`, `deletedAt`) or a
security-bearing field written only by a dedicated flow (`password_hash`,
`email_verified_at`).

**Why:** `update` binds exactly the columns the Edit record carries, so the
record's shape _is_ the editability boundary. Admitting `password_hash` or
`email_verified_at` would give an ordinary profile edit a second, unguarded
channel to forge a credential or a verified-email marker; admitting a
server-managed column would let `update` overwrite it.

## History

- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../../rfc/62-dao-interfaces.md)
