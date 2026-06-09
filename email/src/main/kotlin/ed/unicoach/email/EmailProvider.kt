package ed.unicoach.email

interface EmailProvider {
  // Provider identity; written verbatim to the ledger `provider` column.
  val id: String

  suspend fun send(email: OutboundEmail): ProviderResult
}
