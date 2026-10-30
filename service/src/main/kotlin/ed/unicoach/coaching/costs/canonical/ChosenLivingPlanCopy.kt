package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.ArrangementGap
import ed.unicoach.coaching.costs.ChosenLivingPlan
import ed.unicoach.coaching.costs.NoTotalReason

/**
 * The sentence a coach says for a resolved living plan that carries no total
 * at a school -- one home for it, beside [FigureStatusCopy] and
 * [MoneySourceCopy], so every surface that says why a chosen plan has no
 * total (the cost picture's `chosen_living_arrangement`, and the money plan's
 * `published_price_availability`, RFC 261) says it in the same words.
 *
 * Copy, not a renderer: it writes no JSON key and reads no store, so the cost
 * tool and the money plan both read it rather than one borrowing the other's
 * privates.
 */
object ChosenLivingPlanCopy {
  /**
   * The sentence for a resolved plan this school shows but cannot total.
   *
   * One sentence per [NoTotalReason], because the causes are not one fact: a gap
   * of OURS says so and says what would close it, and only the school's own
   * silence is stated as the school's. Saying "this school has no total for that
   * way of living" because WE have not asked which state the student lives in
   * would blame a price list for our own question (RFC 149 D-B).
   *
   * Every case ends the same way: quote the parts that are here, and never add
   * up what is there and call it the total.
   */
  fun statementOf(chosen: ChosenLivingPlan.NoTotalHere): String =
    when (chosen.reason) {
      NoTotalReason.AWAITING_RESIDENCY_ANSWER -> {
        "The student plans on ${chosen.plan.label}, and this school's published price depends on which state " +
          "the student is a resident of - which we have not been told, so the gap is ours and not this " +
          "school's. Ask where the student is a resident and the total follows; meanwhile quote the parts " +
          "that are here and never add up what is there and call it the total."
      }

      NoTotalReason.TUITION_APPLICABILITY_UNKNOWN -> {
        "The student plans on ${chosen.plan.label}, but we cannot tell which of this school's published " +
          "prices applies to this student, so the missing total is ours and not this school's. Quote the " +
          "parts that are here and never add up what is there and call it the total."
      }

      NoTotalReason.PART_NOT_PUBLISHED -> {
        "The student plans on ${chosen.plan.label}, but this school does not publish every part of what that " +
          "way of living costs, so there is no total for it. Quote the parts of it that are here, say which " +
          "part is missing, and never add up what is there and call it the total."
      }

      // OURS. The school is not named, because it may publish the part
      // perfectly well and we are the ones who do not hold it for the year the
      // rest of this school's price is quoted at (RFC 166 §3, §6).
      NoTotalReason.PART_NOT_COLLECTED_BY_US -> {
        "The student plans on ${chosen.plan.label}, but we have not collected every part of what that way of " +
          "living costs at this school, so there is no total for it. The gap is ours, not this school's - " +
          "never say the school does not publish it. Quote the parts of it that are here and never add up " +
          "what is there and call it the total."
      }
    }

  /**
   * The sentence for a resolved plan this school is not priced for. Two
   * reasons, two sentences (the [ArrangementGap] split RFC 151 landed): a
   * school with no residence halls has ANSWERED, and a school that published no
   * figure has stayed silent. Neither becomes a substituted arrangement.
   */
  fun statementOf(chosen: ChosenLivingPlan.NotPricedHere): String {
    // The gap's own spoken phrase ([ArrangementGap.phrase]), never a second
    // wording of it: the comparison says the same silence the same way.
    return "The student plans on ${chosen.plan.label}, but this school has ${chosen.reason.phrase}. Say that " +
      "plainly, and never quote another way of living in its place or carry a figure across from another school."
  }
}
