package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.coaching.IntendedSubjectsChatTool
import ed.unicoach.coaching.IntendedSubjectsService
import ed.unicoach.coaching.SubjectVocabularyService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.IntendedSubjectsDao
import ed.unicoach.db.models.StudentId
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.UpdateIntendedSubjectsRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET`/`PUT /api/v1/students/me/intended-subjects` (RFC 205 D17): the refusals
 * the route states in words, and the round trip. Same live-server harness as
 * [MoneyProfileRoutingTest], whose caller-resolution ladder and pooled
 * `validation_failed` body this route reuses.
 *
 * NO COUNT OF EITHER LIST IS ASSERTED HERE. Each test seeds its OWN taxonomy
 * rows under a per-test marker, so the size of `db/data/subjects.json` and the
 * size of the federal programme list are both irrelevant to this suite.
 */
class IntendedSubjectsRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0
    private lateinit var dbConnection: Connection

    /**
     * The OTHER door, in this suite on purpose: the cross-door test below sends
     * ONE body text to both, and a refusal only one of them makes is the defect
     * it exists to catch. It parses before it reads anything, so no fixture of
     * its own is needed.
     */
    private lateinit var chatDatabase: Database
    private lateinit var chatTool: IntendedSubjectsChatTool

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

      chatDatabase = Database(dbConfig)
      val vocabulary = SubjectVocabularyService(chatDatabase)
      chatTool = IntendedSubjectsChatTool(IntendedSubjectsService(chatDatabase, vocabulary), vocabulary)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) testServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::dbConnection.isInitialized && !dbConnection.isClosed) dbConnection.close()
      if (::chatDatabase.isInitialized) chatDatabase.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private fun uniqueEmail(): String = "is${UUID.randomUUID()}@company.com"

  private fun markEmailVerified(email: String) {
    dbConnection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  private suspend fun registerAndGetCookie(): String {
    val email = uniqueEmail()
    val req = RegisterRequest(email, "Password123!", "Intended Subjects User")
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    markEmailVerified(email)
    return response.headers[HttpHeaders.SetCookie]!!
      .split(";")
      .first()
      .trim()
  }

  private suspend fun registerStudent(cookie: String) {
    client.post(buildUrl("/api/v1/students")) {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
    }
  }

  /** A registered, email-verified caller who also has a student profile — every write below needs both. */
  private suspend fun studentCookie(): String {
    val cookie = registerAndGetCookie()
    registerStudent(cookie)
    return cookie
  }

  private suspend fun putSubjects(
    cookie: String,
    body: UpdateIntendedSubjectsRequest,
  ) = client.put(buildUrl("/api/v1/students/me/intended-subjects")) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    header(HttpHeaders.Cookie, cookie)
    setBody(mapper.writeValueAsString(body))
  }

  /** The same PUT from a raw body, for shapes the typed request cannot express — a key sent as `false`. */
  private suspend fun putRaw(
    cookie: String,
    body: String,
  ) = client.put(buildUrl("/api/v1/students/me/intended-subjects")) {
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    header(HttpHeaders.Cookie, cookie)
    setBody(body)
  }

  private suspend fun getSubjects(cookie: String) =
    client.get(buildUrl("/api/v1/students/me/intended-subjects")) {
      header(HttpHeaders.Cookie, cookie)
    }

  /** A marker no other row carries, so this test's taxonomy rows are its own. */
  private fun marker(): String = UUID.randomUUID().toString().take(8)

  /**
   * One authored taxonomy row on the shared un-truncated test database, the
   * [CollegeSearchRoutingTest] seeding convention. `cip_prefixes` carries a real
   * two-digit CIP series because `subjects` guards the column with
   * `subjects_cip_prefixes_nonempty_check` and
   * `subjects_cip_prefixes_format_check` (0064); nothing here reads it.
   */
  private fun seedSubject(
    slug: String,
    name: String,
  ) {
    dbConnection
      .prepareStatement(
        "INSERT INTO subjects (slug, name, cip_prefixes) VALUES (?, ?, '{51}'::TEXT[])",
      ).use { stmt ->
        stmt.setString(1, slug)
        stmt.setString(2, name)
        stmt.executeUpdate()
      }
  }

  @Test
  fun `PUT intended-subjects with four slugs returns 400 naming how many were sent`() =
    runBlocking {
      val cookie = studentCookie()
      val marker = marker()
      val slugs = listOf("one", "two", "three", "four").map { "fixture-$marker-$it" }
      slugs.forEach { seedSubject(it, "Fixture $marker ${it.substringAfterLast('-')}") }

      val response = putSubjects(cookie, UpdateIntendedSubjectsRequest(subjects = slugs))
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      assertTrue(
        body.contains("At most ${IntendedSubjectsDao.MAX_SUBJECTS} subjects"),
        "the 400 must name the cap, got $body",
      )
      // The refusal names what the student actually sent, not just the bound.
      assertTrue(body.contains("got ${slugs.size}"), "got $body")
    }

  @Test
  fun `PUT intended-subjects with an unknown slug returns 400 saying it is not a subject on the list`() =
    runBlocking {
      val cookie = studentCookie()
      val marker = marker()
      val known = "fixture-$marker-known"
      seedSubject(known, "Fixture $marker Known")
      val unknown = "fixture-$marker-not-a-subject"

      val response = putSubjects(cookie, UpdateIntendedSubjectsRequest(subjects = listOf(known, unknown)))
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      // D15: a term that is not a subject is refused WITH A REASON, never mapped
      // to something near it.
      assertTrue(body.contains("[$unknown] is not a subject on the list"), "got $body")
    }

  @Test
  fun `PUT intended-subjects with a duplicate slug returns 400`() =
    runBlocking {
      val cookie = studentCookie()
      val marker = marker()
      val slug = "fixture-$marker-twice"
      seedSubject(slug, "Fixture $marker Twice")

      val response = putSubjects(cookie, UpdateIntendedSubjectsRequest(subjects = listOf(slug, slug)))
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      // Refused rather than silently de-duplicated: the student sees what they sent.
      assertTrue(body.contains("[$slug] was sent more than once"), "got $body")
    }

  @Test
  fun `PUT intended-subjects with two of the three keys returns one pooled 400`() =
    runBlocking {
      val cookie = studentCookie()
      val marker = marker()
      val slug = "fixture-$marker-conflicted"
      seedSubject(slug, "Fixture $marker Conflicted")

      val response =
        putSubjects(cookie, UpdateIntendedSubjectsRequest(subjects = listOf(slug), declined = true))
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      // The money route's `count { it } > 1` idiom: ONE error that names every
      // key in the conflict, rather than one error per key.
      assertTrue(body.contains("At most one of subjects, declined, undecided may be set"), "got $body")
      assertEquals(
        1,
        mapper.readTree(body)["fieldErrors"].size(),
        "the conflict is pooled into one field error, got $body",
      )
    }

  @Test
  fun `PUT intended-subjects then GET returns the slugs with their names`() =
    runBlocking {
      val cookie = studentCookie()
      val marker = marker()
      val nursing = "fixture-$marker-nursing"
      val biology = "fixture-$marker-biology"
      seedSubject(nursing, "Fixture $marker Nursing")
      seedSubject(biology, "Fixture $marker Biology")

      val put = putSubjects(cookie, UpdateIntendedSubjectsRequest(subjects = listOf(nursing, biology)))
      assertEquals(HttpStatusCode.OK, put.status)
      val putBody = mapper.readTree(put.bodyAsText())
      assertEquals("answered", putBody["intendedSubjects"]["status"].asText())

      val get = getSubjects(cookie)
      assertEquals(HttpStatusCode.OK, get.status)
      val stored = mapper.readTree(get.bodyAsText())["intendedSubjects"]
      assertEquals("answered", stored["status"].asText())
      // In name order, and each slug carries its label: the client sends the
      // slug back, the student only ever reads the name (D39).
      assertEquals(
        listOf(biology, nursing),
        stored["subjects"].map { it["slug"].asText() },
      )
      assertEquals(
        listOf("Fixture $marker Biology", "Fixture $marker Nursing"),
        stored["subjects"].map { it["name"].asText() },
      )
    }

  @Test
  fun `PUT intended-subjects with declined false returns 400 instead of dropping the key`() =
    runBlocking {
      val cookie = studentCookie()
      val marker = marker()
      val slug = "fixture-$marker-surplus"
      seedSubject(slug, "Fixture $marker Surplus")

      // This route counts the keys that are PRESENT, so a sent `false` is a
      // surplus key. It used to be read as an omission and silently dropped,
      // which served a 200 for a body the chat tool refused.
      val response = putRaw(cookie, """{"subjects":["$slug"],"declined":false}""")
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      assertTrue(body.contains("declined"), "the 400 names the offending key, got $body")

      // The identical body WITHOUT the key is accepted, so the refusal above is
      // about the surplus key and not about the rest of the request.
      val accepted = putRaw(cookie, """{"subjects":["$slug"]}""")
      assertEquals(HttpStatusCode.OK, accepted.status, "got ${accepted.bodyAsText()}")
    }

  @Test
  fun `PUT intended-subjects with both stated keys false pools one field error each`() =
    runBlocking {
      val cookie = studentCookie()
      val response = putRaw(cookie, """{"subjects":[],"declined":false,"undecided":false}""")
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"), "got $body")
      // Pooled: the caller is told about both keys in one answer, not one per
      // round trip.
      assertEquals(2, mapper.readTree(body)["fieldErrors"].size(), "got $body")
    }

  @Test
  fun `the same surplus declined key is refused by both doors`() =
    runBlocking {
      val cookie = studentCookie()
      val marker = marker()
      val slug = "fixture-$marker-agreed"
      seedSubject(slug, "Fixture $marker Agreed")
      // ONE body text, sent to both doors. What a valid answer IS is one rule
      // (`ed.unicoach.coaching.literalTrueViolations`), so a shape refused at one
      // door must be refused at the other; the two doors disagreeing about it is
      // the defect this test exists to catch.
      val malformed = """{"subjects":["$slug"],"declined":false}"""

      val restResponse = putRaw(cookie, malformed)
      assertEquals(HttpStatusCode.BadRequest, restResponse.status, "the REST door must refuse it")

      val chatResult =
        chatTool.execute(
          StudentId(UUID.randomUUID()),
          Json.parseToJsonElement(malformed).jsonObject,
        )
      assertTrue(
        chatResult.containsKey("error"),
        "the chat door must refuse the very same body, got $chatResult",
      )
    }
}
