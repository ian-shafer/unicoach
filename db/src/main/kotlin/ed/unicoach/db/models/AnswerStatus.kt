package ed.unicoach.db.models

/**
 * Tri-state answer status backing `money_profiles.income_band_status`,
 * `money_profiles.residency_status` and `money_profiles.living_plan_status`
 * (RFC 134, third column by RFC 152). `DECLINED` is first-class: the
 * coach can see "asked and declined" and stop asking; `UNANSWERED` is resumable
 * at any time. The schema CHECKs guarantee a value is present exactly when the
 * status is `ANSWERED`.
 */
enum class AnswerStatus(
  val value: String,
) {
  UNANSWERED("unanswered"),
  ANSWERED("answered"),
  DECLINED("declined"),
  ;

  companion object {
    fun fromValue(value: String): AnswerStatus? = entries.find { it.value == value }
  }
}
