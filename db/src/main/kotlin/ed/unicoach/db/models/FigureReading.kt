package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.dao.CorruptPersistedValueException

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

  companion object {
    /**
     * The reverse of the write (RFC 166, D1): rebuilds one reading from the
     * two stored columns a fact row carries. Exhaustive over [FigureStatus]
     * with no `else`, so a seventh status fails to COMPILE here rather than
     * silently landing in a catch-all arm.
     *
     * The DB CHECKs already forbid both invalid pairings
     * (`price_figures_value_iff_status_check`, `0083:206-208`); the Kotlin
     * refusal exists so a hand-written fixture -- or any writer that bypassed
     * the sealed type -- cannot produce a reading the type system says is
     * impossible. [column] and [naturalKey] are the caller's context, in the
     * shared message shape: the column that is corrupt, and the row it is in
     * (the `requireStoredValueWhenAnswered` precedent).
     */
    fun <T : Any> of(
      value: T?,
      status: FigureStatus,
      column: String,
      naturalKey: String,
    ): FigureReading<T> =
      when (status) {
        FigureStatus.REPORTED -> present(value, ValueBearingStatus.REPORTED, column, naturalKey)
        FigureStatus.IMPUTED_BY_PUBLISHER -> present(value, ValueBearingStatus.IMPUTED_BY_PUBLISHER, column, naturalKey)
        FigureStatus.NOT_REPORTED_BY_INSTITUTION -> absent(value, AbsenceStatus.NOT_REPORTED_BY_INSTITUTION, column, naturalKey)
        FigureStatus.NOT_APPLICABLE -> absent(value, AbsenceStatus.NOT_APPLICABLE, column, naturalKey)
        FigureStatus.SUPPRESSED_BY_PUBLISHER -> absent(value, AbsenceStatus.SUPPRESSED_BY_PUBLISHER, column, naturalKey)
        FigureStatus.NOT_COLLECTED_BY_US -> absent(value, AbsenceStatus.NOT_COLLECTED_BY_US, column, naturalKey)
      }

    /** A value-bearing status with no stored value is row corruption, never a silent absence. */
    private fun <T : Any> present(
      value: T?,
      bearing: ValueBearingStatus,
      column: String,
      naturalKey: String,
    ): FigureReading<T> =
      Present(
        value ?: throw CorruptPersistedValueException(
          "null",
          ValidationError.InvalidFormat(expected = "a value present when status is [${bearing.status.value}]"),
          location = "$column (row [$naturalKey])",
        ),
        bearing,
      )

    /** A stored value under a value-free status is the same corruption from the other side. */
    private fun <T : Any> absent(
      value: T?,
      absence: AbsenceStatus,
      column: String,
      naturalKey: String,
    ): FigureReading<T> {
      if (value != null) {
        throw CorruptPersistedValueException(
          value.toString(),
          ValidationError.InvalidFormat(expected = "no value when status is [${absence.status.value}]"),
          location = "$column (row [$naturalKey])",
        )
      }
      return Absent(absence)
    }
  }

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
  ;

  companion object {
    /**
     * The value-bearing half of [status], or null when [status] is not one --
     * the partition's own inverse, on the partition.
     *
     * TOTAL, and null rather than a throw: a caller reading a seed cell knows
     * the file and line and can name the defect; this type knows neither, so
     * raising here would replace a located error with an anonymous one.
     */
    fun ofOrNull(status: FigureStatus): ValueBearingStatus? = entries.firstOrNull { it.status == status }
  }
}

/** The value-free members of [FigureStatus], as a type; a test pins the partition to the enum. */
enum class AbsenceStatus(
  val status: FigureStatus,
) {
  NOT_REPORTED_BY_INSTITUTION(FigureStatus.NOT_REPORTED_BY_INSTITUTION),
  NOT_APPLICABLE(FigureStatus.NOT_APPLICABLE),
  SUPPRESSED_BY_PUBLISHER(FigureStatus.SUPPRESSED_BY_PUBLISHER),
  NOT_COLLECTED_BY_US(FigureStatus.NOT_COLLECTED_BY_US),
  ;

  companion object {
    /** The value-free half of [status], or null when [status] bears one; see [ValueBearingStatus.ofOrNull]. */
    fun ofOrNull(status: FigureStatus): AbsenceStatus? = entries.firstOrNull { it.status == status }
  }
}
