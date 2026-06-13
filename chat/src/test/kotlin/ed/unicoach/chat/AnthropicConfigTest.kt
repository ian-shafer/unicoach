package ed.unicoach.chat

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import ed.unicoach.common.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicConfigTest {
  @Test
  fun `from reads the packaged defaults`() {
    val config = AppConfig.load("chat.conf").getOrThrow()

    val anthropic = AnthropicConfig.from(config).getOrThrow()

    assertNull(anthropic.apiKey)
    assertEquals("https://api.anthropic.com", anthropic.baseUrl)
    assertEquals(10000L, anthropic.connectTimeoutMs)
    assertEquals(60000L, anthropic.socketTimeoutMs)
  }

  @Test
  fun `from reads values verbatim`() {
    val config =
      ConfigFactory.parseString(
        """
        chat.anthropic {
          apiKey = "sk-ant-test"
          baseUrl = "https://proxy.example.com"
          connectTimeoutMs = 5000
          socketTimeoutMs = 120000
        }
        """.trimIndent(),
      )

    val anthropic = AnthropicConfig.from(config).getOrThrow()

    assertEquals("sk-ant-test", anthropic.apiKey)
    assertEquals("https://proxy.example.com", anthropic.baseUrl)
    assertEquals(5000L, anthropic.connectTimeoutMs)
    assertEquals(120000L, anthropic.socketTimeoutMs)
  }

  @Test
  fun `from fails when the block is absent`() {
    val result = AnthropicConfig.from(ConfigFactory.empty())

    assertTrue(result.isFailure)
    assertIs<ConfigException>(result.exceptionOrNull())
  }
}
