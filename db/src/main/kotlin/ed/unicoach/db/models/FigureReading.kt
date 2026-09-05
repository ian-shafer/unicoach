package ed.unicoach.db.models

/**
 * A figure's value and why (RFC 158, D3): a value exists exactly when the
 * status bears one -- by construction, not by convention. The fact-row inputs
 * carry one [FigureReading] instead of an independent nullable value beside a
 * [FigureStatus], so the invalid pairings (a `null` value with `reported`, a
 * value with `suppressed_by_publisher`) do not compile. The DAO derives the
 * two stored columns at bind time; the `*_value_iff_status_check` CHECKs stay
 * as the backstop for any writer that bypasses these types.
 */
sealed interface FigureReading<out T> {
  val status: FigureStatus

  /** A value the source actually carries, with its value-bearing reason. */
  data class Present<T>(
    val value: T,
    val bearing: ValueBearingStatus,
  ) : FigureReading<T> {
    override val status: FigureStatus get() = bearing.status
  }

  /** No value, with the reason there is none. */
  data class Absent(
    val absence: AbsenceStatus,
  ) : FigureReading<Nothing> {
    override val status: FigureStatus get() = absence.status
  }
}

/** The value-bearing members of [FigureStatus], as a type; a test pins the partition to the enum. */
enum class ValueBearingStatus(
  val status: FigureStatus,
) {
  REPORTED(FigureStatus.REPORTED),
  IMPUTED_BY_PUBLISHER(FigureStatus.IMPUTED_BY_PUBLISHER),
}

/** The value-free members of [FigureStatus], as a type; a test pins the partition to the enum. */
enum class AbsenceStatus(
  val status: FigureStatus,
) {
  NOT_REPORTED_BY_INSTITUTION(FigureStatus.NOT_REPORTED_BY_INSTITUTION),
  NOT_APPLICABLE(FigureStatus.NOT_APPLICABLE),
  SUPPRESSED_BY_PUBLISHER(FigureStatus.SUPPRESSED_BY_PUBLISHER),
  NOT_COLLECTED_BY_US(FigureStatus.NOT_COLLECTED_BY_US),
}
