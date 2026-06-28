package ed.unicoach.admin

import com.typesafe.config.ConfigFactory
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminConfigTest {
  /** Parses the packaged `admin-server.conf` (with classpath fallbacks) the way the server does. */
  private fun packagedConfig() = ConfigFactory.load(ConfigFactory.parseResourcesAnySyntax("admin-server.conf"))

  @Test
  fun `parses admin display defaults`() {
    val display = AdminConfig.from(packagedConfig()).getOrThrow().display
    assertEquals(ZoneId.of("UTC"), display.timezone)
    assertEquals("🔗", display.idLinkGlyph)
    assertEquals("✓", display.boolTrueGlyph)
    assertEquals("✗", display.boolFalseGlyph)
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
