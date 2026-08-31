package ed.unicoach.coaching

import com.typesafe.config.ConfigFactory
import ed.unicoach.common.config.AppConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoachingConfigTest {
  @Test
  fun `from reads the packaged defaults`() {
    val config = AppConfig.load("service.conf").getOrThrow()
    val coaching = CoachingConfig.from(config).getOrThrow()
    assertEquals("claude-sonnet-4-6", coaching.model)
    assertEquals(4096, coaching.maxTokens)
    assertEquals("coach", coaching.systemPromptName)
    assertEquals("v10", coaching.systemPromptVersion)
    assertTrue(coaching.surfaceCommitments)
    assertTrue(coaching.surfaceFitSuggestions, "surfaceFitSuggestions (RFC 98) must be read from the packaged defaults")
  }

  @Test
  fun `from fails when a key is absent`() {
    val empty = ConfigFactory.empty()
    assertTrue(CoachingConfig.from(empty).isFailure)
  }
}
