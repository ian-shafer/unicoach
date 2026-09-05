package ed.unicoach.db.models

/**
 * Why a canonical money figure has or lacks a value (RFC 158, D3), backing the
 * `figure_statuses` vocabulary table: an absence always has a reason, and a
 * value exists exactly when [valueBearing] says the status carries one.
 *
 * The two value-bearing members are restated literally inside the fact
 * tables' `*_value_iff_status_check` CHECKs (a CHECK cannot subquery the
 * vocabulary table); a test pins the two lists together.
 */
enum class FigureStatus(
  val value: String,
  /** TRUE exactly when a figure with this status carries a value. */
  val valueBearing: Boolean,
) {
  /** The institution reported it and the source published it. */
  REPORTED("reported", true),

  /** The source publisher imputed it rather than receiving it. */
  IMPUTED_BY_PUBLISHER("imputed_by_publisher", true),

  /** The institution reported nothing for this cell (blank / NULL sentinel). */
  NOT_REPORTED_BY_INSTITUTION("not_reported_by_institution", false),

  /** The cell cannot apply to this institution, and the source says so. */
  NOT_APPLICABLE("not_applicable", false),

  /** The publisher suppressed a reported figure (Scorecard `PrivacySuppressed`). */
  SUPPRESSED_BY_PUBLISHER("suppressed_by_publisher", false),

  /** A source we ingest carries the cell and we deliberately skip it; reserved -- the v1 Scorecard fill never emits it (P7). */
  NOT_COLLECTED_BY_US("not_collected_by_us", false),
  ;

  companion object {
    fun fromValue(value: String): FigureStatus? = entries.find { it.value == value }
  }
}
