# INVARIANTS — admin-server/.../admin/engine

The descriptor-driven, table-agnostic engine: a registry of typed
`AdminResource` descriptors drives all navigation, list/detail rendering, forms,
edges, custom actions, and generic route registration, with no per-table logic
in the router.

## Invariants

### The engine owns no route for `customActions`

**Rule:** `AdminRouting`/the engine MUST NOT register a generic route for any
`CustomAction` (it MUST NOT iterate `customActions` to
`post("/{slug}/{id}/${pathSuffix}")`), and it MUST NEVER inspect or dispatch on
`CustomAction.pathSuffix`. Each custom action's POST endpoint is registered
solely by the owning descriptor through `registerExtraRoutes`.

**Why:** A custom action's handler carries action-specific authorization,
load-scope, and idempotency semantics that only the descriptor knows (e.g.
`send-verification-email` must load at `SoftDeleteScope.ACTIVE` so a forged POST
on a soft-deleted user 404s instead of silently resending). A future
"convenience" edit that auto-registers routes from the `customActions`
declaration to remove boilerplate would mint handlers with none of that defense
— or shadow/duplicate the descriptor's own `registerExtraRoutes` route — turning
a UX affordance (the disabled button) into the only barrier and re-opening the
forged-POST hole the descriptor closes. `pathSuffix` is a render-only token for
building the button's POST target, not a routing key.

## History

- [x] [RFC-76: Admin email-verification actions](../../../../../../../../rfc/76-admin-email-verification-actions.md)
