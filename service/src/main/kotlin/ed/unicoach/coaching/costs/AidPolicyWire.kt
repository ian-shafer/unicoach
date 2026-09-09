package ed.unicoach.coaching.costs

import ed.unicoach.coaching.admissions.putCitation
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.Share
import ed.unicoach.db.models.AssuredFigure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The one home for the aid-policy section's wire shape and its copy (RFC 170,
 * D11): what a school asks a family to file, and how it treated need.
 *
 * The rules are [MeritAidWire]'s, for the same reason: every share is emitted
 * WITH the sentence that names its cohort, from the same construct, so no call
 * site can put a percentage in the model's context without the population it is
 * over. The tempting misreadings here are specific and both wrong -- "94% of
 * need met" is not "94% of families get everything they need", and "300
 * students fully met" is not 300 of the freshman class -- so each label says
 * whose number it is.
 *
 * The forms list is what the school's CDS LISTS. There is no "not required"
 * key and there must not be one: no source publishes a negative (D5), so a
 * form's absence is reported once, in words, by [FORMS_NOTE_KEY].
 */
object AidPolicyWire {
  /** The section key. */
  const val KEY: String = "aid_policy"

  // Every numeric key names its unit, unit last -- the MeritAidWire rule.
  const val NEED_MET_SHARE_KEY: String = "average_need_met_percent"
  const val FULLY_MET_SHARE_KEY: String = "share_of_aid_receiving_freshmen_fully_met_percent"
  const val AVERAGE_GRANT_KEY: String = "average_need_based_grant_usd"
  const val AIDED_FRESHMEN_KEY: String = "freshmen_receiving_any_aid_headcount"
  const val FULLY_MET_KEY: String = "freshmen_with_full_need_met_headcount"

  /**
   * What KIND of number each average is, and the sentence to say it in (RFC
   * 179): the slug beside the sentence, the `income_band` + `income_band_label`
   * convention again.
   *
   * Both averages here are a school's own Common Data Set filing, so both are
   * `voluntary_self_report` today -- but the tier is read off the ROW, not
   * asserted from the section's name, and these keys are how a reader learns it
   * without parsing our English. Neither is numeric, so neither joins
   * [NUMERIC_KEYS].
   *
   * The headcounts and the form flags get no tier: the headcounts reach the
   * wire only through a derived share, whose tier would be a claim about two
   * rows at once, and the forms are not money figures.
   */
  const val NEED_MET_ASSURANCE_KEY: String = "average_need_met_assurance"
  const val NEED_MET_ASSURANCE_STATEMENT_KEY: String = "average_need_met_assurance_statement"
  const val AVERAGE_GRANT_ASSURANCE_KEY: String = "average_need_based_grant_assurance"
  const val AVERAGE_GRANT_ASSURANCE_STATEMENT_KEY: String = "average_need_based_grant_assurance_statement"

  /** The spoken sentences, each emitted from the same construct as its number. */
  const val NEED_MET_LABEL_KEY: String = "average_need_met_label"
  const val FULLY_MET_LABEL_KEY: String = "fully_met_label"
  const val AVERAGE_GRANT_LABEL_KEY: String = "average_need_based_grant_label"

  /** The forms this school's CDS lists as required, and the sentence about everything else. */
  const val FORMS_KEY: String = "required_forms"
  const val FORMS_NOTE_KEY: String = "forms_note"

  /** The forms this school's filing answers and WE could not read (D7), with their own sentence. */
  const val FORMS_NOT_COLLECTED_KEY: String = "forms_we_could_not_read"
  const val FORMS_NOT_COLLECTED_NOTE_KEY: String = "forms_we_could_not_read_note"

  /** The keys under this section whose value is a NUMBER by contract (the RFC 143 guard's allowlist). */
  val NUMERIC_KEYS: Set<String> =
    setOf(NEED_MET_SHARE_KEY, FULLY_MET_SHARE_KEY, AVERAGE_GRANT_KEY, AIDED_FRESHMEN_KEY, FULLY_MET_KEY)

  /**
   * The aid-policy section. A school reporting nothing under it never reaches
   * here ([AidPolicyPractice.from] returns null and the caller names the
   * section in `aid_policy_availability`), so this renderer always has a fact.
   */
  fun objectOf(policy: AidPolicyPractice): JsonObject =
    buildJsonObject {
      policy.averageNeedMet?.let { assured ->
        put(NEED_MET_SHARE_KEY, assured.figure.percent)
        put(NEED_MET_LABEL_KEY, needMetLabel(assured.figure))
        // The softest tier's one visible figure (RFC 179): "average percent of
        // need met" is the school's own unaudited claim, and it is emitted from
        // the same construct as its number so no call site can put the figure
        // in the model's context without what kind of number it is.
        putAssurance(assured, NEED_MET_ASSURANCE_KEY, NEED_MET_ASSURANCE_STATEMENT_KEY)
      }
      // The derived share and both of its counts, or none of them: the two
      // headcounts ARE the derivation, and a share without its denominator is
      // the figure this codebase refuses to publish (RFC 148).
      // The counts and the share they derive are ONE value, so the share can
      // never be put without the two numbers its label speaks -- and nothing
      // here has to re-assert a pair the type already holds.
      policy.fullyMetNeed?.let { counts ->
        counts.share?.let { share ->
          put(FULLY_MET_SHARE_KEY, share.percent)
          put(FULLY_MET_LABEL_KEY, fullyMetLabel(share, counts.freshmenAwardedAnyAid, counts.freshmenNeedFullyMet))
          put(AIDED_FRESHMEN_KEY, counts.freshmenAwardedAnyAid)
          put(FULLY_MET_KEY, counts.freshmenNeedFullyMet)
        }
      }
      policy.averageNeedBasedGrantUsd?.let { assured ->
        put(AVERAGE_GRANT_KEY, assured.figure)
        put(AVERAGE_GRANT_LABEL_KEY, averageGrantLabel(assured.figure))
        putAssurance(assured, AVERAGE_GRANT_ASSURANCE_KEY, AVERAGE_GRANT_ASSURANCE_STATEMENT_KEY)
      }
      if (policy.requiredForms.isNotEmpty()) {
        putJsonArray(FORMS_KEY) { policy.requiredForms.forEach { add(it.value) } }
      }
      // OUR gap, said as ours (D7). Without this key the same form would fall
      // into the "not listed in that filing" sentence below -- which is the
      // school's silence, not ours, and the filing does answer it.
      if (policy.notCollectedForms.isNotEmpty()) {
        putJsonArray(FORMS_NOT_COLLECTED_KEY) { policy.notCollectedForms.forEach { add(it.value) } }
        put(FORMS_NOT_COLLECTED_NOTE_KEY, FORMS_NOT_COLLECTED_NOTE)
      }
      // Said for every school with a filing, whether the list is empty or full:
      // the absence of a form is exactly the thing a family will otherwise read
      // as "we do not have to file it" (D5).
      put(FORMS_NOTE_KEY, FORMS_NOTE)
      putJsonObject("source") { putCitation(policy.source) }
    }

  /**
   * One figure's tier and its sentence, from the figure itself.
   *
   * It takes the [AssuredFigure] and not the policy plus a measure, so there is
   * no lookup to miss: the tier travels inside the figure, so a figure that
   * reaches the wire HAS one. The earlier shape read a side-map and returned
   * early when the key was absent, which dropped the tier of a figure it was
   * emitting in the same breath -- silently, and with nothing failing.
   */
  private fun JsonObjectBuilder.putAssurance(
    figure: AssuredFigure<*>,
    assuranceKey: String,
    statementKey: String,
  ) {
    put(assuranceKey, figure.assurance.value)
    put(statementKey, AssuranceTierCopy.statementOf(figure.assurance))
  }

  /**
   * The average share of need met, with the cohort it is over inside the
   * sentence: the freshmen who received need-based aid (CDS H2 line e), NOT
   * the larger group who received any aid at all.
   *
   * "Received", never "awarded": RFC 141 retired the aid-offer sense of that
   * word from every user-facing string. The CDS's own label says "awarded",
   * and that word stays where the source's words belong -- comments, column
   * comments and slugs -- never in copy the coach reads aloud.
   */
  fun needMetLabel(share: Share): String =
    "this school met ${share.spokenPercent()} of assessed need on average, for the freshmen who received " +
      "need-based aid"

  /**
   * The fully-met share, with BOTH of its counts and its cohort inside the
   * sentence.
   *
   * The cohort is the freshmen who received ANY financial aid (CDS H2 line d),
   * which is the line the school reports the fully-met count against -- a
   * larger population than the need-based-grant recipients the two averages
   * are over, and a much smaller one than "freshmen". Saying the two numbers
   * out loud is what stops either misreading.
   */
  fun fullyMetLabel(
    share: Share,
    freshmenReceivingAnyAid: Int,
    freshmenWithFullNeedMet: Int,
  ): String =
    "of the $freshmenReceivingAnyAid first-time full-time freshmen who received any financial aid, " +
      "$freshmenWithFullNeedMet had their full assessed need met -- ${share.spokenPercent()}"

  /** The average grant, with the recipients it is averaged over (line e) inside the sentence. */
  fun averageGrantLabel(amountUsd: Int): String =
    "the freshmen who received a need-based grant got ${WholeDollars.spoken(amountUsd)} in grant aid on average"

  /**
   * The sentence for the forms the filing answers and we could not read.
   * Separate from [FORMS_NOTE] because the two silences are different claims:
   * one is about the school's filing, this one is about us.
   */
  const val FORMS_NOT_COLLECTED_NOTE: String =
    "This school's Common Data Set answers these forms and we could not read its answer, so we do not know " +
      "whether they are required. Say that we could not read it -- never that the school does not require them " +
      "-- and send the family to the school's financial aid office."

  /**
   * The one sentence about what the forms list does NOT say. The Common Data
   * Set has no "not required" answer -- a school lists the forms it requires
   * and leaves the rest of the block blank -- so a form missing here is missing
   * from that filing, and telling a family it is "not required" would be
   * inventing an answer nobody published.
   */
  const val FORMS_NOTE: String =
    "These are the aid forms this school's Common Data Set lists as required of first-year applicants for aid. " +
      "A form that is not listed is not listed in that filing -- which is not the same as the school saying it " +
      "is not required. Tell the family to check with the financial aid office rather than assuming."
}
