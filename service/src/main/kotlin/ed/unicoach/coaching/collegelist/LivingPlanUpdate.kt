package ed.unicoach.coaching.collegelist

import ed.unicoach.db.models.LivingArrangement

/**
 * What one call asks of a college-list entry's living-plan override (RFC 152
 * D2a, RFC 164).
 *
 * Three states rather than a nullable [LivingArrangement], because "leave it
 * alone" and "drop it back to the family's usual plan" are two different writes
 * onto the same nullable column and a bare `null` cannot tell them apart. The
 * chat tool and REST both speak this vocabulary, so neither has to read the
 * current value and echo it back to mean "keep".
 */
sealed interface LivingPlanUpdate {
  /**
   * The write rule, named on the type: one place decides what each of the
   * three states writes, so a second caller cannot invent a fourth answer.
   *
   * [stored] is the override on the row the caller already read for the
   * optimistic-concurrency check, not a fresh read.
   */
  fun resolveAgainst(stored: LivingArrangement?): LivingArrangement? =
    when (this) {
      is Set -> plan
      Clear -> null
      Keep -> stored
    }

  /** Store [plan] as this school's override. */
  data class Set(
    val plan: LivingArrangement,
  ) : LivingPlanUpdate

  /** Write NULL: no override, this school uses the family's usual plan. */
  data object Clear : LivingPlanUpdate

  /** Say nothing: whatever is stored stays stored. */
  data object Keep : LivingPlanUpdate

  companion object {
    /**
     * The three-state fold every caller performs once it has read its own two
     * keys: a stated [plan] SETS, [clear] CLEARS, and neither KEEPS.
     *
     * Only the MAPPING is shared. Each caller keeps its own mutual-exclusion
     * refusal and its own wording, because a chat tool and a REST handler
     * report a caller error to different audiences in different shapes; what
     * must not drift is which of the three writes a legal pair means.
     *
     * Precondition: `plan == null || !clear`. A stated [plan] together with
     * [clear] is a caller error, and each wire boundary already refuses it in
     * its own wording before folding; this fold does not get to pick a winner
     * for the illegal pair, so it throws rather than silently preferring SET.
     */
    fun of(
      plan: LivingArrangement?,
      clear: Boolean,
    ): LivingPlanUpdate {
      require(plan == null || !clear) {
        "living plan [$plan] cannot be stated together with clear=[$clear]; " +
          "the caller must refuse this pair before folding it"
      }
      return when {
        plan != null -> Set(plan)
        clear -> Clear
        else -> Keep
      }
    }
  }
}
