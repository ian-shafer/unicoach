package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SourceDocumentsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AidForm
import ed.unicoach.db.models.AidFormApplicantGroup
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsCoverage
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FactTable
import ed.unicoach.db.models.FactorRating
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewAidFormRequirement
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewCohortPopulationCount
import ed.unicoach.db.models.NewCollegeAdmissionFactors
import ed.unicoach.db.models.NewCollegeDeadline
import ed.unicoach.db.models.NewCollegeMeritAid
import ed.unicoach.db.models.NewSourceDocument
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.SourceDocumentId
import ed.unicoach.db.models.ValueBearingStatus
import org.apache.commons.csv.CSVRecord
import java.io.File

/**
 * The three CDS seed files as one all-or-nothing provenance group (RFC 148),
 * shaped exactly like [IpedsSources]: each file carries the caller's original
 * argument, so the `college_index_build` row records what the operator named
 * rather than a scratch path.
 */
data class CdsSources(
  val meritAid: SourceFile,
  val admissionFactors: SourceFile,
  val deadlines: SourceFile,
  val aidPolicy: SourceFile,
) {
  /** The four files in provenance order, for digesting. */
  val files: List<SourceFile> get() = listOf(meritAid, admissionFactors, deadlines, aidPolicy)

  /**
   * Each file paired with the ROLE it fills -- the CDS flag the operator typed,
   * without its punctuation. Named HERE, where the field and its role are both
   * known, rather than zipped positionally against a flag list declared in
   * another file: a reorder would then mislabel which option failed, and a
   * fourth file would be dropped from the existence check by `zip` with nothing
   * failing. This is the same rule [CdsSeedLoader.Table] applies to the
   * provenance counts.
   */
  val namedFiles: List<Pair<String, SourceFile>>
    get() =
      listOf(
        "cds-merit" to meritAid,
        "cds-factors" to admissionFactors,
        "cds-deadlines" to deadlines,
        "cds-aid-policy" to aidPolicy,
      )
}

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
  /**
   * One CDS seed file, and every name anything downstream calls it by: the
   * defect [label], the `rows_ingested.cds` [wireKey] the provenance row
   * carries, and the [logLabel] the run report prints.
   *
   * All three ride on the member because the alternative -- separate literal
   * lists zipped against a positional list of summaries -- mislabels the whole
   * provenance row if the order ever changes, and `zip` would silently DROP a
   * fourth table rather than fail. Nothing about that failure is loud, and it
   * would be the build row (the thing that exists to be trustworthy) that lied.
   */
  enum class Table(
    val label: String,
    val wireKey: String,
    val logLabel: String,
  ) {
    MERIT_AID("merit-aid", "merit_aid", "merit aid"),
    ADMISSION_FACTORS("admission-factors", "admission_factors", "admission factors"),
    DEADLINES("deadlines", "deadlines", "deadlines"),

    /**
     * The RFC 170 need-and-forms seed. Unlike the three above it does not have
     * a table of its own: its rows are CANONICAL money facts, so they land in
     * `cohort_money_stats`, `cohort_population_counts` and
     * `aid_form_requirements` -- the tables the domain already puts them in.
     */
    AID_POLICY("aid-policy", "aid_policy", "aid policy"),
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
      /**
       * The caller's ORIGINAL argument beside [file]: `bin/ingest-colleges`
       * downloads an `s3://` source into a temp dir, so the resolved path alone
       * names a file the operator never typed (RFC 139's codified rule, which
       * the other seven header-asserted files already follow).
       */
      val sourceArg: String,
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

    /** A year cell outside the academic years the store admits ([AcademicYear]'s own range). */
    data class NotAnAcademicYear(
      val table: Table,
      val line: Long,
      val column: String,
      val value: Int,
    ) : Defect

    /** A decimal cell that is not a decimal: the CDS publishes 18006.5357, so "not an integer" would be wrong. */
    data class NotADecimal(
      val table: Table,
      val line: Long,
      val column: String,
      val value: String,
    ) : Defect

    /**
     * A cell carrying a value under a status that bears none (RFC 158 D3). Its
     * own variant because the alternative -- an `UnknownCode` whose allowed
     * list is `listOf("")` -- renders as "is not one of []", which tells an
     * operator nothing.
     */
    data class ValueUnderValuelessStatus(
      val table: Table,
      val line: Long,
      val status: String,
      val value: String,
    ) : Defect

    /**
     * Two seed rows naming one filing with different urls (RFC 170, D13). Both
     * documents ride on the defect: which url is "wrong" is the operator's
     * call, and a message that names only one cannot be acted on.
     */
    data class ConflictingFilingUrls(
      val table: Table,
      val line: Long,
      val first: NewSourceDocument,
      val second: NewSourceDocument,
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
    val ipedsUnitId: Int,
    cause: Throwable,
  ) : RuntimeException(
      "[${table.label}] line [$line]: unit_id [$ipedsUnitId] failed: [${cause.message}]",
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
    val unmatchedIpedsUnitIds: List<Int>,
  ) {
    val skipped: Int get() = unmatchedIpedsUnitIds.size
  }

  /**
   * What the aid-policy file wrote (RFC 170). Not a [TableSummary]: these rows
   * are not upserted per natural key, they are a WHOLESALE rebuild of the
   * Common Data Set's share of three canonical tables, so "changed" and
   * "unchanged" are not facts about them -- the count that lands is the count
   * that exists.
   */
  data class AidPolicySummary(
    val cohortMoneyStats: Int,
    val cohortPopulationCounts: Int,
    val aidFormRequirements: Int,
    val unmatchedIpedsUnitIds: List<Int>,
  ) {
    val rows: Int get() = cohortMoneyStats + cohortPopulationCounts + aidFormRequirements
    val skipped: Int get() = unmatchedIpedsUnitIds.size
  }

  data class LoadResult(
    val meritAid: TableSummary,
    val admissionFactors: TableSummary,
    val deadlines: TableSummary,
    val aidPolicy: AidPolicySummary,
    val coverage: CdsCoverage,
  ) {
    /**
     * Every summary KEYED BY its table, so a reporter (the run log, the
     * provenance row) iterates rather than repeats them -- and cannot pair a
     * summary with the wrong table's name, which a positional list zipped
     * against a separate list of labels silently can.
     */
    val tableSummaries: List<Pair<Table, TableSummary>>
      get() =
        listOf(
          Table.MERIT_AID to meritAid,
          Table.ADMISSION_FACTORS to admissionFactors,
          Table.DEADLINES to deadlines,
        )
  }

  private class Tally {
    var upserted = 0
    var changed = 0
    var unchanged = 0
    private val unmatchedIpedsUnitIds = mutableListOf<Int>()

    /** The unmatched UNITIDs this file skipped -- appended THROUGH the tally, never into its list from outside. */
    fun recordUnmatched(ipedsUnitIds: List<Int>) {
      unmatchedIpedsUnitIds += ipedsUnitIds
    }

    fun record(outcome: UpsertOutcome) {
      when (outcome) {
        UpsertOutcome.INSERTED -> upserted++
        UpsertOutcome.CHANGED -> changed++
        UpsertOutcome.UNCHANGED -> unchanged++
      }
    }

    fun getSummary() = TableSummary(upserted, changed, unchanged, unmatchedIpedsUnitIds.toList())
  }

  /**
   * Asserts all three seed headers, reading nothing else and writing nothing.
   * Public and callable on its own so the ingest can validate the CDS files
   * BESIDE the Scorecard and IPEDS ones, before its first phase commits: a
   * renamed column in the third seed file must not be discovered only after
   * five other phases have written rows (RFC 148 D10).
   *
   * Blocking file reads. The caller places it on its IO dispatcher, exactly as
   * it does for its own digests, so this loader advertises no blocking-IO
   * requirement of its own.
   */
  fun assertHeaders(sources: CdsSources) {
    assertHeader(sources.meritAid, MERIT_AID_COLUMNS)
    assertHeader(sources.admissionFactors, ADMISSION_FACTORS_COLUMNS)
    assertHeader(sources.deadlines, DEADLINES_COLUMNS)
    assertHeader(sources.aidPolicy, AID_POLICY_COLUMNS)
  }

  /** Loads the three seed files (header-asserted first) and computes the
   * launch-set coverage report from the DB. */
  suspend fun load(
    meritAidCsv: File,
    admissionFactorsCsv: File,
    deadlinesCsv: File,
    aidPolicyCsv: File,
  ): LoadResult =
    database.withConnection { session ->
      // Assert every header before any row of any file is written, so a renamed
      // column in file three never leaves files one and two half-trusted. Kept
      // here as well as at the ingest's up-front check because `load` is also
      // called directly (bin/ingest-colleges' CDS-only path, the suite), and
      // this is the loader's own contract, not the caller's. Read inside
      // withConnection so every blocking read this loader performs sits on the
      // same injected IO dispatcher -- `load` advertises no blocking-IO
      // requirement to its caller's dispatcher.
      // Wrapped at this call site, where the path the caller passed IS the
      // original argument; the ingest's up-front check passes the real
      // [CdsSources] and so carries the operator's own spelling.
      assertHeader(SourceFile(meritAidCsv, meritAidCsv.path), MERIT_AID_COLUMNS)
      assertHeader(SourceFile(admissionFactorsCsv, admissionFactorsCsv.path), ADMISSION_FACTORS_COLUMNS)
      assertHeader(SourceFile(deadlinesCsv, deadlinesCsv.path), DEADLINES_COLUMNS)
      assertHeader(SourceFile(aidPolicyCsv, aidPolicyCsv.path), AID_POLICY_COLUMNS)

      // The seed carries the two urls on every ROW because a CSV has no other
      // shape to carry them in; the database stores them ONCE, on the document
      // (RFC 170, D13). This value is how the four files agree about that one
      // document: the first row of a filing fixes its urls and a later row --
      // in this file or another -- naming different ones is refused, rather
      // than resolved by write order with nobody told.
      var filings = Filings()

      val meritAid =
        loadTable(session, meritAidCsv, Table.MERIT_AID, MERIT_AID_COLUMNS) { collegeId, record ->
          val (id, seen) = documentIdFor(session, collegeId, record, Table.MERIT_AID, filings)
          filings = seen
          id.thenWrite { document -> CdsAdmissionsDao.upsertMeritAid(session, mapMeritAid(collegeId, record, document)) }
        }
      val factors =
        loadTable(session, admissionFactorsCsv, Table.ADMISSION_FACTORS, ADMISSION_FACTORS_COLUMNS) { collegeId, record ->
          val (id, seen) = documentIdFor(session, collegeId, record, Table.ADMISSION_FACTORS, filings)
          filings = seen
          id.thenWrite { document ->
            CdsAdmissionsDao.upsertAdmissionFactors(session, mapAdmissionFactors(collegeId, record, document))
          }
        }
      val deadlines =
        loadTable(session, deadlinesCsv, Table.DEADLINES, DEADLINES_COLUMNS) { collegeId, record ->
          val (id, seen) = documentIdFor(session, collegeId, record, Table.DEADLINES, filings)
          filings = seen
          id.thenWrite { document -> CdsAdmissionsDao.upsertDeadline(session, mapDeadline(collegeId, record, document)) }
        }
      val aidPolicy = loadAidPolicy(session, aidPolicyCsv, filings)
      val coverage = CdsAdmissionsDao.getCoverage(session).getOrThrow()
      LoadResult(meritAid, factors, deadlines, aidPolicy, coverage)
    }

  /**
   * Streams one seed file's MATCHED rows: arity check, `unit_id` parse, and the
   * `colleges` lookup, in that order, for every row; returns the UNITIDs no
   * college carried.
   *
   * The one traversal every seed file gets, so the arity rule, the located
   * defects and the unmatched discipline cannot differ between them -- they
   * already had two copies, and the copies had already begun to differ. What a
   * caller does with a matched row is [onRow]'s business: a per-key upsert
   * tallies its outcome, the canonical rebuild accumulates rows to insert.
   */
  private fun forEachResolvedRow(
    session: SqlSession,
    file: File,
    table: Table,
    columns: List<String>,
    onRow: (CollegeId, Int, CSVRecord) -> Unit,
  ): List<Int> {
    val unmatched = mutableListOf<Int>()
    parseCsv(file).use { records ->
      for (record in records) {
        // The header assertion covers column NAMES only; this covers a
        // truncated or over-long row, whose record.get would otherwise throw an
        // unlocated IllegalArgumentException from Commons CSV -- outside this
        // loader's error contract and without the table or line number.
        if (!record.isConsistent || record.size() != columns.size) {
          throw FormatException(Defect.RowArity(table, record.recordNumber, record.size(), columns.size))
        }
        val rawIpedsUnitId = record.get("unit_id")
        val ipedsUnitId =
          rawIpedsUnitId.trim().toIntOrNull() ?: throw FormatException(
            Defect.NotAnInteger(table, record.recordNumber, "unit_id", rawIpedsUnitId),
          )
        val college =
          withRowLocation(table, record, ipedsUnitId) { CollegesDao.findByIpedsUnitId(session, ipedsUnitId) }
        if (college == null) {
          // The ONE tolerated mismatch: the corpus covers schools our
          // Scorecard snapshot lacks. Its identity is kept so the run report
          // can name it.
          unmatched += ipedsUnitId
          continue
        }
        onRow(college.id, ipedsUnitId, record)
      }
    }
    return unmatched
  }

  /**
   * Streams one seed file into a per-key upsert, tallying the [UpsertOutcome].
   * Any mapping or upsert failure propagates -- a machine-generated seed row
   * that fails is a broken seed, not a skippable line.
   */
  private fun loadTable(
    session: SqlSession,
    file: File,
    table: Table,
    columns: List<String>,
    upsert: (CollegeId, CSVRecord) -> Result<UpsertOutcome>,
  ): TableSummary {
    val tally = Tally()
    tally.recordUnmatched(
      forEachResolvedRow(session, file, table, columns) { collegeId, ipedsUnitId, record ->
        tally.record(withRowLocation(table, record, ipedsUnitId) { upsert(collegeId, record) })
      },
    )
    return tally.getSummary()
  }

  /** Runs one row's DB call, naming the table, CSV line and UNITID on failure
   * so a fault mid-load names the seed line that provoked it. */
  private fun <T> withRowLocation(
    table: Table,
    record: CSVRecord,
    ipedsUnitId: Int,
    call: () -> Result<T>,
  ): T =
    call().getOrElse { cause ->
      throw LoadException(table, record.recordNumber, ipedsUnitId, cause)
    }

  // ---------------------------------------------------------------------------
  // The aid-policy seed (RFC 170): canonical facts, not a table of their own.
  // ---------------------------------------------------------------------------

  /**
   * What one `aid_policy.csv` fact IS, in the canonical store's own terms. A
   * closed mapping keyed by the seed's `fact` column: an unknown fact is a
   * broken seed, never a row quietly dropped, and the destination of every
   * known one is stated here rather than inferred from its name.
   *
   * [table] is on the member because the rebuild's DELETE set is derived from
   * this mapping: route a new fact to a fourth table and it would otherwise be
   * inserted but never cleared, and the wholesale rebuild would quietly stop
   * being wholesale.
   */
  internal sealed interface AidPolicyFact {
    val table: FactTable

    /**
     * A cohort STATISTIC.
     *
     * [population] rides on the member because the CDS reports its H2 averages
     * over a DIFFERENT line from its fully-met headcount -- lines i and k are
     * "of students who were awarded any need-based aid" and "of those in line
     * e", while line h is "in line d". One population constant for the whole
     * group would have filed both averages against a larger cohort than the
     * school measured.
     *
     * The published-unit conversion is NOT here: it belongs to the measure's
     * own [ed.unicoach.db.models.MeasureUnit], which is where every other
     * loader reads it.
     */
    data class Stat(
      val measure: MoneyMeasure,
      val population: CohortPopulation,
      /**
       * The DENOMINATOR the measure is averaged over (RFC 175), stated by
       * every fact and DEFAULTED by none. It rides on the member for the same
       * reason [population] does, and it is a different question: [population]
       * is the cohort the CDS reports the line against, while this is the set
       * the average divides by. The H2 need figures are averaged over the
       * freshmen awarded need-based aid, each H5 borrowing average over the
       * borrowers OF ITS OWN LOAN TYPE.
       *
       * No default, because a default is a denominator nobody chose: a measure
       * added to the routing table would inherit `need_based_aid_receiving` in
       * silence, which is exactly the defect RFC 162 landed and RFC 170
       * repeated -- a number about one population stored under the name of
       * another. The table must show the whole decision.
       */
      val aidScope: CohortAidScope,
    ) : AidPolicyFact {
      override val table: FactTable get() = FactTable.COHORT_MONEY_STATS
    }

    /** A HEADCOUNT: not money, so `cohort_population_counts` (RFC 162). */
    data class Count(
      val population: CohortPopulation,
    ) : AidPolicyFact {
      override val table: FactTable get() = FactTable.COHORT_POPULATION_COUNTS
    }

    /** A REQUIREMENT: a relation, not a statistic (RFC 170, D4). */
    data class Form(
      val form: AidForm,
    ) : AidPolicyFact {
      override val table: FactTable get() = FactTable.AID_FORM_REQUIREMENTS
    }
  }

  /** Everything one aid-policy seed read produced, before a single row is written. */
  private data class ReadAidPolicy(
    val stats: List<NewCohortMoneyStat>,
    val counts: List<NewCohortPopulationCount>,
    val forms: List<NewAidFormRequirement>,
    val unmatchedIpedsUnitIds: List<Int>,
  )

  /**
   * Loads `aid_policy.csv` into the three canonical tables it belongs to,
   * inside the caller's transaction: read the file into rows, then rebuild.
   *
   * The two halves are separate functions because they answer different
   * questions -- what does the seed say, and what does the database now hold --
   * and because the read is the half that can fail on a malformed cell, before
   * anything has been deleted.
   */
  private fun loadAidPolicy(
    session: SqlSession,
    file: File,
    filings: Filings,
  ): AidPolicySummary = writeAidPolicy(session, readAidPolicy(session, file, filings))

  /**
   * `aid_policy.csv` as canonical rows. Writes ONE thing itself -- the
   * `source_documents` row each fact cites, which must exist before the fact
   * that references it can be inserted.
   */
  private fun readAidPolicy(
    session: SqlSession,
    file: File,
    filings: Filings,
  ): ReadAidPolicy {
    val stats = mutableListOf<NewCohortMoneyStat>()
    val counts = mutableListOf<NewCohortPopulationCount>()
    val forms = mutableListOf<NewAidFormRequirement>()
    var seen = filings
    val unmatched =
      forEachResolvedRow(session, file, Table.AID_POLICY, AID_POLICY_COLUMNS) { collegeId, ipedsUnitId, record ->
        val row = aidPolicyRow(session, collegeId, ipedsUnitId, record, seen)
        seen = row.filings
        // A pure router: which of the three canonical shapes this row IS, and
        // nothing else. The row building lives with the shape it builds.
        when (val fact = getCode(record, "fact", Table.AID_POLICY, AID_POLICY_FACTS)) {
          is AidPolicyFact.Stat -> stats += statOf(fact, row)
          is AidPolicyFact.Count -> counts += countOf(fact, row)
          is AidPolicyFact.Form -> forms += formOf(fact, row)
        }
      }
    return ReadAidPolicy(stats, counts, forms, unmatched)
  }

  /**
   * One aid-policy row's shared reading: everything the three canonical row
   * types need and none of them derive differently -- the college, the cycle,
   * the status, the published cell id, and the document this row cites.
   *
   * [filings] rides through as a VALUE rather than a mutated map: the filing
   * agreement check is order-dependent by nature, and a getter that quietly
   * writes to shared state is how that becomes accidental.
   */
  private data class AidPolicyRow(
    val record: CSVRecord,
    val collegeId: CollegeId,
    val year: AcademicYear,
    val status: FigureStatus,
    val sourceVariable: String,
    val document: SourceDocumentId,
    val filings: Filings,
  )

  private fun aidPolicyRow(
    session: SqlSession,
    collegeId: CollegeId,
    ipedsUnitId: Int,
    record: CSVRecord,
    filings: Filings,
  ): AidPolicyRow {
    val year = getAcademicYear(record, "source_year", Table.AID_POLICY)
    val filing = filingOf(record, collegeId, year, Table.AID_POLICY, filings)
    val document =
      withRowLocation(Table.AID_POLICY, record, ipedsUnitId) {
        SourceDocumentsDao.upsert(session, filing.document)
      }
    return AidPolicyRow(
      record = record,
      collegeId = collegeId,
      year = year,
      status = getCode(record, "status", Table.AID_POLICY, AID_POLICY_STATUSES),
      sourceVariable = getString(record, "source_variable", Table.AID_POLICY),
      document = document,
      filings = filing.filings,
    )
  }

  /** One H2 statistic: the measure's own population and its own published unit. */
  private fun statOf(
    fact: AidPolicyFact.Stat,
    row: AidPolicyRow,
  ) = NewCohortMoneyStat(
    collegeId = row.collegeId.value,
    measure = fact.measure,
    // The denominator, said out loud and per measure: the CDS reports each H2
    // line against a named earlier line, and the fact table's job is to carry
    // that population rather than a convenient one (RFC 162's scope rule).
    population = fact.population,
    residencyScope = CohortResidencyScope.ALL,
    // The scope follows the DENOMINATOR, per fact, for the same reason the
    // population does: the H2 averages divide by the freshmen awarded
    // need-based aid, each H5 average by that loan type's own borrowers.
    aidScope = fact.aidScope,
    incomeBand = null,
    vintage = row.year,
    reading =
      aidPolicyReading(
        row.record,
        row.status,
        ::decimalOrNull,
        fact.measure.unit::storedValueOf,
        { raw -> Defect.NotADecimal(Table.AID_POLICY, row.record.recordNumber, "value", raw) },
      ),
    source = MoneySource.COMMON_DATA_SET,
    sourceVariable = row.sourceVariable,
    sourceDocumentId = row.document,
  )

  /** One H2 headcount: not money, so the sibling table and the explicit not_applicable axes. */
  private fun countOf(
    fact: AidPolicyFact.Count,
    row: AidPolicyRow,
  ) = NewCohortPopulationCount(
    collegeId = row.collegeId.value,
    population = fact.population,
    // The CDS does not split these counts by residency or by way of living, and
    // the vocabulary has an explicit key for that: inapplicability is chosen,
    // never defaulted (RFC 158, P3).
    residencyBasis = ResidencyBasis.NOT_APPLICABLE,
    arrangement = FigureArrangement.NOT_APPLICABLE,
    vintage = row.year,
    reading = aidPolicyReading(row.record, row.status, String::toIntOrNull, { it }),
    source = MoneySource.COMMON_DATA_SET,
    sourceVariable = row.sourceVariable,
    sourceDocumentId = row.document,
  )

  /** One H8 requirement: `true` or nothing, of the domestic first-year group. */
  private fun formOf(
    fact: AidPolicyFact.Form,
    row: AidPolicyRow,
  ) = NewAidFormRequirement(
    collegeId = row.collegeId.value,
    form = fact.form,
    // The H8 block. The nonresident group (H7) is the other value the schema
    // admits and this seed does not carry.
    applicantGroup = AidFormApplicantGroup.DOMESTIC_FIRST_YEAR,
    academicYear = row.year,
    reading =
      aidPolicyReading(
        row.record,
        row.status,
        ::requiredFlag,
        { it },
        // A flag cell is a coded cell: say which value would have been
        // accepted, rather than calling `false` a bad integer.
        { raw ->
          Defect.UnknownCode(Table.AID_POLICY, row.record.recordNumber, "value", raw, listOf(REQUIRED_FLAG))
        },
      ),
    source = MoneySource.COMMON_DATA_SET,
    sourceVariable = row.sourceVariable,
    sourceDocumentId = row.document,
  )

  /**
   * Rebuilds the Common Data Set's share of the three canonical tables, and
   * only its share: the `canonical-money` phase owns the Scorecard and IPEDS
   * rows in the same tables and rebuilds them in the same run
   * ([CanonicalMoneyDao.deleteFactsOfSources]). Idempotency is by construction
   * (RFC 158 P12); a school that stops reporting a figure loses its row rather
   * than keeping last year's.
   */
  private fun writeAidPolicy(
    session: SqlSession,
    rows: ReadAidPolicy,
  ): AidPolicySummary {
    // DERIVED from the fact mapping, never a second list: a fact routed to a
    // new table is cleared because it is routed there, not because someone
    // remembered to add the table name here too.
    for (table in AID_POLICY_FACTS.values.map { it.table }.distinct()) {
      CanonicalMoneyDao.deleteFactsOfSources(session, table, listOf(MoneySource.COMMON_DATA_SET)).getOrThrow()
    }
    return AidPolicySummary(
      cohortMoneyStats = CanonicalMoneyDao.insertCohortMoneyStats(session, rows.stats).getOrThrow(),
      cohortPopulationCounts = CanonicalMoneyDao.insertCohortPopulationCounts(session, rows.counts).getOrThrow(),
      aidFormRequirements = CanonicalMoneyDao.insertAidFormRequirements(session, rows.forms).getOrThrow(),
      unmatchedIpedsUnitIds = rows.unmatchedIpedsUnitIds.distinct(),
    )
  }

  /**
   * The id of the `source_documents` row one seed row cites, upserted on its
   * natural key. Every one of the four seed files goes through here, so a
   * filing is one document however many rows and files name it.
   */
  private fun documentIdFor(
    session: SqlSession,
    collegeId: CollegeId,
    record: CSVRecord,
    table: Table,
    filings: Filings,
  ): Pair<Result<SourceDocumentId>, Filings> {
    val year = getAcademicYear(record, "source_year", table)
    val filing = filingOf(record, collegeId, year, table, filings)
    return SourceDocumentsDao.upsert(session, filing.document) to filing.filings
  }

  /**
   * Chains the document write into the fact write, keeping this loader's two
   * error kinds apart: a DB failure stays a [Result] (which the row-location
   * wrapper turns into a located [LoadException]), while a malformed CELL
   * still THROWS [FormatException] out of [block] as it always did.
   * `mapCatching` would have swallowed the second into the first, and every
   * "named by file and line" guarantee with it.
   */
  private inline fun <T, R> Result<T>.thenWrite(block: (T) -> Result<R>): Result<R> = fold(block) { Result.failure(it) }

  /**
   * The filings this load has read so far, as a VALUE.
   *
   * One filing has one pair of urls (D13), and the seed repeats them on every
   * row they back -- so the first row of a filing fixes them and a later row,
   * in this file or another, naming different ones is refused. Immutable
   * because the check is order-dependent by nature: a shared map written in
   * place by something that reads like a getter is how "first wins" quietly
   * becomes "whoever ran last".
   */
  @JvmInline
  internal value class Filings(
    private val documents: Map<Pair<CollegeId, AcademicYear>, NewSourceDocument> = emptyMap(),
  ) {
    /** The document already read for this filing, or null when it is the first. */
    fun first(
      collegeId: CollegeId,
      year: AcademicYear,
    ): NewSourceDocument? = documents[collegeId to year]

    /** This set plus [document] -- a new value, never a mutation of the old one. */
    fun with(document: NewSourceDocument): Filings = Filings(documents + ((document.collegeId to document.academicYear) to document))
  }

  /** One row's filing and the filing set that now includes it. */
  private data class ReadFiling(
    val document: NewSourceDocument,
    val filings: Filings,
  )

  /**
   * The filing one seed row cites, refusing a second row that names a DIFFERENT
   * url for the same (college, cycle).
   *
   * D13 stores one copy of the two urls, which is what stops them disagreeing
   * in the database -- but a seed carrying two urls for one filing would then
   * be resolved by write order, unreported. This is where that is a located
   * defect instead. PURE: it returns the filing set it would have written.
   */
  private fun filingOf(
    record: CSVRecord,
    collegeId: CollegeId,
    year: AcademicYear,
    table: Table,
    filings: Filings,
  ): ReadFiling {
    val document =
      NewSourceDocument(
        collegeId = collegeId,
        source = MoneySource.COMMON_DATA_SET,
        academicYear = year,
        sourceUrl = getString(record, "source_url", table),
        archiveUrl = getStringOrNull(record, "archive_url"),
      )
    val first = filings.first(collegeId, year)
    if (first != null && first != document) {
      throw FormatException(Defect.ConflictingFilingUrls(table, record.recordNumber, first, document))
    }
    return ReadFiling(document, filings.with(document))
  }

  /**
   * The seed's value and status as ONE reading, whatever the value's type.
   *
   * The seed states the pairing and this is where a seed that breaks it is
   * refused: a value under a status that bears none, or a value-bearing status
   * with an empty cell, is a broken seed rather than a row to patch up. [parse]
   * is the cell's own reading and [scale] its published-unit conversion, so the
   * three fact kinds share one rule instead of three copies of it.
   *
   * [unreadable] names the defect a bad cell IS, and defaults to the numeric
   * one the two figure kinds want: "is not an integer" is the wrong sentence
   * about a checkbox, and the message an operator reads is the whole product of
   * this loader's error contract.
   */
  private fun <T> aidPolicyReading(
    record: CSVRecord,
    status: FigureStatus,
    parse: (String) -> T?,
    scale: (T) -> T,
    unreadable: (String) -> Defect = { raw -> Defect.NotAnInteger(Table.AID_POLICY, record.recordNumber, "value", raw) },
  ): FigureReading<T> {
    val raw =
      valueCell(record, status) ?: return FigureReading.Absent(
        // The partitions are total on FigureStatus and the branch above proved
        // which half this status is in, so neither `?:` can fire -- and if the
        // partition ever stops being total, it says so here rather than
        // defaulting to a status the seed did not state.
        AbsenceStatus.ofOrNull(status) ?: throw FormatException(mapUnpartitionedStatusDefect(record, status)),
      )
    val value = parse(raw) ?: throw FormatException(unreadable(raw))
    return FigureReading.Present(
      scale(value),
      ValueBearingStatus.ofOrNull(status) ?: throw FormatException(mapUnpartitionedStatusDefect(record, status)),
    )
  }

  /** A status that is in neither half of the value-bearing partition -- a code defect, reported as a located one. */
  private fun mapUnpartitionedStatusDefect(
    record: CSVRecord,
    status: FigureStatus,
  ): Defect =
    Defect.UnknownCode(
      table = Table.AID_POLICY,
      line = record.recordNumber,
      column = "status",
      value = status.value,
      allowed = AID_POLICY_STATUSES.keys.toList(),
    )

  /**
   * A decimal cell, strictly: digits with at most one decimal point.
   *
   * NOT `toDoubleOrNull`, which accepts `NaN`, `Infinity`, `0x1p3` and a
   * trailing `d`/`f` -- so a NaN cell was scaled, inserted, and refused by the
   * database with no file or line on the message. The integer twin has always
   * been strict; this is the same rule for the money and percent cells.
   */
  private fun decimalOrNull(raw: String): Double? = if (DECIMAL.matches(raw)) raw.toDoubleOrNull() else null

  /**
   * A form requirement's value: `true`, and nothing else.
   *
   * `false` is refused rather than stored (RFC 170, D5). No source publishes a
   * negative, so a `false` in the seed means the fetcher changed its mind about
   * what an unticked box means, and storing it would turn "not listed in this
   * school's CDS" into the promise "this school does not require it".
   */
  private fun requiredFlag(raw: String): Boolean? = if (raw == REQUIRED_FLAG) true else null

  /** The raw value cell for a value-bearing status, or null when the status bears none. */
  private fun valueCell(
    record: CSVRecord,
    status: FigureStatus,
  ): String? {
    val raw = getStringOrNull(record, "value")
    if (!status.valueBearing) {
      if (raw != null) {
        throw FormatException(
          Defect.ValueUnderValuelessStatus(Table.AID_POLICY, record.recordNumber, status.value, raw),
        )
      }
      return null
    }
    return raw ?: throw FormatException(Defect.EmptyRequiredCell(Table.AID_POLICY, record.recordNumber, "value"))
  }

  // ---------------------------------------------------------------------------
  // Row mapping -- pure CSV-to-model, fatal on any malformed cell.
  // ---------------------------------------------------------------------------

  private fun mapMeritAid(
    collegeId: CollegeId,
    record: CSVRecord,
    sourceDocumentId: SourceDocumentId,
  ) = NewCollegeMeritAid(
    collegeId = collegeId,
    sourceYear = getInt(record, "source_year", Table.MERIT_AID),
    firstTimeFullTimeFreshmenHeadcount = getIntOrNull(record, "freshmen_ft_total", Table.MERIT_AID),
    noNeedMeritRecipientsHeadcount = getIntOrNull(record, "no_need_merit_count", Table.MERIT_AID),
    noNeedMeritAverageUsd = getIntOrNull(record, "no_need_merit_avg", Table.MERIT_AID),
    sourceDocumentId = sourceDocumentId,
  )

  private fun mapAdmissionFactors(
    collegeId: CollegeId,
    record: CSVRecord,
    sourceDocumentId: SourceDocumentId,
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
      sourceDocumentId = sourceDocumentId,
    )
  }

  private fun mapDeadline(
    collegeId: CollegeId,
    record: CSVRecord,
    sourceDocumentId: SourceDocumentId,
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
      sourceDocumentId = sourceDocumentId,
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
   * Asserts [source]'s header row is EXACTLY [expected], in order, before
   * anything is loaded. Missing and unexpected columns are each named; order
   * drift reports both lists. Renames therefore fail loudly instead of loading
   * NULLs (the stale-seed failure this loader exists to prevent).
   *
   * Takes the [SourceFile], not the bare `File`, so the defect names the
   * resolved path AND the argument the operator typed -- the same payload the
   * other seven files of the up-front check already report.
   */
  private fun assertHeader(
    source: SourceFile,
    expected: List<String>,
  ) {
    val actual =
      parseCsv(source.file).use { parser ->
        parser.headerNames.toList()
      }
    if (actual == expected) return
    throw FormatException(Defect.HeaderMismatch(source.file.path, source.sourceArg, expected, actual))
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

  /**
   * A year cell as an [AcademicYear], with the range refusal LOCATED.
   *
   * The type carries the same range its SQL domain states, so constructing one
   * from a raw cell would otherwise throw an unlocated
   * `IllegalArgumentException` -- outside this loader's contract that every
   * malformed cell names its file, line and column.
   */
  private fun getAcademicYear(
    record: CSVRecord,
    column: String,
    table: Table,
  ): AcademicYear {
    val year = getInt(record, column, table)
    if (year !in AcademicYear.FIRST_YEAR..AcademicYear.LAST_YEAR) {
      throw FormatException(Defect.NotAnAcademicYear(table, record.recordNumber, column, year))
    }
    return AcademicYear(year)
  }

  /**
   * A coded cell decoded through [allowed], or a located [Defect.UnknownCode]
   * naming every value that would have been accepted.
   *
   * The ONE place a seed's coded column becomes a typed member: five sites used
   * to write the lookup-else-UnknownCode block themselves, and they had already
   * begun to disagree about what `allowed` means.
   */
  private fun <T> getCode(
    record: CSVRecord,
    column: String,
    table: Table,
    allowed: Map<String, T>,
  ): T {
    val raw = getString(record, column, table)
    return allowed[raw] ?: throw FormatException(
      Defect.UnknownCode(table, record.recordNumber, column, raw, allowed.keys.toList()),
    )
  }

  /** [getCode] over an enumeration's own values -- the seed's usual case. */
  private fun <T> getEnumCode(
    record: CSVRecord,
    column: String,
    table: Table,
    values: List<T>,
    slugOf: (T) -> String,
  ): T = getCode(record, column, table, values.associateBy(slugOf))

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

    val AID_POLICY_COLUMNS =
      listOf(
        "unit_id",
        "source_year",
        "fact",
        "value",
        "status",
        "source_variable",
        "source_url",
        "archive_url",
      )

    /**
     * The statuses this seed can carry -- the three `bin/fetch-cds-seed`
     * writes, not all six [FigureStatus] members.
     *
     * A seed row is not free to claim `imputed_by_publisher`: the Common Data
     * Set publishes no imputation, so accepting the slug would let a value-
     * bearing status the generator cannot produce enter the store unremarked.
     */
    internal val AID_POLICY_STATUSES: Map<String, FigureStatus> =
      listOf(FigureStatus.REPORTED, FigureStatus.NOT_COLLECTED_BY_US, FigureStatus.NOT_APPLICABLE)
        .associateBy { it.value }

    /**
     * The ONE token a form-requirement cell may carry, read by the parser and
     * named in the operator-facing allowed list, so the two cannot disagree
     * about what the seed is permitted to say.
     */
    internal const val REQUIRED_FLAG = "true"

    /** A decimal seed cell: digits, optionally one decimal point. Never NaN, Infinity or a hex float. */
    private val DECIMAL = Regex("""\d+(\.\d+)?""")

    /**
     * H4/H5 borrowing at graduation (RFC 175), DERIVED from [LoanType] rather
     * than hand-listed.
     *
     * That enum is the ONE place a loan type's measure, its DENOMINATOR and its
     * borrower cohort are tied together, and the seed's own column names are
     * the member's own slug plus the suffix for what the cell holds. A second
     * hand-written copy of the triple here could drift from it silently: the
     * READ ([ed.unicoach.db.dao.BorrowingDao]) refuses a measure stored at a
     * scope that is not its own denominator, so the drift would land as a
     * production corrupt-value fault rather than a compile error -- and a
     * fixture that exercised three of the five loan types would be green while
     * it did.
     *
     * Declared ABOVE [AID_POLICY_FACTS] so companion initialisation order is
     * satisfied.
     */
    private val BORROWING_FACTS: Map<String, AidPolicyFact> =
      LoanType.entries
        .flatMap { loanType ->
          listOf(
            "${loanType.slug}_borrower_count" to AidPolicyFact.Count(loanType.borrowers),
            "${loanType.slug}_debt_avg_usd" to
              AidPolicyFact.Stat(loanType.debtAverage, CohortPopulation.GRADUATING_CLASS, loanType.aidScope),
          )
        }.toMap()

    /**
     * The seed's `fact` vocabulary, and what each fact IS in the canonical
     * store (RFC 170). Written here, once: the seed's own names are a CSV
     * detail, and every one of them must resolve to a measure, a population or
     * a form -- an unknown one is a broken seed.
     *
     * The published-unit conversion is not here: it is read off the measure's
     * own MeasureUnit, which is where every loader reads it.
     */
    internal val AID_POLICY_FACTS: Map<String, AidPolicyFact> =
      mapOf(
        // Lines i and k: both averages are reported over line e, the freshmen
        // awarded need-based scholarship or grant aid.
        "avg_need_met_percent" to
          AidPolicyFact.Stat(
            MoneyMeasure.AVG_NEED_MET_SHARE,
            CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_NEED_BASED_GRANT,
            CohortAidScope.NEED_BASED_AID_RECEIVING,
          ),
        "avg_need_based_grant_usd" to
          AidPolicyFact.Stat(
            MoneyMeasure.AVG_NEED_BASED_GRANT,
            CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_NEED_BASED_GRANT,
            CohortAidScope.NEED_BASED_AID_RECEIVING,
          ),
        // Line d, whose only role is to be line h's denominator.
        "aid_awarded_freshmen_count" to
          AidPolicyFact.Count(CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_ANY_AID),
        "need_fully_met_count" to
          AidPolicyFact.Count(CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_NEED_FULLY_MET),
        "fafsa_required" to AidPolicyFact.Form(AidForm.FAFSA),
        "institutional_form_required" to AidPolicyFact.Form(AidForm.INSTITUTIONAL),
        "css_profile_required" to AidPolicyFact.Form(AidForm.CSS_PROFILE),
        "state_aid_form_required" to AidPolicyFact.Form(AidForm.STATE),
        "noncustodial_css_profile_required" to AidPolicyFact.Form(AidForm.NONCUSTODIAL_CSS_PROFILE),
        "business_farm_supplement_required" to AidPolicyFact.Form(AidForm.BUSINESS_FARM_SUPPLEMENT),
        "other_institutional_form_required" to AidPolicyFact.Form(AidForm.OTHER_INSTITUTIONAL),
        // H4, the class every H5 figure is reported over. Named here rather
        // than derived, because it belongs to NO loan type: it is the
        // denominator all five divide by.
        "graduating_class_count" to AidPolicyFact.Count(CohortPopulation.GRADUATING_CLASS),
      ) + BORROWING_FACTS

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
      "[${defect.file}] (from [${defect.sourceArg}]) header mismatch: missing columns ${defect.missing}, " +
        "unexpected columns ${defect.unexpected} (expected ${defect.expected}, got ${defect.actual})"
    }

    is CdsSeedLoader.Defect.RowArity -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.cells}] cells, expected [${defect.expectedCells}]"
    }

    is CdsSeedLoader.Defect.NotAnInteger -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.column}] value [${defect.value}] is not an integer"
    }

    is CdsSeedLoader.Defect.NotAnAcademicYear -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.column}] value [${defect.value}] is not an " +
        "academic year (the range is [${AcademicYear.FIRST_YEAR}..${AcademicYear.LAST_YEAR}])"
    }

    is CdsSeedLoader.Defect.NotADecimal -> {
      "[${defect.table.label}] line [${defect.line}]: [${defect.column}] value [${defect.value}] is not a decimal " +
        "number (digits, optionally one decimal point)"
    }

    is CdsSeedLoader.Defect.ValueUnderValuelessStatus -> {
      "[${defect.table.label}] line [${defect.line}]: status [${defect.status}] bears no value, but [value] " +
        "carries [${defect.value}]"
    }

    is CdsSeedLoader.Defect.ConflictingFilingUrls -> {
      "[${defect.table.label}] line [${defect.line}]: this filing " +
        "(college [${defect.second.collegeId.value}], year [${defect.second.academicYear.label}]) " +
        "was already read with " +
        "source_url [${defect.first.sourceUrl}] archive_url [${defect.first.archiveUrl}], and this row names " +
        "source_url [${defect.second.sourceUrl}] archive_url [${defect.second.archiveUrl}]; one filing has one " +
        "pair of urls"
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
