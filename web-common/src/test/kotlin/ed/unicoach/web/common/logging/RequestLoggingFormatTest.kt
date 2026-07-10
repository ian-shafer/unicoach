package ed.unicoach.web.common.logging

import io.ktor.http.Headers
import io.ktor.http.headersOf
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RequestLoggingFormatTest {
  private val secretHeaders = setOf("cookie", "authorization", "x-unicoach-client-key")

  private fun careful(
    headers: HeaderSelection = HeaderSelection.Allowlist(setOf("Accept", "Content-Type", "Expect")),
    detail: Detail = Detail.FAILURE,
  ) = RequestLoggingConfig(secretHeaders = secretHeaders, headers = headers, detail = detail)

  private fun format(
    config: RequestLoggingConfig,
    method: String = "GET",
    uri: String = "/x",
    status: Int? = 200,
    requestHeaders: Headers = Headers.Empty,
    responseHeaders: Headers = Headers.Empty,
    latency: Duration = Duration.ZERO,
    secretQueryParams: Set<String> = emptySet(),
  ) = config.formatLogLine(method, uri, status, requestHeaders, responseHeaders, latency, secretQueryParams)

  @Test
  fun `careful success is a bare head with no enrichment`() {
    val line = format(careful(), status = 200)
    assertEquals("GET /x -> 200", line)
  }

  @Test
  fun `careful failure attaches allowlist headers including an absent one`() {
    val line =
      format(
        careful(),
        method = "POST",
        status = 406,
        requestHeaders = headersOf("Accept" to listOf("text/event-stream"), "Content-Type" to listOf("application/json")),
      )
    assertContains(line, "Accept=[text/event-stream]")
    assertContains(line, "Content-Type=[application/json]")
    assertContains(line, "Expect=[(absent)]")
  }

  @Test
  fun `always mode enriches a success`() {
    val line = format(careful(detail = Detail.ALWAYS), status = 200)
    assertContains(line, "latency=[0ms]")
    assertContains(line, "respBody=[(none)]")
  }

  @Test
  fun `no response is enriched`() {
    val line = format(careful(), status = null)
    assertContains(line, "-> no-response")
    assertContains(line, "latency=[")
  }

  @Test
  fun `secret headers are subtracted under All`() {
    val line =
      format(
        careful(headers = HeaderSelection.All),
        status = 500,
        requestHeaders =
          headersOf(
            "Accept" to listOf("*/*"),
            "Cookie" to listOf("session=abc"),
            "Authorization" to listOf("Bearer xyz"),
            "X-Unicoach-Client-Key" to listOf("k1"),
          ),
      )
    assertContains(line, "Accept=[*/*]")
    assertFalse(line.contains("Cookie"), "Cookie leaked: $line")
    assertFalse(line.contains("session=abc"), "cookie value leaked: $line")
    assertFalse(line.contains("Authorization"), "Authorization leaked: $line")
    assertFalse(line.contains("Bearer xyz"), "auth value leaked: $line")
    assertFalse(line.contains("X-Unicoach-Client-Key"), "client key header leaked: $line")
    assertFalse(line.contains("k1"), "client key value leaked: $line")
  }

  @Test
  fun `secret subtraction is case-insensitive under All`() {
    val line =
      format(
        careful(headers = HeaderSelection.All),
        status = 500,
        requestHeaders = headersOf("authorization" to listOf("Bearer xyz")),
      )
    assertFalse(line.contains("authorization"), "lowercase authorization leaked: $line")
    assertFalse(line.contains("Bearer xyz"), "auth value leaked: $line")
  }

  @Test
  fun `a secret listed in an allowlist is still never emitted`() {
    val line =
      format(
        careful(headers = HeaderSelection.Allowlist(setOf("Accept", "Authorization"))),
        status = 500,
        requestHeaders = headersOf("Accept" to listOf("*/*"), "Authorization" to listOf("Bearer xyz")),
      )
    assertContains(line, "Accept=[*/*]")
    assertFalse(line.contains("Authorization"), "allowlisted secret leaked: $line")
    assertFalse(line.contains("Bearer xyz"), "auth value leaked: $line")
  }

  @Test
  fun `body sizes render from Content-Length, chunked, and neither`() {
    val fromLength =
      format(careful(), status = 400, requestHeaders = headersOf("Content-Length" to listOf("24")))
    assertContains(fromLength, "body=[24b]")

    val fromChunked =
      format(careful(), status = 400, responseHeaders = headersOf("Transfer-Encoding" to listOf("chunked")))
    assertContains(fromChunked, "respBody=[chunked]")

    val fromNeither = format(careful(), status = 400)
    assertContains(fromNeither, "body=[(none)]")
    assertContains(fromNeither, "respBody=[(none)]")
  }

  @Test
  fun `latency is computed as passed`() {
    val line = format(careful(detail = Detail.ALWAYS), status = 200, latency = 3.milliseconds)
    assertContains(line, "latency=[3ms]")
  }

  @Test
  fun `Content-Length appears both as an allowlist header and as body`() {
    val line =
      format(
        careful(headers = HeaderSelection.Allowlist(setOf("Content-Length"))),
        status = 400,
        requestHeaders = headersOf("Content-Length" to listOf("24")),
      )
    assertContains(line, "body=[24b]")
    assertContains(line, "Content-Length=[24]")
  }

  @Test
  fun `query redaction hides a secret param and preserves the rest and order`() {
    val line =
      format(
        careful(),
        method = "POST",
        uri = "/api/v1/auth/reset?token=abc&status=open",
        status = 400,
        secretQueryParams = setOf("token"),
      )
    assertContains(line, "/api/v1/auth/reset?token=[redacted]&status=open")
    assertFalse(line.contains("token=abc"), "secret query value leaked: $line")
  }

  @Test
  fun `a percent-encoded secret param name is still redacted`() {
    val line =
      format(
        careful(),
        method = "POST",
        uri = "/api/v1/auth/reset?to%6Ben=abc&status=open",
        status = 400,
        secretQueryParams = setOf("token"),
      )
    assertContains(line, "to%6Ben=[redacted]")
    assertContains(line, "status=open")
    assertFalse(line.contains("to%6Ben=abc"), "encoded secret query name bypassed redaction: $line")
  }

  @Test
  fun `a non-secret param and a uri with no query are untouched`() {
    val withQuery =
      format(careful(), uri = "/x?status=open", status = 400, secretQueryParams = setOf("token"))
    assertContains(withQuery, "/x?status=open")

    val noQuery = format(careful(), uri = "/x", status = 400, secretQueryParams = setOf("token"))
    assertTrue(noQuery.startsWith("GET /x ->"), "uri altered: $noQuery")
  }
}
