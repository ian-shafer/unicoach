package ed.unicoach.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

// The chat accumulation extension against inline fake providers: streams that
// violate the port contract (empty, or ending on a non-terminal) are defects
// and must throw, not return.
class ChatProviderTest {
  private val request =
    ChatRequest(
      model = "claude-opus-4-8",
      system = null,
      messages = listOf(ChatMessage(ChatRole.USER, "hello")),
      maxTokens = 64,
    )

  @Test
  fun `chat throws on an empty stream`() =
    runTest {
      val provider = fakeProvider(emptyFlow())

      assertFailsWith<IllegalStateException> { provider.chat(request) }
    }

  @Test
  fun `chat throws when the stream ends on a non-terminal`() =
    runTest {
      val provider =
        fakeProvider(
          flowOf(ChatEvent.MessageStart(providerRequestId = null, model = null, usage = null)),
        )

      assertFailsWith<IllegalStateException> { provider.chat(request) }
    }

  private fun fakeProvider(events: Flow<ChatEvent>): ChatProvider =
    object : ChatProvider {
      override val id: String = "fake"

      override fun stream(request: ChatRequest): Flow<ChatEvent> = events
    }
}
