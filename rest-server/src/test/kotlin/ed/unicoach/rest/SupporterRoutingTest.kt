package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.family.SupporterCeiling
import ed.unicoach.rest.models.AcceptSupporterInviteRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.CreateSupporterInviteRequest
import ed.unicoach.rest.models.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
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
 * `GET /api/v1/supporters/me` — and both ends of a link (RFC 249): the
 * student's list and disconnect, and the supporter's disconnect — on the [SupporterInviteRoutingTest] shape — a
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
  private suspend fun registerVerified(
    name: String,
    email: String = uniqueEmail("parent"),
  ): String {
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

  private suspend fun delete(
    path: String,
    cookie: String?,
  ): HttpResponse =
    client.delete(buildUrl(path)) {
      if (cookie != null) header(HttpHeaders.Cookie, cookie)
    }

  private suspend fun getStudentSupporters(cookie: String?): HttpResponse =
    client.get(buildUrl("/api/v1/students/me/supporters")) {
      if (cookie != null) header(HttpHeaders.Cookie, cookie)
    }

  private fun studentRoute(linkId: String) = "/api/v1/students/me/supporters/$linkId"

  private fun supporterRoute(linkId: String) = "/api/v1/supporters/me/students/$linkId"

  private suspend fun codeOf(response: HttpResponse): String = mapper.readTree(response.bodyAsText()).get("code").asText()

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

      assertEquals(HttpStatusCode.NoContent, delete(supporterRoute(severedLink), parent).status)

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
      // fields and no other, so a leak added later fails here whatever it
      // is called. A bare-year needle was tried and removed — a four-digit run
      // inside one of the body's three UUIDs matched it about once in 1,300
      // runs, failing a green tree for a reason that was not a leak.
      assertEquals(
        listOf(listOf("linkId", "studentId", "name", "relationship", "otherSupporterNames")),
        mapper.readTree(body).get("students").map { it.fieldNames().asSequence().toList() },
        "a roster entry carries these fields and no other: [$body]",
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

  // --- Both ends of a link (RFC 249) ----------------------------------------

  /** A link id no link has: a well-formed UUID, so it reaches the service. */
  private val absentLinkId =
    java.util.UUID
      .randomUUID()
      .toString()

  @Test
  fun `the link doors are 401 without a session`() =
    runBlocking {
      assertEquals(HttpStatusCode.Unauthorized, getStudentSupporters(null).status)
      assertEquals(HttpStatusCode.Unauthorized, delete(studentRoute(absentLinkId), null).status)
      assertEquals(HttpStatusCode.Unauthorized, delete(supporterRoute(absentLinkId), null).status)
    }

  @Test
  fun `the link doors are 403 email_not_verified for an unverified caller`() =
    runBlocking {
      val cookie = register(uniqueEmail("unverified"), "Unverified Caller")

      for (response in listOf(
        getStudentSupporters(cookie),
        delete(studentRoute(absentLinkId), cookie),
        delete(supporterRoute(absentLinkId), cookie),
      )) {
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("email_not_verified", codeOf(response))
      }
    }

  /** The student doors keep `CallerResolution`'s 409; the supporter door never sends it. */
  @Test
  fun `the student doors are 409 for a caller with no student profile, and the supporter door is 404`() =
    runBlocking {
      val cookie = registerVerified("Parent Jane")

      for (response in listOf(getStudentSupporters(cookie), delete(studentRoute(absentLinkId), cookie))) {
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("student_profile_required", codeOf(response))
      }
      val supporterSide = delete(supporterRoute(absentLinkId), cookie)
      assertEquals(HttpStatusCode.NotFound, supporterSide.status, "a caller with no supporter row holds no link")
      assertEquals("supporter_link_not_found", codeOf(supporterSide))
    }

  @Test
  fun `a malformed link id is 400 on both disconnect doors`() =
    runBlocking {
      val student = registerStudent("Ava Okafor")
      val parent = registerVerified("Parent Jane")

      // `1-2-3-4-5` is one `UUID.fromString` accepts: a short-group id is not canonical, so it is not an id.
      for (malformed in listOf("not-a-uuid", "1-2-3-4-5")) {
        for (response in listOf(delete(studentRoute(malformed), student), delete(supporterRoute(malformed), parent))) {
          assertEquals(HttpStatusCode.BadRequest, response.status, "[$malformed]")
          assertEquals("validation_failed", codeOf(response))
        }
      }
    }

  /**
   * The link id comes from the path only: a query parameter of the same name
   * must not redirect a DELETE to a link the path never named.
   */
  @Test
  fun `a linkId query parameter cannot redirect a disconnect`() =
    runBlocking {
      val student = registerStudent("Ava Okafor")
      val jane = registerVerified("Jane Okafor")
      val sam = registerVerified("Sam Okafor")
      val janeLink = link(student, jane)
      val samLink = link(student, sam)

      assertEquals(HttpStatusCode.NoContent, delete("${studentRoute(janeLink)}?linkId=$samLink", student).status)

      assertEquals(
        listOf("Sam Okafor"),
        mapper.readTree(getStudentSupporters(student).bodyAsText()).get("supporters").map { it.get("name").asText() },
        "the path named Jane's link, so Jane's link is the one that ended",
      )
    }

  /**
   * The student's list: each adult by name, the ceiling once, the cost-report
   * flag — and never an adult's email, asserted on the body's VALUES as well as
   * on the entry's field set, because a leak renamed is still a leak.
   */
  @Test
  fun `the student's list names each adult, states the ceiling once and carries no email`() =
    runBlocking {
      val student = registerStudent("Ava Okafor")
      val janeEmail = uniqueEmail("jane")
      val jane = registerVerified("Jane Okafor", janeEmail)
      val samEmail = uniqueEmail("sam")
      val sam = registerVerified("Sam Okafor", samEmail)
      val janeLink = link(student, jane)
      val samLink = link(student, sam)

      val response = getStudentSupporters(student)
      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      val json = mapper.readTree(body)

      assertEquals(
        listOf(janeLink to "Jane Okafor", samLink to "Sam Okafor"),
        json.get("supporters").map { it.get("linkId").asText() to it.get("name").asText() },
      )
      assertEquals(
        listOf(listOf("linkId", "name"), listOf("linkId", "name")),
        json.get("supporters").map { it.fieldNames().asSequence().toList() },
        "an adult on the student's list is a link and a name, and nothing else: [$body]",
      )
      assertEquals(
        SupporterCeiling.toStudent.canSee,
        json.get("ceiling").get("canSee").map { it.asText() },
      )
      assertEquals(
        SupporterCeiling.toStudent.staysPrivate,
        json.get("ceiling").get("staysPrivate").map { it.asText() },
      )
      assertFalse(json.get("costReportLinkLive").asBoolean(), "nothing shared, so no cost-report sentence")
      for (email in listOf(janeEmail, samEmail)) {
        assertFalse(body.contains(email, ignoreCase = true), "the list must never carry an email: [$body]")
      }
    }

  /**
   * DELETE 204 then 404 from the student's side; another student's link is the
   * same 404 and stays live.
   */
  @Test
  fun `a student disconnects their own adult once, and cannot reach another student's link`() =
    runBlocking {
      val student = registerStudent("Ava Okafor")
      val otherStudent = registerStudent("Noor Haddad")
      val parent = registerVerified("Parent Jane")
      val mine = link(student, parent)
      val theirs = link(otherStudent, parent)

      val foreign = delete(studentRoute(theirs), student)
      assertEquals(HttpStatusCode.NotFound, foreign.status)
      assertEquals("supporter_link_not_found", codeOf(foreign))

      assertEquals(HttpStatusCode.NoContent, delete(studentRoute(mine), student).status)
      val again = delete(studentRoute(mine), student)
      assertEquals(HttpStatusCode.NotFound, again.status, "a second disconnect has nothing left to end")
      assertEquals("supporter_link_not_found", codeOf(again))

      assertEquals(
        listOf("Noor Haddad"),
        studentNamesOf(getSupporterHome(parent).bodyAsText()),
        "the student's disconnect ended their link only",
      )
    }

  /**
   * Each end has its own door, and the side is the authorisation: a supporter
   * cannot use the student's door on their own link, and a student cannot use
   * the supporter's door on theirs.
   */
  @Test
  fun `each end can only use its own door`() =
    runBlocking {
      val student = registerStudent("Ava Okafor")
      val parent = registerVerified("Parent Jane")
      val linkId = link(student, parent)

      assertEquals(HttpStatusCode.Conflict, delete(studentRoute(linkId), parent).status, "a supporter has no student door")
      assertEquals(HttpStatusCode.NotFound, delete(supporterRoute(linkId), student).status, "a student holds no supporter link")
      assertEquals(listOf("Ava Okafor"), studentNamesOf(getSupporterHome(parent).bodyAsText()), "neither severed the link")

      assertEquals(HttpStatusCode.NoContent, delete(supporterRoute(linkId), parent).status)
      assertEquals(HttpStatusCode.NotFound, delete(supporterRoute(linkId), parent).status)
      assertEquals(
        emptyList(),
        mapper.readTree(getStudentSupporters(student).bodyAsText()).get("supporters").toList(),
        "the student's list no longer names the adult who left",
      )
    }

  /**
   * The one case where the side predicate alone guards a link at this door: a
   * caller with a student profile passes the student door's 409 check, so only
   * `student_id = ?` stops them ending a link they hold as a supporter.
   */
  @Test
  fun `a caller who is both a student and a supporter cannot end their supporter link through the student door`() =
    runBlocking {
      val supported = registerStudent("Ava Okafor")
      val dual = registerStudent("Noor Haddad")
      val linkId = link(supported, dual)

      val response = delete(studentRoute(linkId), dual)
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("supporter_link_not_found", codeOf(response))
      assertEquals(listOf("Ava Okafor"), studentNamesOf(getSupporterHome(dual).bodyAsText()), "the link is still live")
    }

  @Test
  fun `the roster names the other adults on each student and never the caller`() =
    runBlocking {
      val student = registerStudent("Ava Okafor")
      val jane = registerVerified("Jane Okafor")
      val sam = registerVerified("Sam Okafor")
      link(student, jane)
      link(student, sam)

      val body = getSupporterHome(jane).bodyAsText()

      assertEquals(
        listOf(listOf("Sam Okafor")),
        mapper.readTree(body).get("students").map { entry -> entry.get("otherSupporterNames").map { it.asText() } },
        "Jane reads Sam, and not herself: [$body]",
      )
    }
}
