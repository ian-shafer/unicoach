package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsCoverage
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FactorRating
import ed.unicoach.db.models.NewCollegeAdmissionFactors
import ed.unicoach.db.models.NewCollegeDeadline
import ed.unicoach.db.models.NewCollegeMeritAid
import org.apache.commons.csv.CSVRecord
import java.io.File

/**
 * Loader for the repo-committed CDS seed (RFC 140): `db/seed/cds/merit-aid.csv`,
 * `admission-factors.csv`, and `deadlines.csv` into the three CDS admissions
 * reference tables, upserting on each table's natural key via
 * [CdsAdmissionsDao].
 *
 * Deliberately a dumb typed CSV reader: ALL interpretation (the C7 rating
 * whitelist, per-schema-version deadline field mapping, latest-document
 * selection) lives in `bin/fetch-cds-seed`, which generated the seed. The
 * loader's job is to fail loudly on anything that means the seed and the code
 * disagree:
 *
 * - The header of every file is asserted FIRST, before any DB write; a missing,
 *   renamed, or unexpected column is fatal and named ([FormatException]).
 * - A cell that does not parse as its column's type (a non-integer unit_id, a
 *   rating outside [FactorRating], a round outside [ApplicationRound]) is fatal
 *   too -- the seed is machine-generated, so a malformed cell is a broken seed,
 *   never a row to soft-skip.
 * - The ONE expected mismatch -- a seed UNITID with no `colleges` row (the
 *   corpus covers schools our Scorecard snapshot lacks) -- is skipped, and its
 *   UNITID is returned per table so the run report can name it (a count alone
 *   would force a second ingest to recover the identities).
 *
 * The three files load inside one connection; a fatal error therefore rolls
 * back the whole CDS load rather than leaving a half-applied seed.
 */
class CdsSeedLoader(
  private val database: Database,
) {
  /** Which seed file a defect came from; the label is display-only. */
  enum class Table(
    val label: String,
  ) {
    MERIT_AID("merit-aid"),
    ADMISSION_FACTORS("admission-factors"),
    DEADLINES("deadlines"),
  }

  /**
   * The closed set of ways a machine-generated seed can disagree with this
   * loader. Every variant carries the coordinates the operator needs -- file or
   * table, CSV line, column, raw value -- as typed fields rather than as prose
   * interpolated at the throw site, so a caller (and this suite's tests) can
   * read them without parsing a sentence.
   */
  sealed interface Defect {
    data class HeaderMismatch(
      val file: String,
      val expected: List<String>,
      val actual: List<String>,
    ) : Defect {
      val missing: List<String> get() = expected - actual.toSet()
      val unexpected: List<String> get() = actual - expected.toSet()
    }

    data class RowArity(
      val table: Table,
      val line: Long,
      val cells: Int,
      val expectedCells: Int,
    ) : Defect

    data class NotAnInteger(
      val table: Table,
      val line: Long,
      val column: String,
      val value: String,
    ) : Defect

    data class EmptyRequiredCell(
      val table: Table,
      val line: Long,
      val column: String,
    ) : Defect

    data class UnknownCode(
      val table: Table,
      val line: Long,
      val column: String,
      val value: String,
      val allowed: List<String>,
    ) : Defect

    data class DayWithoutMonth(
      val table: Table,
      val line: Long,
      val dayColumn: String,
      val monthColumn: String,
      val day: Int,
    ) : Defect

    /** A month/day pair that is not a real calendar date (month out of 1..12,
     * or a day past that month's length -- Feb 29 IS a real CDS date, the
     * corpus is cycle-relative and carries no year). */
    data class NotACalendarDate(
      val table: Table,
      val line: Long,
      val monthColumn: String,
      val dayColumn: String,
      val month: Int,
      val day: Int?,
    ) : Defect
  }

  /**
   * A seed file whose shape disagrees with this loader: fatal, never skipped.
   * The structured [defect] is the payload; the message is one rendering of it,
   * produced at the boundary rather than at each throw site.
   */
  class FormatException(
    val defect: Defect,
  ) : RuntimeException(renderDefect(defect))

  /**
   * A DB failure on ONE seed row, restating that row's seed coordinates (table,
   * CSV line, UNITID) with the DAO exception as the cause -- a constraint or
   * connection fault on row 900 of 1032 must not surface as a bare "Database
   * constraint violation".
   */
  class LoadException(
    val table: Table,
    val line: Long,
    val unitId: Int,
    cause: Throwable,
  ) : RuntimeException(
      "[${table.label}] line [$line]: unit_id [$unitId] failed: [${cause.message}]",
      cause,
    )

  /**
   * Per-table upsert disposition counts, plus the seed UNITIDs that matched no
   * `colleges` row. The identities travel with the count so the caller can
   * report WHICH schools were skipped without a second run.
   */
  data class TableSummary(
    val upserted: Int,
    val changed: Int,
    val unchanged: Int,
    val unmatchedUnitIds: List<Int>,
  ) {
    val skipped: Int get() = unmatchedUnitIds.size
  }

  data class LoadResult(
    val meritAid: TableSummary,
    val admissionFactors: TableSummary,
    val deadlines: TableSummary,
    val coverage: CdsCoverage,
  )

  private class Tally {
    var upserted = 0
    var changed = 0
    var unchanged = 0
    val unmatchedUnitIds = mutableListOf<Int>()

    fun record(outcome: UpsertOutcome) {
      when (outcome) {
        UpsertOutcome.INSERTED -> upserted++
        UpsertOutcome.CHANGED -> changed++
        UpsertOutcome.UNCHANGED -> unchanged++
      }
    }

    fun summary() = TableSummary(upserted, changed, unchanged, unmatchedUnitIds.toList())
  }

  /** Loads the three seed files (header-asserted first) and computes the
   * launch-set coverage report from the DB. */
  suspend fun load(
    meritAidCsv: File,
    admissionFactorsCsv: File,
    deadlinesCsv: File,
  ): LoadResult =
    database.withConnection { session ->
      // Assert every header before any row of any file is written, so a renamed
      // column in file three never leaves files one and two half-trusted. Read
      // inside withConnection so every blocking read this loader performs sits
      // on the same injected IO dispatcher -- `load` advertises no blocking-IO
      // requirement to its caller's dispatcher.
      assertHeader(meritAidCsv, MERIT_AID_COLUMNS)
      assertHeader(admissionFactorsCsv, ADMISSION_FACTORS_COLUMNS)
      assertHeader(deadlinesCsv, DEADLINES_COLUMNS)

      val meritAid =
        loadTable(session, meritAidCsv, Table.MERIT_AID, MERIT_AID_COLUMNS) { collegeId, record ->
          CdsAdmissionsDao.upsertMeritAid(session, mapMeritAid(collegeId, record))
        }
      val factors =
        loadTable(session, admissionFactorsCsv, Table.ADMISSION_FACTORS, ADMISSION_FACTORS_COLUMNS) { collegeId, record ->
          CdsAdmissionsDao.upsertAdmissionFactors(session, mapAdmissionFactors(collegeId, record))
        }
      val deadlines =
        loadTable(session, deadlinesCsv, Table.DEADLINES, DEADLINES_COLUMNS) { collegeId, record ->
          CdsAdmissionsDao.upsertDeadline(session, mapDeadline(collegeId, record))
        }
      val coverage = CdsAdmissionsDao.getCoverage(session).getOrThrow()
      LoadResult(meritAid, factors, deadlines, coverage)
    }

  /**
   * Streams one seed file: per row, resolve UNITID -> `colleges.id` (unmatched:
   * skip and record the UNITID), map, upsert, tally the [UpsertOutcome]. Any
   * mapping or upsert failure propagates -- a machine-generated seed row that
   * fails is a broken seed, not a skippable line.
   */
  private fun loadTable(
    session: SqlSession,
    file: File,
    table: Table,
    columns: List<String>,
    upsert: (CollegeId, CSVRecord) -> Result<UpsertOutcome>,
  ): TableSummary {
    val tally = Tally()
    parseCsv(file).use { records ->
      for (record in records) {
        // The header assertion covers column NAMES only; this covers a
        // truncated or over-long row, whose record.get would otherwise throw an
        // unlocated IllegalArgumentException from Commons CSV -- outside this
        // loader's error contract and without the table or line number.
        if (!record.isConsistent || record.size() != columns.size) {
          throw FormatException(Defect.RowArity(table, record.recordNumber, record.size(), columns.size))
        }
        val rawUnitId = record.get("unit_id")
        val unitId =
          rawUnitId.trim().toIntOrNull() ?: throw FormatException(
            Defect.NotAnInteger(table, record.recordNumber, "unit_id", rawUnitId),
          )
        val college = withRowLocation(table, record, unitId) { CollegesDao.findByUnitId(session, unitId) }
        if (college == null) {
          tally.unmatchedUnitIds += unitId
          continue
        }
        tally.record(withRowLocation(table, record, unitId) { upsert(college.id, record) })
      }
    }
    return tally.summary()
  }

  /** Runs one row's DB call, naming the table, CSV line and UNITID on failure
   * so a fault mid-load names the seed line that provoked it. */
  private fun <T> withRowLocation(
    table: Table,
    record: CSVRecord,
    unitId: Int,
    call: () -> Result<T>,
  ): T =
    call().getOrElse { cause ->
      throw LoadException(table, record.recordNumber, unitId, cause)
    }

  // ---------------------------------------------------------------------------
  // Row mapping -- pure CSV-to-model, fatal on any malformed cell.
  // ---------------------------------------------------------------------------

  private fun mapMeritAid(
    collegeId: CollegeId,
    record: CSVRecord,
  ) = NewCollegeMeritAid(
    collegeId = collegeId,
    sourceYear = getInt(record, "source_year", Table.MERIT_AID),
    freshmenFtTotal = getIntOrNull(record, "freshmen_ft_total", Table.MERIT_AID),
    noNeedMeritCount = getIntOrNull(record, "no_need_merit_count", Table.MERIT_AID),
    noNeedMeritAvg = getIntOrNull(record, "no_need_merit_avg", Table.MERIT_AID),
    sourceUrl = getString(record, "source_url", Table.MERIT_AID),
    archiveUrl = getStringOrNull(record, "archive_url"),
  )

  private fun mapAdmissionFactors(
    collegeId: CollegeId,
    record: CSVRecord,
  ): NewCollegeAdmissionFactors {
    fun getRatingOrNull(
      record: CSVRecord,
      column: String,
    ): FactorRating? {
      val raw = getStringOrNull(record, column) ?: return null
      return FactorRating.fromValue(raw) ?: throw FormatException(
        Defect.UnknownCode(
          table = Table.ADMISSION_FACTORS,
          line = record.recordNumber,
          column = column,
          value = raw,
          allowed = FactorRating.entries.map { it.value },
        ),
      )
    }
    return NewCollegeAdmissionFactors(
      collegeId = collegeId,
      sourceYear = getInt(record, "source_year", Table.ADMISSION_FACTORS),
      rigor = getRatingOrNull(record, "rigor"),
      classRank = getRatingOrNull(record, "class_rank"),
      gpa = getRatingOrNull(record, "gpa"),
      testScores = getRatingOrNull(record, "test_scores"),
      essay = getRatingOrNull(record, "essay"),
      recommendations = getRatingOrNull(record, "recommendations"),
      interview = getRatingOrNull(record, "interview"),
      extracurriculars = getRatingOrNull(record, "extracurriculars"),
      talent = getRatingOrNull(record, "talent"),
      characterQualities = getRatingOrNull(record, "character_qualities"),
      firstGeneration = getRatingOrNull(record, "first_generation"),
      alumniRelation = getRatingOrNull(record, "alumni_relation"),
      geography = getRatingOrNull(record, "geography"),
      stateResidency = getRatingOrNull(record, "state_residency"),
      religiousAffiliation = getRatingOrNull(record, "religious_affiliation"),
      volunteerWork = getRatingOrNull(record, "volunteer_work"),
      workExperience = getRatingOrNull(record, "work_experience"),
      applicantInterest = getRatingOrNull(record, "applicant_interest"),
      sourceUrl = getString(record, "source_url", Table.ADMISSION_FACTORS),
      archiveUrl = getStringOrNull(record, "archive_url"),
    )
  }

  private fun mapDeadline(
    collegeId: CollegeId,
    record: CSVRecord,
  ): NewCollegeDeadline {
    val roundRaw = getString(record, "round", Table.DEADLINES)
    val offeredRaw = getString(record, "offered", Table.DEADLINES)
    return NewCollegeDeadline(
      collegeId = collegeId,
      sourceYear = getInt(record, "source_year", Table.DEADLINES),
      round =
        ApplicationRound.fromValue(roundRaw) ?: throw FormatException(
          Defect.UnknownCode(
            table = Table.DEADLINES,
            line = record.recordNumber,
            column = "round",
            value = roundRaw,
            allowed = ApplicationRound.entries.map { it.value },
          ),
        ),
      offered =
        when (offeredRaw) {
          "true" -> true

          "false" -> false

          else -> throw FormatException(
            Defect.UnknownCode(
              table = Table.DEADLINES,
              line = record.recordNumber,
              column = "offered",
              value = offeredRaw,
              allowed = listOf("true", "false"),
            ),
          )
        },
      closing = getMonthDayOrNull(record, "closing_month", "closing_day"),
      notification = getMonthDayOrNull(record, "notification_month", "notification_day"),
      sourceUrl = getString(record, "source_url", Table.DEADLINES),
      archiveUrl = getStringOrNull(record, "archive_url"),
    )
  }

  /**
   * Reads one month/day cell pair. A month with no day is real CDS reporting
   * ("closes in March") and lands as a day-less [CdsMonthDay]; both cells empty
   * is "not reported" (null). A DAY WITH NO MONTH is a junk state the fetcher
   * never emits and nothing can render, so -- like a bad round code -- it is a
   * broken seed and fatal.
   *
   * An impossible pair (month 13, or Feb 30) is rejected HERE, by name, with
   * the file, line and columns that produced it: [CdsMonthDay] refuses to
   * construct one and the DB would refuse to store one, but neither can say
   * which seed line was at fault.
   */
  private fun getMonthDayOrNull(
    record: CSVRecord,
    monthColumn: String,
    dayColumn: String,
  ): CdsMonthDay? {
    val month = getIntOrNull(record, monthColumn, Table.DEADLINES)
    val day = getIntOrNull(record, dayColumn, Table.DEADLINES)
    if (month == null) {
      if (day != null) {
        throw FormatException(
          Defect.DayWithoutMonth(Table.DEADLINES, record.recordNumber, dayColumn, monthColumn, day),
        )
      }
      return null
    }
    if (!CdsMonthDay.isCalendarPair(month, day)) {
      throw FormatException(
        Defect.NotACalendarDate(Table.DEADLINES, record.recordNumber, monthColumn, dayColumn, month, day),
      )
    }
    return CdsMonthDay(month, day)
  }

  // ---------------------------------------------------------------------------
  // CSV plumbing
  // ---------------------------------------------------------------------------

  /**
   * Asserts [file]'s header row is EXACTLY [expected], in order, before
   * anything is loaded. Missing and unexpected columns are each named; order
   * drift reports both lists. Renames therefore fail loudly instead of loading
   * NULLs (the stale-seed failure this loader exists to prevent).
   */
  private fun assertHeader(
    file: File,
    expected: List<String>,
  ) {
    val actual =
      parseCsv(file).use { parser ->
        parser.headerNames.toList()
      }
    if (actual == expected) return
    throw FormatException(Defect.HeaderMismatch(file.name, expected, actual))
  }

  private fun getInt(
    record: CSVRecord,
    column: String,
    table: Table,
  ): Int =
    record.get(column).trim().toIntOrNull() ?: throw FormatException(
      Defect.NotAnInteger(table, record.recordNumber, column, record.get(column)),
    )

  private fun getIntOrNull(
    record: CSVRecord,
    column: String,
    table: Table,
  ): Int? {
    val raw = record.get(column).trim()
    if (raw.isEmpty()) return null
    return raw.toIntOrNull() ?: throw FormatException(
      Defect.NotAnInteger(table, record.recordNumber, column, raw),
    )
  }

  private fun getString(
    record: CSVRecord,
    column: String,
    table: Table,
  ): String {
    val raw = record.get(column).trim()
    if (raw.isEmpty()) {
      throw FormatException(Defect.EmptyRequiredCell(table, record.recordNumber, column))
    }
    return raw
  }

  private fun getStringOrNull(
    record: CSVRecord,
    column: String,
  ): String? = record.get(column).trim().ifEmpty { null }

  companion object {
    val MERIT_AID_COLUMNS =
      listOf(
        "unit_id",
        "source_year",
        "freshmen_ft_total",
        "no_need_merit_count",
        "no_need_merit_avg",
        "source_url",
        "archive_url",
      )

    val ADMISSION_FACTORS_COLUMNS =
      listOf(
        "unit_id",
        "source_year",
        "rigor",
        "class_rank",
        "gpa",
        "test_scores",
        "essay",
        "recommendations",
        "interview",
        "extracurriculars",
        "talent",
        "character_qualities",
        "first_generation",
        "alumni_relation",
        "geography",
        "state_residency",
        "religious_affiliation",
        "volunteer_work",
        "work_experience",
        "applicant_interest",
        "source_url",
        "archive_url",
      )

    val DEADLINES_COLUMNS =
      listOf(
        "unit_id",
        "source_year",
        "round",
        "offered",
        "closing_month",
        "closing_day",
        "notification_month",
        "notification_day",
        "source_url",
        "archive_url",
      )

    /**
     * Renders the RFC 140 launch-set coverage report block printed at the end
     * of every CDS ingest run.
     */
    fun render(coverage: CdsCoverage): String {
      val total = coverage.launchSetCount
      val missing = coverage.studentListedMissing
      val missingSuffix = if (missing.isEmpty()) "0" else "${missing.size} (${missing.joinToString(", ")})"
      return buildString {
        appendLine("CDS coverage: $total launch-set colleges")
        appendLine("  merit aid          ${coverage.meritAidCount}/$total (${total - coverage.meritAidCount} without)")
        appendLine("  admission factors  ${coverage.admissionFactorsCount}/$total")
        appendLine(
          "  deadlines (flags)  ${coverage.deadlinesFlagsCount}/$total, " +
            "with >=1 concrete date ${coverage.deadlinesWithDateCount}/$total",
        )
        append("  student-listed schools missing from corpus: $missingSuffix")
      }
    }
  }
}

/**
 * The ONE place a [CdsSeedLoader.Defect] becomes prose. Keeping the
 * rendering here (rather than at each throw site) is what lets every throw site
 * carry data instead of a sentence.
 */
private fun renderDefect(defect: CdsSeedLoader.Defect): String =
  when (defect) {
    is CdsSeedLoader.Defect.HeaderMismatch -> {
      "[${defect.file}] header mismatch: missing columns ${defect.missing}, " +
        "unexpected columns ${defect.unexpected} (expected ${defect.expected}, got ${defect.actual})"
    }

    is CdsSeedLoader.Defect.RowArity -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.cells}] cells, expected [${defect.expectedCells}]"
    }

    is CdsSeedLoader.Defect.NotAnInteger -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.column}] value [${defect.value}] is not an integer"
    }

    is CdsSeedLoader.Defect.EmptyRequiredCell -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.column}] must not be empty"
    }

    is CdsSeedLoader.Defect.UnknownCode -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.column}] value [${defect.value}] " +
        "is not one of ${defect.allowed}"
    }

    is CdsSeedLoader.Defect.DayWithoutMonth -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.dayColumn}] is [${defect.day}] with an " +
        "empty [${defect.monthColumn}]; a day without a month is not a date"
    }

    is CdsSeedLoader.Defect.NotACalendarDate -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.monthColumn}]/[${defect.dayColumn}] " +
        "value [${defect.month}]/[${defect.day}] is not a real calendar date"
    }
  }
