# INVARIANTS — public-web/.../web

The process-bootstrap and routing layer of `public-web`, the internet-facing
Ktor/Netty server for Unicoach's brand, legal, and error pages.

## Invariants

### Every route stays public — no auth or client-key gate

**Rule:** `installPublicWebRouting` MUST NOT install an authentication gate or
the RFC-54 client-key gate. Every route — home, legal, error, and `/healthz` —
MUST remain reachable with no credential or header.

**Why:** This module is modeled on `rest-server` and `admin-server`, both of
which DO gate their routes; the gate is the natural thing to copy in. But the
public web presence must answer ordinary browsers and email clients following a
verification link. Installing a gate here 401s the entire intended audience and
silently breaks the public site.

### `/healthz` stays dependency-free

**Rule:** The `/healthz` handler MUST NOT touch any backing service. It returns
a constant `200`/`{"status":"ok"}` unconditionally.

**Why:** A future email-verification RFC will add a `Database` to this module.
If a DB (or any other) probe is then wired into `/healthz`, the liveness check
starts failing on backend blips and the supervisor/load-balancer kills a process
that is in fact serving every page correctly. The probe must reflect only
whether the process accepts connections.

## History

- [x] [RFC-61: Public Web Module (Dynamic HTML via Shared Layout)](../../../../../../../rfc/61-static-marketing-site.md)
