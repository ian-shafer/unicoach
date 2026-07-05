# INVARIANTS — admin-server/.../admin/resources

The concrete admin descriptors (`UsersResource`, `StudentsResource`,
`SessionsResource`, `SystemPromptsResource`) and the shared `occSoftDelete`
write-path helper ([OccDelete.kt](./OccDelete.kt)). Each descriptor marshals an
untyped HTML form into validated DAO input; the engine owns routing/rendering.

## Invariants

### Action handlers enforce their own preconditions, not the disabled button

**Rule:** Every state-changing action handler MUST enforce its own preconditions
server-side. The UI's enabled/disabled affordance reflects backend state and is
never the gate; a handler MUST re-check the conditions that disabled state
implies (e.g. `send-verification-email` loads at `SoftDeleteScope.ACTIVE`;
`verify-email` leans on `markEmailVerified`'s `deleted_at IS NULL` guard and
idempotent no-op).

**Why:** The frontend reflects backend state but can drift, and a client can
forge or replay any POST directly. Routes register unconditionally, so the
handler's own checks are the only real barrier. A handler that trusts the button
state lets a forged POST perform an action the UI would have blocked.

### Manual-trigger routes enqueue the unmodified existing job type/payload

**Rule:** Admin manual-trigger routes MUST enqueue the unmodified existing job
type/payload for that feature (`EXTRACT_CONVERSATION` / `SYNTHESIZE_STUDENT` /
`FIT_LENS`) — no bypass flag that skips the freshness gate, watermark, or
circuit-breaker a normal run would hit.

**Why:** These gates prevent duplicate billed LLM calls and duplicate writes
(RFC 66/93/98); a manual "force" path would silently reintroduce the
double-billing/double-write risk those gates exist to close.

## History

- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
- [x] [RFC-76: Admin email-verification actions](../../../../../../../../rfc/76-admin-email-verification-actions.md)
- [x] [RFC-100: Admin manual run triggers](../../../../../../../../rfc/100-admin-manual-run-triggers.md)
