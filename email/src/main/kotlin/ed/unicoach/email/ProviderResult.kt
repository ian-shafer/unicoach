package ed.unicoach.email

sealed interface ProviderResult {
  // Accepted/delivered by the provider.
  data class Sent(
    val providerMessageId: String?,
  ) : ProviderResult

  // Permanent failure; no retry helps.
  data class Rejected(
    val reason: String,
  ) : ProviderResult

  // Retriable failure.
  data class TransientFailure(
    val reason: String,
  ) : ProviderResult
}
