package ed.unicoach.family

import ed.unicoach.chat.ChatTool
import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.aid.FafsaProcessChatTool
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.FindCollegeTool
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The supporter coach's registry (RFC 258 §5): read-only by construction, and
 * exactly the four tools the RFC names.
 */
class SupporterToolRegistryTest {
  /** A student-scoped tool that would write: the kind the registry must refuse. */
  private class WritingTool : StudentScopedChatTool() {
    override val name: String = "update_something_of_the_students"

    override val definition: JsonObject = buildJsonObject {}

    override suspend fun execute(
      studentId: StudentId,
      input: JsonObject,
    ): JsonObject = buildJsonObject {}
  }

  /** A plain tool that has not declared itself read-only: outside the allowlist, whatever it does. */
  private class UndeclaredPlainTool : ChatTool {
    override val name: String = "an_undeclared_plain_tool"

    override val definition: JsonObject = buildJsonObject {}

    override suspend fun execute(input: JsonObject): JsonObject = buildJsonObject {}
  }

  @Test
  fun `building with a plain tool not marked ReadOnlyChatTool throws, naming the tool`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        SupporterToolRegistry.of(listOf(FafsaProcessChatTool(), UndeclaredPlainTool()))
      }

    assertTrue(error.message!!.contains("[an_undeclared_plain_tool]"), error.message)
  }

  @Test
  fun `building with a StudentScopedChatTool throws, naming the tool`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        SupporterToolRegistry.of(listOf(FafsaProcessChatTool(), WritingTool()))
      }

    assertTrue(error.message!!.contains("[update_something_of_the_students]"), error.message)
  }

  @Test
  fun `the production registry holds exactly the four parent tools`() {
    val database = CostsTestDb.database
    val subjects =
      SubjectReadService(database, CollegeListService(database), CollegeCostService(database), SharingPermissionService(database))

    val registry =
      SupporterToolRegistry.standard(subjects, FafsaDeadlineService(database), FindCollegeTool(CollegeSearchService(database)))

    assertEquals(
      listOf("family_cost_picture", "fafsa_deadlines", "fafsa_process", "find_college"),
      registry.registry.definitions().map { it["name"]!!.jsonPrimitive.content },
    )
  }
}
