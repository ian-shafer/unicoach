package ed.unicoach.coaching.admissions

import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.Share
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The one home for the merit-aid section's wire shape and its copy (RFC 148 D4,
 * D7). Both `college_admissions_profile` and `college_cost_profile` render this
 * same sub-object, and it must read identically in both: a share whose
 * denominator rides in its own key and again in a label emitted from the same
 * construct as the number (RFC 142's "leave no vacuum" rule), so no call site
 * can put the figure in the model's context without the population it is over.
 *
 * Every measure is omitted when the school did not report it, and nothing here
 * ever computes a figure the school did not publish.
 */
object MeritAidWire {
  /** The section key, shared by both tools. */
  const val KEY: String = "merit_aid"

  // Every numeric key names its unit, and the unit comes LAST: the two counts
  // are people, the share rides on the CDS's own 0-100 scale (`Share.percent`),
  // and the average is whole US dollars. The section key itself carries no
  // unit -- it keys an OBJECT, and a container has no unit to name.
  const val FULL_TIME_FRESHMEN_KEY: String = "full_time_freshmen_headcount"
  const val RECIPIENTS_KEY: String = "non_need_merit_recipients_headcount"
  const val SHARE_KEY: String = "share_of_all_full_time_freshmen_percent"
  const val AVERAGE_KEY: String = "average_non_need_aid_usd"

  /**
   * The two spoken-sentence keys. Named because BOTH tool descriptions point a
   * sentence at them ("say it the way share_label says it"): a bare literal
   * restated in the copy would let a renamed key leave the instruction pointing
   * at a key that no longer exists, with nothing failing.
   */
  const val SHARE_LABEL_KEY: String = "share_label"
  const val AVERAGE_LABEL_KEY: String = "average_label"

  /** The keys under this section whose value is a NUMBER by contract (the RFC 143 guard's allowlist). */
  val NUMERIC_KEYS: Set<String> = setOf(FULL_TIME_FRESHMEN_KEY, RECIPIENTS_KEY, SHARE_KEY, AVERAGE_KEY)

  /**
   * The merit section. A school that reports nothing under it never reaches
   * here at all -- [MeritPractice.from] returns null and the caller names
   * [AdmissionsField.MERIT_AID] in `data_availability` -- so this renderer
   * always has at least one merit measure to put on the wire.
   */
  fun objectOf(merit: MeritPractice): JsonObject =
    buildJsonObject {
      merit.fullTimeFreshmen?.let { put(FULL_TIME_FRESHMEN_KEY, it) }
      merit.nonNeedMeritRecipients?.let { put(RECIPIENTS_KEY, it) }
      // The share and its sentence are emitted together or not at all: the
      // number is meaningless without the population it is over, and the
      // population is the one thing a reader will assume wrongly.
      merit.shareOfAllFullTimeFreshmen?.let { share ->
        put(SHARE_KEY, share.percent)
        put(SHARE_LABEL_KEY, shareLabel(share))
      }
      merit.averageNonNeedAid?.let { amount ->
        put(AVERAGE_KEY, amount)
        put(AVERAGE_LABEL_KEY, averageLabel(amount))
      }
      putJsonObject("source") { putCitation(merit.source) }
    }

  /**
   * The spoken share. The population is IN the sentence, because the tempting
   * reading -- a share of the students who have no financial need -- is a
   * different statistic that the CDS does not report and nobody measured.
   */
  fun shareLabel(share: Share): String = "${share.spoken()}% of all full-time freshmen received non-need (merit) aid"

  /**
   * The spoken average. "Non-need aid" is money given for something other than
   * financial need; it is a grant, so the sentence says plainly that it is not
   * repaid, and it is deliberately NOT called an award (RFC 141's glossary).
   * It is also not an offer to this student and must never be subtracted from
   * a published price.
   */
  fun averageLabel(amount: Int): String =
    "the average non-need (merit) aid was ${WholeDollars.spoken(amount)} - a grant the student never pays back, " +
      "reported for last year's class, not an offer to this student"
}
