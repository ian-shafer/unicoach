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

  private fun chatConfig(provider: String): ChatConfig =
    ChatConfig
      .from(ConfigFactory.parseString("chat.provider = \"$provider\""))
      .getOrThrow()
}
