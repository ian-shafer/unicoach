package ed.unicoach.chat

import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatProviderFactoryTest {
  @Test
  fun `log selects LogOnlyChatProvider`() {
    val provider = ChatProviderFactory.fromConfig(chatConfig("log")).getOrThrow()

    assertIs<LogOnlyChatProvider>(provider)
    assertEquals("log", provider.id)
  }

  @Test
  fun `unknown provider yields failure, not a fallback`() {
    val result = ChatProviderFactory.fromConfig(chatConfig("smtp"))

    assertTrue(result.isFailure)
    val failure = assertIs<IllegalArgumentException>(result.exceptionOrNull())
    assertTrue(failure.message!!.contains("[smtp]"))
  }

  @Test
  fun `anthropic with an api key selects AnthropicChatProvider`() {
    val provider = ChatProviderFactory.fromConfig(chatConfig("anthropic", apiKey = "sk-ant-test")).getOrThrow()

    val anthropic = assertIs<AnthropicChatProvider>(provider)
    assertEquals("anthropic", anthropic.id)
    // Release the real HttpClient constructed offline.
    anthropic.close()
  }

  @Test
  fun `anthropic without an api key fails at construction`() {
    val result = ChatProviderFactory.fromConfig(chatConfig("anthropic"))

    assertTrue(result.isFailure)
    val failure = assertIs<IllegalArgumentException>(result.exceptionOrNull())
    assertTrue(failure.message!!.contains("[chat.anthropic.apiKey]"))
  }

  private fun chatConfig(
    provider: String,
    apiKey: String? = null,
  ): ChatConfig =
    ChatConfig
      .from(
        ConfigFactory.parseString(
          buildString {
            append("chat.provider = \"$provider\"\n")
            append("chat.anthropic {\n")
            if (apiKey != null) append("  apiKey = \"$apiKey\"\n")
            append("  baseUrl = \"https://api.anthropic.com\"\n")
            append("  connectTimeoutMs = 10000\n")
            append("  socketTimeoutMs = 60000\n")
            append("}\n")
          },
        ),
      ).getOrThrow()
}
