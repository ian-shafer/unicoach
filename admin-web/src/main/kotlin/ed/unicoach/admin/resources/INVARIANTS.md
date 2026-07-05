# INVARIANTS — admin-server/.../admin/resources

The concrete admin descriptors (`UsersResource`, `StudentsResource`,
`SessionsResource`, `SystemPromptsResource`) and the shared `occSoftDelete`
write-path helper ([OccDelete.kt](./OccDelete.kt)). Each descriptor marshals an
untyped HTML form into validated DAO input; the engine owns routing/rendering.

## Invariants

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
