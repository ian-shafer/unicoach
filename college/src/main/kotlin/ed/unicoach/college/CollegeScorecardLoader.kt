package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.DaoException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.College
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.error.errorCategory
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.SQLException

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
   * Why a row was skipped, bucketed precisely so the end-of-load summary reports
   * a counted reason for every dropped row — never a silent loss. The buckets are
   * the three disposition mechanisms' skip outcomes (mechanism A nulls a cell and
   * does not skip): missing required/key field, no owning college, the two known
   * field-of-study source sentinels, a constraint violation keyed by its name,
   * and a retryable transient fault.
   */
  sealed interface SkipReason {
    /** One or more required/key columns were absent or blank, named in
     * [missingFields] so the structured result carries which columns were missing
     * — not only the log. */
    data class MissingRequiredField(
      val missingFields: List<String>,
    ) : SkipReason

    data object NoCollegeForUnitId : SkipReason

    /** `UNITID=NA` field-of-study rows: non-IPEDS institutions absent from the
     * institution file (RFC 78). Detected and counted, not linked. */
    data object UnitIdNa : SkipReason

    /** `CREDLEV` outside `1..8`: the `99` "Non-Credential Program" sentinel and
     * any other out-of-domain credential level. */
    data object CredentialLevelOutOfDomain : SkipReason

    /** A DB CHECK/unique violation, keyed by the violated constraint name (null
     * when the driver did not report one). */
    data class ConstraintViolation(
      val constraint: String?,
    ) : SkipReason

    data object Transient : SkipReason

    /** An upsert failure that is neither transient, a constraint violation, nor
     * any other permanent DB error with a known mapping — including a null
     * exception or an unexpected [DaoException] subtype. Bucketed distinctly so a
     * genuinely-unnamed [ConstraintViolation] is never conflated with "we could
     * not classify this at all". */
    data object UnknownFailure : SkipReason
  }

  /**
   * Per-file outcome: rows upserted, plus the structured breakdown of every
   * skipped row (by [SkipReason]) and every optional cell coerced to NULL
   * (mechanism A, by column name). The legacy `transientSkips`/`permanentSkips`
   * accessors are preserved as derived values so existing callers keep compiling:
   * the split lets a caller tell "retry the ingest" (transient) from "this row is
   * permanently corrupt".
   */
  data class LoadResult(
    val collegesLoaded: Int,
    val programsLoaded: Int,
    val skipsByReason: Map<SkipReason, Int> = emptyMap(),
    val fieldsCoercedToNull: Map<String, Int> = emptyMap(),
  ) {
    val transientSkips: Int get() = skipsByReason[SkipReason.Transient] ?: 0
    val permanentSkips: Int get() = skipsByReason.filterKeys { it != SkipReason.Transient }.values.sum()
  }

  /**
   * The outcome of mapping one CSV row to a domain value, returned by the pure
   * mappers so the load loop — not the mapper — folds it into the [LoadCount]
   * accumulator. A [Mapped] carries the value to upsert plus the optional cells
   * that were coerced to NULL (by column name, count 1 each), so the loop records
   * the coercions only when the value actually reaches the DB; a [Skipped] carries
   * the precise [SkipReason] the loop tallies and the row is dropped.
   */
  private sealed interface MapResult<out T> {
    data class Mapped<T>(
      val value: T,
      val coercions: Map<String, Int>,
    ) : MapResult<T>

    data class Skipped(
      val reason: SkipReason,
    ) : MapResult<Nothing>
  }

  /** Mutable per-file accumulator, folded into [LoadResult] by [load]. */
  private class LoadCount {
    var loaded: Int = 0
    val skipsByReason: MutableMap<SkipReason, Int> = mutableMapOf()
    val fieldsCoercedToNull: MutableMap<String, Int> = mutableMapOf()

    fun recordSkip(reason: SkipReason) {
      skipsByReason.merge(reason, 1, Int::plus)
    }

    fun recordCoercions(coercions: Map<String, Int>) {
      for ((column, n) in coercions) fieldsCoercedToNull.merge(column, n, Int::plus)
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
      skipsByReason = mergeCounts(colleges.skipsByReason, programs.skipsByReason),
      fieldsCoercedToNull = mergeCounts(colleges.fieldsCoercedToNull, programs.fieldsCoercedToNull),
    )
  }

  private fun <K> mergeCounts(
    a: Map<K, Int>,
    b: Map<K, Int>,
  ): Map<K, Int> {
    val merged = a.toMutableMap()
    for ((key, count) in b) merged.merge(key, count, Int::plus)
    return merged
  }

  private suspend fun loadInstitutions(file: File): LoadCount =
    database.withConnection { session ->
      val count = LoadCount()
      parse(file).use { records ->
        for (record in records) {
          val mapped =
            when (val mapResult = mapInstitution(record)) {
              is MapResult.Skipped -> {
                count.recordSkip(mapResult.reason)
                continue
              }

              is MapResult.Mapped -> {
                mapResult
              }
            }
          val newCollege = mapped.value
          // Coercions are tallied for every mapped row (mechanism A nulled a cell
          // and did not skip), independent of the later upsert outcome.
          count.recordCoercions(mapped.coercions)
          val result = upsertWithSavepoint(session) { CollegesDao.upsert(session, newCollege) }
          if (result.isFailure) {
            val error = result.exceptionOrNull()
            recordUpsertFailure(count, error, "institution", "unit_id", newCollege.unitId, record.recordNumber)
          } else {
            count.loaded++
          }
        }
      }
      logSummary("institutions", count)
      count
    }

  private suspend fun loadFields(file: File): LoadCount =
    database.withConnection { session ->
      val count = LoadCount()
      parse(file).use { records ->
        for (record in records) {
          // mapField is pure CSV validation (sentinels, missing/key fields, the
          // CREDLEV domain). The owning-college resolution is a DB read, kept in
          // the loop: it runs before the savepoint, so the transaction is
          // unaborted and the read is always valid.
          val fields =
            when (val mapResult = mapField(record)) {
              is MapResult.Skipped -> {
                count.recordSkip(mapResult.reason)
                continue
              }

              is MapResult.Mapped -> {
                mapResult.value
              }
            }

          val college = resolveCollege(count, session, fields, record) ?: continue

          val program =
            NewCollegeProgram(
              collegeId = college.id,
              cipCode = fields.cipCode,
              cipTitle = fields.cipTitle,
              credentialLevel = fields.credentialLevel,
            )
          val result = upsertWithSavepoint(session) { CollegesDao.upsertProgram(session, program) }
          if (result.isFailure) {
            val error = result.exceptionOrNull()
            recordUpsertFailure(count, error, "program", "cip_code", program.cipCode, record.recordNumber)
          } else {
            count.loaded++
          }
        }
      }
      logSummary("fields", count)
      count
    }

  /**
   * Resolves the owning college for a field-of-study row. [CollegesDao.findByUnitId]
   * returns success(null) for the genuine "no owning college" case and failure(e)
   * only for a real DB fault — both are absorbed here, returning null to signal
   * "skip this row": a DB fault is classified + logged like any upsert failure
   * (never mislabeled NoCollegeForUnitId), a missing college is the counted
   * [SkipReason.NoCollegeForUnitId]. The read runs before the savepoint, so the
   * transaction is unaborted and the read is always valid.
   */
  private fun resolveCollege(
    count: LoadCount,
    session: SqlSession,
    fields: ProgramFields,
    record: CSVRecord,
  ): College? {
    val lookup = CollegesDao.findByUnitId(session, fields.unitId)
    if (lookup.isFailure) {
      recordUpsertFailure(count, lookup.exceptionOrNull(), "program", "unit_id", fields.unitId, record.recordNumber)
      return null
    }
    val college = lookup.getOrThrow()
    if (college == null) {
      count.recordSkip(SkipReason.NoCollegeForUnitId)
      logger.debug(
        "Skipping program row [line={}]: no college for [unit_id={}]",
        record.recordNumber,
        fields.unitId,
      )
      return null
    }
    return college
  }

  /**
   * Buckets a post-DB upsert failure (mechanism C): a [TransientError] is a
   * retryable [SkipReason.Transient] kept at WARN (rare); a
   * [ConstraintViolationException] is bucketed by its constraint name; any other
   * [PermanentError] is an unkeyed [SkipReason.ConstraintViolation]; a null or
   * otherwise-unclassifiable failure is [SkipReason.UnknownFailure] — never
   * silently fused into an unnamed constraint violation. The per-row line is
   * demoted to DEBUG (transient stays WARN) and carries the row's natural key,
   * line number, and the exception's constraint/detail so a drill-down shows
   * which value failed without dumping every row at WARN.
   */
  private fun recordUpsertFailure(
    count: LoadCount,
    error: Throwable?,
    kind: String,
    keyName: String,
    keyValue: Any?,
    line: Long,
  ) {
    val reason = classifyUpsertFailure(error)
    count.recordSkip(reason)
    logUpsertSkip(reason, kind, keyName, keyValue, line, error)
  }

  /**
   * Emits the per-row skip line for an upsert failure: the row's natural key and
   * line number plus the constraint/detail pulled from a
   * [ConstraintViolationException] (null otherwise) and the categorized cause. A
   * [SkipReason.Transient] stays WARN (rare, retryable); every other reason is
   * demoted to DEBUG so a drill-down shows which value failed without dumping
   * every row at WARN.
   */
  private fun logUpsertSkip(
    reason: SkipReason,
    kind: String,
    keyName: String,
    keyValue: Any?,
    line: Long,
    error: Throwable?,
  ) {
    val violation = error as? ConstraintViolationException
    val constraintName = violation?.constraint
    val detail = violation?.detail
    val message = "Skipping [$kind] row [$keyName={}] [line={}] [constraint={}] [detail={}]: [{}]"
    if (reason == SkipReason.Transient) {
      logger.warn(message, keyValue, line, constraintName, detail, describe(error))
    } else {
      logger.debug(message, keyValue, line, constraintName, detail, describe(error))
    }
  }

  /**
   * Pure classifier (no logging, no mutation) mapping an upsert failure to its
   * [SkipReason] bucket. Null-guarded first so a missing exception is an explicit
   * [SkipReason.UnknownFailure], never an unnamed constraint violation.
   */
  internal fun classifyUpsertFailure(error: Throwable?): SkipReason =
    when (error) {
      null -> SkipReason.UnknownFailure
      is TransientError -> SkipReason.Transient
      is ConstraintViolationException -> SkipReason.ConstraintViolation(error.constraint)
      is PermanentError -> SkipReason.ConstraintViolation(null)
      else -> SkipReason.UnknownFailure
    }

  private fun logSummary(
    file: String,
    count: LoadCount,
  ) {
    logger.info(
      "Load summary [{}]: [loaded={}] [skips={}] [coercions={}]",
      file,
      count.loaded,
      count.skipsByReason,
      count.fieldsCoercedToNull,
    )
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
  // Row mapping — PURE: validate one CSV row to a [MapResult]. A mapper neither
  // reads the DB nor mutates the [LoadCount] accumulator; it returns the value to
  // upsert (plus the cells it coerced to NULL) or the precise skip reason, and the
  // load loop folds that into the accumulator. Skip/coercion DEBUG logs still fire
  // here, once per dropped row / coerced cell, so the audit trail is unchanged.
  // ---------------------------------------------------------------------------

  private fun mapInstitution(record: CSVRecord): MapResult<NewCollege> {
    val unitId = intOrNull(record, "UNITID")
    val name = stringOrNull(record, "INSTNM")
    val city = stringOrNull(record, "CITY")
    val state = stringOrNull(record, "STABBR")
    val control = intOrNull(record, "CONTROL")

    if (unitId == null || name == null || city == null || state == null || control == null) {
      val missing =
        buildList {
          if (unitId == null) add("unit_id")
          if (name == null) add("name")
          if (city == null) add("city")
          if (state == null) add("state")
          if (control == null) add("control")
        }
      logger.debug(
        "Skipping institution row [line={}]: missing required field {} " +
          "[unit_id={}] [name={}] [city={}] [state={}] [control={}]",
        record.recordNumber,
        missing,
        unitId,
        name,
        city,
        state,
        control,
      )
      return MapResult.Skipped(SkipReason.MissingRequiredField(missing))
    }

    // net_price coalesce: public uses NPT4_PUB, all else NPT4_PRIV; both blank => null.
    // net_price is excluded from mechanism-A coercion: negatives are valid (0022).
    val netPrice = if (control == 1) intOrNull(record, "NPT4_PUB") else intOrNull(record, "NPT4_PRIV")

    val coercions = mutableMapOf<String, Int>()
    val college =
      NewCollege(
        unitId = unitId,
        // OPEID is the 8-digit OPE ID (column 2). The loader historically read the
        // nonexistent OPEID8, so opeid always loaded null.
        opeid = stringOrNull(record, "OPEID"),
        name = name,
        city = city,
        state = state,
        region = intInDomainOrNull(record, "REGION", REGION_MIN, REGION_MAX, "region", coercions),
        locale = intInDomainOrNull(record, "LOCALE", LOCALE_MIN, LOCALE_MAX, "locale", coercions),
        latitude = doubleOrNull(record, "LATITUDE"),
        longitude = doubleOrNull(record, "LONGITUDE"),
        control = control,
        undergradEnrollment =
          intInDomainOrNull(record, "UGDS", 0, Int.MAX_VALUE, "undergrad_enrollment", coercions),
        admissionRate = doubleInDomainOrNull(record, "ADM_RATE", RATE_MIN, RATE_MAX, "admission_rate", coercions),
        satAvg = intInDomainOrNull(record, "SAT_AVG", 0, Int.MAX_VALUE, "sat_avg", coercions),
        costAttendance = intInDomainOrNull(record, "COSTT4_A", 0, Int.MAX_VALUE, "cost_attendance", coercions),
        netPrice = netPrice,
        tuitionInState = intInDomainOrNull(record, "TUITIONFEE_IN", 0, Int.MAX_VALUE, "tuition_in_state", coercions),
        tuitionOutState =
          intInDomainOrNull(record, "TUITIONFEE_OUT", 0, Int.MAX_VALUE, "tuition_out_state", coercions),
        graduationRate = doubleInDomainOrNull(record, "C150_4", RATE_MIN, RATE_MAX, "graduation_rate", coercions),
        medianEarnings =
          intInDomainOrNull(record, "MD_EARN_WNE_P10", 0, Int.MAX_VALUE, "median_earnings", coercions),
        pctPell = doubleInDomainOrNull(record, "PCTPELL", RATE_MIN, RATE_MAX, "pct_pell", coercions),
        website = stringOrNull(record, "INSTURL"),
      )
    return MapResult.Mapped(college, coercions)
  }

  /** The validated key/required columns of a field-of-study row, resolved to a
   * [NewCollegeProgram] by the load loop once its owning college is found. */
  private data class ProgramFields(
    val unitId: Int,
    val cipCode: String,
    val cipTitle: String,
    val credentialLevel: Int,
  )

  private fun mapField(record: CSVRecord): MapResult<ProgramFields> {
    // Mechanism B: known source sentinels in required/key columns are skipped
    // under their own precise bucket before any DB work, never silently.
    if (record.isMapped("UNITID") && record.get("UNITID")?.trim() == "NA") {
      logger.debug("Skipping program row [line={}]: UNITID=NA sentinel", record.recordNumber)
      return MapResult.Skipped(SkipReason.UnitIdNa)
    }

    val unitId = intOrNull(record, "UNITID")
    val cipCode = stringOrNull(record, "CIPCODE")
    val cipTitle = stringOrNull(record, "CIPDESC")
    val credentialLevel = intOrNull(record, "CREDLEV")

    if (unitId == null || cipCode == null || cipTitle == null || credentialLevel == null) {
      val missing =
        buildList {
          if (unitId == null) add("unit_id")
          if (cipCode == null) add("cip_code")
          if (cipTitle == null) add("cip_title")
          if (credentialLevel == null) add("credential_level")
        }
      logger.debug(
        "Skipping program row [line={}]: missing required field {} " +
          "[unit_id={}] [cip_code={}] [cip_title={}] [credential_level={}]",
        record.recordNumber,
        missing,
        unitId,
        cipCode,
        cipTitle,
        credentialLevel,
      )
      return MapResult.Skipped(SkipReason.MissingRequiredField(missing))
    }

    // Mechanism B: CREDLEV=99 ("Non-Credential Program") and any other
    // out-of-domain credential level. credential_level is NOT NULL and part of
    // the upsert key, so it cannot be nulled; pre-filter to a named bucket
    // instead of a generic constraint reject + savepoint round-trip.
    if (credentialLevel !in CREDENTIAL_LEVEL_MIN..CREDENTIAL_LEVEL_MAX) {
      logger.debug(
        "Skipping program row [line={}]: credential_level [{}] out of domain [unit_id={}]",
        record.recordNumber,
        credentialLevel,
        unitId,
      )
      return MapResult.Skipped(SkipReason.CredentialLevelOutOfDomain)
    }

    return MapResult.Mapped(
      ProgramFields(
        unitId = unitId,
        cipCode = cipCode,
        cipTitle = cipTitle,
        credentialLevel = credentialLevel,
      ),
      emptyMap(),
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
   * Mechanism A for a bounded **optional** integer metric: parses the cell, and
   * if it is absent/non-int it is simply null (the blank-cell idiom); if it is a
   * valid int outside `[min, max]` it is coerced to NULL and the coercion is
   * tallied by [columnName]. A junk optional cell thus never drops the row — the
   * DB CHECK remains the backstop for anything this misses.
   */
  private fun intInDomainOrNull(
    record: CSVRecord,
    column: String,
    min: Int,
    max: Int,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Int? {
    val value = intOrNull(record, column) ?: return null
    if (value < min || value > max) {
      logCoercion(columnName, record.recordNumber, value, min, max, coercions)
      return null
    }
    return value
  }

  /** Mechanism A for a bounded **optional** double metric; see [intInDomainOrNull]. */
  private fun doubleInDomainOrNull(
    record: CSVRecord,
    column: String,
    min: Double,
    max: Double,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Double? {
    val value = doubleOrNull(record, column) ?: return null
    if (value < min || value > max) {
      logCoercion(columnName, record.recordNumber, value, min, max, coercions)
      return null
    }
    return value
  }

  /**
   * Tallies an out-of-domain optional-metric coercion by [columnName] and emits
   * the per-cell DEBUG line. Shared by [intInDomainOrNull] and
   * [doubleInDomainOrNull] so the tally and log template live in one place.
   */
  private fun logCoercion(
    columnName: String,
    line: Long,
    value: Any,
    min: Any,
    max: Any,
    coercions: MutableMap<String, Int>,
  ) {
    coercions.merge(columnName, 1, Int::plus)
    logger.debug(
      "Coercing out-of-domain optional metric to null [column={}] [line={}] [value={}] [domain=[{}, {}]]",
      columnName,
      line,
      value,
      min,
      max,
    )
  }

  /**
   * A bracketed cause description for a skip warning, tagged with the failure
   * category (transient vs permanent) so a human scanning the log can tell a
   * retryable blip from permanently-corrupt source data. The wrapping
   * [DaoException] carries only a generic message (e.g. "Database constraint
   * violation"), so the actionable detail — which constraint failed, on what
   * value — is pulled from the root cause it wraps (and its SQLSTATE when that
   * cause is a [SQLException]).
   */
  private fun describe(error: Throwable?): String {
    if (error == null) return "[unknown error]"
    val root = rootCause(error)
    val detail =
      if (root is SQLException) {
        "[${root::class.simpleName}] [sqlstate=${root.sqlState}]: [${root.message?.trim()}]"
      } else {
        "[${root::class.simpleName}]: [${root.message}]"
      }
    return "[${error.errorCategory()}] [${error::class.simpleName}]: $detail"
  }

  private fun rootCause(error: Throwable): Throwable {
    var cause = error
    while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
    return cause
  }

  companion object {
    private const val ROW_SAVEPOINT = "scorecard_row"

    // Optional-metric domains, mirrored from the 0015 CHECKs (the DB CHECK is the
    // backstop; this duplication is intentional defense-in-depth). Required-field
    // domains (CONTROL) and key-field domains (CREDENTIAL_LEVEL) are pre-filters,
    // not coercions.
    private const val REGION_MIN = 0
    private const val REGION_MAX = 9
    private const val LOCALE_MIN = 11
    private const val LOCALE_MAX = 43
    private const val RATE_MIN = 0.0
    private const val RATE_MAX = 1.0
    private const val CREDENTIAL_LEVEL_MIN = 1
    private const val CREDENTIAL_LEVEL_MAX = 8
  }
}
