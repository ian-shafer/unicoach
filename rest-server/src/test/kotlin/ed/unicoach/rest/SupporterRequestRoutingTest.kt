package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.models.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The student's two request doors and the two reads that carry requests (RFC
 * 254), on the [SupporterRoutingTest] shape: a real embedded server and client.
 *
 * A request is made by `POST /report/connect` on public-web, which this server
 * does not serve, so the suite writes the pending row by hand on a real share —
 * the one state no door here can set.
 */
class SupporterRequestRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0
    private lateinit var dbConnection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      testServer = startServer(wait = false, port = 0)
      boundPort =
        runBlocking {
          testServer.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)

      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      dbConnection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) testServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::dbConnection.isInitialized && !dbConnection.isClosed) dbConnection.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private class Caller(
    val cookie: String,
    val email: String,
  )

  private suspend fun register(
    name: String,
    verified: Boolean = true,
  ): Caller {
    val email = "request-${UUID.randomUUID()}@company.com"
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", name)))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    if (verified) {
      dbConnection
        .prepareStatement("UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ?")
        .use { stmt ->
          stmt.setString(1, email)
          stmt.executeUpdate()
        }
    }
    val cookie =
      response.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    return Caller(cookie, email)
  }

  /** A verified student, and their student id. */
  private suspend fun student(name: String): Pair<Caller, UUID> {
    val caller = register(name)
    val created =
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, caller.cookie)
        setBody(mapper.writeValueAsString(createStudentBody()))
      }
    assertEquals(HttpStatusCode.Created, created.status)
    return caller to
      UUID.fromString(
        mapper
          .readTree(created.bodyAsText())
          .get("student")
          .get("id")
          .asText(),
      )
  }

  /**
   * [parentName]'s pending request to connect with [studentId], made on the
   * student's live share. The parent's supporter row is made through its own
   * door while they are verified; [verified] false then un-verifies them.
   */
  private suspend fun pendingRequest(
    parentName: String,
    studentId: UUID,
    verified: Boolean = true,
  ): Pair<Caller, UUID> {
    val parent = register(parentName)
    val minted = client.post(buildUrl("/api/v1/supporters")) { header(HttpHeaders.Cookie, parent.cookie) }
    assertEquals(HttpStatusCode.Created, minted.status)
    val supporterId =
      UUID.fromString(
        mapper
          .readTree(minted.bodyAsText())
          .get("supporter")
          .get("id")
          .asText(),
      )
    if (!verified) {
      sql("UPDATE users SET version = version + 1, email_verified_at = NULL WHERE email = ?", parent.email)
    }
    val shareId =
      single("SELECT id FROM cost_report_shares WHERE student_id = ? AND revoked_at IS NULL", studentId)
        ?: single(
          "INSERT INTO cost_report_shares (student_id, token_hash) VALUES (?, ?) RETURNING id",
          studentId,
          UUID.randomUUID().toString().toByteArray(),
        )
    val requestId =
      single(
        "INSERT INTO supporter_link_requests (supporter_id, student_id, cost_report_share_id) VALUES (?, ?, ?) RETURNING id",
        supporterId,
        studentId,
        shareId!!,
      )
    return parent to requestId!!
  }

  private fun sql(
    statement: String,
    vararg args: Any,
  ) = dbConnection.prepareStatement(statement).use { stmt ->
    args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
    stmt.executeUpdate()
  }

  private fun single(
    statement: String,
    vararg args: Any,
  ): UUID? =
    dbConnection.prepareStatement(statement).use { stmt ->
      args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
      stmt.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) as UUID else null }
    }

  private suspend fun get(
    path: String,
    cookie: String?,
  ): HttpResponse = client.get(buildUrl(path)) { cookie?.let { header(HttpHeaders.Cookie, it) } }

  private suspend fun post(
    path: String,
    cookie: String?,
  ): HttpResponse = client.post(buildUrl(path)) { cookie?.let { header(HttpHeaders.Cookie, it) } }

  private fun approvePath(id: Any) = "/api/v1/students/me/supporter-requests/$id/approve"

  private fun declinePath(id: Any) = "/api/v1/students/me/supporter-requests/$id/decline"

  private suspend fun codeOf(response: HttpResponse): String = mapper.readTree(response.bodyAsText()).get("code").asText()

  private suspend fun requestsOf(cookie: String) =
    mapper.readTree(get("/api/v1/students/me/supporters", cookie).bodyAsText()).get("requests")

  @Test
  fun `the student's list carries each verified claimant's request, and an unverified one's not at all`(): Unit =
    runBlocking {
      val (ava, avaId) = student("Ava Okafor")
      val (jane, requestId) = pendingRequest("Jane Okafor", avaId)
      pendingRequest("Unverified Stranger", avaId, verified = false)

      val requests = requestsOf(ava.cookie)

      assertEquals(1, requests.size())
      val only = requests.single()
      assertEquals(
        setOf("requestId", "name", "email", "requestedAt"),
        only.fieldNames().asSequence().toSet(),
        "exactly the four published fields",
      )
      assertEquals(requestId.toString(), only.get("requestId").asText())
      assertEquals("Jane Okafor", only.get("name").asText())
      assertEquals(jane.email, only.get("email").asText())
      Instant.parse(only.get("requestedAt").asText())
    }

  @Test
  fun `approve connects the parent and answers the connected adult, once`() =
    runBlocking {
      val (ava, avaId) = student("Ava Okafor")
      val (jane, requestId) = pendingRequest("Jane Okafor", avaId)
      assertEquals(1, mapper.readTree(get("/api/v1/supporters/me", jane.cookie).bodyAsText()).get("pendingRequests").asInt())

      val approved = post(approvePath(requestId), ava.cookie)

      assertEquals(HttpStatusCode.OK, approved.status)
      val body = mapper.readTree(approved.bodyAsText())
      assertEquals(setOf("linkId", "name"), body.fieldNames().asSequence().toSet(), "the PublicLinkedSupporter shape")
      assertEquals("Jane Okafor", body.get("name").asText())
      val list = mapper.readTree(get("/api/v1/students/me/supporters", ava.cookie).bodyAsText())
      assertEquals(listOf(body.get("linkId").asText()), list.get("supporters").map { it.get("linkId").asText() })
      assertEquals(0, list.get("requests").size())
      val home = mapper.readTree(get("/api/v1/supporters/me", jane.cookie).bodyAsText())
      assertEquals(0, home.get("pendingRequests").asInt())
      assertEquals(listOf("Ava Okafor"), home.get("students").map { it.get("name").asText() })

      val again = post(approvePath(requestId), ava.cookie)
      assertEquals(HttpStatusCode.NotFound, again.status)
      assertEquals("supporter_request_not_found", codeOf(again))
    }

  @Test
  fun `decline answers 204, connects nobody, and a second decline is not found`() =
    runBlocking {
      val (ava, avaId) = student("Ava Okafor")
      val (_, requestId) = pendingRequest("Jane Okafor", avaId)

      assertEquals(HttpStatusCode.NoContent, post(declinePath(requestId), ava.cookie).status)
      val list = mapper.readTree(get("/api/v1/students/me/supporters", ava.cookie).bodyAsText())
      assertEquals(0, list.get("supporters").size())
      assertEquals(0, list.get("requests").size())

      val again = post(declinePath(requestId), ava.cookie)
      assertEquals(HttpStatusCode.NotFound, again.status)
      assertEquals("supporter_request_not_found", codeOf(again))
    }

  /** Not yours, never existed and from an unverified or closed claimant are ONE answer, so the doors cannot probe ids. */
  @Test
  fun `every request the student cannot act on is the same 404`() =
    runBlocking {
      val (ava, avaId) = student("Ava Okafor")
      val (_, noorId) = student("Noor Haddad")
      val (_, noorsRequest) = pendingRequest("Noor's Parent", noorId)
      val (_, unverifiedRequest) = pendingRequest("Unverified Stranger", avaId, verified = false)
      val (closed, closedRequest) = pendingRequest("Closed Parent", avaId)
      sql("UPDATE users SET version = version + 1, deleted_at = NOW() WHERE email = ?", closed.email)

      for (id in listOf(noorsRequest, UUID.randomUUID(), unverifiedRequest, closedRequest)) {
        for (path in listOf(approvePath(id), declinePath(id))) {
          val response = post(path, ava.cookie)
          assertEquals(HttpStatusCode.NotFound, response.status, "[$path]")
          assertEquals("supporter_request_not_found", codeOf(response), "[$path]")
        }
      }
    }

  @Test
  fun `a malformed id is 400, a caller with no student profile is 409, and no session is 401`() =
    runBlocking {
      val (ava, _) = student("Ava Okafor")
      val parent = register("Jane Okafor")

      for (path in listOf(approvePath("not-a-uuid"), declinePath("1-2-3-4-5"))) {
        val malformed = post(path, ava.cookie)
        assertEquals(HttpStatusCode.BadRequest, malformed.status, "[$path]")
        assertEquals("validation_failed", codeOf(malformed))
      }
      val noStudent = post(approvePath(UUID.randomUUID()), parent.cookie)
      assertEquals(HttpStatusCode.Conflict, noStudent.status)
      assertEquals("student_profile_required", codeOf(noStudent))
      assertEquals(HttpStatusCode.Unauthorized, post(declinePath(UUID.randomUUID()), null).status)
      assertEquals(HttpStatusCode.Unauthorized, post(approvePath(UUID.randomUUID()), null).status)
    }
}
