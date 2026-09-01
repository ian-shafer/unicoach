package ed.unicoach.db.models

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * The three living arrangements a published price is quoted for, and the one
 * home for that vocabulary (RFC 152 D1).
 *
 * It was born in `:service` beside `CostBreakdown` (RFC 149), where it also
 * carried the `CostField` components each arrangement is made of. RFC 152 gives
 * the arrangement a persisted home too — `money_profiles.living_plan` (the
 * family's usual plan) and `college_list_entries.living_plan` (that one
 * school's override) — and `:db` cannot depend on `:service`. So the vocabulary
 * moves down here, on the [InstitutionControl] precedent, and the component
 * mapping stays up in the cost domain as an extension, which is `:service`'s
 * business and no part of what the column stores.
 *
 * Exactly one enum, as CLAUDE.md's schema convention requires: `TEXT` +
 * `CHECK IN (...)` in the schema, and one Kotlin enum with [value] and a
 * [fromValue] companion here. A second enum would give one concept two
 * vocabularies — the precise harm [IncomeBand.bracket] and [label] exist to
 * prevent.
 */
enum class LivingArrangement(
  /**
   * The stored value, and the key the cost surfaces put on the wire. The
   * `money_profiles_living_plan_check` and
   * `college_list_entries_living_plan_check` CHECK constraints list exactly
   * these three strings.
   */
  val value: String,
  /**
   * The way of living in the words a student says it — the spoken twin of
   * [value], beside it in the one home for this vocabulary
   * ([InstitutionControl.label] precedent).
   *
   * It lives here rather than in whichever construct happens to speak an
   * arrangement aloud, so a wire key can never be read out to a family and two
   * sentences can never call the same arrangement two different things.
   */
  val label: String,
) {
  ON_CAMPUS("on_campus", "living on campus"),
  OFF_CAMPUS("off_campus", "renting off campus"),
  WITH_FAMILY("with_family", "living at home"),
  ;

  companion object {
    fun fromValue(value: String): LivingArrangement? = entries.find { it.value == value }
  }
}

/**
 * Writes the plan pair — `living_plan` (the machine code) and
 * `living_plan_label` ([LivingArrangement.label], the words a student says it
 * in) — into the object being built (RFC 152, [putIncomeBand]'s rule applied to
 * the third money-profile field).
 *
 * The pair has exactly one emitter on purpose: every model-facing surface that
 * names a plan names it in the student's own words too, and no site can
 * half-fire by writing the code and forgetting the label. Whenever a plan
 * reaches the wire, it reaches it through here.
 */
fun JsonObjectBuilder.putLivingPlan(plan: LivingArrangement) {
  put("living_plan", plan.value)
  put("living_plan_label", plan.label)
}
