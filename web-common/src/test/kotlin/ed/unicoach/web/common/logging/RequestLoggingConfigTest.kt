package ed.unicoach.web.common.logging

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestLoggingConfigTest {
  private fun parse(hocon: String) = RequestLoggingConfig.from(ConfigFactory.parseString(hocon))

  private fun block(
    secretHeaders: String = "Cookie,Authorization",
    headers: String = "Accept,Content-Type",
    detail: String = "failure",
  ) = """
    requestLogging {
        secretHeaders = "$secretHeaders"
        headers = "$headers"
        detail = "$detail"
    }
    """.trimIndent()

  @Test
  fun `secretHeaders comma list parses to a lowercased set`() {
    val config = parse(block(secretHeaders = "Cookie,Authorization,X-Unicoach-Client-Key")).getOrThrow()
    assertEquals(setOf("cookie", "authorization", "x-unicoach-client-key"), config.secretHeaders)
  }

  @Test
  fun `whitespace around secretHeaders and headers entries is trimmed and empties dropped`() {
    val config = parse(block(secretHeaders = " Cookie , , Authorization ", headers = " Accept , , User-Agent ")).getOrThrow()
    assertEquals(setOf("cookie", "authorization"), config.secretHeaders)
    assertEquals(HeaderSelection.Allowlist(setOf("Accept", "User-Agent")), config.headers)
  }

  @Test
  fun `headers wildcard parses to All`() {
    val config = parse(block(headers = "*")).getOrThrow()
    assertEquals(HeaderSelection.All, config.headers)
  }

  @Test
  fun `headers list parses to Allowlist with original case retained`() {
    val config = parse(block(headers = "Accept,Content-Type")).getOrThrow()
    assertEquals(HeaderSelection.Allowlist(setOf("Accept", "Content-Type")), config.headers)
  }

  @Test
  fun `detail failure parses to FAILURE`() {
    assertEquals(Detail.FAILURE, parse(block(detail = "failure")).getOrThrow().detail)
  }

  @Test
  fun `detail always parses to ALWAYS`() {
    assertEquals(Detail.ALWAYS, parse(block(detail = "always")).getOrThrow().detail)
  }

  @Test
  fun `detail bogus returns failure`() {
    assertTrue(parse(block(detail = "bogus")).isFailure)
  }

  @Test
  fun `missing requestLogging section returns failure`() {
    assertTrue(parse("{}").isFailure)
  }
}
