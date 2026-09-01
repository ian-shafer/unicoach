package ed.unicoach.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ed.unicoach.coaching.costs.CollegeControl
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `GET /report` route contract (RFC 155): what the response carries, what a
 * dead token gets, and what the request log is allowed to say.
 *
 * DB-free: the whole read is [FakeCostReportSource], exactly as the verify-email
 * route tests fake [ed.unicoach.auth.EmailVerifier].
 */
class CostReportRoutingTest {
  private fun profile() =
    costProfile(
      listOf(costFixture("Riverside College", control = CollegeControl.PrivateNonprofit, tuitionInState = 41000)),
    )

  /** The standing fake for this suite: one live token, everything else not found. */
  private fun liveSource(token: String = TEST_LIVE_TOKEN) = FakeCostReportSource(FakeReportAnswer.Live(profile(), token))

  @Test
  fun `a live token renders the report with the site chrome`() =
    testApplication {
      application {
        testPublicWebModule(costReportSource = liveSource())
      }

      val response = client.get("/report?token=$TEST_LIVE_TOKEN")

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Text.Html.toString()),
        "the report is HTML",
      )
      val body = response.bodyAsText()
      assertTrue(body.contains("Your student's college list"), "missing the report heading marker")
      assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
      assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
    }

  @Test
  fun `an unknown, a revoked and a blank token all render the same branded 404`() =
    testApplication {
      val source = liveSource()
      application {
        testPublicWebModule(costReportSource = source)
      }

      val unknown = client.get("/report?token=never-minted")
      val revoked = client.get("/report?token=revoked-yesterday")
      val blank = client.get("/report?token=")
      val absent = client.get("/report")

      listOf(unknown, revoked, blank, absent).forEach {
        assertEquals(HttpStatusCode.NotFound, it.status, "a dead token is a 404")
      }
      val bodies = listOf(unknown, revoked, blank, absent).map { it.bodyAsText() }
      assertTrue(bodies.first().contains("404 Not Found"), "missing the branded 404 marker")
      assertEquals(1, bodies.toSet().size, "the four dead-token bodies must be identical: [$bodies]")
      // The route never asks the port about a token it can see is empty, so
      // "blank" cannot even be distinguished by a timing difference in the read.
      assertEquals(listOf("never-minted", "revoked-yesterday"), source.tokensSeen)
    }

  @Test
  fun `the report is not stored, not indexed and not referred`() =
    testApplication {
      application {
        testPublicWebModule(costReportSource = liveSource())
      }

      val response = client.get("/report?token=$TEST_LIVE_TOKEN")

      assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
      assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
      assertEquals("no-referrer", response.headers["Referrer-Policy"])
      assertTrue(
        response.bodyAsText().contains("<meta name=\"robots\" content=\"noindex, nofollow\">"),
        "missing the robots meta for a crawler that reads the body",
      )
    }

  @Test
  fun `the three headers ride the 404 too, so a dead link leaks nothing either`() =
    testApplication {
      application {
        testPublicWebModule(costReportSource = liveSource())
      }

      val response = client.get("/report?token=never-minted")

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
      assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
      assertEquals("no-referrer", response.headers["Referrer-Policy"])
    }

  @Test
  fun `a read fault renders the branded 503, never a dead link`() =
    testApplication {
      application {
        testPublicWebModule(costReportSource = FakeCostReportSource(FakeReportAnswer.Fault(RuntimeException("db fault"))))
      }

      val response = client.get("/report?token=$TEST_LIVE_TOKEN")

      assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
      assertTrue(response.bodyAsText().contains("503 Service Unavailable"), "missing the branded 503 marker")
    }

  /**
   * TWO credentials presented is a request we have no reading of, so it is a
   * miss — never "the first one, and some noise". `queryParameters[name]` is
   * `getAll(name).firstOrNull()`, so `?token=junk&token=<live>` used to resolve
   * the live one.
   */
  @Test
  fun `a repeated token parameter is a miss, and the port is never asked`() =
    testApplication {
      val source = liveSource()
      application {
        testPublicWebModule(costReportSource = source)
      }

      val repeated = client.get("/report?token=junk&token=$TEST_LIVE_TOKEN")
      val unknown = client.get("/report?token=never-minted")

      assertEquals(HttpStatusCode.NotFound, repeated.status, "two presented credentials resolve to nothing")
      assertEquals(
        unknown.bodyAsText(),
        repeated.bodyAsText(),
        "the 404 body must be byte-identical whatever the reason for the miss",
      )
      assertEquals(listOf("never-minted"), source.tokensSeen, "a repeated parameter never reaches the port")
    }

  /**
   * The reason a link resolved to nothing is LOG ONLY. The body is the proof:
   * every miss renders the same bytes, so nothing about it tells a stranger
   * holding a dead link that the token was once real.
   */
  @Test
  fun `the miss reason is logged and the rendered 404 body is unchanged by it`() {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.level = ch.qos.logback.classic.Level.DEBUG
    root.addAppender(appender)
    val bodies = mutableListOf<String>()
    try {
      testApplication {
        application {
          testPublicWebModule(costReportSource = liveSource())
        }

        bodies += client.get("/report?token=never-minted").bodyAsText()
        bodies += client.get("/report").bodyAsText()
        bodies += client.get("/report?token=a&token=b").bodyAsText()
      }
    } finally {
      root.detachAppender(appender)
      root.level = ch.qos.logback.classic.Level.INFO
    }

    assertEquals(1, bodies.toSet().size, "the 404 body must not vary with the reason: [$bodies]")
    val reasons = appender.list.map { it.formattedMessage }.filter { it.contains("resolved to nothing") }
    assertTrue(reasons.any { it.contains("REPEATED_TOKEN_PARAM") }, "the repeated-parameter miss was not logged: [$reasons]")
    assertTrue(reasons.any { it.contains("BLANK_TOKEN") }, "the absent-token miss was not logged: [$reasons]")
  }

  /**
   * A 5xx with no log entry must never happen — and the log must carry the PATH
   * and never the query, because the query of this route is a live credential.
   */
  @Test
  fun `a read fault is logged with its cause, and never with the token`() {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
      testApplication {
        application {
          testPublicWebModule(costReportSource = FakeCostReportSource(FakeReportAnswer.Fault(RuntimeException("db fault"))))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/report?token=SECRET").status)
      }
    } finally {
      root.detachAppender(appender)
    }

    val failure = appender.list.single { it.formattedMessage.contains("public-web call failed") }
    assertTrue(failure.formattedMessage.contains("path=[/report]"), "the path is missing: [${failure.formattedMessage}]")
    assertFalse(failure.formattedMessage.contains("SECRET"), "the raw share token reached the error log")
    assertTrue(failure.throwableProxy?.message == "db fault", "the cause was discarded: [${failure.throwableProxy?.message}]")
  }

  @Test
  fun `the request log redacts the share token and never writes the raw value`() {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
      testApplication {
        application {
          testPublicWebModule(costReportSource = liveSource(token = "SECRET"))
        }

        assertEquals(HttpStatusCode.OK, client.get("/report?token=SECRET").status)
      }
    } finally {
      root.detachAppender(appender)
    }

    val line = appender.list.map { it.formattedMessage }.single { it.contains(" -> ") }
    assertTrue(line.contains("/report?token=[redacted]"), "the token was not redacted: [$line]")
    assertFalse(line.contains("SECRET"), "the raw share token reached the log: [$line]")
  }
}
