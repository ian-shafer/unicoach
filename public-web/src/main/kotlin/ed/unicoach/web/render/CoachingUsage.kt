package ed.unicoach.web.render

import ed.unicoach.coaching.COACH_ZONE
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.coaching.budget.EntitlementBasis
import ed.unicoach.common.util.UsDate
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.progress
import kotlinx.html.span
import java.time.Instant

// EVERY SENTENCE ABOUT THE COACHING METER LIVES HERE (RFC 273 §3): the Settings
// page and the chat's out-of-allowance notice both read these, so the two
// surfaces cannot drift apart about what the number means or what to do next.
// Where the iPhone app already says it (PaywallView, SubscriptionView,
// UsageMeter, ManageSubscriptionLink) the words are its; the two web-only
// sentences name the iPhone app as the way to subscribe, because no web
// purchase exists and the copy must not imply one (brief 0013 D18).

/** The heading of the out-of-allowance notice — the iOS paywall's own title. */
const val OUT_OF_ALLOWANCE_TITLE = "Coaching is paused"

/** The meter's visible label, and its accessible name. */
const val COACHING_USED_LABEL = "Coaching used"

/** The class the meter fragment carries, so the stylesheet's rules find it. */
const val USAGE_METER_CLASS = "usage-meter"

/** The caption under a meter that never resets: the lifetime free allowance, and the operator's exemption. */
const val FREE_ALLOWANCE_CAPTION = "One-time free allowance"

/** What Settings says when the budget read failed: guided, never gated (RFC 273 §2). */
const val COACHING_USAGE_UNAVAILABLE = "Coaching usage is unavailable right now."

/** The one sentence an operator-exempt account reads on Settings. */
const val ADMIN_EXEMPT_SENTENCE = "This account is exempt from the coaching meter."

/** Where Apple manages a subscription — the iPhone app's `ManageSubscriptionLink` destination. */
const val MANAGE_SUBSCRIPTION_URL = "https://apps.apple.com/account/subscriptions"

/** The manage link's label. */
const val MANAGE_SUBSCRIPTION_LABEL = "Manage subscription"

/** What follows the manage link: the web cannot cancel for them. */
const val CANCELLING_NOTE = "Cancelling is handled by the App Store."

/** The way on for a family on the free allowance (D18): the iPhone app, in words that promise no web purchase. */
const val SUBSCRIBE_ON_IPHONE = "To keep going, subscribe in the Unicoach app on iPhone. Subscribing on the web isn't available yet."

/** The chat notice's closing line, every basis: the rest of the site is not paused. */
const val EVERYTHING_ELSE_WORKS = "Everything else here still works."

/** The free allowance explained, before it is spent. */
const val FREE_ALLOWANCE_SENTENCE =
  "Free coaching is a one-time allowance. Subscribing for a fresh allowance every month happens in the Unicoach app on iPhone; " +
    "it isn't on the web yet."

/**
 * A reset point in the words the coach says dates in: the coach's own zone, the
 * one calendar rule on the web (RFC 272).
 */
fun resetDate(resetsAt: Instant): String = UsDate.of(resetsAt.atZone(COACH_ZONE).toLocalDate())

/**
 * What a subscriber who spent the period reads first, wherever they read it:
 * the chat notice closes the clause with a full stop, Settings continues it
 * with when the subscription renews (RFC 273 §3). One spelling, so the two
 * surfaces cannot drift apart on the words or the date.
 */
private fun periodSpentClause(resetsAt: Instant): String = "You've used this period's coaching. It resets ${resetDate(resetsAt)}"

/**
 * The sentence for the basis the allowance was spent on — the iOS paywall's
 * sentences (`PaywallCopy`), one per basis. The chat notice's own line, and
 * the first half of the Settings page's free-spent sentence.
 */
internal fun exhaustedSentence(entitlement: Entitlement): String =
  when (val basis = entitlement.basis) {
    EntitlementBasis.FreeAllowance -> "You've used your free coaching."
    is EntitlementBasis.Subscription -> "${periodSpentClause(basis.resetsAt)}."
    EntitlementBasis.AdminExemption -> "You've used your coaching allowance."
  }

/**
 * What the chat notice says to do next, per basis (RFC 273 §3). Only the free
 * allowance has a way on the web can name — the iPhone app — and a subscriber
 * who spent the period must NOT be told to subscribe: their meter resets.
 */
internal fun nextStepSentence(basis: EntitlementBasis): String =
  when (basis) {
    EntitlementBasis.FreeAllowance -> "$SUBSCRIBE_ON_IPHONE $EVERYTHING_ELSE_WORKS"
    is EntitlementBasis.Subscription, EntitlementBasis.AdminExemption -> EVERYTHING_ELSE_WORKS
  }

/** The meter's caption: what kind of allowance the bar measures, and when it resets if it does. */
internal fun meterCaption(basis: EntitlementBasis): String =
  when (basis) {
    EntitlementBasis.FreeAllowance, EntitlementBasis.AdminExemption -> FREE_ALLOWANCE_CAPTION
    is EntitlementBasis.Subscription -> "Resets ${resetDate(basis.resetsAt)}"
  }

/**
 * The Settings page's explanation of the meter (RFC 273 §3): the subscription
 * sheet's sentences for a subscriber — the exact twin of the iPhone surface
 * Settings mirrors — and the web's own for the free allowance.
 */
internal fun settingsSentence(entitlement: Entitlement): String =
  when (val basis = entitlement.basis) {
    EntitlementBasis.FreeAllowance -> {
      if (entitlement.exhausted) "${exhaustedSentence(entitlement)} $SUBSCRIBE_ON_IPHONE" else FREE_ALLOWANCE_SENTENCE
    }

    is EntitlementBasis.Subscription -> {
      if (entitlement.exhausted) {
        "${periodSpentClause(basis.resetsAt)}, when your subscription renews."
      } else {
        "Your monthly subscription runs to ${resetDate(basis.resetsAt)}, and your coaching allowance resets then."
      }
    }

    EntitlementBasis.AdminExemption -> {
      // The service caps this basis at the percentage ceiling, so the gate
      // never exhausts it -- but the state is representable, and if it ever
      // arrives Settings must say what the chat notice says, not "exempt"
      // over a full bar.
      if (entitlement.exhausted) exhaustedSentence(entitlement) else ADMIN_EXEMPT_SENTENCE
    }
  }

/**
 * The ends of [Entitlement.usedPercent]'s scale: the meter's ARIA range and its
 * bar's `max`, named once so the two cannot disagree.
 */
private const val METER_MIN_PERCENT = 0
private const val METER_MAX_PERCENT = 100

/**
 * The meter the iPhone app's `UsageMeter` shows, in HTML: a `role="meter"`
 * carrying the percentage [Entitlement] reports — the same `usedPercent` the
 * coach's gate blocks on, so the bar and the block cannot disagree — its label
 * and value as text, the bar, and the caption.
 *
 * The bar is a `<progress>` rather than a width on a `div`: the CSP serves no
 * inline style, so a fill whose width is set per request has no attribute to
 * live in, while `<progress value max>` draws itself from its attributes. It
 * is hidden from assistive technology because the wrapping meter already
 * carries the number; two readings of one figure would be noise.
 */
fun FlowContent.usageMeter(entitlement: Entitlement) {
  val percent = entitlement.usedPercent.toString()
  div(USAGE_METER_CLASS) {
    attributes["role"] = "meter"
    attributes["aria-label"] = COACHING_USED_LABEL
    attributes["aria-valuemin"] = METER_MIN_PERCENT.toString()
    attributes["aria-valuemax"] = METER_MAX_PERCENT.toString()
    attributes["aria-valuenow"] = percent
    span("usage-meter-label") { +COACHING_USED_LABEL }
    span("usage-meter-value") { +"$percent%" }
    progress {
      attributes["aria-hidden"] = "true"
      value = percent
      max = METER_MAX_PERCENT.toString()
    }
    span("usage-meter-caption") { +meterCaption(entitlement.basis) }
  }
}

/**
 * The way to Apple's subscription management, then the sentence that says why
 * the web offers nothing more: cancelling is the App Store's (RFC 273 §2).
 * `rel="noopener"`: a page on another origin gets no handle on this one.
 */
fun FlowContent.manageSubscriptionLine() {
  p {
    a(href = MANAGE_SUBSCRIPTION_URL) {
      attributes["rel"] = "noopener"
      +MANAGE_SUBSCRIPTION_LABEL
    }
    +" $CANCELLING_NOTE"
  }
}
