package ed.unicoach.coaching

import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The coach's door to the sharing permission (RFC 251), over the real store. */
class UpdateSharingPermissionChatToolTest {
  private val service = SharingPermissionService(CoachingTestDb.database)
  private val tool = UpdateSharingPermissionChatTool(service)

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate("sharing_permissions_versions", "sharing_permissions", "students", "users")
  }

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun resultOf(result: JsonObject): JsonObject = result.getValue(UpdateSharingPermissionChatTool.RESULT_KEY).jsonObject

  @Test
  fun `a yes is stored and said back, and a withdrawal turns it off again`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("usp-grant")

      val granted = resultOf(tool.execute(student, input("""{"permission": "intended_subjects", "status": "granted"}""")))
      assertEquals("granted", granted.getValue("status").jsonPrimitive.content)
      assertEquals(SharingPermissionCopy.reachOf(SharingPermissionStatus.GRANTED), granted.getValue("statement").jsonPrimitive.content)
      assertEquals(SharingPermissionStatus.GRANTED, service.read(student).getOrThrow()[SharingPermission.INTENDED_SUBJECTS])

      val refused = resultOf(tool.execute(student, input("""{"permission": "intended_subjects", "status": "refused"}""")))
      assertEquals("refused", refused.getValue("status").jsonPrimitive.content)
      assertEquals(SharingPermissionCopy.reachOf(SharingPermissionStatus.REFUSED), refused.getValue("statement").jsonPrimitive.content)
      assertEquals(SharingPermissionStatus.REFUSED, service.read(student).getOrThrow()[SharingPermission.INTENDED_SUBJECTS])
    }

  @Test
  fun `never asked cannot be written, and nothing is stored when it is tried`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("usp-not-asked")

      val result = tool.execute(student, input("""{"permission": "intended_subjects", "status": "not_asked"}"""))

      assertEquals("[status] must be one of [granted, refused], got: [not_asked]", result["error"]?.jsonPrimitive?.content)
      assertNull(service.read(student).getOrThrow()[SharingPermission.INTENDED_SUBJECTS], "the student is still never asked")
    }

  @Test
  fun `an unknown permission, a missing field and a surplus field are refused`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("usp-bad")

      assertEquals(
        "unknown [permission]: [location]",
        tool.execute(student, input("""{"permission": "location", "status": "granted"}"""))["error"]?.jsonPrimitive?.content,
      )
      assertEquals(
        "[status] is required",
        tool.execute(student, input("""{"permission": "intended_subjects"}"""))["error"]?.jsonPrimitive?.content,
      )
      assertEquals(
        "unknown field(s): [student_id]",
        tool
          .execute(student, input("""{"permission": "intended_subjects", "status": "granted", "student_id": "x"}"""))["error"]
          ?.jsonPrimitive
          ?.content,
      )
      assertNull(service.read(student).getOrThrow()[SharingPermission.INTENDED_SUBJECTS], "no refused call wrote anything")
    }

  @Test
  fun `the schema offers exactly the stored vocabularies and nothing else`() {
    val schema = tool.definition.getValue("input_schema").jsonObject
    val properties = schema.getValue("properties").jsonObject

    assertEquals(
      SharingPermission.entries.map { it.value },
      properties
        .getValue("permission")
        .jsonObject
        .getValue("enum")
        .jsonArray
        .map { it.jsonPrimitive.content },
    )
    assertEquals(
      listOf("granted", "refused"),
      properties
        .getValue("status")
        .jsonObject
        .getValue("enum")
        .jsonArray
        .map { it.jsonPrimitive.content },
      "never asked is the absence of an answer, so the model is never offered it",
    )
    assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
  }

  /**
   * The description is what the model reads before it calls: the consent words
   * ride in it, so a yes given in chat with no share result in hand is still
   * preceded by them, and every wire word it teaches is one the schema accepts.
   */
  @Test
  fun `the description carries the consent words and teaches only wire words the schema accepts`() {
    val description =
      tool.definition
        .getValue("description")
        .jsonPrimitive.content

    assertTrue(description.contains("\"${SharingPermissionCopy.CONSENT_WORDS}\""), "the consent words, whole: [$description]")
    (SharingPermission.entries.map { it.value } + SharingPermissionStatus.entries.map { it.value }).forEach { word ->
      assertTrue(description.contains("\"$word\""), "[$word] is taught by the description: [$description]")
    }
  }
}
