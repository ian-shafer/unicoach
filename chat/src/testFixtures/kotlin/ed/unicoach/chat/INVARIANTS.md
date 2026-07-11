# INVARIANTS — chat testFixtures

Shared fakes and wire-faithful SSE fixtures for testing the chat provider (RFC
107).

## Invariants

### Fake the transport seam, not the provider port

**Rule:** Integration tests fake the `AnthropicStreamTransport` seam and drive
the real `AnthropicChatProvider`; they MUST NOT fake the `ChatProvider` port.

**Why:** The provider parses the SSE wire, maps it to the
`ChatEvent`/`ChatResponse` taxonomy, and classifies the terminal. Faking the
port skips the code the tests exist to cover, so the mapping stays unverified
while the tests still pass.

### Chat fixtures must decode through the real parser

**Rule:** The chat fixtures are wire-faithful Anthropic SSE frames, and every
fixture stream MUST decode cleanly through the real
`KtorAnthropicStreamTransport` parser (asserted in the transport tests).

**Why:** A fixture that used a shape the real API cannot emit would verify the
provider against a fiction. Requiring the production parser to accept every
fixture is the enforceable fidelity guard — it does not require a live capture,
but it does forbid loose approximations the parser would reject.
