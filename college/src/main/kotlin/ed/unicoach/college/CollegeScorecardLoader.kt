package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.DaoException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Re-runnable ingester for a version-pinned College Scorecard CSV pair (RFC 67):
 * the institution-level file and the field-of-study file. It upserts on the
 * natural keys (`unit_id`; `(college_id, cip_code, credential_level)`) so a
 * re-run re-applies the same snapshot with no duplicates.
 *
 * The load is best-effort over the dataset, not all-or-nothing: a row missing a
 * required field, or whose upsert fails with any [DaoException] (a CHECK
 * violation from dirty source data, or a transient fault — both are swallowed
 * identically because the per-row blast radius is one row), is logged with a
 * bracketed warning and skipped so one corrupt line never loses the rest.
 */
class CollegeScorecardLoader(
  private val database: Database,
) {
  private val logger = LoggerFactory.getLogger(CollegeScorecardLoader::class.java)

  /**
   * Per-file counts: rows successfully upserted, and rows whose upsert was skipped
   * split by failure category. A [DaoException] carrying [TransientError] (e.g. a
   * connection/serialization fault) is counted as a transient skip — the snapshot
   * may load more rows on a re-run — while a [PermanentError] (e.g. a CHECK/unique
   * violation from dirty source data) is a permanent skip that re-running will not
   * recover. The split lets a caller tell "retry the ingest" from "this row is
   * permanently corrupt"; the flat per-file [LoadResult] alone could not.
   */
  data class LoadResult(
    val collegesLoaded: Int,
    val programsLoaded: Int,
    val transientSkips: Int = 0,
    val permanentSkips: Int = 0,
  )

  /** Mutable per-file accumulator, folded into [LoadResult] by [load]. */
  private class LoadCount(
    var loaded: Int = 0,
    var transientSkips: Int = 0,
    var permanentSkips: Int = 0,
  ) {
    fun recordSkip(error: Throwable?) {
      if (error is TransientError) transientSkips++ else permanentSkips++
    }
  }

  /**
   * Loads [institutionCsv] then [fieldsCsv]. Institutions are loaded first so a
   * program can resolve its owning college by `UNITID`; a program referencing an
   * unknown institution is skipped with a warning.
   */
  suspend fun load(
    institutionCsv: File,
    fieldsCsv: File,
  ): LoadResult {
    val colleges = loadInstitutions(institutionCsv)
    val programs = loadFields(fieldsCsv)
    return LoadResult(
      collegesLoaded = colleges.loaded,
      programsLoaded = programs.loaded,
      transientSkips = colleges.transientSkips + programs.transientSkips,
      permanentSkips = colleges.permanentSkips + programs.permanentSkips,
    )
  }

  private suspend fun loadInstitutions(file: File): LoadCount =
    database.withConnection { session ->
      val count = LoadCount()
      parse(file).use { records ->
        for (record in records) {
          val newCollege = mapInstitution(record) ?: continue
          val result = upsertWithSavepoint(session) { CollegesDao.upsert(session, newCollege) }
          if (result.isFailure) {
            val error = result.exceptionOrNull()
            count.recordSkip(error)
            logger.warn(
              "Skipping institution row [unit_id={}] [line={}]: [{}]",
              newCollege.unitId,
              record.recordNumber,
              describe(error),
            )
          } else {
            count.loaded++
          }
        }
      }
      count
    }

  private suspend fun loadFields(file: File): LoadCount =
    database.withConnection { session ->
      val count = LoadCount()
      parse(file).use { records ->
        for (record in records) {
          // mapField (incl. its findByUnitId read) runs before the savepoint: the
          // savepoint discipline keeps the transaction unaborted, so the read is
          // always valid, and a null map result is a pre-DB skip (already logged).
          val program = mapField(session, record) ?: continue
          val result = upsertWithSavepoint(session) { CollegesDao.upsertProgram(session, program) }
          if (result.isFailure) {
            val error = result.exceptionOrNull()
            count.recordSkip(error)
            logger.warn(
              "Skipping program row [cip_code={}] [line={}]: [{}]",
              program.cipCode,
              record.recordNumber,
              describe(error),
            )
          } else {
            count.loaded++
          }
        }
      }
      count
    }

  /**
   * Runs one row's [upsert] inside a SQL `SAVEPOINT` so a CHECK/unique violation
   * (or any [DaoException]) rolls back only that row, not the whole file: without
   * a savepoint, PostgreSQL aborts the enclosing transaction on the first failed
   * statement (SQLSTATE `25P02`) and every subsequent row would falsely "skip"
   * and the terminal commit would discard the good rows. On success the savepoint
   * is released; on a failed [Result] it is rolled back to, leaving the
   * transaction usable for the next row.
   */
  private fun <T> upsertWithSavepoint(
    session: SqlSession,
    upsert: () -> Result<T>,
  ): Result<T> {
    session.prepareStatement("SAVEPOINT $ROW_SAVEPOINT").use { it.execute() }
    val result = upsert()
    if (result.isFailure) {
      session.prepareStatement("ROLLBACK TO SAVEPOINT $ROW_SAVEPOINT").use { it.execute() }
    } else {
      session.prepareStatement("RELEASE SAVEPOINT $ROW_SAVEPOINT").use { it.execute() }
    }
    return result
  }

  // ---------------------------------------------------------------------------
  // Row mapping (returns null to skip a row, after logging the reason)
  // ---------------------------------------------------------------------------

  private fun mapInstitution(record: CSVRecord): NewCollege? {
    val unitId = intOrNull(record, "UNITID")
    val name = stringOrNull(record, "INSTNM")
    val city = stringOrNull(record, "CITY")
    val state = stringOrNull(record, "STABBR")
    val control = intOrNull(record, "CONTROL")

    if (unitId == null || name == null || city == null || state == null || control == null) {
      logger.warn(
        "Skipping institution row [line={}]: missing required field " +
          "[unit_id={}] [name={}] [city={}] [state={}] [control={}]",
        record.recordNumber,
        unitId,
        name,
        city,
        state,
        control,
      )
      return null
    }

    // net_price coalesce: public uses NPT4_PUB, all else NPT4_PRIV; both blank => null.
    val netPrice = if (control == 1) intOrNull(record, "NPT4_PUB") else intOrNull(record, "NPT4_PRIV")

    return NewCollege(
      unitId = unitId,
      opeid = stringOrNull(record, "OPEID8"),
      name = name,
      city = city,
      state = state,
      region = intOrNull(record, "REGION"),
      locale = intOrNull(record, "LOCALE"),
      latitude = doubleOrNull(record, "LATITUDE"),
      longitude = doubleOrNull(record, "LONGITUDE"),
      control = control,
      undergradEnrollment = intOrNull(record, "UGDS"),
      admissionRate = doubleOrNull(record, "ADM_RATE"),
      satAvg = intOrNull(record, "SAT_AVG"),
      costAttendance = intOrNull(record, "COSTT4_A"),
      netPrice = netPrice,
      tuitionInState = intOrNull(record, "TUITIONFEE_IN"),
      tuitionOutState = intOrNull(record, "TUITIONFEE_OUT"),
      graduationRate = doubleOrNull(record, "C150_4"),
      medianEarnings = intOrNull(record, "MD_EARN_WNE_P10"),
      pctPell = doubleOrNull(record, "PCTPELL"),
      website = stringOrNull(record, "INSTURL"),
    )
  }

  private fun mapField(
    session: SqlSession,
    record: CSVRecord,
  ): NewCollegeProgram? {
    val unitId = intOrNull(record, "UNITID")
    val cipCode = stringOrNull(record, "CIPCODE")
    val cipTitle = stringOrNull(record, "CIPDESC")
    val credentialLevel = intOrNull(record, "CREDLEV")

    if (unitId == null || cipCode == null || cipTitle == null || credentialLevel == null) {
      logger.warn(
        "Skipping program row [line={}]: missing required field " +
          "[unit_id={}] [cip_code={}] [cip_title={}] [credential_level={}]",
        record.recordNumber,
        unitId,
        cipCode,
        cipTitle,
        credentialLevel,
      )
      return null
    }

    val college = CollegesDao.findByUnitId(session, unitId).getOrNull()
    if (college == null) {
      logger.warn(
        "Skipping program row [line={}]: no college for [unit_id={}]",
        record.recordNumber,
        unitId,
      )
      return null
    }

    return NewCollegeProgram(
      collegeId = college.id,
      cipCode = cipCode,
      cipTitle = cipTitle,
      credentialLevel = credentialLevel,
    )
  }

  // ---------------------------------------------------------------------------
  // CSV parsing + cell coercion
  // ---------------------------------------------------------------------------

  private fun parse(file: File): org.apache.commons.csv.CSVParser =
    CSVFormat.DEFAULT
      .builder()
      .setHeader()
      .setSkipHeaderRecord(true)
      .get()
      .parse(file.bufferedReader())

  /** A trimmed cell, or null when absent or blank (the Scorecard blank-cell idiom). */
  private fun stringOrNull(
    record: CSVRecord,
    column: String,
  ): String? {
    if (!record.isMapped(column)) return null
    val value = record.get(column)?.trim()
    return value?.takeIf { it.isNotEmpty() }
  }

  private fun intOrNull(
    record: CSVRecord,
    column: String,
  ): Int? = stringOrNull(record, column)?.toIntOrNull()

  private fun doubleOrNull(
    record: CSVRecord,
    column: String,
  ): Double? = stringOrNull(record, column)?.toDoubleOrNull()

  /**
   * A bracketed cause description for a skip warning, tagged with the failure
   * category (transient vs permanent) so a human scanning the log can tell a
   * retryable blip from permanently-corrupt source data.
   */
  private fun describe(error: Throwable?): String =
    error?.let { "[${category(it)}] [${it::class.simpleName}]: [${it.message}]" } ?: "[unknown error]"

  private fun category(error: Throwable): String =
    when (error) {
      is TransientError -> "transient"
      is PermanentError -> "permanent"
      else -> "unknown"
    }

  companion object {
    private const val ROW_SAVEPOINT = "scorecard_row"
  }
}
