package ed.unicoach.appstore

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * The real [AppStoreTransport]: GETs `{baseUrl}{path}` with `Authorization:
 * Bearer`. IO failures propagate. The backing [HttpClient] is owned and closed
 * by the composition root (`ApplicationStopped`), mirroring the chat client's
 * lifecycle.
 */
class KtorAppStoreTransport(
  private val client: HttpClient,
  private val config: AppStoreConfig,
) : AppStoreTransport {
  override suspend fun get(
    path: String,
    bearerToken: String,
  ): AppStoreTransportResponse {
    val response =
      client.get("${config.baseUrl}$path") {
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
      }
    return AppStoreTransportResponse(status = response.status.value, body = response.bodyAsText())
  }
}
