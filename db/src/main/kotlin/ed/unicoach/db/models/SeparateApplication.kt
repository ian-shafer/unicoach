package ed.unicoach.db.models

/** Closed enum backing `college_tuition_exchanges.separate_application` (RFC 277): does the student have to ask for it? */
enum class SeparateApplication(
  val value: String,
) {
  REQUIRED("required"),
  NOT_REQUIRED("not_required"),

  /** The school's page does not say. */
  UNSTATED("unstated"),
  ;

  companion object {
    fun fromValue(value: String): SeparateApplication? = entries.find { it.value == value }
  }
}
