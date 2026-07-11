package ed.unicoach.email

/**
 * A port-level [EmailProvider] fake returning a fixed [ProviderResult] and
 * capturing the last [OutboundEmail] it was handed.
 *
 * Retained only for the `:email` unit tests that exercise `EmailService` /
 * `EmailSendHandler` above the provider (they legitimately stub the port). The
 * RFC 107 end-to-end integration tests fake one level lower, at the
 * [SesSendOperation] seam, and drive the real [SesEmailProvider] — they do NOT
 * use this.
 */
class FakeEmailProvider(
  private val outcome: ProviderResult,
  override val id: String = "fake",
) : EmailProvider {
  var captured: OutboundEmail? = null
    private set

  override suspend fun send(email: OutboundEmail): ProviderResult {
    captured = email
    return outcome
  }
}
