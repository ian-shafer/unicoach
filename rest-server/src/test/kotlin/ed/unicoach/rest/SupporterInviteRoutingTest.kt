package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.rest.models.AcceptSupporterInviteRequest
import ed.unicoach.rest.models.AcceptSupporterInviteStatus
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.CreateSupporterInviteRequest
import ed.unicoach.rest.models.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two supporter-invite REST doors (RFC 232): mint, and accept — including
 * the correction-X8 exemption that lets a recipient accept before verifying
 * their email.
 */
class SupporterInviteRoutingTest {
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
  private suspend fun register(email: String): String {
    val req = RegisterRequest(email, "Password123!", "Invite User")
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.headers[HttpHeaders.SetCookie]!!
      .split(";")
      .first()
      .trim()
  }

  /** Registers a verified student WITH a student profile, and returns the cookie. */
  private suspend fun registerStudent(): Pair<String, String> {
    val email = uniqueEmail("student")
    val cookie = register(email)
    markEmailVerified(email)
    val body = CreateStudentRequest(expectedHighSchoolGraduationDate = "2028", dateOfBirth = "2009-07-10")
    val response =
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(body))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    return email to cookie
  }

  private fun linkCount(): Int = count("SELECT count(*) FROM supporter_student_links")

  private fun inviteCount(): Int = count("SELECT count(*) FROM supporter_invites")

  private fun count(sql: String): Int {
    dbConnection.createStatement().use { stmt ->
      stmt.executeQuery(sql).use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `mint unauthenticated returns 401`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/supporter-invites")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(CreateSupporterInviteRequest()))
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `student mints an invite and an unverified parent accepts it`() =
    runBlocking {
      val (_, studentCookie) = registerStudent()
      val parentEmail = uniqueEmail("parent")
      val parentCookie = register(parentEmail) // NOT verified — the point of the test.
      // A DELTA, not an absolute: this class shares one database with every
      // other suite and resets nothing, so an absolute count is green or red by
      // JUnit's method order rather than by what this test did.
      val linksBefore = linkCount()

      val mint =
        client.post(buildUrl("/api/v1/supporter-invites")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, studentCookie)
          setBody(mapper.writeValueAsString(CreateSupporterInviteRequest(relationship = "parent")))
        }
      assertEquals(HttpStatusCode.Created, mint.status)
      val mintBody = mint.bodyAsText()
      val mintTree = jacksonObjectMapper().readTree(mintBody)
      assertEquals("student", mintTree.get("inviterRole").asText(), "the student is the inviter")
      assertEquals("parent", mintTree.get("relationship").asText())
      // Parsed, never split: `substringAfter("token=")` returns everything after
      // the FIRST match, so one added parameter — or a `token=` anywhere in the
      // host or path — asserts against a token nobody minted.
      val inviteUrl = Url(mintTree.get("inviteUrl").asText())
      assertEquals("/invite", inviteUrl.encodedPath, "mint returns an invite link: [$mintBody]")
      val token = inviteUrl.parameters["token"]
      assertNotNull(token, "mint returned no [token] parameter: [$mintBody]")
      assertTrue(token.isNotBlank())

      val accept =
        client.post(buildUrl("/api/v1/supporter-invites/accept")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, parentCookie)
          setBody(mapper.writeValueAsString(AcceptSupporterInviteRequest(token)))
        }
      assertEquals(HttpStatusCode.OK, accept.status, "accept is exempt from email verification (X8)")
      assertEquals(
        AcceptSupporterInviteStatus.ACCEPTED.value,
        jacksonObjectMapper().readTree(accept.bodyAsText()).get("status").asText(),
      )
      assertEquals(linksBefore + 1, linkCount(), "exactly one link is created")
    }

  /**
   * The relationship NOBODY stated (RFC 240 decision 4). iOS sends no
   * relationship at all, so this is the word almost every real invite carries:
   * it must be the catch-all `supporter` and not `parent`, which claimed a
   * family relationship nobody had named. Asserted on the STORED row as well as
   * on the response, because the response word is what the page renders from.
   */
  @Test
  fun `minting with no relationship field stores supporter`() =
    runBlocking {
      val (_, studentCookie) = registerStudent()

      val mint =
        client.post(buildUrl("/api/v1/supporter-invites")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, studentCookie)
          setBody(mapper.writeValueAsString(CreateSupporterInviteRequest()))
        }

      assertEquals(HttpStatusCode.Created, mint.status)
      val body = mint.bodyAsText()
      val tree = jacksonObjectMapper().readTree(body)
      assertEquals("supporter", tree.get("relationship").asText(), "an unstated relationship mints supporter: [$body]")
      assertEquals(
        "supporter",
        storedRelationship(tree.get("id").asText()),
        "the stored row carries the same word the response named",
      )
    }

  private fun storedRelationship(inviteId: String): String? {
    dbConnection.prepareStatement("SELECT relationship FROM supporter_invites WHERE id = ?").use { stmt ->
      stmt.setObject(1, java.util.UUID.fromString(inviteId))
      stmt.executeQuery().use { rs ->
        return if (rs.next()) rs.getString(1) else null
      }
    }
  }

  /**
   * The word the caller sent is NOT in the vocabulary. This is the arm that was
   * dead code: `relationshipOrDefault` folded "absent" and "unreadable" into one
   * null, so `{"relationship":"stepdad"}` minted a `parent` invite with a 201
   * and nothing here ever executed the 400.
   */
  @Test
  fun `minting with an unreadable relationship is a 400 and writes no invite`() =
    runBlocking {
      val (_, studentCookie) = registerStudent()
      val invitesBefore = inviteCount()

      val mint =
        client.post(buildUrl("/api/v1/supporter-invites")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, studentCookie)
          setBody(mapper.writeValueAsString(CreateSupporterInviteRequest(relationship = "grandparnt")))
        }

      assertEquals(HttpStatusCode.BadRequest, mint.status, "an unreadable relationship word is refused")
      val body = mint.bodyAsText()
      assertTrue(body.contains("grandparnt"), "the refusal names the word it rejected: [$body]")
      // The legal set is read from the enum, never retyped, and compared WHOLE
      // rather than by containment: "parent" is a substring of "grandparent", so
      // a listing that had LOST a word would still satisfy a per-word
      // `contains` and this guard would pass on the one defect it exists to
      // catch. An absent "must be one of: " leaves an empty remainder, which is
      // not the legal set either, so the malformed refusal fails here too.
      val message = mapper.readTree(body).get("message").asText()
      assertEquals(
        SupporterRelationship.entries.map { it.value },
        message.substringAfter("must be one of: ", "").split(", "),
        "the refusal must name the whole legal set, exactly and in order: [$message]",
      )
      assertEquals(invitesBefore, inviteCount(), "a refused mint writes no invite row")
    }

  @Test
  fun `accepting a dead token is a uniform 404`() =
    runBlocking {
      val (_, studentCookie) = registerStudent()
      val parentCookie = register(uniqueEmail("parent"))
      val linksBefore = linkCount()

      val accept =
        client.post(buildUrl("/api/v1/supporter-invites/accept")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, parentCookie)
          setBody(mapper.writeValueAsString(AcceptSupporterInviteRequest("never-minted")))
        }
      assertEquals(HttpStatusCode.NotFound, accept.status)
      assertEquals(linksBefore, linkCount(), "a dead token links nobody")
    }
}
