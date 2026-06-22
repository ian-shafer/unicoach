# INVARIANTS — rest/routing

The HTTP routing layer: maps cookies and request bodies onto service calls and
service outcomes onto HTTP/SSE responses. Contains no domain logic.

## Invariants

### Best-effort fire-and-forget side-effects never alter a response

**Rule:** A best-effort, fire-and-forget side-effect attached to a request —
such as the `EXTRACT_CONVERSATION` enqueue fired after a successful coaching
turn in `ConvoRouteHandler` — MUST NOT change or fail that request's response.
It runs only after the response outcome is determined (and, when tied to
success, only on the successful terminal), and any failure (a returned error
outcome or a thrown exception) MUST be caught, logged, and swallowed. It MUST
NOT alter the HTTP status, body, or SSE frames, and MUST NOT run on a failed or
short-circuited request.

**Why:** These side-effects are out-of-band optimizations, not part of the
request's contract. If one's failure could surface to the caller, a transient
dependency hiccup (a queue write, a background enqueue) would corrupt or fail a
request that already succeeded — and, for a streamed turn, was already partly
written to the client — turning a background optimization into a user-visible
outage.

## History

- [x] [RFC-66: Extraction](../../../../../../../../rfc/66-extraction.md)
