package ed.unicoach.common.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
private data class SampleData(
  val name: String,
  val count: Int,
)

class JsonExtensionsTest {
  @Test
  fun `asJson encodes Serializable data class to JsonObject`() {
    val data = SampleData(name = "test", count = 42)
    val json: JsonObject = data.asJson()
    assertEquals("\"test\"", json["name"].toString())
    assertEquals("42", json["count"].toString())
  }

  @Test
  fun `deserialize decodes JsonObject to typed data class`() {
    val data = SampleData(name = "hello", count = 7)
    val json: JsonObject = data.asJson()
    val decoded: SampleData = json.deserialize()
    assertEquals(data, decoded)
  }

  @Test
  fun `deserialize fails with SerializationException for invalid structure`() {
    val data = SampleData(name = "bad", count = 1)
    val json: JsonObject = data.asJson()
    assertFailsWith<SerializationException> {
      // SampleData cannot be decoded as a different incompatible type
      @Serializable
      data class OtherData(
        val nonexistent: Int,
      )
      json.deserialize<OtherData>()
    }
  }
}
