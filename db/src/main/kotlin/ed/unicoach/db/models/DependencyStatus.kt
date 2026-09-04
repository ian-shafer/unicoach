package ed.unicoach.db.models

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * Federal aid dependency status backing `money_profiles.dependency` (RFC 159):
 * whether the student is dependent or independent for federal student aid.
 * The answer selects which Direct Loan limit table and which max-Pell income
 * test apply, so it is the one money-profile fact the federal-aid surface
 * invites. Tri-state alongside it: `money_profiles.dependency_status` is an
 * [AnswerStatus], and the schema CHECK guarantees a value is stored exactly
 * when that status is `answered`.
 */
enum class DependencyStatus(
  /** The stored value, and the key the aid surfaces put on the wire. */
  val value: String,
  /**
   * The status in the words a coach says it -- the spoken twin of [value]
   * ([LivingArrangement.label] precedent), so a wire code is never read out
   * to a family.
   */
  val label: String,
) {
  DEPENDENT("dependent", "dependent for federal aid"),
  INDEPENDENT("independent", "independent for federal aid"),
  ;

  companion object {
    fun fromValue(value: String): DependencyStatus? = entries.find { it.value == value }
  }
}

/**
 * Writes the dependency pair -- `dependency` (the machine code) and
 * `dependency_label` ([DependencyStatus.label], the words a coach says it in)
 * -- into the object being built ([putIncomeBand]/[putLivingPlan] precedent).
 * The pair has exactly one emitter on purpose: whenever the answer reaches the
 * wire, it reaches it through here, code and spoken words together.
 */
fun JsonObjectBuilder.putDependency(dependency: DependencyStatus) {
  put("dependency", dependency.value)
  put("dependency_label", dependency.label)
}
