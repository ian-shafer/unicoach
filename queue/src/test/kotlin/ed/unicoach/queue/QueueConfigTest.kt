package ed.unicoach.queue

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class QueueConfigTest {
  @Test
  fun `from parses valid minimal configuration`() {
    val config =
      ConfigFactory.parseString(
        """
        queue {
        }
        """.trimIndent(),
      )
    val result = QueueConfig.from(config)
    assertTrue(result.isSuccess, "Expected successful parse, got: ${result.exceptionOrNull()}")
  }

  @Test
  fun `from fails if queue block is completely missing`() {
    val config = ConfigFactory.parseString("")
    val result = QueueConfig.from(config)
    assertTrue(result.isFailure, "Expected failure when queue block is absent")
  }
}
