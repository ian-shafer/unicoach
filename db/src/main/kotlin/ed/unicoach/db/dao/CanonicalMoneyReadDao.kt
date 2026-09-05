package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.CanonicalMoneyRead
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortMoneyStat
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MeasureUnit
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.PriceFigure
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.UnreadableMoneyRow
import java.sql.ResultSet
import java.util.UUID

/**
 * The read half of the canonical money store (RFC 166, D1): two batched reads
 * over `price_figures` and `cohort_money_stats`, grouped by college.
 *
 * A NEW FILE rather than functions on [CanonicalMoneyDao], deliberately: the
 * read path has its own failure shape -- one undecodable row leaves the batch
 * as data -- which nothing on the write side shares, and the two grew (RFC
 * 161/162) by different hands at the same time.
 *
 * Stateless `object`, the caller owns the [SqlSession] and its transaction --
 * the Family Cost Report reads on the caller's connection. Both reads mirror
 * [CollegesDao.listByIds]'s contract: ONE statement whatever the list size, an
 * empty id list short-circuits with NO query, and ids with no row are simply
 * absent from the map. No latest-year SQL and no window function: a college
 * carries ~48 price rows and ~35 cohort rows (SFA files three aid years), so
 * year selection is a pure Kotlin function a DB-free test can drive.
 *
 * Both answer a [CanonicalMoneyRead]: the rows this build could decode, beside
 * the ones it could not. See [readRows] for why one unreadable row may not fail
 * a whole batch.
 */
object CanonicalMoneyReadDao {
  /**
   * What [UnreadableMoneyRow.location] says when the failure carries no
   * location of its own -- a NAMED state, never the empty string a `.orEmpty()`
   * would leave behind, because a row nobody can find is the one thing the
   * unreadable-row channel exists to prevent.
   */
  private const val UNLOCATED_ROW = "location not recorded"

  /** Every `price_figures` row for [collegeIds], grouped by college, in no particular order. */
  fun listPriceFigures(
    session: SqlSession,
    collegeIds: List<CollegeId>,
  ): Result<CanonicalMoneyRead<PriceFigure>> =
    readRows(
      session = session,
      table = "price_figures",
      columns =
        "college_id, price_concept, residency_basis, arrangement, academic_year, " +
          "amount_usd, status, source, source_variable, publisher_flag",
      collegeIds = collegeIds,
      map = ::mapPriceFigure,
      collegeOf = PriceFigure::collegeId,
    )

  /** Every `cohort_money_stats` row for [collegeIds], grouped by college, in no particular order. */
  fun listCohortStats(
    session: SqlSession,
    collegeIds: List<CollegeId>,
  ): Result<CanonicalMoneyRead<CohortMoneyStat>> =
    readRows(
      session = session,
      table = "cohort_money_stats",
      columns =
        "college_id, measure, population, residency_scope, aid_scope, income_band, " +
          "vintage, value, status, source, source_variable, publisher_flag",
      collegeIds = collegeIds,
      map = ::mapCohortMoneyStat,
      collegeOf = CohortMoneyStat::collegeId,
    )

  /**
   * ONE batched statement, decoded ROW BY ROW (RFC 166): a row this build
   * cannot DECODE leaves the batch as an [UnreadableMoneyRow] instead of
   * failing the read.
   *
   * DECODE is the exact width of the promise, and the catch below is narrowed
   * to it: a stored code no enum reads, a value/status contradiction, a
   * `college_id` that is not a UUID, a `NUMERIC` amount with no whole-dollar
   * form. A JDBC-level fault -- a renamed or retyped
   * column, a dropped connection -- is NOT one row's problem, and deliberately
   * fails the whole read as [Result.failure]: reporting a broken connection as
   * fifty unreadable rows would state something false about the data, and every
   * figure would then be spoken as one we have not collected.
   *
   * The blast radius is what forces this. Both reads are batched over every
   * college a request selected, so a single undecodable cell used to fail the
   * money answer for EVERY school on the list -- a read-failed cost report and
   * a tool error, for one school's one column. The state needs no corruption to
   * reach: the coded columns are `TEXT` + vocabulary FK, so a widened
   * vocabulary plus a loader writing the new value leaves an OLDER running
   * instance unable to read a perfectly legal row. That is deploy skew.
   *
   * A dropped row is not a silence: the address it would have answered simply
   * has no row, so the caller's existing `not_collected_by_us` /
   * `part_not_collected_by_us` machinery states it, and no wrong number is ever
   * printed. [Result.failure] keeps its own meaning -- a broken connection, a
   * missing table, a SQL fault -- which is what the whole batch really may fail
   * on.
   *
   * The DAO does not log: nothing in `:db` does. The unreadable rows travel out
   * as DATA and the service-layer reader warns over them, so the layer that
   * knows whose request it is writes the line.
   */
  private fun <T : Any> readRows(
    session: SqlSession,
    table: String,
    columns: String,
    collegeIds: List<CollegeId>,
    map: (ResultSet) -> T,
    collegeOf: (T) -> CollegeId,
  ): Result<CanonicalMoneyRead<T>> {
    val unreadable = mutableListOf<UnreadableMoneyRow>()
    return session
      .queryListWhereIn(
        table = table,
        columns = columns,
        keyColumn = "college_id",
        keys = collegeIds,
        bindKey = { stmt, i, id -> stmt.setObject(i, id.value) },
        map = { rs ->
          try {
            map(rs)
          } catch (e: CorruptPersistedValueException) {
            // The ONE boundary: an unknown enum slug (decode), a value/status
            // contradiction (FigureReading.of), a non-UUID key ([collegeIdOf])
            // and an amount no dollar figure can hold ([cohortValueOf]) all
            // land here, and each costs one row.
            //
            // The exception travels WHOLE: its own [location] names the cell
            // (never its rendered message, which would make this DAO's wording
            // a contract of a value type two modules read), and the throwable
            // itself rides along so the service layer's warning can log it.
            unreadable +=
              UnreadableMoneyRow(
                table = table,
                stored = e.value,
                location = e.location ?: UNLOCATED_ROW,
                cause = e,
              )
            null
          }
        },
      ).onFailure { e ->
        // The batch may still fail for real -- a broken connection, a missing
        // table -- and that failure keeps its own type and its own meaning. But
        // the rows already decoded as unreadable are evidence in hand: they ride
        // out with the fault as suppressed detail rather than being dropped,
        // because deploy skew is exactly what an operator is hunting when a SQL
        // fault and corrupt rows show up in the same read.
        if (unreadable.isNotEmpty()) e.addSuppressed(UnreadableRowsBeforeFailureException(unreadable.toList()))
      }.map { rows -> CanonicalMoneyRead(rows.filterNotNull().groupBy(collegeOf), unreadable.toList()) }
  }

  private fun mapPriceFigure(rs: ResultSet): PriceFigure {
    val collegeId = collegeIdOf(rs, "price_figures")
    val concept = rs.getString("price_concept")
    val residency = rs.getString("residency_basis")
    val arrangement = rs.getString("arrangement")
    val academicYear = rs.getString("academic_year")
    // The natural key of the row, so a corrupt cell can be found straight from the log.
    val key =
      "college_id=[${collegeId.value}] price_concept=[$concept] residency_basis=[$residency] " +
        "arrangement=[$arrangement] academic_year=[$academicYear]"
    return PriceFigure(
      collegeId = collegeId,
      priceConcept = decode(concept, PriceConcept::fromValue, "PriceConcept", "price_figures.[price_concept]", key),
      residencyBasis = decode(residency, ResidencyBasis::fromValue, "ResidencyBasis", "price_figures.[residency_basis]", key),
      arrangement = decode(arrangement, FigureArrangement::fromValue, "FigureArrangement", "price_figures.[arrangement]", key),
      academicYear = academicYear,
      reading =
        FigureReading.of(
          value = rs.getIntOrNull("amount_usd"),
          status = decodeStatus(rs.getString("status"), "price_figures.[status]", key),
          column = "price_figures.[amount_usd]",
          naturalKey = key,
        ),
      source = decode(rs.getString("source"), MoneySource::fromValue, "MoneySource", "price_figures.[source]", key),
      sourceVariable = rs.getString("source_variable"),
      publisherFlag = rs.getString("publisher_flag"),
    )
  }

  private fun mapCohortMoneyStat(rs: ResultSet): CohortMoneyStat {
    val collegeId = collegeIdOf(rs, "cohort_money_stats")
    val measure = rs.getString("measure")
    val population = rs.getString("population")
    val residencyScope = rs.getString("residency_scope")
    val aidScope = rs.getString("aid_scope")
    val incomeBand = rs.getString("income_band")
    val vintage = rs.getString("vintage")
    val key =
      "college_id=[${collegeId.value}] measure=[$measure] population=[$population] " +
        "residency_scope=[$residencyScope] aid_scope=[$aidScope] income_band=[${incomeBand ?: "overall"}] " +
        "vintage=[$vintage]"
    val decodedMeasure = decode(measure, MoneyMeasure::fromValue, "MoneyMeasure", "cohort_money_stats.[measure]", key)
    return CohortMoneyStat(
      collegeId = collegeId,
      measure = decodedMeasure,
      population = decode(population, CohortPopulation::fromValue, "CohortPopulation", "cohort_money_stats.[population]", key),
      residencyScope =
        decode(residencyScope, CohortResidencyScope::fromValue, "CohortResidencyScope", "cohort_money_stats.[residency_scope]", key),
      aidScope = decode(aidScope, CohortAidScope::fromValue, "CohortAidScope", "cohort_money_stats.[aid_scope]", key),
      incomeBand = incomeBand?.let { decode(it, IncomeBand::fromValue, "IncomeBand", "cohort_money_stats.[income_band]", key) },
      vintage = vintage,
      reading =
        FigureReading.of(
          value = cohortValueOf(rs, decodedMeasure, key),
          status = decodeStatus(rs.getString("status"), "cohort_money_stats.[status]", key),
          column = "cohort_money_stats.[value]",
          naturalKey = key,
        ),
      source = decode(rs.getString("source"), MoneySource::fromValue, "MoneySource", "cohort_money_stats.[source]", key),
      sourceVariable = rs.getString("source_variable"),
      publisherFlag = rs.getString("publisher_flag"),
    )
  }

  /**
   * A `cohort_money_stats.value` read back, refused HERE when it has no form
   * the domain can carry.
   *
   * The column is an unconstrained `NUMERIC`, and Postgres admits `'NaN'` and
   * `'Infinity'` in it; a USD-per-year measure is then narrowed to whole
   * dollars above (`CollegeFigures.toWholeDollars`), which REQUIRES a finite,
   * `Int`-range value and throws otherwise -- there is no rounding fallback and
   * no clamp. That refusal stays where it is, but it fires from a property
   * initializer in `:service`, OUTSIDE this read's row boundary,
   * so one impossible cell would fail the money answer for every school in the
   * batch. Refusing it here as an ordinary decode failure keeps the blast
   * radius at one row -- the address it would have answered simply has no row,
   * and the caller's "we have not collected this" machinery states it.
   *
   * The `Int`-range half is asked of USD measures ONLY, exactly as the narrowing
   * downstream is: a [MeasureUnit.SHARE] value is a fraction and is never read
   * as dollars. Non-finite is refused for every measure -- no unit has a use
   * for `NaN` -- but only the USD arm is REACHABLE, and is therefore the only
   * one a test pins: `cohort_money_stats_share_range_check` (schema 0085)
   * already refuses a non-finite share, because `NaN BETWEEN 0 AND 1` is false.
   * The share arm stands as defence against a future measure whose range the
   * store does not bound, not as a state the store admits today.
   */
  private fun cohortValueOf(
    rs: ResultSet,
    measure: MoneyMeasure,
    naturalKey: String,
  ): Double? {
    val value = rs.getDouble("value").takeUnless { rs.wasNull() } ?: return null
    val whollyDollars = value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()
    if (!value.isFinite() || (measure.unit == MeasureUnit.USD_PER_YEAR && !whollyDollars)) {
      throw CorruptPersistedValueException(
        value.toString(),
        ValidationError.InvalidFormat(
          expected =
            if (measure.unit == MeasureUnit.USD_PER_YEAR) {
              "a finite amount with a whole-dollar form"
            } else {
              "a finite ${measure.value} value"
            },
        ),
        location = "cohort_money_stats.[value] (row [$naturalKey])",
      )
    }
    return value
  }

  /**
   * The row's college. A `college_id` the driver does not hand back as a UUID
   * is refused in the SAME vocabulary as a bad code, not as a bare
   * [ClassCastException]: the cast sits inside the row boundary, so a fault
   * here must name its column and cost its own row rather than the whole batch.
   *
   * The natural key is built from columns read AFTER this one, so it cannot be
   * quoted here -- the column and table are what a fault at the key itself can
   * honestly give.
   */
  private fun collegeIdOf(
    rs: ResultSet,
    table: String,
  ): CollegeId {
    val stored = rs.getObject("college_id")
    return CollegeId(
      stored as? UUID
        ?: throw CorruptPersistedValueException(
          stored?.toString() ?: "null",
          ValidationError.InvalidFormat(expected = "a UUID"),
          location = "$table.[college_id] (row not otherwise identified)",
        ),
    )
  }

  private fun decodeStatus(
    stored: String,
    column: String,
    naturalKey: String,
  ): FigureStatus = decode(stored, FigureStatus::fromValue, "FigureStatus", column, naturalKey)

  /**
   * Reconstructs a persisted enum string. Every coded column here is either a
   * vocabulary foreign key or a domain CHECK, so a value the enum does not
   * read is row corruption -- surfaced as a [CorruptPersistedValueException]
   * naming the column and the row, the [MoneyProfilesDao] convention.
   *
   * The throw is per ROW, not per read: [readRows] catches it at the row
   * boundary and carries the row out of the batch, so the column that cannot be
   * read costs its own row and nobody else's.
   */
  private fun <T : Any> decode(
    stored: String,
    fromValue: (String) -> T?,
    domain: String,
    column: String,
    naturalKey: String,
  ): T =
    fromValue(stored)
      ?: throw CorruptPersistedValueException(
        stored,
        ValidationError.InvalidFormat(expected = "a known $domain value"),
        location = "$column (row [$naturalKey])",
      )
}

/**
 * The unreadable rows a batched read had already collected when the read
 * ITSELF failed. Never thrown: it is attached to the real fault as suppressed
 * detail (`readRows`), so the natural keys reach the log with the stack trace
 * that carries them and the fault keeps its own type and error classification.
 */
class UnreadableRowsBeforeFailureException(
  val rows: List<UnreadableMoneyRow>,
) : RuntimeException(
    "the read also skipped ${rows.size} unreadable row(s) before it failed: " +
      rows.joinToString("; ") { "table=[${it.table}] stored=[${it.stored}] at ${it.location}" },
  )
