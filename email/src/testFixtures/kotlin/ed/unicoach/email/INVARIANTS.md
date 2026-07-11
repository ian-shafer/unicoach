# INVARIANTS — email testFixtures

Shared fakes and recorded SES shapes for testing the email provider (RFC 107).

## Invariants

### Fake the send seam, not the provider port

**Rule:** Integration tests fake the `SesSendOperation` seam and drive the real
`SesEmailProvider`; they MUST NOT fake the `EmailProvider` port.

**Why:** The provider builds the `SendEmailRequest` and maps SES SDK exceptions
to the `ProviderResult` taxonomy (Sent/Rejected/TransientFailure). Faking the
port skips that mapping, so it stays unverified while the tests still pass.

### Replay real SES SDK shapes, not stand-ins

**Rule:** The fixtures replay the real SES SDK response and exception types
(`SendEmailResponse`, `MessageRejected`, `TooManyRequestsException`, …), never
stand-in types.

**Why:** The provider's mapping keys off the concrete SDK exception classes; a
stand-in would exercise a different branch than production and hide mapping
drift.
