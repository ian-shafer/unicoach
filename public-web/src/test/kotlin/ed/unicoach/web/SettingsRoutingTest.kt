package ed.unicoach.web

import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.User
import ed.unicoach.web.auth.CHANGE_EMAIL_PATH
import ed.unicoach.web.render.CANCELLING_NOTE
import ed.unicoach.web.render.CHANGE_EMAIL_HEADING
import ed.unicoach.web.render.COACHING_USAGE_UNAVAILABLE
import ed.unicoach.web.render.COACHING_USED_LABEL
import ed.unicoach.web.render.EMAIL_HEADING
import ed.unicoach.web.render.FREE_ALLOWANCE_CAPTION
import ed.unicoach.web.render.FREE_ALLOWANCE_SENTENCE
import ed.unicoach.web.render.MANAGE_SUBSCRIPTION_LABEL
import ed.unicoach.web.render.MANAGE_SUBSCRIPTION_URL
import ed.unicoach.web.render.SETTINGS_HEADING
import ed.unicoach.web.render.SIGNED_IN_AS_HEADING
import ed.unicoach.web.render.SUBSCRIBE_ON_IPHONE
import ed.unicoach.web.render.SUPPORTER_APP_NOTE
import ed.unicoach.web.render.YOUR_COACHING_HEADING
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings (RFC 273): the page at `/account`, its coaching meter read from the
 * REAL `BudgetService` over the real database — the same read the coach's
 * gate makes — and the words each basis gets.
 */
class SettingsRoutingTest {
  @BeforeEach
  fun reset() {
    WebAuthTestSupport.resetDatabase()
  }

  private val periodEnd = WebAuthTestSupport.seededPeriodEnd()

  @Test
  fun `a fresh student reads Settings, who they are, an empty meter and the free-allowance words`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, _, token) = signedInStudent()

      val page = settingsPage(token)

      assertEquals(SETTINGS_HEADING, page.selectFirst("main h1")?.text())
      assertEquals(
        listOf(SIGNED_IN_AS_HEADING, YOUR_COACHING_HEADING, EMAIL_HEADING),
        page.select("main h2").map(Element::text),
        "the three sections, in the page's order",
      )
      val meter = meterOf(page)
      assertEquals("0", meter.attr("aria-valuenow"))
      assertEquals(COACHING_USED_LABEL, meter.selectFirst(".usage-meter-label")?.text())
      assertEquals("0%", meter.selectFirst(".usage-meter-value")?.text())
      assertEquals(FREE_ALLOWANCE_CAPTION, meter.selectFirst(".usage-meter-caption")?.text())
      val main = page.selectFirst("main")!!.text()
      assertTrue(main.contains(FREE_ALLOWANCE_SENTENCE), main)
      assertNull(manageLinkOf(page), "a free-tier student was offered Apple's subscription management")
      assertFalse(main.contains(CANCELLING_NOTE), main)
      assertEquals(CHANGE_EMAIL_HEADING, page.selectFirst("main a[href=\"$CHANGE_EMAIL_PATH\"]")?.text(), "the change-email link")
    }
  }

  /** The page and the service agree on the number: the percent is `Entitlement`'s, not the page's own arithmetic. */
  @Test
  fun `a seeded spend of half the allowance renders the percent the entitlement reports`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, studentId, token) = signedInStudent()
      val allowance = BudgetConfig.from(WebAuthTestSupport.config).getOrThrow().freeAllowance
      WebAuthTestSupport.seedSpend(studentId, Nanodollars.of(allowance.value / 2))
      val reported = runBlocking { WebAuthTestSupport.defaultBudgetService.entitlement(studentId).getOrThrow() }.usedPercent
      assertEquals(50, reported, "half the allowance is not half the meter: the fixture spent something else")

      val meter = meterOf(settingsPage(token))

      assertEquals("$reported", meter.attr("aria-valuenow"))
      assertEquals("$reported%", meter.selectFirst(".usage-meter-value")?.text())
      assertEquals("$reported", meter.selectFirst("progress")?.attr("value"))
    }
  }

  @Test
  fun `an exhausted student reads 100% and the way on, which names the iPhone app and no web purchase`() {
    testApplication {
      application { testPublicWebModule(budgetService = WebAuthTestSupport.budgetService("0.00")) }
      val (_, _, token) = signedInStudent()

      val page = settingsPage(token)

      assertEquals("100", meterOf(page).attr("aria-valuenow"))
      assertEquals("100%", meterOf(page).selectFirst(".usage-meter-value")?.text())
      val main = page.selectFirst("main")!!.text()
      assertTrue(
        main.contains("You've used your free coaching. $SUBSCRIBE_ON_IPHONE"),
        main,
      )
      assertNull(manageLinkOf(page), "a free-tier student was offered Apple's subscription management")
    }
  }

  @Test
  fun `a subscriber reads the reset date in Eastern, the manage link, and never the iPhone way on`() {
    testApplication {
      application { testPublicWebModule() }
      val (user, _, token) = signedInStudent()
      WebAuthTestSupport.seedSubscription(user.id, periodEnd = periodEnd.instant)

      val page = settingsPage(token)

      val meter = meterOf(page)
      assertEquals("0", meter.attr("aria-valuenow"))
      assertEquals("Resets ${periodEnd.resetDate}", meter.selectFirst(".usage-meter-caption")?.text())
      val main = page.selectFirst("main")!!.text()
      assertTrue(main.contains("Your monthly subscription runs to ${periodEnd.resetDate}, and your coaching allowance resets then."), main)
      val manage = assertNotNull(manageLinkOf(page), "no manage link on a subscriber's Settings")
      assertEquals(MANAGE_SUBSCRIPTION_LABEL, manage.text())
      assertEquals("noopener", manage.attr("rel"))
      assertTrue(main.contains("$MANAGE_SUBSCRIPTION_LABEL $CANCELLING_NOTE"), main)
      assertFalse(main.contains(SUBSCRIBE_ON_IPHONE), "a subscriber was told to subscribe: $main")
    }
  }

  @Test
  fun `a subscriber who spent the period reads when it renews`() {
    testApplication {
      application { testPublicWebModule() }
      val (user, studentId, token) = signedInStudent()
      WebAuthTestSupport.seedSubscription(user.id, periodEnd = periodEnd.instant)
      WebAuthTestSupport.seedSpend(studentId, WebAuthTestSupport.periodBudget())

      val page = settingsPage(token)

      assertEquals("100", meterOf(page).attr("aria-valuenow"))
      val main = page.selectFirst("main")!!.text()
      assertTrue(
        main.contains("You've used this period's coaching. It resets ${periodEnd.resetDate}, when your subscription renews."),
        main,
      )
      assertNotNull(manageLinkOf(page), "no manage link on a subscriber's Settings")
      assertFalse(main.contains(SUBSCRIBE_ON_IPHONE), "a subscriber was told to subscribe: $main")
    }
  }

  @Test
  fun `a supporter reads where their product is and no coaching section`() {
    testApplication {
      application { testPublicWebModule() }
      val supporter = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      runBlocking { WebAuthTestSupport.supporterService.ensure(supporter.id).getOrThrow() }
      val token = WebAuthTestSupport.login(supporter.email.value)

      val page = settingsPage(token)

      assertEquals(SETTINGS_HEADING, page.selectFirst("main h1")?.text())
      assertEquals(listOf(SIGNED_IN_AS_HEADING, EMAIL_HEADING), page.select("main h2").map(Element::text))
      assertNull(page.selectFirst(".usage-meter"), "a supporter was shown a coaching meter")
      assertTrue(page.selectFirst("main")!!.text().contains(SUPPORTER_APP_NOTE))
    }
  }

  @Test
  fun `a failed budget read renders the page with the unavailable line, never a 503`() {
    testApplication {
      application { testPublicWebModule(budgetService = WebAuthTestSupport.unreachableBudgetService()) }
      val (user, _, token) = signedInStudent()

      val page = settingsPage(token)

      assertEquals(SETTINGS_HEADING, page.selectFirst("main h1")?.text())
      assertEquals(listOf(SIGNED_IN_AS_HEADING, YOUR_COACHING_HEADING, EMAIL_HEADING), page.select("main h2").map(Element::text))
      val main = page.selectFirst("main")!!.text()
      assertTrue(main.contains(COACHING_USAGE_UNAVAILABLE), main)
      assertNull(page.selectFirst(".usage-meter"), "a meter rendered from a read that failed")
      assertTrue(main.contains(user.email.value), "the rest of the page did not render")
      assertEquals(CHANGE_EMAIL_HEADING, page.selectFirst("main a[href=\"$CHANGE_EMAIL_PATH\"]")?.text(), "the change-email link")
    }
  }

  // --- helpers ----------------------------------------------------------------------

  /** A verified user with a `students` row, signed in: the user, their student id and their session token. */
  private fun signedInStudent(): Triple<User, StudentId, String> {
    val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
    val studentId = WebAuthTestSupport.seedStudent(user)
    return Triple(user, studentId, WebAuthTestSupport.login(user.email.value))
  }

  /** `GET /account` as [token], asserted `200` and parsed. */
  private suspend fun ApplicationTestBuilder.settingsPage(token: String): Document {
    val response = client.get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return Jsoup.parse(response.bodyAsText())
  }

  /** The one meter on [page], asserted to be an accessible meter from 0 to 100 named for what it measures. */
  private fun meterOf(page: Document): Element {
    val meters = page.select(".usage-meter")
    assertEquals(1, meters.size, "expected one meter: ${page.selectFirst("main")?.html()}")
    val meter = meters.single()
    assertEquals("meter", meter.attr("role"))
    assertEquals(COACHING_USED_LABEL, meter.attr("aria-label"))
    assertEquals("0", meter.attr("aria-valuemin"))
    assertEquals("100", meter.attr("aria-valuemax"))
    assertEquals("100", meter.selectFirst("progress")?.attr("max"))
    assertEquals(meter.attr("aria-valuenow"), meter.selectFirst("progress")?.attr("value"), "the bar and the meter disagree")
    return meter
  }

  /** The link to Apple's subscription management, by its exact href, or null when the page offers none. */
  private fun manageLinkOf(page: Document): Element? = page.selectFirst("main a[href=\"$MANAGE_SUBSCRIPTION_URL\"]")
}
