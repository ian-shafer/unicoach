package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CipCode
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CredentialLevel
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ProgramMoneyMeasure
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * The program-grained money store (RFC 218): `program_money_stats`, created by
 * `db/schema/create-program-money-stats.sql`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller -- the [CanonicalMoneyDao] shape, and raw JDBC with `Result<T>`
 * returns rather than an ORM. The table is never upserted: the
 * `program-money` phase is a wholesale DELETE + batch re-insert, because the
 * Scorecard field-of-study file is a SNAPSHOT and this table is a projection of
 * it, so idempotency is by construction.
 *
 * It is a SIBLING of [CanonicalMoneyDao] and not a method on it: that DAO's
 * fill resolves competing publishers for one institution-level key, and these
 * figures have exactly one publisher and a different grain.
 */
object ProgramMoneyDao {
  /**
   * Deletes every `program_money_stats` row of [source] -- the rebuild's own
   * first step, bounded to the publisher this run is about to write back.
   *
   * SINGULAR, unlike [CanonicalMoneyDao.deleteFactsOfSources]: that table is
   * filled from three publishers under a precedence order, and this one has
   * exactly one. A `Collection` parameter here would be an argument that the
   * caller has a choice it does not have.
   */
  fun deleteFactsOfSource(
    session: SqlSession,
    source: MoneySource,
  ): Result<Int> =
    session.execute("DELETE FROM program_money_stats WHERE source = ?") { stmt ->
      stmt.setString(1, source.value)
    }

  /**
   * Every `college_programs` row, keyed as the publisher's field-of-study file
   * keys it -- the one read the fill needs to turn a CSV row into a program id.
   *
   * Read WHOLE, once, like `CollegeIpedsDao.collegeIdsByIpedsUnitId`: the fill
   * streams ~228,000 rows, and a per-row `SELECT` would be one round trip each.
   */
  fun programIdsByKey(session: SqlSession): Result<Map<ProgramKey, UUID>> =
    session
      .queryList(
        """
        SELECT c.ipeds_unit_id, p.cip_code, p.credential_level, p.id
          FROM college_programs p
          JOIN colleges c ON c.id = p.college_id
        """.trimIndent(),
        bind = {},
        map = { rs ->
          ProgramKey(
            ipedsUnitId = rs.getInt("ipeds_unit_id"),
            cipCode = rs.getString("cip_code"),
            credentialLevel = rs.getInt("credential_level"),
          ) to rs.getObject("id", UUID::class.java)
        },
      ).map { it.toMap() }

  /**
   * Batch-inserts [rows], returning how many landed. A plain INSERT, no ON
   * CONFLICT: the caller just deleted this source's rows, so a collision is a
   * defect in the fill's own key discipline and deserves the constraint
   * violation rather than a silent overwrite.
   *
   * The amount and the status are bound FROM THE SAME reading, so the pairing
   * cannot be got wrong at a call site.
   */
  fun batchInsert(
    session: SqlSession,
    rows: List<NewProgramMoneyStat>,
  ): Result<Int> =
    batchInsert(
      session,
      """
      INSERT INTO program_money_stats (
        college_program_id, measure,
        cohort_first_year, cohort_last_year,
        amount_usd, status, source, source_variable
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      rows,
      ::mapWriteError,
    ) { stmt: PreparedStatement, row: NewProgramMoneyStat ->
      stmt.setObject(1, row.collegeProgramId)
      stmt.setString(2, row.measure.value)
      stmt.setInt(3, row.cohortFirstYear.firstCalendarYear)
      stmt.setInt(4, row.cohortLastYear.firstCalendarYear)
      stmt.setIntOrNull(5, (row.reading as? FigureReading.Present)?.value)
      stmt.setString(6, row.reading.status.value)
      stmt.setString(7, row.source.value)
      stmt.setString(8, row.sourceVariable)
    }

  /**
   * Per-status row counts, typed: an unknown stored slug is refused loudly
   * rather than smuggled to the operator summary as a string key. The
   * suppressed and not-reported counts are the point of the table -- two thirds
   * of its rows are absences, and RFC 206 exists because we once served those
   * as silence.
   */
  fun countsByStatus(session: SqlSession): Result<Map<FigureStatus, Int>> = countsByStatus(session, "program_money_stats")

  /**
   * The (subject, bachelor's program) match BOTH major-answer reads stand on
   * (RFC 231): each stored subject's `cip_prefixes` expanded from `subjects`
   * (`unnest`), ready to be matched by prefix against `college_programs`.
   *
   * ONE copy because it is ONE rule. A second transcription is a second prefix
   * rule, free to drift at the next change -- and the two reads would then
   * disagree about which programs a subject reaches, which is the one thing
   * the money question and the program question must never do.
   *
   * Prefix matching is `starts_with(cip_code, prefix)`: a 2-digit prefix
   * matches every narrower code under that series, and a subject that lists
   * several prefixes matches their union.
   *
   * `starts_with` and not `LIKE prefix || '%'`, which glues a STORED value into
   * a pattern: a `%` or a `_` in `subjects.cip_prefixes` would be a wildcard
   * matching programs the subject does not name. That the authored prefixes are
   * digits today is a fact about today's rows, not about this query, and
   * `starts_with` takes its argument as a literal, so there is no pattern to
   * escape and no constraint in another file to go and read.
   *
   * `subjects.status` is deliberately NOT filtered, unlike the search path
   * (RFC 205 D40, `CollegesDao`): the slug here is the STUDENT'S OWN stored
   * answer, and a deprecated row survives precisely so that student keeps
   * their subject. D40 stops a retired subject matching colleges for EVERYONE,
   * which a per-student read never does.
   *
   * The first bound array is the subject slugs; [bindSubjectsAndColleges]
   * binds it.
   */
  private val SUBJECT_PREFIX_MATCH_CTE =
    """
    WITH wanted AS (
      SELECT s.slug AS subject_slug, prefix.prefix
      FROM subjects s
      CROSS JOIN LATERAL unnest(s.cip_prefixes) AS prefix(prefix)
      WHERE s.slug = ANY ($TEXT_ARRAY_PARAM)
    )
    """.trimIndent()

  /**
   * The matched programs themselves: [SUBJECT_PREFIX_MATCH_CTE] joined to
   * `college_programs` at the ONE credential level this coach leads with.
   *
   * The level is [CredentialLevel.BACHELORS], never a bare `3`: the code set is
   * the publisher's (`CREDLEV`, `db/schema/0015.create-colleges.sql`) and the
   * enum is where this repository's source-defined credential codes live, so
   * neither read can spell it differently from the other.
   *
   * Matched against `college_programs` rather than the
   * `college_programs_census` table the search index matches (`CollegesDao`):
   * they are two ingests of the same IPEDS completions file, so the two
   * surfaces CAN disagree about whether a school runs a subject. The money
   * store keys on `college_programs.id`, which is why this read cannot use the
   * census copy.
   */
  private val MATCHED_BACHELOR_PROGRAMS_JOIN =
    """
    FROM wanted w
    JOIN college_programs p
      ON p.credential_level = ${CredentialLevel.BACHELORS.code}
     AND starts_with(p.cip_code, w.prefix)
    """.trimIndent()

  /**
   * The colleges the caller asked about -- the second bound array.
   *
   * `college_id` is a `uuid` column and [TEXT_ARRAY_PARAM] yields `text[]`, so
   * the array is cast at the parameter: Postgres has no `uuid = text` operator
   * and rejects the whole statement without one.
   */
  private val SELECTED_COLLEGES_PREDICATE = "WHERE p.college_id = ANY ($TEXT_ARRAY_PARAM::uuid[])"

  /** The two arrays the fragments above take, bound in their own order: subjects, then colleges. */
  private fun bindSubjectsAndColleges(
    subjectSlugs: List<String>,
    collegeIds: List<CollegeId>,
  ): (PreparedStatement) -> Unit =
    { stmt ->
      jsonbArrayBinder(subjectSlugs)(stmt, 1)
      jsonbArrayBinder(collegeIds.map { it.value.toString() })(stmt, 2)
    }

  /**
   * The major-answer read (RFC 231): for every matched program, the MOST RECENT
   * stored cohort of each measure -- one query for the whole answer, never one
   * per subject, per college or per program.
   *
   * The match itself is [SUBJECT_PREFIX_MATCH_CTE] +
   * [MATCHED_BACHELOR_PROGRAMS_JOIN]; this read adds the money store, the
   * cohort choice and its own projection, and nothing else.
   *
   * ONE ROW PER (subject, college, program, measure), and WHICH row is chosen
   * IN THE QUERY. Two cohorts for one measure is a SUPPORTED state of the table
   * -- `program_money_stats_unique_idx` keys on (college_program_id, measure,
   * cohort_first_year, cohort_last_year), so a snapshot that re-dates a column
   * adds rows beside the old ones -- and an unbounded read served the same
   * measure twice, with the one a family saw decided by row order.
   * `DISTINCT ON` plus `cohort_last_year DESC, cohort_first_year DESC` makes the
   * newest cohort the answer, deterministically, and the row carries its OWN
   * years so the sentence beside it is about the cohort it is about.
   *
   * `DISTINCT ON` and NOT the `SELECT DISTINCT` this read used to carry. That
   * form deduplicated by VALUE over a projection that did not name the program,
   * so two DIFFERENT programs whose figures coincided -- the same measure, the
   * same amount, the same cohort -- collapsed into one row and one of the two
   * majors silently vanished from the answer. `p.cip_code` is now both selected
   * and part of the key: the duplicate a prefix union really does produce (one
   * program reached through a subject's `2` series AND its narrower `4` family)
   * is collapsed by IDENTITY, which is one fact, while two programs stay two.
   *
   * The store's `source` IS selected, and is not the publisher a family is told
   * (that comes from `ScorecardFieldOfStudyColumns` through `source_variable`,
   * RFC 231). It rides out as the ENVELOPE the caller audits before reading a
   * row in one publisher's registry: the column is the `money_source` DOMAIN,
   * `money_source_check` admits `('ipeds_sfa', 'ipeds_ic_ay', 'scorecard',
   * 'common_data_set')`, and no CHECK on this table narrows it further -- so a
   * row of another publisher's file is a legal stored state that the read has
   * to be able to SEE rather than assume away.
   *
   * `DISTINCT ON` requires its keys to lead the `ORDER BY`, so the rows arrive
   * in (subject, college, program, measure) order and the cohort tie-break
   * trails it. The payload's own ordering is the assembly's
   * (`programReturnOf`): this read owns WHICH row, not where it renders.
   */
  fun programMoneyBySubject(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
    subjectSlugs: Collection<String>,
  ): Result<List<ProgramReturnStat>> {
    val distinctCollegeIds = collegeIds.distinct()
    val distinctSlugs = subjectSlugs.distinct()
    if (distinctCollegeIds.isEmpty() || distinctSlugs.isEmpty()) return Result.success(emptyList())
    val sql =
      listOf(
        SUBJECT_PREFIX_MATCH_CTE,
        """
        SELECT DISTINCT ON (w.subject_slug, p.college_id, p.cip_code, m.measure)
               p.college_id, w.subject_slug, p.cip_code, m.measure,
               m.cohort_first_year, m.cohort_last_year, m.amount_usd, m.status,
               m.source, m.source_variable
        """.trimIndent(),
        MATCHED_BACHELOR_PROGRAMS_JOIN,
        "JOIN program_money_stats m ON m.college_program_id = p.id",
        SELECTED_COLLEGES_PREDICATE,
        """
        ORDER BY w.subject_slug, p.college_id, p.cip_code, m.measure,
                 m.cohort_last_year DESC, m.cohort_first_year DESC
        """.trimIndent(),
      ).joinToString("\n")
    return session
      .queryList(
        sql,
        bind = bindSubjectsAndColleges(distinctSlugs, distinctCollegeIds),
        map = ::mapProgramReturnStat,
      )
  }

  /**
   * The PROGRAM question of the major answer (RFC 231), asked apart from the
   * money question: which (subject slug, college, `cip_code`) triples name a
   * bachelor's program whose code starts with one of that subject's stored CIP
   * prefixes ([SubjectProgramMatch]).
   *
   * SEPARATE from [programMoneyBySubject] because "this school does not run
   * that program" and "we hold no figure for it" are two different sentences to
   * a family, and a read that can only see `program_money_stats` rows cannot
   * tell them apart: a matched program with no money row would be spoken as a
   * school that does not run it.
   *
   * The SAME match as the money read, because it is the same match: the two
   * share [SUBJECT_PREFIX_MATCH_CTE] and [MATCHED_BACHELOR_PROGRAMS_JOIN] and
   * differ only in what they select. `SELECT DISTINCT` because this is a SET
   * question -- one program reachable through two of a subject's own prefixes
   * is one match.
   *
   * `p.cip_code` is selected, so a college that runs TWO matched programs
   * answers with two members and not one: at the fixed credential level this
   * read uses, the code is the program's identity (see [SubjectProgramMatch]).
   *
   * Each match maps to the program's published title (`cip_title`, RFC 251), so
   * a surface can name the program instead of reading out a code. The title is
   * the VALUE and not part of the key: `college_programs_unique_idx` makes it a
   * function of (college, code) at this level, and the key is the one the money
   * rows join on ([ProgramReturnStat.match]).
   */
  fun bachelorProgramsBySubject(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
    subjectSlugs: Collection<String>,
  ): Result<Map<SubjectProgramMatch, String>> {
    val distinctCollegeIds = collegeIds.distinct()
    val distinctSlugs = subjectSlugs.distinct()
    if (distinctCollegeIds.isEmpty() || distinctSlugs.isEmpty()) return Result.success(emptyMap())
    val sql =
      listOf(
        SUBJECT_PREFIX_MATCH_CTE,
        "SELECT DISTINCT w.subject_slug, p.college_id, p.cip_code, p.cip_title",
        MATCHED_BACHELOR_PROGRAMS_JOIN,
        SELECTED_COLLEGES_PREDICATE,
        "ORDER BY w.subject_slug, p.college_id, p.cip_code",
      ).joinToString("\n")
    return session
      .queryList(
        sql,
        bind = bindSubjectsAndColleges(distinctSlugs, distinctCollegeIds),
        map = { rs ->
          SubjectProgramMatch(
            subjectSlug = rs.getString("subject_slug"),
            collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
            cipCode = CipCode(rs.getString("cip_code")),
          ) to rs.getString("cip_title")
        },
      ).map { it.toMap() }
  }

  /**
   * Reconstructs one read row. The three stored slugs -- `measure`, `status`
   * and `source` -- are parsed through their owned `fromValue` companions and
   * an unknown one is row corruption, named with the subject, the college and
   * the measure once that is known, never softened to a silence.
   *
   * WHY all three refuse, when an unregistered `source_variable` does not: a
   * value of a vocabulary WE control may be exceptional, and a value of a
   * vocabulary somebody ELSE controls may not. `measure` is closed by
   * `program_money_stats_measure_check` and `source` by the `money_source`
   * domain's `money_source_check`, so neither can hold an unreadable value
   * without the schema and this code having skewed. `status` carries no CHECK
   * -- it is `REFERENCES figure_statuses(slug)` -- but that table is OUR
   * vocabulary, seeded by OUR migration beside [FigureStatus] itself, so a slug
   * in it that this enum does not carry is the database and the code
   * disagreeing: a deploy fault, and loud. `source_variable` is the
   * PUBLISHER's column under a non-empty CHECK alone, which is why the widening
   * it can carry is answered one module out, with a WARN and the no-figure arm,
   * and never here.
   */
  private fun mapProgramReturnStat(rs: ResultSet): ProgramReturnStat {
    val collegeId = CollegeId(UUID.fromString(rs.getString("college_id")))
    val subjectSlug = rs.getString("subject_slug")
    val cipCode = CipCode(rs.getString("cip_code"))
    val rawMeasure = rs.getString("measure")
    val rawStatus = rs.getString("status")
    val rawSource = rs.getString("source")
    // [corruptValue] and not a hand-written exception: every read in `:db`
    // raises this refusal the same way, and a second transcription of the
    // sentence is a second spelling for an operator to grep for. The status
    // refusal carries the MEASURE parsed on the line above, because one (school,
    // major) pair holds three rows and an address without the measure names all
    // three.
    val measure =
      ProgramMoneyMeasure.fromValue(rawMeasure)
        ?: throw corruptValue(
          rawMeasure,
          "ProgramMoneyMeasure",
          ProgramMoneyStatLocation(ProgramMoneyStatColumn.MEASURE, collegeId, subjectSlug, cipCode).toString(),
        )
    val status =
      FigureStatus.fromValue(rawStatus)
        ?: throw corruptValue(
          rawStatus,
          "FigureStatus",
          ProgramMoneyStatLocation(ProgramMoneyStatColumn.STATUS, collegeId, subjectSlug, cipCode, measure).toString(),
        )
    // The `money_source` DOMAIN closes this column by CHECK, exactly as
    // `program_money_stats_measure_check` closes `measure`, so a value no
    // [MoneySource] carries is row CORRUPTION and takes the same refusal --
    // never the no-figure arm, which is for values the schema genuinely admits.
    val source =
      MoneySource.fromValue(rawSource)
        ?: throw corruptValue(
          rawSource,
          "MoneySource",
          ProgramMoneyStatLocation(ProgramMoneyStatColumn.SOURCE, collegeId, subjectSlug, cipCode, measure).toString(),
        )
    return ProgramReturnStat(
      collegeId = collegeId,
      subjectSlug = subjectSlug,
      cipCode = cipCode,
      measure = measure,
      cohortFirstYear = AcademicYear(rs.getInt("cohort_first_year")),
      cohortLastYear = AcademicYear(rs.getInt("cohort_last_year")),
      reading =
        FigureReading.of(
          rs.getIntOrNull("amount_usd"),
          status,
          column = programMoneyStatColumn(ProgramMoneyStatColumn.AMOUNT_USD),
          naturalKey = programMoneyStatNaturalKey(collegeId, subjectSlug, cipCode, measure),
        ),
      source = source,
      sourceVariable = rs.getString("source_variable"),
    )
  }

  /**
   * The write-path SQLSTATE mapping. A missing reference here is a row naming a
   * `college_programs` id or a `figure_statuses` slug that is not there -- the
   * `fields` and `money-vocabulary` phases are both write preconditions -- so
   * the message says that rather than a constant sentence.
   */
  private fun mapWriteError(e: SQLException): Exception =
    mapReferenceWriteError(
      e,
      "Referenced row not found: a program money row names a college_programs id or a figure status slug that " +
        "does not exist (the fields and money-vocabulary phases are write preconditions)",
    )
}
