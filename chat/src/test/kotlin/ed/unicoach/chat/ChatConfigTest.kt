package ed.unicoach.chat

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.common.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatConfigTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  @Test
  fun `from reads the packaged default`() {
    val config = AppConfig.load("chat.conf").getOrThrow()

    val chatConfig = ChatConfig.from(config).getOrThrow()

    assertEquals("log", chatConfig.provider)
  }

  @Test
  fun `required CHAT_PROVIDER fails to resolve when unset`() {
    // provider = ${CHAT_PROVIDER} is a required substitution (no .conf default):
    // resolving the packaged conf offline, with no CHAT_PROVIDER supplied, must
    // fail rather than silently routing prod chat to the `log` sink (RFC 95).
    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      ConfigFactory.parseResources("chat.conf").resolve(offlineOptions)
    }
  }

  @Test
  fun `CHAT_PROVIDER resolves to the set value`() {
    val config =
      ConfigFactory
        .parseString("CHAT_PROVIDER = anthropic")
        .withFallback(ConfigFactory.parseResources("chat.conf"))
        .resolve(offlineOptions)

    assertEquals("anthropic", ChatConfig.from(config).getOrThrow().provider)
  }

  @Test
  fun `from reads the provider verbatim`() {
    val config =
      ConfigFactory.parseString(
        """
        chat.provider = "anthropic"
        chat.anthropic {
          baseUrl = "https://api.anthropic.com"
          connectTimeoutMs = 10000
          socketTimeoutMs = 60000
        }
        """.trimIndent(),
      )

    val chatConfig = ChatConfig.from(config).getOrThrow()

    // No value validation in the reader — the factory is the single place an
    // unknown selector is rejected.
    assertEquals("anthropic", chatConfig.provider)
  }

  @Test
  fun `from fails when the key is absent`() {
    val result = ChatConfig.from(ConfigFactory.empty())

    assertTrue(result.isFailure)
    assertIs<ConfigException>(result.exceptionOrNull())
  }
}
