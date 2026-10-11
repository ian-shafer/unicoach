package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.rest.routing.STUDENT_ID_PARAM
import ed.unicoach.rest.routing.SUBJECT_COLLEGE_LIST_PATH
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `GET /api/v1/students/{studentId}/college-list` (RFC 250): a linked adult's
 * read of a student's list, over a real server, a real client and a real
 * database.
 *
 * The student's own `GET /api/v1/students/me/college-list` is held unchanged
 * by `CollegeListRoutingTest`, which this slice does not edit.
 */
class SubjectRoutingTest {
  companion object {
    private lateinit var family: FamilyRoutes

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      family = FamilyRoutes.start()
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::family.isInitialized) family.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun listPath(studentId: String) = SUBJECT_COLLEGE_LIST_PATH.replace("{$STUDENT_ID_PARAM}", studentId)

  private suspend fun readList(
    studentId: String,
    cookie: String?,
  ): HttpResponse = family.get(listPath(studentId), cookie)

  @Test
  fun `a linked adult reads the list as the projection, without the student's reasons`() =
    runBlocking {
      val linked = family.family()
      val collegeId = family.seedCollege("Shared College")
      family.addToList(linked.student, collegeId, "Loved the campus tour")

      val response = readList(linked.studentId, linked.parent.cookie)
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()

      val entry = mapper.readTree(body).get("entries").single()
      assertEquals(
        listOf("collegeId", "collegeName", "status", "livingPlan", "updatedAt"),
        entry.fieldNames().asSequence().toList(),
        "an entry carries these fields and no other: [$body]",
      )
      assertEquals(collegeId.toString(), entry.get("collegeId").asText())
      assertEquals("Shared College", entry.get("collegeName").asText())
      assertEquals("applying", entry.get("status").asText())
      assertEquals("on_campus", entry.get("livingPlan").asText())
      assertFalse(body.contains("Loved the campus tour"), "the student's own words never reach the adult: [$body]")
    }

  /**
   * Every way a read can be refused, each on its own family, answers the SAME
   * bytes: a body that differed by cause would tell a parent why, and whether
   * the student exists.
   *
   * A closed ADULT `users` row is the fifth cause, and it is not here: closing
   * the account ends its sessions, so that caller is a 401 before the seam is
   * asked (see the test below). The DAO suite holds it.
   */
  @Test
  fun `every refusal is one 404 with one body`() =
    runBlocking {
      val refusals = mutableMapOf<String, HttpResponse>()

      val neverLinked = family.family()
      val anotherFamily = family.family()
      refusals["never linked"] = readList(neverLinked.studentId, anotherFamily.parent.cookie)

      val severed = family.family()
      family.sever(severed)
      refusals["severed link"] = readList(severed.studentId, severed.parent.cookie)

      val closedSupporter = family.family()
      family.closeSupporterRow(closedSupporter.parent)
      refusals["closed supporter row"] = readList(closedSupporter.studentId, closedSupporter.parent.cookie)

      val closedStudent = family.family()
      family.closeStudentRow(closedStudent.student)
      refusals["closed student row"] = readList(closedStudent.studentId, closedStudent.parent.cookie)

      val closedStudentUser = family.family()
      family.closeUser(closedStudentUser.student)
      refusals["closed student user"] = readList(closedStudentUser.studentId, closedStudentUser.parent.cookie)

      refusals["never a student"] = readList(UUID.randomUUID().toString(), anotherFamily.parent.cookie)

      val bodies = refusals.mapValues { (_, response) -> response.bodyAsText() }
      for ((cause, response) in refusals) {
        assertEquals(HttpStatusCode.NotFound, response.status, "[$cause] must be a 404: [${bodies[cause]}]")
      }
      assertEquals(
        "student_not_found",
        mapper.readTree(bodies.getValue("never a student")).get("code").asText(),
      )
      assertEquals(
        setOf(bodies.getValue("never a student")),
        bodies.values.toSet(),
        "every refusal is byte-identical: $bodies",
      )
    }

  @Test
  fun `a closed adult account is 401, because its session ended with it`() =
    runBlocking {
      val linked = family.family()
      family.closeUser(linked.parent)

      assertEquals(HttpStatusCode.Unauthorized, readList(linked.studentId, linked.parent.cookie).status)
    }

  /** A self-link is refused by the database, so a student's own id answers what any unlinked id does. */
  @Test
  fun `a student reading their own id through the seam is refused, and keeps using me`() =
    runBlocking {
      val linked = family.family()

      val own = readList(linked.studentId, linked.student.cookie)
      assertEquals(HttpStatusCode.NotFound, own.status)
      assertEquals(HttpStatusCode.OK, family.get("/api/v1/students/me/college-list", linked.student.cookie).status)
    }

  @Test
  fun `a malformed id is 400 and no session is 401`() =
    runBlocking {
      val linked = family.family()

      for (malformed in listOf("not-a-uuid", "1-2-3-4-5")) {
        val response = readList(malformed, linked.parent.cookie)
        assertEquals(HttpStatusCode.BadRequest, response.status, "[$malformed]")
        assertEquals("validation_failed", mapper.readTree(response.bodyAsText()).get("code").asText())
      }
      assertEquals(HttpStatusCode.Unauthorized, readList(linked.studentId, null).status)
    }

  /** In `call.parameters` a query parameter of the same name wins over the path; the route reads the path only. */
  @Test
  fun `a studentId query cannot redirect the path`() =
    runBlocking {
      val linked = family.family()
      val other = UUID.randomUUID().toString()

      assertEquals(
        HttpStatusCode.NotFound,
        family.get("${listPath(other)}?$STUDENT_ID_PARAM=${linked.studentId}", linked.parent.cookie).status,
        "the query names the linked student, the path does not",
      )
      assertEquals(
        HttpStatusCode.OK,
        family.get("${listPath(linked.studentId)}?$STUDENT_ID_PARAM=$other", linked.parent.cookie).status,
      )
    }
}
