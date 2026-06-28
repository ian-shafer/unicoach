package ed.unicoach.admin

import com.typesafe.config.ConfigFactory
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminConfigTest {
  /** Parses the packaged `admin-server.conf` (with classpath fallbacks) the way the server does. */
  private fun packagedConfig() = ConfigFactory.load(ConfigFactory.parseResourcesAnySyntax("admin-server.conf"))

  /**
   * Builds a full admin config whose `display` block is the supplied lines (each a
   * `key = value` pair). The other sections carry valid fixtures so only the
   * display block under test drives the outcome.
   */
  private fun configWithDisplay(displayLines: String) =
    ConfigFactory.parseString(
      """
      admin {
        server { host = "127.0.0.1", port = 8081 }
        session {
          cookieName = "admin_session"
          cookieDomain = ""
          cookieSecure = false
          expirationSeconds = 86400
        }
        display {
          $displayLines
        }
      }
      """.trimIndent(),
    )

  @Test
  fun `parses admin display defaults`() {
    val display = AdminConfig.from(packagedConfig()).getOrThrow().display
    assertEquals(ZoneId.of("UTC"), display.timezone)
    assertEquals("🔗", display.idLinkGlyph)
    assertEquals("✓", display.boolTrueGlyph)
    assertEquals("✗", display.boolFalseGlyph)
    assertEquals(8, display.idTailChars)
    assertEquals("⧉", display.copyGlyph)
  }

  @Test
  fun `idTailChars override is parsed`() {
    val config =
      configWithDisplay(
        """
        timezone       = "UTC"
        idLinkGlyph    = "🔗"
        boolTrueGlyph  = "✓"
        boolFalseGlyph = "✗"
        idTailChars    = 4
        copyGlyph      = "⧉"
        """.trimIndent(),
      )
    assertEquals(
      4,
      AdminConfig
        .from(config)
        .getOrThrow()
        .display.idTailChars,
    )
  }

  @Test
  fun `copyGlyph override is parsed`() {
    val config =
      configWithDisplay(
        """
        timezone       = "UTC"
        idLinkGlyph    = "🔗"
        boolTrueGlyph  = "✓"
        boolFalseGlyph = "✗"
        idTailChars    = 8
        copyGlyph      = "CP"
        """.trimIndent(),
      )
    assertEquals(
      "CP",
      AdminConfig
        .from(config)
        .getOrThrow()
        .display.copyGlyph,
    )
  }

  @Test
  fun `non-positive idTailChars fails fast`() {
    val config =
      configWithDisplay(
        """
        timezone       = "UTC"
        idLinkGlyph    = "🔗"
        boolTrueGlyph  = "✓"
        boolFalseGlyph = "✗"
        idTailChars    = 0
        copyGlyph      = "⧉"
        """.trimIndent(),
      )
    assertTrue(AdminConfig.from(config).isFailure, "A non-positive idTailChars must make AdminConfig.from fail fast")
  }

  @Test
  fun `missing idTailChars fails fast`() {
    val config =
      configWithDisplay(
        """
        timezone       = "UTC"
        idLinkGlyph    = "🔗"
        boolTrueGlyph  = "✓"
        boolFalseGlyph = "✗"
        copyGlyph      = "⧉"
        """.trimIndent(),
      )
    assertTrue(AdminConfig.from(config).isFailure, "A missing idTailChars must make AdminConfig.from fail fast")
  }

  @Test
  fun `a malformed timezone fails fast`() {
    val config =
      ConfigFactory
        .parseString(
          """
          admin {
            server { host = "127.0.0.1", port = 8081 }
            session {
              cookieName = "admin_session"
              cookieDomain = ""
              cookieSecure = false
              expirationSeconds = 86400
            }
            display {
              timezone       = "Not/AZone"
              idLinkGlyph    = "🔗"
              boolTrueGlyph  = "✓"
              boolFalseGlyph = "✗"
            }
          }
          """.trimIndent(),
        )

    val result = AdminConfig.from(config)
    assertTrue(result.isFailure, "A malformed timezone must make AdminConfig.from fail fast")
  }
}
