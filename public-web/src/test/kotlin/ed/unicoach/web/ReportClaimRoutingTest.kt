package ed.unicoach.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ed.unicoach.auth.DbEmailVerifier
import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.report.ShareToken
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.ShareEventsDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.NewVerificationToken
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.email.EmailTemplate
import ed.unicoach.family.ClaimOutcome
import ed.unicoach.queue.JobType
import ed.unicoach.web.render.CLAIM_ASK_BUTTON
import ed.unicoach.web.render.CLAIM_CONSENT
import ed.unicoach.web.render.CLAIM_HEADING
import ed.unicoach.web.render.CLAIM_NOT_SENT_NOTICE
import ed.unicoach.web.render.CLAIM_PENDING_LINE
import ed.unicoach.web.render.CLAIM_PENDING_NOTICE
import ed.unicoach.web.render.CLAIM_SENT_NOTICE
import ed.unicoach.web.render.REPORT_CLAIM_CLASS
import ed.unicoach.web.render.SUPPORTER_APP_NOTE
import ed.unicoach.web.render.TOKEN_FIELD
import ed.unicoach.web.report.ServiceCostReportSource
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Claim from the report (RFC 254), through the real report read, the real
 * signup doors and the real database: the offer after the report, the report
 * token carried through signup on its own key, `POST /report/connect`, the
 * signed-in landing, and the referrer policy that lets those forms work in a
 * browser at all.
 */
class ReportClaimRoutingTest {
  private val database = WebAuthTestSupport.database
  private val linkRequests = WebAuthTestSupport.linkRequestService
  private val source =
    ServiceCostReportSource(database, CollegeCostService(database), SharingPermissionService(database), linkRequests)

  @BeforeEach
  fun reset() {
    WebAuthTestSupport.resetDatabase()
  }

  private class Shared(
    val user: User,
    val student: StudentId,
    val token: String,
  )

  /**
   * A token in the mint shape — 32 random bytes, base64url, no padding — that
   * no share carries until one is created with it. Checked against
   * [ShareToken.isWellFormed], so a change to the mint shape fails here rather
   * than turning a dead-link test into a malformed-token one.
   */
  private fun mintShapedToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }).also {
      assertTrue(ShareToken.isWellFormed(it), "a test token must be in the mint shape, or it tests MALFORMED_TOKEN: [$it]")
    }

  /** A student with a live report link, and its raw token in the mint shape. */
  private fun sharedStudent(): Shared {
    val user = WebAuthTestSupport.seedUser("student-${WebAuthTestSupport.uniqueEmail()}", name = "Ava Okafor")
    val student = WebAuthTestSupport.seedStudent(user)
    val raw = mintShapedToken()
    runBlocking {
      database.withConnection { session ->
        val id = CostReportSharesDao.nextId(session).getOrThrow()
        CostReportSharesDao.create(session, NewCostReportShare(id, student, TokenHash.fromRawToken(raw))).getOrThrow()
      }
    }
    return Shared(user, student, raw)
  }

  private companion object {
    /** Non-blank and base64url, but not the 43-character mint shape. */
    const val NOT_MINT_SHAPED = "not-a-minted-token"
  }

  private fun ApplicationTestBuilder.module() = application { testPublicWebModule(costReportSource = source) }

  private suspend fun ApplicationTestBuilder.reportBody(
    token: String,
    session: String? = null,
  ): String {
    val response =
      client.get("/report?token=$token") {
        session?.let { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(it)) }
      }
    assertEquals(HttpStatusCode.OK, response.status)
    return response.bodyAsText()
  }

  /** The offer block, or null when the page renders none. */
  private fun claimBlockOf(body: String): String? =
    body
      .substringAfter("<aside class=\"$REPORT_CLAIM_CLASS\"", missingDelimiterValue = "")
      .takeIf { it.isNotEmpty() }
      ?.substringBefore("</aside>")

  // `&amp;` is the only entity these hrefs can carry: fixed paths, `?`/`=`/`&`
  // and base64url tokens. No HTML parser is on this classpath.
  private fun hrefsOf(fragment: String): List<String> =
    Regex("""href="([^"]*)"""").findAll(fragment).map { it.groupValues[1].replace("&amp;", "&") }.toList()

  private fun count(sql: String): Int =
    runBlocking {
      database.withConnection { session ->
        session.prepareStatement(sql).use { stmt ->
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
          }
        }
      }
    }

  private fun requestCount() = count("SELECT count(*) FROM supporter_link_requests")

  private fun linkRequestedEmailCount(): Int =
    runBlocking {
      database.withConnection { session ->
        session.prepareStatement("SELECT count(*) FROM jobs WHERE job_type = ? AND payload->>'template' = ?").use { stmt ->
          stmt.setString(1, JobType.SEND_EMAIL.value)
          stmt.setString(2, EmailTemplate.LINK_REQUESTED.name)
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
          }
        }
      }
    }

  private fun claim(
    user: User,
    token: String,
  ) = runBlocking { linkRequests.claimFromReport(user.id, token).getOrThrow() }

  private fun registerForm(
    email: String,
    vararg extra: Pair<String, String>,
  ) = Parameters.build {
    append("name", "Jane Parent")
    append("email", email)
    append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
    extra.forEach { (key, value) -> append(key, value) }
  }

  // --- the offer after the report ---------------------------------------------------

  @Test
  fun `a signed-out reader is offered an account after the report, with the report token on its own key`() =
    testApplication {
      module()
      val ava = sharedStudent()

      val body = reportBody(ava.token)
      val block = assertNotNull(claimBlockOf(body), "a signed-out reader is offered the account")

      assertTrue(
        body.indexOf("<aside class=\"$REPORT_CLAIM_CLASS\"") > body.indexOf("report-sources"),
        "the offer comes after the report, never in front of it",
      )
      assertTrue(block.contains(CLAIM_HEADING) && block.contains(CLAIM_CONSENT))
      assertEquals(
        listOf("/register?report=${ava.token}", "/sign-in?report=${ava.token}"),
        hrefsOf(block),
        "the offer's own two links, and nothing else",
      )
    }

  @Test
  fun `a signed-in reader with nothing pending is offered a one-tap request`() =
    testApplication {
      module()
      val ava = sharedStudent()
      val jane = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Jane Parent")

      val block = assertNotNull(claimBlockOf(reportBody(ava.token, WebAuthTestSupport.login(jane.email.value))))

      assertTrue(block.contains("action=\"$REPORT_CONNECT_PATH\""), "a POST form to the connect door")
      assertTrue(block.contains("name=\"report\" value=\"${ava.token}\""), "the token rides in its own hidden field")
      assertTrue(block.contains(CLAIM_ASK_BUTTON))
      assertEquals(emptyList(), hrefsOf(block), "no signup links for a reader who is already signed in")
    }

  @Test
  fun `a reader whose request is waiting sees one line, and a connected, declined or student reader sees nothing`() =
    testApplication {
      module()
      val ava = sharedStudent()
      val waiting = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Waiting Parent")
      val connected = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Connected Parent")
      val declined = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Declined Parent")
      claim(waiting, ava.token)
      claim(connected, ava.token)
      claim(declined, ava.token)
      val requests =
        runBlocking {
          WebAuthTestSupport.supporterService
            .supportersOf(ava.student)
            .getOrThrow()
            .requests
        }
      runBlocking {
        linkRequests.approve(ava.student, requests.single { it.name.value == "Connected Parent" }.requestId).getOrThrow()
        linkRequests.decline(ava.student, requests.single { it.name.value == "Declined Parent" }.requestId).getOrThrow()
      }

      val pendingBlock = assertNotNull(claimBlockOf(reportBody(ava.token, WebAuthTestSupport.login(waiting.email.value))))
      assertTrue(pendingBlock.contains(CLAIM_PENDING_LINE))
      assertFalse(pendingBlock.contains("<form"), "nothing to submit while a request waits")

      for (reader in listOf(connected, declined, ava.user)) {
        assertEquals(
          null,
          claimBlockOf(reportBody(ava.token, WebAuthTestSupport.login(reader.email.value))),
          "[${reader.name.value}] is offered nothing",
        )
      }
    }

  /**
   * D-F, kept: the offer is chosen by the reader alone, so a fact about the
   * student — here, that they opted out of share offers — cannot change one
   * byte of what a link holder is served.
   */
  @Test
  fun `the page's bytes do not change with the student's opt-out, and the offer names nobody`() =
    testApplication {
      module()
      val ava = sharedStudent()

      val before = reportBody(ava.token)
      runBlocking { database.withConnection { ShareEventsDao.recordOptOut(it, ava.student).getOrThrow() } }
      val after = reportBody(ava.token)

      assertEquals(before, after, "an opted-out student's report is served byte for byte as before")
      val block = assertNotNull(claimBlockOf(after))
      assertFalse(block.contains("Ava") || block.contains("Okafor"), "the offer names no student")
      assertFalse(after.contains("@"), "the page carries no email address")
    }

  // --- the token through signup ---------------------------------------------------

  @Test
  fun `registering from the report makes an account, a request, and the confirm-your-email notice`() =
    testApplication {
      module()
      val ava = sharedStudent()

      val page = client.get("/register?report=${ava.token}").bodyAsText()
      assertTrue(page.contains("name=\"report\" value=\"${ava.token}\""), "the register form carries the report token")
      assertTrue(hrefsOf(page).contains("/sign-in?report=${ava.token}"), "and so does its link to the other form")

      val email = WebAuthTestSupport.uniqueEmail()
      val response = formPost("/register", registerForm(email, "report" to ava.token), client = noFollowClient())

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/verify-email?notice=claim-pending", response.headers[HttpHeaders.Location])
      assertEquals(1, requestCount(), "the request is made")
      assertEquals(0, linkRequestedEmailCount(), "and the student is told nothing until the address is confirmed")
      assertEquals(0, WebAuthTestSupport.supporterLinkCount(), "nothing is linked")
    }

  /**
   * The whole verify step, through the real door: a claimant who registered from
   * the report is silent until they confirm their address, the confirm tells the
   * student, and confirming again tells nobody.
   */
  @Test
  fun `confirming the address of a claimant who registered from the report tells the student once`() =
    testApplication {
      application { testPublicWebModule(DbEmailVerifier(database, linkRequests::releaseOnVerified), costReportSource = source) }
      val ava = sharedStudent()
      val email = WebAuthTestSupport.uniqueEmail()
      formPost("/register", registerForm(email, "report" to ava.token), client = noFollowClient())
      assertEquals(0, linkRequestedEmailCount(), "nothing is sent before the address is confirmed")

      val claimant =
        runBlocking {
          database.withConnection { UsersDao.findByEmail(it, (EmailAddress.create(email) as ValidationResult.Valid).value).getOrThrow() }
        }

      suspend fun confirm() {
        val raw = "verify-${UUID.randomUUID()}"
        runBlocking {
          database.withConnection { session ->
            VerificationTokensDao
              .create(session, NewVerificationToken(claimant.id, TokenHash.fromRawToken(raw), Instant.now().plus(1, ChronoUnit.DAYS)))
              .getOrThrow()
          }
        }
        assertEquals(HttpStatusCode.OK, formPost("/verify-email", Parameters.build { append(TOKEN_FIELD, raw) }).status)
      }

      confirm()
      assertEquals(1, linkRequestedEmailCount(), "the confirm tells the student")

      confirm()
      assertEquals(1, linkRequestedEmailCount(), "a second confirm sends nothing")
    }

  @Test
  fun `signing in from the report asks the student and says so`() =
    testApplication {
      module()
      val ava = sharedStudent()
      val jane = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Jane Parent")

      val page = client.get("/sign-in?report=${ava.token}").bodyAsText()
      assertTrue(page.contains("name=\"report\" value=\"${ava.token}\""))
      assertTrue(hrefsOf(page).contains("/register?report=${ava.token}"))

      val response =
        formPost(
          "/sign-in",
          Parameters.build {
            append("email", jane.email.value)
            append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
            append("report", ava.token)
          },
          client = noFollowClient(),
        )

      assertEquals("/account?notice=claim-sent", response.headers[HttpHeaders.Location])
      assertEquals(1, requestCount())
      assertEquals(1, linkRequestedEmailCount(), "a verified claimant's request is sent to the student at once")
    }

  @Test
  fun `a report token is never read as an invite, and an invite token is never read as a report`() =
    testApplication {
      module()
      val ava = sharedStudent()

      val asReport = formPost("/register", registerForm(WebAuthTestSupport.uniqueEmail(), "report" to ava.token), client = noFollowClient())
      assertEquals("/verify-email?notice=claim-pending", asReport.headers[HttpHeaders.Location], "no invite notice")

      val asInvite = formPost("/register", registerForm(WebAuthTestSupport.uniqueEmail(), "token" to ava.token), client = noFollowClient())
      assertEquals("/verify-email?notice=invite-not-accepted", asInvite.headers[HttpHeaders.Location])
      assertEquals(1, requestCount(), "the token sent as an invite made no request")
    }

  @Test
  fun `a claim that makes no request still lands the account, with one sentence for every refusal`() =
    testApplication {
      module()
      val ava = sharedStudent()
      val avaSession = WebAuthTestSupport.login(ava.user.email.value)

      val own =
        formPost(
          REPORT_CONNECT_PATH,
          Parameters.build { append("report", ava.token) },
          sessionToken = avaSession,
          client = noFollowClient(),
        )
      val dead =
        formPost(
          REPORT_CONNECT_PATH,
          Parameters.build { append("report", mintShapedToken()) },
          sessionToken = avaSession,
          client = noFollowClient(),
        )

      assertEquals("/account?notice=claim-not-sent", own.headers[HttpHeaders.Location])
      assertEquals(own.headers[HttpHeaders.Location], dead.headers[HttpHeaders.Location], "the same notice for every refusal")
      assertEquals(0, requestCount())
    }

  @Test
  fun `ask to connect from the page makes the request, and an unverified reader is sent to confirm first`() =
    testApplication {
      module()
      val ava = sharedStudent()
      val verified = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Jane Parent")
      val unverified = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "New Parent", verified = false)

      val sent =
        formPost(
          REPORT_CONNECT_PATH,
          Parameters.build { append("report", ava.token) },
          sessionToken = WebAuthTestSupport.login(verified.email.value),
          client = noFollowClient(),
        )
      val pending =
        formPost(
          REPORT_CONNECT_PATH,
          Parameters.build { append("report", ava.token) },
          sessionToken = WebAuthTestSupport.login(unverified.email.value),
          client = noFollowClient(),
        )

      assertEquals("/account?notice=claim-sent", sent.headers[HttpHeaders.Location])
      assertEquals("/verify-email?notice=claim-pending", pending.headers[HttpHeaders.Location])
      assertEquals(2, requestCount())
      assertEquals(1, linkRequestedEmailCount(), "only the verified reader's request reached the student")
    }

  @Test
  fun `each notice renders its own sentence`() =
    testApplication {
      module()
      val jane = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Jane Parent")
      val session = WebAuthTestSupport.login(jane.email.value)

      for ((key, sentence) in listOf(
        "claim-sent" to CLAIM_SENT_NOTICE,
        "claim-not-sent" to CLAIM_NOT_SENT_NOTICE,
      )) {
        val body = client.get("/account?notice=$key") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(session)) }.bodyAsText()
        assertTrue(body.contains(sentence), "[$key] renders its sentence")
      }
      val unverified = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), verified = false)
      val hold =
        client
          .get("/verify-email?notice=claim-pending") {
            header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(WebAuthTestSupport.login(unverified.email.value)))
          }.bodyAsText()
      assertTrue(hold.contains(CLAIM_PENDING_NOTICE), "the hold screen renders the claim-pending sentence")
    }

  // --- the signed-in landing --------------------------------------------------------

  @Test
  fun `a signed-in reader following a signup door from the report is sent back to the report`() =
    testApplication {
      module()
      val ava = sharedStudent()
      val jane = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Jane Parent")
      val cookie = WebAuthTestSupport.cookieHeader(WebAuthTestSupport.login(jane.email.value))
      val noFollow = noFollowClient()

      for (door in listOf("/register", "/sign-in")) {
        val response = noFollow.get("$door?report=${ava.token}") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/report?token=${ava.token}", response.headers[HttpHeaders.Location], "[$door] keeps the token")
      }
      assertEquals(
        "/account",
        noFollow.get("/register") { header(HttpHeaders.Cookie, cookie) }.headers[HttpHeaders.Location],
        "without a report token the landing is unchanged",
      )
      assertEquals(
        "/account",
        noFollow.get("/register?report=$NOT_MINT_SHAPED") { header(HttpHeaders.Cookie, cookie) }.headers[HttpHeaders.Location],
        "a value the server could not have minted is never turned into a redirect",
      )
    }

  @Test
  fun `a signup door carries a report token only in the mint shape`() =
    testApplication {
      module()

      for (door in listOf("/register", "/sign-in")) {
        val page = client.get("$door?report=$NOT_MINT_SHAPED").bodyAsText()
        assertFalse(page.contains(NOT_MINT_SHAPED), "[$door] does not echo a value the server could not have minted")
      }
    }

  // --- the account page for a parent ---------------------------------------------

  @Test
  fun `a supporter account is told where the parent product is, and a plain account is not`() =
    testApplication {
      module()
      val ava = sharedStudent()
      val jane = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Jane Parent")
      val plain = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Plain User")
      assertIs<ClaimOutcome.Requested>(claim(jane, ava.token))
      assertNotNull(runBlocking { database.withConnection { SupportersDao.findByUserId(it, jane.id).getOrThrow() } })

      fun account(user: User) =
        runBlocking {
          client
            .get("/account") {
              header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(WebAuthTestSupport.login(user.email.value)))
            }.bodyAsText()
        }

      assertTrue(account(jane).contains(SUPPORTER_APP_NOTE))
      assertFalse(account(plain).contains("iPhone app"))
    }

  // --- the referrer policy, and the guard it lets through ----------------------------

  /** Literal on purpose: the value is the decision (RFC 254 decision 2), not whatever the constant now says. */
  @Test
  fun `every link-holder page sends Referrer-Policy same-origin`() =
    testApplication {
      module()
      val ava = sharedStudent()

      for (path in listOf(
        "/report?token=${ava.token}",
        "/register?report=${ava.token}",
        "/sign-in?report=${ava.token}",
        "/invite?token=dead",
      )) {
        assertEquals("same-origin", client.get(path).headers["Referrer-Policy"], "[$path]")
      }
    }

  /**
   * What `same-origin` buys: a browser now sends the page's real origin on its
   * form POST, and the guard admits it. `Origin: null` — what a `no-referrer`
   * page sent — is still refused.
   */
  @Test
  fun `the connect form passes the guard with a real Origin, and Origin null is still refused`() =
    testApplication {
      module()
      val ava = sharedStudent()
      val jane = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Jane Parent")
      val session = WebAuthTestSupport.login(jane.email.value)

      val opaque =
        noFollowClient().post(REPORT_CONNECT_PATH) {
          header(HttpHeaders.Origin, "null")
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(session))
          setBody(FormDataContent(Parameters.build { append("report", ava.token) }))
        }
      assertEquals(HttpStatusCode.Forbidden, opaque.status)
      assertEquals(0, requestCount(), "a refused POST made nothing")

      val real =
        formPost(REPORT_CONNECT_PATH, Parameters.build { append("report", ava.token) }, sessionToken = session, client = noFollowClient())
      assertEquals(HttpStatusCode.SeeOther, real.status)
      assertEquals(1, requestCount())
    }

  @Test
  fun `the report token never reaches a log line`() {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    lateinit var token: String
    root.addAppender(appender)
    try {
      testApplication {
        module()
        val ava = sharedStudent()
        token = ava.token
        val jane = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Jane Parent")
        val cookie = WebAuthTestSupport.cookieHeader(WebAuthTestSupport.login(jane.email.value))

        client.get("/report?token=$token")
        client.get("/register?report=$token")
        client.get("/sign-in?report=$token")
        noFollowClient().get("/register?report=$token") { header(HttpHeaders.Cookie, cookie) }
        formPost("/register", registerForm(WebAuthTestSupport.uniqueEmail(), "report" to token), client = noFollowClient())
        formPost(REPORT_CONNECT_PATH, Parameters.build { append("report", token) }, client = noFollowClient()) {
          header(HttpHeaders.Cookie, cookie)
          header(HttpHeaders.Referrer, "${WebAuthTestSupport.urlBase}/report?token=$token")
        }
        noFollowClient().post(REPORT_CONNECT_PATH) {
          header(HttpHeaders.Origin, "null")
          header(HttpHeaders.Referrer, "${WebAuthTestSupport.urlBase}/report?token=$token")
          setBody(FormDataContent(Parameters.build { append("report", token) }))
        }
      }
    } finally {
      root.detachAppender(appender)
    }

    val lines = appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
    assertTrue(lines.any { it.contains(REPORT_CONNECT_PATH) }, "the probe must see the request log, or it proves nothing")
    val leaked = lines.filter { it.contains(token) }
    assertEquals(emptyList(), leaked, "the raw report token reached the log")
  }
}
