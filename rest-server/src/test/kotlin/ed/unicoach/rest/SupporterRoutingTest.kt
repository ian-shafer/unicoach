package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.rest.models.AcceptSupporterInviteRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.CreateSupporterInviteRequest
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
import io.ktor.http.Url
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two supporter doors (RFC 241): `POST /api/v1/supporters` and
 * `GET /api/v1/supporters/me`, on the [SupporterInviteRoutingTest] shape — a
 * real embedded server, a real HTTP client, and one raw JDBC connection for the
 * state no door can set.
 *
 * Links are made through the INVITE doors rather than by hand, because that is
 * the only way a link is minted in production and a roster assertion built on a
 * hand-written row would not prove the two halves meet.
 */
class SupporterRoutingTest {
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

  private fun uniqueEmail(prefix: String) = "$prefix${java.util.UUID.randomUUID()}@company.com"

  private fun markEmailVerified(email: String) {
    dbConnection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  /** Registers a fresh user and returns the session cookie, leaving the address UNVERIFIED. */
  private suspend fun register(
    email: String,
    name: String,
  ): String {
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", name)))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.headers[HttpHeaders.SetCookie]!!
      .split(";")
      .first()
      .trim()
  }

  /** A verified user with no student profile and no supporter row — a parent-first signup. */
  private suspend fun registerVerified(name: String): String {
    val email = uniqueEmail("parent")
    val cookie = register(email, name)
    markEmailVerified(email)
    return cookie
  }

  /** A verified user WITH a student profile. */
  private suspend fun registerStudent(name: String): String {
    val email = uniqueEmail("student")
    val cookie = register(email, name)
    markEmailVerified(email)
    val response =
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(createStudentBody()))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    return cookie
  }

  private suspend fun createSupporter(cookie: String): HttpResponse =
    client.post(buildUrl("/api/v1/supporters")) {
      header(HttpHeaders.Cookie, cookie)
    }

  private suspend fun getSupporterHome(cookie: String): HttpResponse =
    client.get(buildUrl("/api/v1/supporters/me")) {
      header(HttpHeaders.Cookie, cookie)
    }

  /**
   * Links [parentCookie]'s account to [studentCookie]'s student through the
   * invite doors, and returns the new link's id.
   */
  private suspend fun link(
    studentCookie: String,
    parentCookie: String,
  ): String {
    val mint =
      client.post(buildUrl("/api/v1/supporter-invites")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, studentCookie)
        setBody(mapper.writeValueAsString(CreateSupporterInviteRequest(relationship = "parent")))
      }
    assertEquals(HttpStatusCode.Created, mint.status)
    val token =
      assertNotNull(
        Url(
          mapper
            .readTree(mint.bodyAsText())
            .get("inviteUrl")
            .asText(),
        ).parameters["token"],
      )

    val accept =
      client.post(buildUrl("/api/v1/supporter-invites/accept")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, parentCookie)
        setBody(mapper.writeValueAsString(AcceptSupporterInviteRequest(token)))
      }
    assertEquals(HttpStatusCode.OK, accept.status)
    return mapper
      .readTree(accept.bodyAsText())
      .get("linkId")
      .asText()
  }

  /** Severs a link the way `family/04` eventually will, which no door does yet. */
  private fun unlink(linkId: String) {
    dbConnection
      .prepareStatement(
        "UPDATE supporter_student_links SET deleted_at = NOW(), version = version + 1 WHERE id = ?::uuid",
      ).use { stmt ->
        stmt.setString(1, linkId)
        stmt.executeUpdate()
      }
  }

  /**
   * CLOSES a supporter row the way `family/09` eventually will, which no door
   * does yet. `supporters_user_id_unique_idx` is TOTAL, so the closed row keeps
   * its `user_id` and no live one can replace it.
   */
  private fun closeSupporter(supporterId: String) {
    dbConnection
      .prepareStatement("UPDATE supporters SET deleted_at = NOW(), version = version + 1 WHERE id = ?::uuid")
      .use { stmt ->
        stmt.setString(1, supporterId)
        stmt.executeUpdate()
      }
  }

  /** How many supporter rows this one account holds — the idempotence claim, per user. */
  private fun supporterRowsFor(supporterId: String): Int {
    dbConnection
      .prepareStatement("SELECT count(*) FROM supporters WHERE user_id = (SELECT user_id FROM supporters WHERE id = ?::uuid)")
      .use { stmt ->
        stmt.setString(1, supporterId)
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getInt(1)
        }
      }
  }

  private fun supporterIdOf(body: String): String =
    mapper
      .readTree(body)
      .get("supporter")
      .get("id")
      .asText()

  private fun studentNamesOf(body: String): List<String> =
    mapper
      .readTree(body)
      .get("students")
      .map { it.get("name").asText() }

  @Test
  fun `both doors are 401 without a session`() =
    runBlocking {
      assertEquals(HttpStatusCode.Unauthorized, client.post(buildUrl("/api/v1/supporters")).status)
      assertEquals(HttpStatusCode.Unauthorized, client.get(buildUrl("/api/v1/supporters/me")).status)
    }

  /**
   * Neither door is exempt from `EmailVerificationGate`. RFC 232 exempted the
   * ACCEPT path alone (correction X8); a parent signing themselves up is not
   * following a stranger's link, so they verify like everybody else.
   */
  @Test
  fun `both doors are 403 email_not_verified for an unverified caller`() =
    runBlocking {
      val cookie = register(uniqueEmail("unverified"), "Unverified Parent")

      val created = createSupporter(cookie)
      assertEquals(HttpStatusCode.Forbidden, created.status)
      assertEquals("email_not_verified", mapper.readTree(created.bodyAsText()).get("code").asText())

      val home = getSupporterHome(cookie)
      assertEquals(HttpStatusCode.Forbidden, home.status)
      assertEquals("email_not_verified", mapper.readTree(home.bodyAsText()).get("code").asText())
    }

  @Test
  fun `POST is 201 then 200, with one row and the same body`() =
    runBlocking {
      val cookie = registerVerified("Parent Jane")

      val first = createSupporter(cookie)
      assertEquals(HttpStatusCode.Created, first.status, "the first call minted the row")
      val firstBody = first.bodyAsText()

      val second = createSupporter(cookie)
      assertEquals(HttpStatusCode.OK, second.status, "the second call must not claim a create")
      val secondBody = second.bodyAsText()

      assertEquals(firstBody, secondBody, "the same body either way, byte for byte")
      assertEquals(1, supporterRowsFor(supporterIdOf(firstBody)), "an idempotent door writes one row")
      assertEquals(emptyList(), studentNamesOf(firstBody), "a new supporter has linked nobody")
    }

  @Test
  fun `GET is 404 supporter_not_found before the POST and 200 after it`() =
    runBlocking {
      val cookie = registerVerified("Parent Jane")

      val before = getSupporterHome(cookie)
      assertEquals(HttpStatusCode.NotFound, before.status)
      assertEquals(
        "supporter_not_found",
        mapper.readTree(before.bodyAsText()).get("code").asText(),
        "the mirror of GET /api/v1/students/me's student_not_found",
      )

      val created = createSupporter(cookie)
      assertEquals(HttpStatusCode.Created, created.status)

      val after = getSupporterHome(cookie)
      assertEquals(HttpStatusCode.OK, after.status)
      assertEquals(supporterIdOf(created.bodyAsText()), supporterIdOf(after.bodyAsText()))
    }

  /**
   * The roster's two filters at once: a severed link is gone, and another
   * supporter's link was never there.
   */
  @Test
  fun `the roster carries only this supporter's active links`() =
    runBlocking {
      val parent = registerVerified("Parent Jane")
      val keptStudent = registerStudent("Ava Okafor")
      val severedStudent = registerStudent("Noor Haddad")
      link(keptStudent, parent)
      val severedLink = link(severedStudent, parent)

      val otherParent = registerVerified("Other Parent")
      val otherStudent = registerStudent("Someone Elses Child")
      link(otherStudent, otherParent)

      assertEquals(
        listOf("Ava Okafor", "Noor Haddad"),
        studentNamesOf(getSupporterHome(parent).bodyAsText()),
        "both links are on the roster, oldest first",
      )

      unlink(severedLink)

      val body = getSupporterHome(parent).bodyAsText()
      assertEquals(listOf("Ava Okafor"), studentNamesOf(body), "a severed link leaves the roster")
      assertFalse(
        body.contains("Someone Elses Child"),
        "another supporter's student is never on this roster: [$body]",
      )
    }

  /**
   * The `family/05/the-subject-seam` boundary, asserted rather than assumed: a
   * supporter reading their own home learns their student's NAME and nothing
   * else about them. Both the field names and the fixture VALUES are checked,
   * because a leak renamed is still a leak.
   */
  @Test
  fun `the home carries no date of birth and no graduation date`() =
    runBlocking {
      val parent = registerVerified("Parent Jane")
      val student = registerStudent("Ava Okafor")
      link(student, parent)

      val body = getSupporterHome(parent).bodyAsText()

      assertTrue(body.contains("Ava Okafor"), "the roster does carry the student's name: [$body]")

      // The SHAPE, which subsumes any needle list: a roster entry carries these
      // four fields and no fifth, so a leak added later fails here whatever it
      // is called. A bare-year needle was tried and removed — a four-digit run
      // inside one of the body's three UUIDs matched it about once in 1,300
      // runs, failing a green tree for a reason that was not a leak.
      assertEquals(
        listOf(listOf("linkId", "studentId", "name", "relationship")),
        mapper.readTree(body).get("students").map { it.fieldNames().asSequence().toList() },
        "a roster entry carries these four fields and no fifth: [$body]",
      )

      // And the values as well as the names, because a leak renamed is still a
      // leak. Each needle here is specific enough that no UUID can spell it.
      for (
      forbidden in
      listOf(
        "dateOfBirth",
        "date_of_birth",
        "graduation",
        StudentRowFixture.DEFAULT_DATE_OF_BIRTH.toString(),
      )
      ) {
        assertFalse(
          body.contains(forbidden, ignoreCase = true),
          "the supporter home must not carry [$forbidden]: [$body]",
        )
      }
    }

  /**
   * The mint's one refusal, on the wire. A CLOSED row is a predictable state of
   * the account, so it arrives as `409 supporter_closed` — not as the
   * one-per-user index's own "already exists" violation, which would have
   * reached the client as `400 permanent_error` and told them a row exists that
   * does not.
   */
  @Test
  fun `POST is 409 supporter_closed for a caller whose row was closed`() =
    runBlocking {
      val cookie = registerVerified("Closed Parent")
      val created = createSupporter(cookie)
      assertEquals(HttpStatusCode.Created, created.status)
      closeSupporter(supporterIdOf(created.bodyAsText()))

      val again = createSupporter(cookie)

      assertEquals(HttpStatusCode.Conflict, again.status, "a closed row is refused, and refused on its own terms")
      assertEquals("supporter_closed", mapper.readTree(again.bodyAsText()).get("code").asText())
      assertEquals(HttpStatusCode.NotFound, getSupporterHome(cookie).status, "and there is still no home to read")
    }

  /**
   * A caller who is already a student is not refused. A `supporters` row is a
   * home surface, not a claim about what somebody is not, and the database's own
   * self-link guard already refuses the one dangerous case.
   */
  @Test
  fun `a caller who also holds a student profile may become a supporter`() =
    runBlocking {
      val cookie = registerStudent("Student And Parent")

      val created = createSupporter(cookie)

      assertEquals(HttpStatusCode.Created, created.status, "a student is not refused a supporter row")
      assertEquals(HttpStatusCode.OK, getSupporterHome(cookie).status)
      assertEquals(HttpStatusCode.OK, client.get(buildUrl("/api/v1/students/me")) { header(HttpHeaders.Cookie, cookie) }.status)
    }
}
