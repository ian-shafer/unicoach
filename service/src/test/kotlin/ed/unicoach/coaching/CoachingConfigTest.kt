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
    // Only the SHAPE of the pin, never the label. `bin/prompt-seed` OWNS this
    // line as of RFC 181: at land it generates the next coach seed from
    // prompts/coach-system-prompt.txt and rewrites systemPromptVersion to the
    // label it just generated. A literal "v24" here would therefore be
    // invalidated by the very step that made it wrong, and an assertion whose
    // repair is part of the change it is meant to catch guards nothing.
    //
    // The label's real contract — that the pin is the catalog's newest coach
    // row — is asserted in SystemPromptCatalogTest, which has the database
    // session and already owns the pin-versus-catalog relation.
    val version = coaching.systemPromptVersion
    val digits = version.removePrefix("v")
    assertTrue(
      version.startsWith("v") && digits.isNotEmpty() && digits.all(Char::isDigit),
      "coaching.systemPromptVersion must be a vNN label; bin/prompt-seed rewrites it at land (RFC 181): [$version]",
    )
    assertTrue(coaching.surfaceCommitments)
    assertTrue(coaching.surfaceFitSuggestions, "surfaceFitSuggestions (RFC 98) must be read from the packaged defaults")
  }

  @Test
  fun `from fails when a key is absent`() {
    val empty = ConfigFactory.empty()
    assertTrue(CoachingConfig.from(empty).isFailure)
  }
}
