package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.vocabulary.VocabularyService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/v1/vocabularies` (RFC 165): the wire shape, the session gate, and
 * the deliberate ABSENCE of a student-profile gate — asserted so a later
 * refactor cannot quietly add one.
 */
class VocabularyRoutingTest {
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
      if (::dbConnection.isInitialized && !dbConnection.isClosed) {
        // Hand the shared database back the way it was found: another suite's
        // probe needs region 9 absent (see CodebookReferenceFixture).
        CodebookReferenceFixture.removeOtherJurisdictions(dbConnection)
        dbConnection.close()
      }
    }
  }

  private val mapper = jacksonObjectMapper()

  @BeforeEach
  fun seedJurisdictions() {
    dbConnection.autoCommit = true
    // All 59: the residency vocabulary fails the read on a code with no row.
    CodebookReferenceFixture.seed(dbConnection)
    CodebookReferenceFixture.seedOtherJurisdictions(dbConnection)
  }

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private fun markEmailVerified(email: String) {
    dbConnection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  /** A verified session and NO student profile — the state `profile/02` renders the picker in. */
  private suspend fun registerAndGetCookie(): String {
    val email = "voc${UUID.randomUUID()}@company.com"
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Vocabulary User")))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    markEmailVerified(email)
    return response.headers[HttpHeaders.SetCookie]!!
      .split(";")
      .first()
      .trim()
  }

  @Test
  fun `GET vocabularies without a session returns 401`() =
    runBlocking {
      val response = client.get(buildUrl("/api/v1/vocabularies"))
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `GET vocabularies serves both vocabularies to a session with no student profile`() =
    runBlocking {
      // No registerStudent call: reading a published vocabulary is not an
      // operation on your money profile, so there is no student-profile gate.
      val cookie = registerAndGetCookie()
      val response = client.get(buildUrl("/api/v1/vocabularies")) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

      val body = mapper.readTree(response.bodyAsText())
      assertFalse(body["version"].asText().isBlank(), "the document carries its content hash")

      val bands = body["vocabularies"]["income_bands"]["entries"]
      assertEquals(
        IncomeBand.entries.map { it.value },
        bands.map { it["value"].asText() },
        "the served bands are the enum's, in declaration order",
      )
      assertEquals(IncomeBand.entries.first().bracket, bands.first()["label"].asText())
      assertTrue(bands.first().path(VocabularyService.JURISDICTION_KIND).isMissingNode, "income bands declare no extra keys")

      val states = body["vocabularies"]["residency_states"]["entries"]
      assertEquals(59, states.size(), "the closed residency set is 59 jurisdictions")
      assertEquals("AL", states.first()["value"].asText())
      assertEquals("Alabama", states.first()["label"].asText())
      // The extras are FLATTENED into the entry, not nested under `extras`.
      assertEquals("state", states.first()[VocabularyService.JURISDICTION_KIND].asText())
      assertTrue(states.first().path("extras").isMissingNode, "extras are flattened, never served as an object")
      for (entry in states) {
        val value = entry["value"].asText()
        assertEquals(value, MoneyProfileService.parseResidencyState(value), "a served code must be an accepted code")
        assertFalse(entry["label"].asText().isBlank(), "[$value] must carry a label")
      }
    }

  @Test
  fun `GET vocabularies is byte-identical for two different callers`() =
    runBlocking {
      // The payload is user-independent, which is why it is not part of the
      // per-student money-profile resource.
      val first = client.get(buildUrl("/api/v1/vocabularies")) { header(HttpHeaders.Cookie, registerAndGetCookie()) }
      val second = client.get(buildUrl("/api/v1/vocabularies")) { header(HttpHeaders.Cookie, registerAndGetCookie()) }
      assertEquals(first.bodyAsText(), second.bodyAsText())
    }

  @Test
  fun `an unsupported method on vocabularies is rejected`() =
    runBlocking {
      val response = client.delete(buildUrl("/api/v1/vocabularies")) { header(HttpHeaders.Cookie, registerAndGetCookie()) }
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertEquals("GET", response.headers[HttpHeaders.Allow])
    }
}
