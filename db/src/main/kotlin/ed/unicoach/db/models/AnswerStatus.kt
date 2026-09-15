package ed.unicoach.db.models

import java.time.Instant

/**
 * Tri-state answer status backing `money_profiles.income_band_status`,
 * `money_profiles.residency_status`, `money_profiles.living_plan_status` and
 * `money_profiles.dependency_status` (RFC 134, third column by RFC 152, fourth
 * by RFC 159). `DECLINED` is first-class: the coach can see "asked and
 * declined" and stop asking; `UNANSWERED` is resumable at any time. The
 * schema's `value_iff_answered` rule guarantees a value is present exactly when
 * the status is `ANSWERED`.
 *
 * The schema spells this vocabulary in ONE place since RFC 209 — the
 * `answer_status` domain, which is itself `answer_status_or_undecided` minus
 * the fourth value — and this enum is its one Kotlin home. A new table names
 * the domain; it does not retype the words.
 */
enum class AnswerStatus(
  val value: String,
) {
  UNANSWERED("unanswered"),
  ANSWERED("answered"),
  DECLINED("declined"),

  /**
   * "I don't know yet" — a real, common and useful answer at 16, and not the
   * same sentence as [DECLINED] (RFC 205 D16/D30).
   *
   * Reachable ONLY on an intended-subject answer. The three `money_profiles`
   * status CHECKs stay at `('unanswered','answered','declined')`, so the
   * database refuses this value on every money column; the three money OpenAPI
   * enums are therefore `entries` MINUS this one, and every exhaustive `when`
   * over a money status states in a named branch what it does here rather than
   * folding it silently into [DECLINED].
   */
  UNDECIDED("undecided"),
  ;

  companion object {
    fun fromValue(value: String): AnswerStatus? = entries.find { it.value == value }

    /**
     * The answer status of a question whose VALUES live in a sibling table
     * (RFC 209, brief 0010 D24).
     *
     * Where the value sits in the same row, the status is stored and the
     * schema pairs the two. Where it does not, a stored status is a claim the
     * database cannot check — `answered` on the parent row beside zero child
     * rows is a contradiction no CHECK can reach, because the two facts are in
     * different tables. So only the REFUSAL is persisted ([declinedAt]) and
     * "answered" is computed from the rows that exist, which is what
     * CLAUDE.md's schema convention already says about any derived figure.
     *
     * A refusal wins over rows: a family that declined after answering has
     * declined, and the rows are whatever the soft delete left behind.
     */
    fun of(
      declinedAt: Instant?,
      hasValue: Boolean,
    ): AnswerStatus =
      when {
        declinedAt != null -> DECLINED
        hasValue -> ANSWERED
        else -> UNANSWERED
      }

    /**
     * The statuses a `money_profiles` status column may hold: [entries] MINUS
     * [UNDECIDED], which the three money CHECKs forbid.
     *
     * Named once, in declaration order, because several money reads state the
     * same rule and one of them has to say the words. The Swift client declares
     * the same set as `AnswerStatus.moneyCases`, so the two halves cannot drift.
     */
    val moneyCases: List<AnswerStatus> = entries - UNDECIDED
  }
}
