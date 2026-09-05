package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.rest.models.CreateCollegeListEntryRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.UpdateCollegeListEntryRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollegeListRoutingTest {
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

  private fun uniqueEmail(): String = "cle${UUID.randomUUID()}@company.com"

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
    val req = RegisterRequest(email, "Password123!", "College List User")
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

  /**
   * The published `us_states` row `colleges.state` foreign-keys into since
   * migration 0067. Seeded here rather than in a `@BeforeEach`: this suite
   * shares an un-truncated dev database and only [seedCollege] needs the rows,
   * and the fixture is idempotent, so the cheapest correct place is the one
   * call site that would otherwise fail.
   */
  private fun seedCodebookReference() = CodebookReferenceFixture.seed(dbConnection)

  private fun seedCollege(): UUID {
    val id = UUID.randomUUID()
    // Each test class instance is fresh per @Test (JUnit default), so an
    // instance counter would restart at the same value across tests sharing
    // the un-truncated dev DB; a masked random int keeps ipeds_unit_id unique
    // across the whole suite without a shared counter.
    val uniqueIpedsUnitId = (id.leastSignificantBits and 0x3FFFFFFF).toInt()
    seedCodebookReference()
    dbConnection
      .prepareStatement(
        """
        INSERT INTO colleges (id, ipeds_unit_id, name, city, state, control)
        VALUES (?, ?, 'Test College', 'Townsville', 'CA', 1)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.setInt(2, uniqueIpedsUnitId)
        stmt.executeUpdate()
      }
    return id
  }

  // --- POST /students/me/college-list ---

  @Test
  fun `POST college-list without a student profile returns 409`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      val college = seedCollege()
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college)))
        }
      assertEquals(HttpStatusCode.Conflict, response.status)
      assertTrue(response.bodyAsText().contains("student_profile_required"))
    }

  @Test
  fun `POST college-list with an invalid status string returns 400 with a FieldError naming status`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(
            """{"collegeId":"$college","status":"bogus"}""",
          )
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("status"))
    }

  @Test
  fun `POST college-list with oversized reasons returns 400 with a FieldError naming reasons`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college, "considering", "x".repeat(2049))))
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("reasons"))
    }

  @Test
  fun `POST college-list with empty reasons returns 400 with a FieldError naming reasons`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college, "considering", "")))
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains("validation_failed"))
      assertTrue(body.contains("reasons"))
    }

  @Test
  fun `POST college-list with an unknown collegeId returns 404`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(UUID.randomUUID())))
        }
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertTrue(response.bodyAsText().contains("not_found"))
    }

  @Test
  fun `POST college-list unauthenticated returns 401`() =
    runBlocking {
      val response =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(UUID.randomUUID())))
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  // --- Full CRUD happy path ---

  @Test
  fun `full CRUD happy path through HTTP`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()

      val createResponse =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college, "considering", "Good fit")))
        }
      assertEquals(HttpStatusCode.Created, createResponse.status)
      val created = mapper.readTree(createResponse.bodyAsText())
      val entryId = created["entry"]["id"].asText()
      assertEquals(1, created["entry"]["version"].asInt())
      // RFC 137: every success body names the college, not just its id.
      assertEquals("Test College", created["entry"]["collegeName"].asText())

      val listResponse =
        client.get(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, listResponse.status)
      val listBody = listResponse.bodyAsText()
      assertTrue(listBody.contains(entryId))
      assertTrue(listBody.contains("Test College"), "collection GET must carry collegeName")

      val getResponse =
        client.get(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, getResponse.status)
      assertEquals("Test College", mapper.readTree(getResponse.bodyAsText())["entry"]["collegeName"].asText())

      val patchResponse =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", "Applied now", null)))
        }
      assertEquals(HttpStatusCode.OK, patchResponse.status)
      val patched = mapper.readTree(patchResponse.bodyAsText())
      assertEquals("applying", patched["entry"]["status"].asText())
      assertEquals(2, patched["entry"]["version"].asInt())
      assertEquals("Test College", patched["entry"]["collegeName"].asText())

      val deleteResponse =
        client.delete(buildUrl("/api/v1/students/me/college-list/$entryId?version=2")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

      val getAfterDelete =
        client.get(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, getAfterDelete.status)
    }

  /**
   * A registered student with one college-list entry whose living-plan override
   * is `with_family`, returned as (cookie, entryId) at version 1.
   *
   * The living-plan tests below all need the same stored override to watch: the
   * only interesting thing about the prologue is that a plan IS stored, so it
   * is written once here rather than three times inline.
   */
  private suspend fun entryWithStoredLivingPlan(): Pair<String, String> {
    val cookie = registerAndGetCookie()
    registerStudent(cookie)
    val college = seedCollege()

    val createResponse =
      client.post(buildUrl("/api/v1/students/me/college-list")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(
          mapper.writeValueAsString(
            CreateCollegeListEntryRequest(college, "considering", "Good fit", "with_family"),
          ),
        )
      }
    assertEquals(HttpStatusCode.Created, createResponse.status)
    val entry = mapper.readTree(createResponse.bodyAsText())["entry"]
    assertEquals("with_family", entry["livingPlan"].asText())
    return cookie to entry["id"].asText()
  }

  /** The entry as the server now stores it, read back over GET -- what a PATCH actually did, not what its response said. */
  private suspend fun storedEntry(
    cookie: String,
    entryId: String,
  ): JsonNode {
    val response =
      client.get(buildUrl("/api/v1/students/me/college-list/$entryId")) {
        header(HttpHeaders.Cookie, cookie)
      }
    assertEquals(HttpStatusCode.OK, response.status, "got ${response.bodyAsText()}")
    return mapper.readTree(response.bodyAsText())["entry"]
  }

  @Test
  fun `the per-college living plan round-trips, and livingPlanClear on PATCH clears it`() =
    runBlocking {
      // RFC 152 D2a at the REST boundary, in RFC 164's three states: a stated
      // value SETS the override, livingPlanClear CLEARS it back to the family's
      // usual plan, and a body that mentions neither KEEPS what is stored.
      // Clearing is an act the client performs by name -- never one an omitted
      // key performs for it, which is what silently deleted a fact the family
      // stated when the client only meant to change the status.
      val (cookie, entryId) = entryWithStoredLivingPlan()

      val changed =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", "Good fit", "on_campus")))
        }
      assertEquals(HttpStatusCode.OK, changed.status)
      assertEquals("on_campus", mapper.readTree(changed.bodyAsText())["entry"]["livingPlan"].asText())

      // livingPlanClear on the next PATCH: the override goes back to "no override".
      val cleared =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody("""{"version":2,"status":"applying","reasons":"Good fit","livingPlanClear":true}""")
        }
      assertEquals(HttpStatusCode.OK, cleared.status, "got ${cleared.bodyAsText()}")
      assertTrue(
        mapper.readTree(cleared.bodyAsText())["entry"]["livingPlan"].isNull,
        "livingPlanClear CLEARS the override back to the family's usual plan",
      )

      // Both keys at once is a caller error, worded as the money profile words it.
      val both =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody("""{"version":3,"status":"applying","reasons":"Good fit","livingPlan":"on_campus","livingPlanClear":true}""")
        }
      assertEquals(HttpStatusCode.BadRequest, both.status)
      val bothBody = both.bodyAsText()
      // Asserted on the PARSED field, not on the message text: the message
      // contains the word "livingPlan" anyway, so a contains() check passes even
      // when the FieldError names something else entirely.
      assertEquals(
        "livingPlan",
        mapper
          .readTree(bothBody)
          .get("fieldErrors")
          .single()
          .get("field")
          .asText(),
        "got $bothBody",
      )
      assertTrue(bothBody.contains("At most one of livingPlan, livingPlanClear may be set"), "got $bothBody")
    }

  @Test
  fun `PATCH with an explicit null livingPlan and no livingPlanClear KEEPS the stored override`() =
    runBlocking {
      // RFC 164 D1: Jackson cannot tell an explicit null from an omitted key, so
      // an explicit null reads as "not stated" -- KEEP, not clear. This is a
      // behaviour change from the old contract, where null meant clear, so it
      // gets its own guard: without it a regression to "explicit null clears"
      // stays green.
      val (cookie, entryId) = entryWithStoredLivingPlan()

      val explicitNull =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody("""{"version":1,"status":"applying","reasons":"Good fit","livingPlan":null}""")
        }
      assertEquals(HttpStatusCode.OK, explicitNull.status, "got ${explicitNull.bodyAsText()}")

      assertEquals(
        "with_family",
        storedEntry(cookie, entryId)["livingPlan"].asText(),
        "an explicit null livingPlan reads as \"not stated\" and KEEPS the stored override; " +
          "a client that means clear says so with livingPlanClear",
      )
      Unit
    }

  @Test
  fun `PATCH with a body that OMITS livingPlan entirely (the iOS client's shape)`() =
    runBlocking {
      val (cookie, entryId) = entryWithStoredLivingPlan()

      // This is the body the iOS client sends, verbatim: it is what
      // ios-app/UnicoachiOS/CollegeListModels.swift, struct
      // UpdateCollegeListEntryRequest, emits in its LivingPlanUpdate.keep state
      // -- the default for every existing call site (RFC 168). That struct's
      // encode(to:) is hand-written and states the absence deliberately: keep
      // emits neither livingPlan nor livingPlanClear. Keep the two in step -- if
      // keep ever starts emitting a key, this string stops imitating the client
      // it exists to imitate.
      val rawBody = """{"version":1,"status":"applying","reasons":"Good fit"}"""
      val omitted =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(rawBody)
        }
      // The shipped iOS build sends exactly this body on every restatus and
      // every edit-reasons Save. It stays a 200 -- RFC 164 deliberately does
      // NOT make an omitted key a 400, because that would break every client
      // already in the field.
      assertEquals(HttpStatusCode.OK, omitted.status, "got ${omitted.bodyAsText()}")

      val storedPlan = storedEntry(cookie, entryId)["livingPlan"]
      assertEquals(
        "with_family",
        storedPlan.asText(),
        "an omitted livingPlan key KEEPS the stored override; a client that says " +
          "nothing about the field must not delete a fact the family stated",
      )
      Unit
    }

  @Test
  fun `PATCH with the iOS client's set and clear bodies, verbatim`() =
    runBlocking {
      val (cookie, entryId) = entryWithStoredLivingPlan()

      // The other two bodies ios-app/UnicoachiOS/CollegeListModels.swift emits,
      // byte for byte: struct UpdateCollegeListEntryRequest in its
      // LivingPlanUpdate.set and .clear states (RFC 168). They are RAW strings
      // and not mapper.writeValueAsString(UpdateCollegeListEntryRequest(...))
      // on purpose -- a body built from the server DTO also carries
      // livingPlanClear:false and addObservationIds:[], so it would keep passing
      // after the Swift encoder drifted away from it. Keep these two in step
      // with that encoder.
      val setBodyRaw = """{"version":1,"status":"applying","reasons":"Good fit","livingPlan":"on_campus"}"""
      val set =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(setBodyRaw)
        }
      assertEquals(HttpStatusCode.OK, set.status, "got ${set.bodyAsText()}")
      assertEquals(
        "on_campus",
        storedEntry(cookie, entryId)["livingPlan"].asText(),
        "the client's set body SETS the override",
      )

      val clearBodyRaw = """{"version":2,"status":"applying","reasons":"Good fit","livingPlanClear":true}"""
      val cleared =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(clearBodyRaw)
        }
      assertEquals(HttpStatusCode.OK, cleared.status, "got ${cleared.bodyAsText()}")
      assertTrue(
        storedEntry(cookie, entryId)["livingPlan"].isNull,
        "the client's clear body CLEARS the override back to the family's usual plan",
      )
      Unit
    }

  @Test
  fun `PATCH that OMITS reasons CLEARS the note, and that asymmetry with livingPlan is deliberate`() =
    runBlocking {
      // RFC 164 D4. reasons has the same SHAPE as the old livingPlan defect --
      // an omitted key writes null -- and the opposite meaning: omission there
      // is load-bearing. The shipped iOS client clears the note by dropping the
      // key (CollegeEntryDetailView.normalizedReasons returns nil for an
      // emptied field, and the struct's hand-written encoder uses
      // encodeIfPresent for reasons, so a nil omits the key -- RFC 168),
      // so omitted-means-clear IS its Clear button. Giving reasons the
      // livingPlan treatment would silently disable clearing on every build in
      // the field, so this guard states the asymmetry rather than leaving it to
      // be "fixed" later.
      val (cookie, entryId) = entryWithStoredLivingPlan()

      val withoutReasons =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody("""{"version":1,"status":"applying"}""")
        }
      assertEquals(HttpStatusCode.OK, withoutReasons.status, "got ${withoutReasons.bodyAsText()}")

      assertTrue(
        storedEntry(cookie, entryId)["reasons"].isNull,
        "an omitted reasons key CLEARS the stored note -- the shipped client's only way to clear it",
      )
    }

  @Test
  fun `POST and PATCH college-list with an unknown living plan return 400 naming livingPlan`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()

      val badCreate =
        client.post(buildUrl("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(
            mapper.writeValueAsString(
              CreateCollegeListEntryRequest(college, "considering", null, "in_a_yurt"),
            ),
          )
        }
      assertEquals(HttpStatusCode.BadRequest, badCreate.status)
      assertTrue(badCreate.bodyAsText().contains("livingPlan"), "got ${badCreate.bodyAsText()}")

      val entryId = createEntryFor(cookie, college)
      val badPatch =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", null, "in_a_yurt")))
        }
      assertEquals(HttpStatusCode.BadRequest, badPatch.status)
      assertTrue(badPatch.bodyAsText().contains("livingPlan"), "got ${badPatch.bodyAsText()}")
    }

  // --- GET/PATCH/DELETE on another student's entry ---

  private suspend fun createEntryFor(
    cookie: String,
    college: UUID,
  ): String {
    val response =
      client.post(buildUrl("/api/v1/students/me/college-list")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(college)))
      }
    return mapper.readTree(response.bodyAsText())["entry"]["id"].asText()
  }

  @Test
  fun `GET PATCH DELETE on another students entry id returns 404`() =
    runBlocking {
      val ownerCookie = registerAndGetCookie()
      registerStudent(ownerCookie)
      val college = seedCollege()
      val entryId = createEntryFor(ownerCookie, college)

      val otherCookie = registerAndGetCookie()
      registerStudent(otherCookie)

      val getResponse =
        client.get(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.Cookie, otherCookie)
        }
      assertEquals(HttpStatusCode.NotFound, getResponse.status)

      val patchResponse =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, otherCookie)
          setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", null, null)))
        }
      assertEquals(HttpStatusCode.NotFound, patchResponse.status)

      val deleteResponse =
        client.delete(buildUrl("/api/v1/students/me/college-list/$entryId?version=1")) {
          header(HttpHeaders.Cookie, otherCookie)
        }
      assertEquals(HttpStatusCode.NotFound, deleteResponse.status)
    }

  // --- PATCH stale version ---

  @Test
  fun `PATCH with a stale version returns 409`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val entryId = createEntryFor(cookie, college)

      client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", null, null)))
      }

      val stale =
        client.patch(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "admitted", null, null)))
        }
      assertEquals(HttpStatusCode.Conflict, stale.status)
      assertTrue(stale.bodyAsText().contains("version_conflict"))
    }

  // --- DELETE missing version ---

  @Test
  fun `DELETE missing the version query parameter returns 400`() =
    runBlocking {
      val cookie = registerAndGetCookie()
      registerStudent(cookie)
      val college = seedCollege()
      val entryId = createEntryFor(cookie, college)

      val response =
        client.delete(buildUrl("/api/v1/students/me/college-list/$entryId")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(response.bodyAsText().contains("validation_failed"))
    }

  // --- Unauthenticated on every route ---

  @Test
  fun `unauthenticated request to every route returns 401`() =
    runBlocking {
      val randomId = UUID.randomUUID()

      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get(buildUrl("/api/v1/students/me/college-list")).status,
      )
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.get(buildUrl("/api/v1/students/me/college-list/$randomId")).status,
      )
      assertEquals(
        HttpStatusCode.Unauthorized,
        client
          .patch(buildUrl("/api/v1/students/me/college-list/$randomId")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(mapper.writeValueAsString(UpdateCollegeListEntryRequest(1, "applying", null, null)))
          }.status,
      )
      assertEquals(
        HttpStatusCode.Unauthorized,
        client.delete(buildUrl("/api/v1/students/me/college-list/$randomId?version=1")).status,
      )
    }

  // --- 405 with Allow header, per-route ---

  @Test
  fun `PUT college-list returns 405 with Allow`() =
    runBlocking {
      val response =
        client.request(buildUrl("/api/v1/students/me/college-list")) {
          method = HttpMethod.Put
        }
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertTrue(response.headers[HttpHeaders.Allow]?.contains("POST") == true)
      assertTrue(response.headers[HttpHeaders.Allow]?.contains("GET") == true)
    }

  @Test
  fun `POST college-list entryId returns 405 with Allow`() =
    runBlocking {
      val randomId = UUID.randomUUID()
      val response = client.post(buildUrl("/api/v1/students/me/college-list/$randomId"))
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      val allow = response.headers[HttpHeaders.Allow].orEmpty()
      assertTrue(allow.contains("GET"))
      assertTrue(allow.contains("PATCH"))
      assertTrue(allow.contains("DELETE"))
    }
}
