package ed.unicoach.rest.config

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientKeyGateConfigTest {
  private fun parse(hocon: String) = ClientKeyGateConfig.from(ConfigFactory.parseString(hocon))

  @Test
  fun `comma-separated keys parse to a set`() {
    val result =
      parse(
        """
        clientKeyGate {
            keys = "k1,k2,k3"
            allowlistPaths = ["/hello"]
        }
        """.trimIndent(),
      )
    assertTrue(result.isSuccess)
    assertEquals(setOf("k1", "k2", "k3"), result.getOrThrow().validKeys)
  }

  @Test
  fun `whitespace around delimited keys is trimmed`() {
    val result =
      parse(
        """
        clientKeyGate {
            keys = " k1 , k2 "
            allowlistPaths = ["/hello"]
        }
        """.trimIndent(),
      )
    assertEquals(setOf("k1", "k2"), result.getOrThrow().validKeys)
  }

  @Test
  fun `blank keys parses to an empty set with success`() {
    val result =
      parse(
        """
        clientKeyGate {
            keys = ""
            allowlistPaths = ["/hello"]
        }
        """.trimIndent(),
      )
    assertTrue(result.isSuccess)
    assertTrue(result.getOrThrow().validKeys.isEmpty())
  }

  @Test
  fun `empty segments are dropped`() {
    val result =
      parse(
        """
        clientKeyGate {
            keys = "k1,,k2,"
            allowlistPaths = ["/hello"]
        }
        """.trimIndent(),
      )
    assertEquals(setOf("k1", "k2"), result.getOrThrow().validKeys)
  }

  @Test
  fun `allowlistPaths parses from a string list into a set`() {
    val result =
      parse(
        """
        clientKeyGate {
            keys = ""
            allowlistPaths = ["/hello", "/health"]
        }
        """.trimIndent(),
      )
    assertEquals(setOf("/hello", "/health"), result.getOrThrow().allowlistPaths)
  }

  @Test
  fun `missing clientKeyGate section returns failure`() {
    val result = parse("{}")
    assertTrue(result.isFailure)
  }

  @Test
  fun `scalar allowlistPaths returns failure rather than throwing`() {
    val result =
      parse(
        """
        clientKeyGate {
            keys = ""
            allowlistPaths = "/hello"
        }
        """.trimIndent(),
      )
    assertTrue(result.isFailure)
  }
}
