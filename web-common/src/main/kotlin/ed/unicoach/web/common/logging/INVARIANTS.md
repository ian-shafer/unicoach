# INVARIANTS — web-common/web/common/logging

The shared, configurable request-logging component (`configureRequestLogging`,
`RequestLoggingConfig`, the pure `formatLogLine`, and the route-scoped
`secretQueryParams` opt-in) consumed by rest-server, admin-web, and public-web.

## Invariants

### Secret request-header values are never logged, in any environment

**Rule:** The request log MUST NOT emit the value of any header whose name
matches an entry in `requestLogging.secretHeaders`, in any environment or
verbosity mode (including `headers="*"`); the secret set is subtracted last,
after header selection, by case-insensitive name match.

**Why:** Secret headers carry session and credential material — `Cookie` and
`Authorization` for every service, plus any service-specific credential header
(e.g. rest-server's `X-Unicoach-Client-Key`); logging them writes durable
credentials into the log store. Dev's `headers="*"` selects every header sent,
so this subtraction is the only barrier — applied before the wildcard expansion,
or matched case-sensitively (so a lowercase `authorization` slips through), it
would leak. The guarantee protects all three services through the one shared
component.

## History

- [x] [RFC-105: Shared request-logging component](../../../../../../../../../rfc/105-shared-request-logging-component.md)
