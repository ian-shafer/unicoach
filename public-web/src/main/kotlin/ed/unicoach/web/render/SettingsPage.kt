package ed.unicoach.web.render

import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.coaching.budget.EntitlementBasis
import ed.unicoach.db.models.User
import ed.unicoach.web.auth.CHANGE_EMAIL_PATH
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.dd
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.section

/**
 * The page's heading, and the header link's label: the iPhone app's own word
 * for the screen (RFC 273 D-A). The path stays `/account`.
 */
const val SETTINGS_HEADING = "Settings"

/** The section naming who is signed in. */
const val SIGNED_IN_AS_HEADING = "Signed in as"

/** The student's meter section (RFC 273). */
const val YOUR_COACHING_HEADING = "Your coaching"

/** The section holding the change-email link. */
const val EMAIL_HEADING = "Email"

/** The class the signed-in summary carries, so its definition-list rules find it. */
const val ACCOUNT_CLASS = "account"

/**
 * Where a parent's product is (RFC 254): public-web has no supporter surface, so
 * a supporter account is told it lives in the iPhone app.
 */
const val SUPPORTER_APP_NOTE =
  "unicoach for parents and supporters is in the iPhone app. Sign in there with this email to search " +
    "colleges and see who you're connected to."

/**
 * What the Settings page has to say about the caller's coaching meter: the
 * [Entitlement] read for them, or the fact that the read failed. A failed read
 * is a state of the page and not a 503 (RFC 273 §2, guided not gated), so it
 * is typed here rather than carried as a null that could also mean "not asked".
 */
sealed interface CoachingMeter {
  /** The budget read answered: the same [Entitlement] the coach's gate blocks on. */
  data class Read(
    val entitlement: Entitlement,
  ) : CoachingMeter

  /** The budget read failed; the route logged why. The page says so and renders the rest. */
  data object Unavailable : CoachingMeter
}

/**
 * Whose Settings this is, as far as the coaching section is concerned. A
 * supporter has no coaching surface on the web (RFC 254's note stands), so
 * their page carries the note on where their product is in the section's
 * place; a student's carries the meter — or its unavailability.
 */
sealed interface SettingsCoaching {
  /** A supporter: the iPhone-app note, no meter. */
  data object Supporter : SettingsCoaching

  /** A student: the "Your coaching" section around [meter]. */
  data class Student(
    val meter: CoachingMeter,
  ) : SettingsCoaching
}

/**
 * Settings (RFC 273): who is signed in, the student's coaching meter, and the
 * change-email link.
 *
 * Rendered at [ShellWidth.WIDE] — the first production caller of the seam RFC
 * 210 opened. It is not prose read in lines; it is the shell every later web
 * slice hangs its content in, and the reading measure would crop that to a
 * column before any of it exists.
 *
 * Sign-out is not on the page: the shared header carries it on every page,
 * this one included, and a second control would be a duplicate (RFC 273 §1).
 *
 * [notice] is the post-redirect-get confirmation: every successful POST that
 * lands here answers `303` and the message is rendered by the GET that follows,
 * so a reload cannot resubmit the form that produced it. It is a [WebNotice]
 * and not a sentence, so an unknown query key renders nothing at all.
 */
suspend fun ApplicationCall.respondSettingsPage(
  user: User,
  coaching: SettingsCoaching,
  nav: ShellNav.SignedIn,
  notice: WebNotice? = null,
) {
  respondHtml {
    siteLayout(SETTINGS_HEADING, width = ShellWidth.WIDE, nav = nav) {
      section(ACCOUNT_CLASS) {
        h1 { +SETTINGS_HEADING }
        notice(notice)
        h2 { +SIGNED_IN_AS_HEADING }
        dl {
          dt { +"Name" }
          dd { +user.name.value }
          dt { +"Email" }
          dd { +user.email.value }
        }
        when (coaching) {
          SettingsCoaching.Supporter -> p { +SUPPORTER_APP_NOTE }
          is SettingsCoaching.Student -> coachingSection(coaching.meter)
        }
        h2 { +EMAIL_HEADING }
        p {
          // Label and path both read from the page itself, so this link cannot
          // name it one thing while the page calls itself another.
          a(href = CHANGE_EMAIL_PATH) { +CHANGE_EMAIL_HEADING }
        }
      }
    }
  }
}

/**
 * "Your coaching": the meter the iPhone app shows, the sentence that says what
 * the number means, and — for an Apple-billed subscriber only — the way to
 * where Apple manages the subscription. Every sentence is `CoachingUsage.kt`'s.
 */
private fun FlowContent.coachingSection(meter: CoachingMeter) {
  h2 { +YOUR_COACHING_HEADING }
  when (meter) {
    CoachingMeter.Unavailable -> {
      p { +COACHING_USAGE_UNAVAILABLE }
    }

    is CoachingMeter.Read -> {
      usageMeter(meter.entitlement)
      p { +settingsSentence(meter.entitlement) }
      if (meter.entitlement.basis is EntitlementBasis.Subscription) manageSubscriptionLine()
    }
  }
}
