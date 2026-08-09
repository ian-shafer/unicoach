package ed.unicoach.appstore

/**
 * The narrowest HTTP boundary of the App Store Server API client — what tests
 * fake (RFC 107). One authenticated GET; IO failures propagate as thrown
 * exceptions from the real transport.
 */
fun interface AppStoreTransport {
  suspend fun get(
    path: String,
    bearerToken: String,
  ): AppStoreTransportResponse
}

class AppStoreTransportResponse(
  val status: Int,
  val body: String,
)
