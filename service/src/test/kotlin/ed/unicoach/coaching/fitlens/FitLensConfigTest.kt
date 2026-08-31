package ed.unicoach.coaching.fitlens

import com.typesafe.config.ConfigFactory
import ed.unicoach.common.config.AppConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FitLensConfigTest {
  @Test
  fun `from reads every key from the packaged defaults`() {
    val config = AppConfig.load("service.conf").getOrThrow()
    val fitLens = FitLensConfig.from(config).getOrThrow()
    assertFalse(fitLens.enabled, "fit-lens ships disabled by default")
    assertEquals("claude-sonnet-4-6", fitLens.model)
    assertEquals(1024, fitLens.queryMaxTokens)
    assertEquals(2048, fitLens.reasonMaxTokens)
    assertEquals(15, fitLens.searchLimit)
    assertEquals(3, fitLens.minClaims)
    assertEquals(200, fitLens.maxClaims)
    assertEquals(3, fitLens.maxConsecutiveFailures)
    assertEquals("fit_lens_query", fitLens.queryPromptName)
    assertEquals("v3", fitLens.queryPromptVersion)
    assertEquals("fit_lens_reason", fitLens.reasonPromptName)
    assertEquals("v2", fitLens.reasonPromptVersion)
  }

  @Test
  fun `from fails when a key is absent`() {
    val empty = ConfigFactory.empty()
    assertTrue(FitLensConfig.from(empty).isFailure, "an absent fitLens key must fail the reader")
  }
}
