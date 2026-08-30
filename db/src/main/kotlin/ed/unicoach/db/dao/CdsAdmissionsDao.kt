package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.AdmissionFactor
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsCoverage
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CollegeAdmissionFactors
import ed.unicoach.db.models.CollegeAdmissionFactorsId
import ed.unicoach.db.models.CollegeDeadline
import ed.unicoach.db.models.CollegeDeadlineId
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMeritAid
import ed.unicoach.db.models.CollegeMeritAidId
import ed.unicoach.db.models.FactorRating
import ed.unicoach.db.models.NewCollegeAdmissionFactors
import ed.unicoach.db.models.NewCollegeDeadline
import ed.unicoach.db.models.NewCollegeMeritAid
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the three CDS admissions reference tables (RFC 140):
 * `college_merit_aid`, `college_admission_factors`, and `college_deadlines`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller (the [CollegesDao] shape). Every upsert goes through the shared
 * [upsertDetectingChange] primitive: an `INSERT ... ON CONFLICT` on the table's
 * natural key whose `DO UPDATE` carries an `IS DISTINCT FROM` row-tuple guard,
 * so a re-ingest of an unchanged row writes nothing and does not advance
 * `updated_at`. Unlike `colleges` these tables are unversioned (`source_year`
 * in the natural key makes history explicit), so instead of a version bump each
 * write reports a per-row [UpsertOutcome], which the ingest folds into its
 * inserted/changed/unchanged summary.
 */
object CdsAdmissionsDao {
  // ---------------------------------------------------------------------------
  // Column tables (the one place a column list is written)
  // ---------------------------------------------------------------------------

  /**
   * The CDS C7 rating columns in schema order, each paired with the accessor
   * that reads it off a row being written. The insert list, the
   * `DO UPDATE SET`, both sides of the change guard and the bind order are all
   * derived from this one table.
   *
   * DERIVED from [AdmissionFactor], never re-listed: that enum already owns the
   * eighteen column names (its `value` IS the column) plus the words the coach
   * says for each. A nineteenth factor typed here and nowhere else would be
   * ingested and then never rendered, silently; deriving means the member and
   * its migration are the whole change.
   *
   * Internal rather than private so `CdsAdmissionsDaoTest` can pin it against
   * the enum AND against the columns the migration really created.
   */
  internal val FACTOR_COLUMNS: List<Pair<String, (NewCollegeAdmissionFactors) -> FactorRating?>> =
    AdmissionFactor.entries.map { factor -> factor.value to { row: NewCollegeAdmissionFactors -> factor.ratingOf(row) } }

  // ---------------------------------------------------------------------------
  // Row mappers
  // ---------------------------------------------------------------------------

  /** Reads a rating column through [FactorRating.fromValue]; a persisted value
   * outside the enum (impossible under the `cds_factor_rating` domain) fails loudly. */
  private fun ResultSet.getRatingOrNull(column: String): FactorRating? {
    val raw = getString(column) ?: return null
    return FactorRating.fromValue(raw)
      ?: throw CorruptPersistedValueException(
        raw,
        ValidationError.InvalidFormat(expected = "a known FactorRating value"),
        // Eighteen rating columns share this mapper: without the column and the
        // row id the log names neither, and triage starts with a table scan.
        location = "college_admission_factors.[$column] (row [${getString("id")}])",
      )
  }

  /**
   * Reads a stored month/day pair. A null month is "not reported" -- the whole
   * pair is null; a day without a month cannot be stored
   * (`college_deadlines_day_requires_month_check`) and is unrepresentable in
   * [CdsMonthDay].
   */
  private fun ResultSet.getMonthDayOrNull(
    monthColumn: String,
    dayColumn: String,
  ): CdsMonthDay? {
    val month = getIntOrNull(monthColumn) ?: return null
    val day = getIntOrNull(dayColumn)
    // [CdsMonthDay] refuses an impossible pair; a stored one (only reachable by
    // a hand-written INSERT) is a corrupt row, reported as one and located.
    if (!CdsMonthDay.isCalendarPair(month, day)) {
      throw CorruptPersistedValueException(
        "$month/$day",
        ValidationError.InvalidFormat(expected = "a real calendar month/day"),
        location = "college_deadlines.[$monthColumn]/[$dayColumn] (row [${getString("id")}])",
      )
    }
    return CdsMonthDay(month, day)
  }

  private fun mapMeritAid(rs: ResultSet): CollegeMeritAid =
    CollegeMeritAid(
      id = CollegeMeritAidId(UUID.fromString(rs.getString("id"))),
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      sourceYear = rs.getInt("source_year"),
      freshmenFtTotal = rs.getIntOrNull("freshmen_ft_total"),
      noNeedMeritCount = rs.getIntOrNull("no_need_merit_count"),
      noNeedMeritAvg = rs.getIntOrNull("no_need_merit_avg"),
      sourceUrl = rs.getString("source_url"),
      archiveUrl = rs.getString("archive_url"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
    )

  private fun mapFactors(rs: ResultSet): CollegeAdmissionFactors =
    CollegeAdmissionFactors(
      id = CollegeAdmissionFactorsId(UUID.fromString(rs.getString("id"))),
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      sourceYear = rs.getInt("source_year"),
      rigor = rs.getRatingOrNull("rigor"),
      classRank = rs.getRatingOrNull("class_rank"),
      gpa = rs.getRatingOrNull("gpa"),
      testScores = rs.getRatingOrNull("test_scores"),
      essay = rs.getRatingOrNull("essay"),
      recommendations = rs.getRatingOrNull("recommendations"),
      interview = rs.getRatingOrNull("interview"),
      extracurriculars = rs.getRatingOrNull("extracurriculars"),
      talent = rs.getRatingOrNull("talent"),
      characterQualities = rs.getRatingOrNull("character_qualities"),
      firstGeneration = rs.getRatingOrNull("first_generation"),
      alumniRelation = rs.getRatingOrNull("alumni_relation"),
      geography = rs.getRatingOrNull("geography"),
      stateResidency = rs.getRatingOrNull("state_residency"),
      religiousAffiliation = rs.getRatingOrNull("religious_affiliation"),
      volunteerWork = rs.getRatingOrNull("volunteer_work"),
      workExperience = rs.getRatingOrNull("work_experience"),
      applicantInterest = rs.getRatingOrNull("applicant_interest"),
      sourceUrl = rs.getString("source_url"),
      archiveUrl = rs.getString("archive_url"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
    )

  private fun mapDeadline(rs: ResultSet): CollegeDeadline {
    val roundRaw = rs.getString("round")
    return CollegeDeadline(
      id = CollegeDeadlineId(UUID.fromString(rs.getString("id"))),
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      sourceYear = rs.getInt("source_year"),
      round =
        ApplicationRound.fromValue(roundRaw)
          ?: throw CorruptPersistedValueException(
            roundRaw,
            ValidationError.InvalidFormat(expected = "a known ApplicationRound value"),
            location = "college_deadlines.round (row [${rs.getString("id")}])",
          ),
      offered = rs.getBoolean("offered"),
      closing = rs.getMonthDayOrNull("closing_month", "closing_day"),
      notification = rs.getMonthDayOrNull("notification_month", "notification_day"),
      sourceUrl = rs.getString("source_url"),
      archiveUrl = rs.getString("archive_url"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
    )
  }

  // ---------------------------------------------------------------------------
  // Upserts (change-detecting, via the shared primitive)
  // ---------------------------------------------------------------------------

  /** The `(college_id, source_year)` natural key shared by the two wide tables. */
  private fun mapCycleKey(
    collegeId: CollegeId,
    sourceYear: Int,
  ): Map<String, Bind> =
    linkedMapOf(
      "college_id" to { stmt: PreparedStatement, i: Int -> stmt.setObject(i, collegeId.value) },
      "source_year" to { stmt: PreparedStatement, i: Int -> stmt.setInt(i, sourceYear) },
    )

  /** The provenance pair every CDS fact row carries. */
  private fun mapProvenance(
    sourceUrl: String,
    archiveUrl: String?,
  ): Map<String, Bind> =
    linkedMapOf(
      "source_url" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, sourceUrl) },
      "archive_url" to { stmt: PreparedStatement, i: Int -> stmt.setStringOrNull(i, archiveUrl) },
    )

  /**
   * Upserts one H2A merit-aid row on `(college_id, source_year)`. On conflict
   * every fact column is overwritten from [input] only when some value actually
   * differs; `id`/`created_at` are preserved and the `_03` trigger advances
   * `updated_at` on a real write.
   */
  fun upsertMeritAid(
    session: SqlSession,
    input: NewCollegeMeritAid,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "college_merit_aid",
      keyColumns = mapCycleKey(input.collegeId, input.sourceYear),
      columns =
        linkedMapOf<String, Bind>(
          "freshmen_ft_total" to { stmt, i -> stmt.setIntOrNull(i, input.freshmenFtTotal) },
          "no_need_merit_count" to { stmt, i -> stmt.setIntOrNull(i, input.noNeedMeritCount) },
          "no_need_merit_avg" to { stmt, i -> stmt.setIntOrNull(i, input.noNeedMeritAvg) },
        ) + mapProvenance(input.sourceUrl, input.archiveUrl),
      mapError = writeError("college_merit_aid", input.collegeId, input.sourceYear),
    )

  /**
   * Upserts one C7 factor-grid row on `(college_id, source_year)`, the same
   * change-detecting shape as [upsertMeritAid] over the [FACTOR_COLUMNS] grid.
   */
  fun upsertAdmissionFactors(
    session: SqlSession,
    input: NewCollegeAdmissionFactors,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "college_admission_factors",
      keyColumns = mapCycleKey(input.collegeId, input.sourceYear),
      columns =
        FACTOR_COLUMNS.associateTo(LinkedHashMap<String, Bind>()) { (column, read) ->
          column to { stmt: PreparedStatement, i: Int -> stmt.setStringOrNull(i, read(input)?.value) }
        } + mapProvenance(input.sourceUrl, input.archiveUrl),
      mapError = writeError("college_admission_factors", input.collegeId, input.sourceYear),
    )

  /**
   * Upserts one application-round row on `(college_id, source_year, round)`,
   * the same change-detecting shape as [upsertMeritAid].
   */
  fun upsertDeadline(
    session: SqlSession,
    input: NewCollegeDeadline,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "college_deadlines",
      keyColumns =
        mapCycleKey(input.collegeId, input.sourceYear) +
          linkedMapOf<String, Bind>(
            "round" to { stmt, i -> stmt.setString(i, input.round.value) },
          ),
      columns =
        linkedMapOf<String, Bind>(
          "offered" to { stmt, i -> stmt.setBoolean(i, input.offered) },
          "closing_month" to { stmt, i -> stmt.setIntOrNull(i, input.closing?.month) },
          "closing_day" to { stmt, i -> stmt.setIntOrNull(i, input.closing?.day) },
          "notification_month" to { stmt, i -> stmt.setIntOrNull(i, input.notification?.month) },
          "notification_day" to { stmt, i -> stmt.setIntOrNull(i, input.notification?.day) },
        ) + mapProvenance(input.sourceUrl, input.archiveUrl),
      mapError = writeError("college_deadlines", input.collegeId, input.sourceYear),
    )

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** One college's merit-aid row for one CDS cycle, or null when unreported. */
  fun findMeritAid(
    session: SqlSession,
    collegeId: CollegeId,
    sourceYear: Int,
  ): Result<CollegeMeritAid?> =
    session
      .queryOne(
        "SELECT * FROM college_merit_aid WHERE college_id = ? AND source_year = ?",
        bind = {
          it.setObject(1, collegeId.value)
          it.setInt(2, sourceYear)
        },
        map = ::mapMeritAid,
      ).orNullOnNotFound()

  /** One college's C7 factor grid for one CDS cycle, or null when unreported. */
  fun findAdmissionFactors(
    session: SqlSession,
    collegeId: CollegeId,
    sourceYear: Int,
  ): Result<CollegeAdmissionFactors?> =
    session
      .queryOne(
        "SELECT * FROM college_admission_factors WHERE college_id = ? AND source_year = ?",
        bind = {
          it.setObject(1, collegeId.value)
          it.setInt(2, sourceYear)
        },
        map = ::mapFactors,
      ).orNullOnNotFound()

  /** One college's application rounds for one CDS cycle, ordered by round. */
  fun listDeadlines(
    session: SqlSession,
    collegeId: CollegeId,
    sourceYear: Int,
  ): Result<List<CollegeDeadline>> =
    session.queryList(
      "SELECT * FROM college_deadlines WHERE college_id = ? AND source_year = ? ORDER BY round",
      bind = {
        it.setObject(1, collegeId.value)
        it.setInt(2, sourceYear)
      },
      map = ::mapDeadline,
    )

  // ---------------------------------------------------------------------------
  // Latest-cycle batch reads (RFC 148)
  // ---------------------------------------------------------------------------

  /**
   * The chat read path (RFC 148) needs the newest cycle a college actually
   * reported, and the corpus resolves that **per table**: S4a keeps the newest
   * document that reports each fact group, so one college can hold a 2024
   * factor grid beside a 2025 merit row. `DISTINCT ON (college_id)` over
   * `source_year DESC` therefore runs per table, and no year is ever written
   * down by a caller -- a hardcoded cycle would silently drop the half of the
   * corpus published in the other one.
   *
   * Batch by construction: one query per table for the whole answer, never one
   * per college. An empty [collegeIds] short-circuits without a query; a
   * college with no row is simply absent from the result, which the caller
   * reports as "this school does not report it" rather than as a zero.
   */
  private fun <T> listLatestPerCollege(
    session: SqlSession,
    source: LatestSource,
    collegeIds: Collection<CollegeId>,
    map: (ResultSet) -> T,
  ): Result<List<T>> {
    val ids = collegeIds.distinct()
    if (ids.isEmpty()) return Result.success(emptyList())
    val placeholders = ids.joinToString(", ") { "?" }
    // Both clauses are written from the ONE key list on the enum member:
    // Postgres requires the DISTINCT ON keys to be the leftmost ORDER BY terms,
    // and two separately spelled strings are two things a caller has to keep in
    // agreement.
    val keys = source.distinctOn.joinToString(", ")
    return session.queryList(
      "SELECT DISTINCT ON ($keys) * FROM ${source.table} " +
        "WHERE college_id IN ($placeholders) " +
        "ORDER BY $keys, source_year DESC",
      bind = { stmt -> ids.forEachIndexed { i, id -> stmt.setObject(i + 1, id.value) } },
      map = map,
    )
  }

  /**
   * The three CDS reference tables this DAO reads latest-cycle rows from, each
   * with the key columns its newest row is resolved per. A CLOSED set, because
   * both the table name and the keys reach SQL as text: a value outside this
   * set is not a different read, it is a defect, and it cannot be written.
   *
   * The keys are a LIST, not a flattened `"college_id, round"` fragment: they
   * are spliced into `DISTINCT ON` and into the leftmost `ORDER BY` terms, so
   * the helper -- not the caller -- owns that spelling and that Postgres rule.
   */
  private enum class LatestSource(
    val table: String,
    val distinctOn: List<String>,
  ) {
    MERIT_AID("college_merit_aid", listOf("college_id")),
    ADMISSION_FACTORS("college_admission_factors", listOf("college_id")),

    /** Keyed `(college_id, round)`: a deadline row is per round, so each round resolves to its own newest cycle. */
    DEADLINES("college_deadlines", listOf("college_id", "round")),
  }

  /** Each college's merit-aid row from its own latest CDS cycle; colleges with no row are absent. */
  fun listLatestMeritAid(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): Result<List<CollegeMeritAid>> =
    listLatestPerCollege(
      session,
      source = LatestSource.MERIT_AID,
      collegeIds = collegeIds,
      map = ::mapMeritAid,
    )

  /** Each college's C7 factor grid from its own latest CDS cycle; colleges with no grid are absent. */
  fun listLatestAdmissionFactors(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): Result<List<CollegeAdmissionFactors>> =
    listLatestPerCollege(
      session,
      source = LatestSource.ADMISSION_FACTORS,
      collegeIds = collegeIds,
      map = ::mapFactors,
    )

  /**
   * Each college's application rounds, keyed `(college_id, round)` so a round
   * resolves to its own newest cycle -- the extra key the two wide tables do
   * not need, because a deadline row is per round.
   */
  fun listLatestDeadlines(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): Result<List<CollegeDeadline>> =
    listLatestPerCollege(
      session,
      source = LatestSource.DEADLINES,
      collegeIds = collegeIds,
      map = ::mapDeadline,
    )

  // ---------------------------------------------------------------------------
  // Launch-set coverage (RFC 140)
  // ---------------------------------------------------------------------------

  /**
   * Computes the launch-set coverage report from the DB, not from the run's own
   * counters: the launch set is every college with at least one CDS fact row
   * across the three tables; per-group counts are distinct colleges;
   * `deadlinesWithDateCount` requires a COMPLETE month+day (a month-only date
   * is real CDS reporting and is stored, but "March, day unreported" is not a
   * concrete date and must not inflate the gate's number); and
   * `studentListedMissing` names the colleges on ANY student's active college
   * list (`college_list_entries`, soft-delete respected) that have no CDS row
   * at all -- so college-list popularity is monitored explicitly, not assumed.
   */
  fun getCoverage(session: SqlSession): Result<CdsCoverage> {
    val sql =
      """
      WITH cds_colleges AS (
        SELECT college_id FROM college_merit_aid
        UNION
        SELECT college_id FROM college_admission_factors
        UNION
        SELECT college_id FROM college_deadlines
      )
      SELECT
        (SELECT count(*) FROM cds_colleges) AS launch_set,
        (SELECT count(DISTINCT college_id) FROM college_merit_aid) AS merit_aid,
        (SELECT count(DISTINCT college_id) FROM college_admission_factors) AS admission_factors,
        (SELECT count(DISTINCT college_id) FROM college_deadlines) AS deadlines_flags,
        (SELECT count(DISTINCT college_id) FROM college_deadlines
         WHERE (closing_month IS NOT NULL AND closing_day IS NOT NULL)
            OR (notification_month IS NOT NULL AND notification_day IS NOT NULL)) AS deadlines_dated,
        (SELECT coalesce(array_agg(DISTINCT c.name), ARRAY[]::text[])
         FROM college_list_entries e
         JOIN colleges c ON c.id = e.college_id
         WHERE e.deleted_at IS NULL
           AND NOT EXISTS (SELECT 1 FROM cds_colleges x WHERE x.college_id = e.college_id)) AS student_listed_missing
      """.trimIndent()
    return session.queryOne(
      sql,
      bind = {},
      map = { rs ->
        CdsCoverage(
          launchSetCount = rs.getInt("launch_set"),
          meritAidCount = rs.getInt("merit_aid"),
          admissionFactorsCount = rs.getInt("admission_factors"),
          deadlinesFlagsCount = rs.getInt("deadlines_flags"),
          deadlinesWithDateCount = rs.getInt("deadlines_dated"),
          studentListedMissing = rs.getStringList("student_listed_missing"),
        )
      },
    )
  }

  // ---------------------------------------------------------------------------
  // Error mapping
  // ---------------------------------------------------------------------------

  /**
   * The shared reference-table write-path mapping, built per write so a
   * `23503` names the key that failed -- the absent `colleges` id, the table
   * being written and the CDS cycle -- rather than one constant sentence shared
   * by three tables and thousands of rows.
   */
  private fun writeError(
    table: String,
    collegeId: CollegeId,
    sourceYear: Int,
  ): (SQLException) -> Exception =
    { e ->
      mapReferenceWriteError(
        e,
        "No colleges row [${collegeId.value}] for [$table] write at source_year [$sourceYear]",
      )
    }
}
