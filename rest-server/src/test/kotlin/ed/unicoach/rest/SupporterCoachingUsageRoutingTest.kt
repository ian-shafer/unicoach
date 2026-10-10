package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.appstore.AppStoreTestFixtures
import ed.unicoach.appstore.ScriptedAppStoreTransport
import ed.unicoach.appstore.SwappableAppStoreTransport
import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.LlmCallRowFixture
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.SubscriptionVerifyRequest
import ed.unicoach.rest.routing.SUPPORTER_COACHING_USAGE_PATH
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
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.http.renderCookieHeader
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins `GET /api/v1/supporters/me/coaching-usage` (RFC 244 §6): the parent's own
 * coaching meter, and WHICH allowance it meters.
 *
 * A suite of its own rather than more cases in [SupporterRoutingTest], for one
 * reason that is not organisational: the `own_subscription` arm needs a
 * subscription, and the only way this system mints one is
 * `POST /api/v1/subscriptions/verify` against a scripted App Store conversation.
 * [SupporterRoutingTest] boots the production [startServer] wiring, whose App
 * Store client holds unconfigured credentials and answers `503` before any
 * transport is reached, so that suite CANNOT reach this arm. This one boots
 * `appModule` over a [SwappableAppStoreTransport] instead ([startAppModuleServer],
 * the harness it shares with [SubscriptionRoutingTest]), and buys the
 * subscription through the real door — a hand-written `subscriptions`
 * row would prove the route reads a row somebody inserted, not that a parent who
 * pays is metered on what they paid for.
 */
class SupporterCoachingUsageRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var dbConnection: java.sql.Connection
    private lateinit var budgetConfig: BudgetConfig
    private var boundPort: Int = 0

    /** The session cookie's configured name, so [register] selects it rather than guessing its position. */
    private lateinit var sessionCookieName: String

    /**
     * Unique Apple keys across the suite, so bindings never collide.
     *
     * The BASE must differ from every other suite's: `subscriptions` is keyed by
     * `original_transaction_id` alone and this shared test database is not
     * truncated between classes, so two suites counting from the same base bind
     * the same key to different accounts and the second one reads
     * `409 subscription_owned_by_other_account` — which is what happened with
     * [AppleNotificationRoutingTest]'s `940_000_000_000`. Hence `960_…` here.
     */
    private val OTID_COUNTER = AtomicLong(960_000_000_000L)

    /** The swap point for the Apple conversation, fixed at boot — one per suite ([SwappableAppStoreTransport]). */
    private val appStoreTransport = SwappableAppStoreTransport()

    /**
     * No turn is ever taken here — this suite reads a meter and never spends —
     * so the provider is the loud kind: a call to it is a test that wandered
     * off its subject, not a fixture to be satisfied quietly.
     */
    private val unusedProvider =
      object : ChatProvider {
        override val id: String = "log"

        override fun stream(request: ChatRequest): Flow<ChatEvent> =
          throw IllegalStateException("SupporterCoachingUsageRoutingTest takes no coaching turns")
      }

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf", "session.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      val database = Database(dbConfig)
      dbConnection = java.sql.DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
      budgetConfig = BudgetConfig.from(config).getOrThrow()
      sessionCookieName = SessionConfig.from(config).getOrThrow().cookieName

      testServer = startAppModuleServer(config, database, budgetConfig, appStoreTransport, unusedProvider)
      boundPort = testServer.boundPort()
      client = HttpClient(CIO)
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

  private fun url(path: String) = "http://localhost:$boundPort$path"

  // ---------------------------------------------------------------------------
  // Callers
  // ---------------------------------------------------------------------------

  /**
   * Registers an account and returns its session cookie, leaving the address
   * UNVERIFIED.
   *
   * The cookie is selected BY NAME out of Ktor's own parse of every `Set-Cookie`
   * header, and rendered back with Ktor's writer. A `Set-Cookie` header is a
   * grammar, and `headers[SetCookie]` reads only the FIRST of possibly several:
   * a positional split would hand back whatever cookie the register route
   * happens to set first, and every assertion in this file would then run as an
   * unauthenticated caller — reporting a wrong status rather than a wrong cookie.
   */
  private suspend fun register(email: String): String {
    val registration =
      client.post(url("/api/v1/auth/register")) {
        contentType(ContentType.Application.Json)
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Parent User")))
      }
    assertEquals(HttpStatusCode.Created, registration.status)
    return renderCookieHeader(
      registration.headers
        .getAll(HttpHeaders.SetCookie)!!
        .map(::parseServerSetCookieHeader)
        .single { it.name == sessionCookieName },
    )
  }

  private fun markEmailVerified(email: String) {
    dbConnection
      .prepareStatement("UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ?")
      .use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  private fun uniqueEmail(prefix: String) = "$prefix${UUID.randomUUID()}@company.com"

  /** A verified account with NO student profile and NO supporter row. */
  private suspend fun registerVerifiedAccount(): Pair<String, String> {
    val email = uniqueEmail("usage")
    val cookie = register(email)
    markEmailVerified(email)
    return cookie to email
  }

  /** A verified account that has walked through the supporter door (RFC 241). */
  private suspend fun registerSupporter(): Supporter {
    val (cookie, email) = registerVerifiedAccount()
    val created =
      client.post(url("/api/v1/supporters")) {
        header(HttpHeaders.Cookie, cookie)
      }
    assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
    val supporterId =
      UUID.fromString(
        mapper
          .readTree(created.bodyAsText())
          .get("supporter")
          .get("id")
          .asText(),
      )
    return Supporter(cookie, email, supporterId)
  }

  private class Supporter(
    val cookie: String,
    val email: String,
    val id: UUID,
  )

  /** Gives [cookie]'s account a student profile as well — the dual-role account of RFC 244 §7a. */
  private suspend fun addStudentProfile(cookie: String) {
    val created =
      client.post(url("/api/v1/students")) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(createStudentBody()))
      }
    assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
  }

  // ---------------------------------------------------------------------------
  // Buying a subscription through the real door
  // ---------------------------------------------------------------------------

  private fun uniqueOriginalTransactionId(): String = OTID_COUNTER.incrementAndGet().toString()

  /**
   * Binds a current subscription to [cookie]'s ACCOUNT through
   * `POST /api/v1/subscriptions/verify`, and answers the period end the meter
   * must then report as `resetsAt`.
   */
  private suspend fun subscribe(cookie: String): Instant {
    val periodStart = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    val periodEnd = Instant.now().plus(25, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    appStoreTransport.delegate =
      ScriptedAppStoreTransport.of(
        200,
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(
            status = 1,
            signedTransactionInfo =
              AppStoreTestFixtures.signedTransaction(
                originalTransactionId = uniqueOriginalTransactionId(),
                purchaseDate = periodStart,
                expiresDate = periodEnd,
              ),
          ),
        ),
      )
    val verified =
      client.post(url("/api/v1/subscriptions/verify")) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(SubscriptionVerifyRequest(AppStoreTestFixtures.signedTransaction())))
      }
    assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
    return periodEnd
  }

  // ---------------------------------------------------------------------------
  // Spend seeding, on the SUPPORTER side of the ledger
  // ---------------------------------------------------------------------------

  /**
   * Attributes one costed call of [costNanodollars] to [supporterId] through
   * `supporter_llm_costs` — the owner row `family/06` will write per call, and
   * the only thing that can move a parent's meter off zero today.
   *
   * The call itself is [LlmCallRowFixture]'s, the one definition of a costed
   * call in the corpus: a fourth copy of that insert here is the drift the
   * fixture was added to end.
   */
  private fun seedSupporterSpend(
    supporterId: UUID,
    costNanodollars: Long,
  ) = LlmCallRowFixture.attributeToSupporter(
    dbConnection,
    supporterId,
    LlmCallRowFixture.insert(dbConnection, costNanodollars),
  )

  // ---------------------------------------------------------------------------
  // Requests and body reading
  // ---------------------------------------------------------------------------

  private suspend fun getUsage(cookie: String?): HttpResponse =
    client.get(url(SUPPORTER_COACHING_USAGE_PATH)) {
      if (cookie != null) header(HttpHeaders.Cookie, cookie)
    }

  private suspend fun HttpResponse.errorCode(): String = mapper.readTree(bodyAsText()).get("code").asText()

  private fun fieldNamesOf(node: JsonNode): Set<String> = node.fieldNames().asSequence().toSet()

  /**
   * The published SHAPE of the 200 body, asserted on the raw JSON and never on a
   * decoded model: a decoded [ed.unicoach.rest.models.SupporterCoachingUsageResponse]
   * would ignore any field added beside it, which is exactly the regression this
   * guards. Two keys at the root, three in the meter, one in the coverage — a
   * dollar figure, a token count or a provider name arriving later fails here
   * whatever it is called (RFC 109's refusal, RFC 244 §6's body).
   */
  private fun assertBodyShape(body: String) {
    val root = mapper.readTree(body)
    assertEquals(setOf("usage", "coverage"), fieldNamesOf(root), "the body carries a meter and a coverage, and nothing else: [$body]")
    assertEquals(
      setOf("usedPercent", "exhausted", "resetsAt"),
      fieldNamesOf(root.get("usage")),
      "the meter is a percentage, a block condition and a reset point — no dollars and no tokens: [$body]",
    )
    assertEquals(setOf("kind"), fieldNamesOf(root.get("coverage")), "the coverage is a word, not a price: [$body]")
  }

  // ---------------------------------------------------------------------------
  // The refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `usage is 401 without a session`() =
    runBlocking {
      val response = getUsage(cookie = null)

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals("unauthorized", response.errorCode())
    }

  /**
   * The mirror of `GET /api/v1/supporters/me`'s own answer for the same absence.
   * NOT a 200 with a zero bar, which would render a parent surface to somebody
   * who has no parent surface — and NOT a 409 `student_profile_required`, the
   * refusal RFC 244 takes off this side of the house entirely.
   */
  @Test
  fun `usage is 404 supporter_not_found for a caller with no supporters row`() =
    runBlocking {
      val (cookie, _) = registerVerifiedAccount()

      val response = getUsage(cookie)

      assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
      assertEquals("supporter_not_found", response.errorCode())
    }

  /** The route is inside `/api/v1/supporters`, so the email gate covers it like its siblings. */
  @Test
  fun `usage is 403 email_not_verified for an unverified caller`() =
    runBlocking {
      val response = getUsage(register(uniqueEmail("unverified")))

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("email_not_verified", response.errorCode())
    }

  @Test
  fun `usage rejects a non-GET method`() =
    runBlocking {
      val supporter = registerSupporter()

      val response = client.post(url(SUPPORTER_COACHING_USAGE_PATH)) { header(HttpHeaders.Cookie, supporter.cookie) }

      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

  // ---------------------------------------------------------------------------
  // The two coverage kinds
  // ---------------------------------------------------------------------------

  /**
   * A fresh parent, byte for byte: RFC 244 §6's published body with no
   * substitutions. Compared as a whole tree rather than field by field, so a
   * field this route must not carry fails the assertion by being there at all.
   */
  @Test
  fun `a fresh supporter reads exactly the free_allowance body`() =
    runBlocking {
      val supporter = registerSupporter()

      val response = getUsage(supporter.cookie)

      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
      assertEquals(
        mapper.readTree(
          """{"usage":{"usedPercent":0,"exhausted":false,"resetsAt":null},"coverage":{"kind":"free_allowance"}}""",
        ),
        mapper.readTree(response.bodyAsText()),
        "the RFC 244 §6 body, whole: nothing spent, nothing blocked, a lifetime allowance that never resets",
      )
      assertBodyShape(response.bodyAsText())
    }

  /**
   * And the meter is the parent's REAL spend, not a constant: half the CONFIGURED
   * free allowance attributed to this supporter reads 50%. Without this the
   * zero above is satisfied by a route that hardcodes zero.
   *
   * The spend is attributed to ANOTHER supporter too, at the same size, so the
   * reading also proves the meter is scoped to the caller: a route summing every
   * supporter's spend would read 100 here.
   */
  @Test
  fun `usage reports this supporter's own metered spend and nobody else's`() =
    runBlocking {
      val supporter = registerSupporter()
      val other = registerSupporter()
      seedSupporterSpend(supporter.id, budgetConfig.freeAllowance.value / 2)
      seedSupporterSpend(other.id, budgetConfig.freeAllowance.value / 2)

      val usage = mapper.readTree(getUsage(supporter.cookie).bodyAsText()).get("usage")

      assertEquals(50, usage.get("usedPercent").asInt())
      assertFalse(usage.get("exhausted").asBoolean())
      assertTrue(usage.get("resetsAt").isNull, "still the free allowance, which never resets")
    }

  /**
   * The `own_subscription` arm on the dual-role account RFC 244 §7a names as the
   * one that reaches it through the app today: one human with a `students` row
   * and a `supporters` row, holding ONE subscription that Apple sold their one
   * Apple ID. The parent surface meters that subscription's period, and says so
   * on the wire.
   */
  @Test
  fun `a supporter whose account holds a subscription reads own_subscription and the period end`() =
    runBlocking {
      val supporter = registerSupporter()
      addStudentProfile(supporter.cookie)
      val periodEnd = subscribe(supporter.cookie)

      val response = getUsage(supporter.cookie)

      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
      assertBodyShape(response.bodyAsText())
      val body = mapper.readTree(response.bodyAsText())
      assertEquals("own_subscription", body.get("coverage").get("kind").asText())
      assertEquals(0, body.get("usage").get("usedPercent").asInt(), "the account has spent nothing inside this period")
      assertFalse(body.get("usage").get("exhausted").asBoolean())
      assertEquals(
        periodEnd,
        Instant.parse(body.get("usage").get("resetsAt").asText()),
        "the subscribed meter resets at the period end Apple reported",
      )
    }

  /**
   * The slice's whole point, end to end: an account with NO student profile pays
   * for itself (RFC 244 §5 removed the role refusal) and its parent surface then
   * reports `own_subscription`. Before this slice the verify call was a `409
   * student_profile_required` and this reading was unreachable.
   */
  @Test
  fun `a supporter with no student profile pays for their own account and is metered on it`() =
    runBlocking {
      val supporter = registerSupporter()
      val periodEnd = subscribe(supporter.cookie)

      val response = getUsage(supporter.cookie)

      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
      assertEquals(
        0,
        studentRowsFor(supporter.email),
        "the premise: this account never held a student profile",
      )
      val body = mapper.readTree(response.bodyAsText())
      assertEquals("own_subscription", body.get("coverage").get("kind").asText())
      assertEquals(periodEnd, Instant.parse(body.get("usage").get("resetsAt").asText()))
    }

  /** How many `students` rows this address's account holds — 0 is what "no student profile" MEANS. */
  private fun studentRowsFor(email: String): Int =
    dbConnection
      .prepareStatement("SELECT count(*) FROM students s JOIN users u ON u.id = s.user_id WHERE u.email = ?")
      .use { stmt ->
        stmt.setString(1, email)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
}
