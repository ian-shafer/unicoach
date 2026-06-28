# INVARIANTS — admin-server/.../admin

The process-bootstrap layer of the admin website: loads configuration,
constructs shared dependencies, binds the Ktor/Netty server, and assembles the
application module.

## Invariants

### `admin.display.timezone` is validated at config parse, not at first render

**Rule:** `AdminConfig.from` MUST validate the `admin.display.timezone` config
value through `ZoneId.of` at parse time — `DisplayConfig.timezone` MUST be a
`ZoneId`, not a raw `String`. A malformed zone MUST yield `Result.failure` (and
via `getOrThrow()` in `startServer`, crash the process before the server binds),
not a successful boot that throws on the first timestamp cell render.

**Why:** The admin server's config-parse pattern (`Result.failure` +
`getOrThrow()` before the server binds) is the guarantee that a misconfigured
deployment fails loudly at startup. `ADMIN_DISPLAY_TIMEZONE` can be set to an
arbitrary environment value; if validation is deferred to the render layer (e.g.
by storing the timezone as a `String` and calling `ZoneId.of` lazily in
`renderValue`), a misconfigured value causes the server to bind and accept
requests normally, then throws on the first page that renders a timestamp cell.
That first-render throw surfaces as a 500 or an uncaught exception on a
production request — the exact class of opaque runtime failure the startup
fail-fast exists to prevent.

## History

- [x] [RFC-79: Admin display conventions](../../../../../../../rfc/79-admin-display-conventions.md)
