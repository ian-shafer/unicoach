package ed.unicoach.rest.models

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorCodeTest {
  private val mapper = jacksonObjectMapper()

  @Test
  fun `every wire code is lowercase snake_case`() {
    val pattern = Regex("^[a-z][a-z0-9_]*$")
    for (code in ErrorCode.entries) {
      assertTrue(
        pattern.matches(code.wire),
        "ErrorCode.${code.name} wire string '${code.wire}' must be lowercase snake_case",
      )
    }
  }

  @Test
  fun `wire codes are distinct`() {
    val wires = ErrorCode.entries.map { it.wire }
    assertEquals(wires.size, wires.toSet().size, "Two ErrorCode entries serialize to the same wire string")
  }

  @Test
  fun `EMAIL_NOT_VERIFIED serializes its wire string via JsonValue`() {
    val json = mapper.writeValueAsString(ErrorResponse(ErrorCode.EMAIL_NOT_VERIFIED, "Email verification required."))
    val node = mapper.readTree(json)
    assertEquals("email_not_verified", node["code"].asText())
  }
}
