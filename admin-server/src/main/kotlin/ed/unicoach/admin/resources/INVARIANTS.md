# INVARIANTS — admin-server/.../admin/resources

The concrete admin descriptors (`UsersResource`, `StudentsResource`,
`SessionsResource`, `SystemPromptsResource`) and the shared `occSoftDelete`
write-path helper ([OccDelete.kt](./OccDelete.kt)). Each descriptor marshals an
untyped HTML form into validated DAO input; the engine owns routing/rendering.

## Invariants

### `system_prompts` create passes `body` verbatim

**Rule:** `SystemPromptsResource`'s create handler MUST pass the submitted
`body` to `NewSystemPrompt` **byte-for-byte** — it MUST NOT `trim`, normalize
newlines, or otherwise canonicalize it. It MAY (and does) `trim` `name` and
`version`, which are identifiers.

**Why:** `body` is the exact text sent to the LLM, and the `system_prompts`
table deliberately exempts it from a `*_trimmed_check` while keeping no
raw-payload backup (schema 0007, D-5). The DB guarantees only non-empty-after-
trim and a size bound — it has no guard against over-trimming. Trailing
whitespace/newlines can be intentional, so any canonicalization here silently
alters what reaches the model with no surviving original — a correctness
corruption that neither a constraint nor the `name`/`version` trimmed-column
tests would catch.

### `email_verified_at` is never editable through the generic update path

**Rule:** `UsersResource`'s `emailVerifiedAt` field MUST stay `editable = false`
and MUST NOT appear in `createExtraInputs` or the edit form, and `UserEdit` MUST
NOT carry an `emailVerifiedAt`. The verification marker MUST be written only by
the dedicated `verify-email` route (`UsersDao.markEmailVerified`) and the auth
layer's change-email path — never by the generic `update`/`UserEdit` write.

**Why:** `email_verified_at` is a security-bearing trust marker (a verified
address gates downstream behavior). The generic OCC update path applies whatever
fields the edit form carries; if `emailVerifiedAt` leaked into the editable set
or into `UserEdit`, an operator (or a forged edit-form POST) could stamp or
clear verification at will, bypassing the dedicated DAO path that owns that
column's lifecycle — forging verification state. This mirrors the same
write-isolation the sensitive `password_hash` column already enjoys; collapsing
the two write paths re-opens the hole.

### Verification actions are enforced by their routes, not by the disabled button

**Rule:** The `verify-email` and `send-verification-email` POST handlers MUST
independently reject an action that the UI would disable:
`send-verification-
email` MUST load the user at `SoftDeleteScope.ACTIVE` (so a
soft-deleted user yields `NotFoundException` → 404), and `verify-email` MUST
rely on `markEmailVerified`'s `deleted_at IS NULL` guard and its idempotent
no-op on an already-verified row. Neither handler may treat the rendered
disabled button as the gate.

**Why:** The disabled button is a client-side affordance only; an operator can
forge or replay either POST directly. The routes register unconditionally, so
the DAO/service contract is the sole real barrier. A refactor that "simplifies"
a handler to skip the ACTIVE-scope load, or that trusts the button state, would
silently let a forged POST resend mail to — or re-verify — a soft-deleted user,
the exact bypass these guards exist to close.

## History

- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
- [x] [RFC-76: Admin email-verification actions](../../../../../../../../rfc/76-admin-email-verification-actions.md)
