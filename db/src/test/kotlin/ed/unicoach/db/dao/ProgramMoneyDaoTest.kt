package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CipCode
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CredentialLevel
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ProgramMoneyMeasure
import ed.unicoach.db.models.SubjectStatus
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `program_money_stats` and its DAO (RFC 218): every constraint asserted BY
 * NAME, the natural key, the rebuild-by-delete shape, and the
 * `ProgramMoneyMeasure` enum pinned to the SQL CHECK list in both directions.
 *
 * On `CanonicalMoneyDaoTest`'s shape: one connection from the harness DB, a
 * TRUNCATE + re-seed of the committed vocabulary per test, and an inline
 * anonymous [SqlSession] over the connection.
 */
class ProgramMoneyDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @org.junit.jupiter.api.BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @org.junit.jupiter.api.AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE colleges, college_programs, program_money_stats, subjects, " +
          "residency_bases, arrangements, figure_statuses, price_concepts, income_bands, aid_forms, " +
          "ipeds_regions, us_states, nces_locales CASCADE",
      )
    }
    CodebookReferenceFixture.seed(connection)
    MoneyVocabularyFixture.seed(connection)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  /** A program at a freshly upserted college -- [programAt] of a new [college], never a second copy of it. */
  private fun program(
    ipedsUnitId: Int = 110100,
    cipCode: String = "2607",
    credentialLevel: Int = CredentialLevel.BACHELORS.code,
  ): UUID = programAt(college(ipedsUnitId), cipCode, credentialLevel)

  /** The college id behind one [program] call, for reads that must name the college. */
  private fun college(ipedsUnitId: Int): CollegeId = CollegesDao.upsert(session, newCollegeFixture(ipedsUnitId)).getOrThrow().id

  /** A program on [collegeId] -- the read path separates the two, unlike [program] which owns both. */
  private fun programAt(
    collegeId: CollegeId,
    cipCode: String,
    credentialLevel: Int = CredentialLevel.BACHELORS.code,
  ): UUID =
    SubjectTaxonomyFixture.insertProgram(
      session,
      collegeId = collegeId,
      cipCode = cipCode,
      cipTitle = "Marine Biology and Biological Oceanography",
      credentialLevel = credentialLevel,
    )

  /** One authored taxonomy row; [prefixes] are the canonical digits-only CIP prefixes. */
  private fun subject(
    slug: String,
    name: String,
    vararg prefixes: String,
  ) = SubjectTaxonomyFixture.insertSubject(session, slug, name, *prefixes)

  private fun stat(
    programId: UUID,
    measure: ProgramMoneyMeasure = ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY,
    first: AcademicYear = AcademicYear(2017),
    last: AcademicYear = AcademicYear(2018),
    reading: FigureReading<Int> = FigureReading.Present(52000, ValueBearingStatus.REPORTED),
    sourceVariable: String = "EARN_MDN_4YR",
  ) = NewProgramMoneyStat(
    collegeProgramId = programId,
    measure = measure,
    cohortFirstYear = first,
    cohortLastYear = last,
    reading = reading,
    source = MoneySource.SCORECARD,
    sourceVariable = sourceVariable,
  )

  /** Raw SQL, because the Kotlin types refuse most of these pairings before the CHECK ever sees them. */
  private fun insertRaw(
    programId: UUID?,
    measure: String = "median_earnings_4yr_after_entry",
    first: Int = 2017,
    last: Int = 2018,
    amount: String = "52000",
    status: String = "reported",
    sourceVariable: String = "EARN_MDN_4YR",
  ) {
    connection
      .prepareStatement(
        "INSERT INTO program_money_stats (college_program_id, measure, cohort_first_year, " +
          "cohort_last_year, amount_usd, status, source, source_variable) " +
          "VALUES (?, '$measure', $first, $last, $amount, '$status', 'scorecard', '$sourceVariable')",
      ).use { stmt ->
        stmt.setObject(1, programId)
        stmt.executeUpdate()
      }
  }

  private fun rowCount(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT count(*) FROM program_money_stats").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  @Test
  fun `one row inserts and reads back under its status`() {
    val programId = program()
    assertEquals(1, ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow())
    assertEquals(
      mapOf(FigureStatus.REPORTED to 1),
      ProgramMoneyDao.countsByStatus(session).getOrThrow(),
    )
  }

  @Test
  fun `a batch writes all three measures of one program, absences included`() {
    val programId = program()
    val written =
      ProgramMoneyDao
        .batchInsert(
          session,
          listOf(
            stat(programId),
            stat(
              programId,
              measure = ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
              first = AcademicYear(2018),
              last = AcademicYear(2019),
              reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
              sourceVariable = "DEBT_ALL_STGP_EVAL_MDN",
            ),
            stat(
              programId,
              measure = ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT,
              first = AcademicYear(2018),
              last = AcademicYear(2019),
              reading = FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
              sourceVariable = "DEBT_ALL_STGP_EVAL_MDN10YRPAY",
            ),
          ),
        ).getOrThrow()
    assertEquals(3, written)
    // The three silences stay three, and they are distinguishable in ONE read:
    // an absence row is stored on purpose (RFC 206).
    assertEquals(
      mapOf(
        FigureStatus.REPORTED to 1,
        FigureStatus.SUPPRESSED_BY_PUBLISHER to 1,
        FigureStatus.NOT_REPORTED_BY_INSTITUTION to 1,
      ),
      ProgramMoneyDao.countsByStatus(session).getOrThrow(),
    )
  }

  @Test
  fun `the rebuild is a delete of this source's rows, then a re-insert`() {
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    assertEquals(1, ProgramMoneyDao.deleteFactsOfSource(session, MoneySource.SCORECARD).getOrThrow())
    assertEquals(0, rowCount())
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    assertEquals(1, rowCount())
  }

  @Test
  fun `the delete is bounded to the source named -- another publisher's rows stay`() {
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    assertEquals(0, ProgramMoneyDao.deleteFactsOfSource(session, MoneySource.IPEDS_SFA).getOrThrow())
    assertEquals(1, rowCount())
  }

  @Test
  fun `an amount under a status that bears none is refused by name, and so is the reverse`() {
    val programId = program()
    for ((amount, status) in listOf("52000" to "suppressed_by_publisher", "NULL" to "reported")) {
      val thrown =
        assertFailsWith<Exception>("amount=$amount status=$status") {
          insertRaw(programId, amount = amount, status = status)
        }
      assertTrue(
        thrown.message!!.contains("program_money_stats_amount_iff_status_check"),
        thrown.message!!,
      )
    }
  }

  @Test
  fun `a negative amount is refused by name`() {
    val programId = program()
    val thrown = assertFailsWith<Exception> { insertRaw(programId, amount = "-1") }
    assertTrue(
      thrown.message!!.contains("program_money_stats_amount_nonnegative_check"),
      thrown.message!!,
    )
  }

  @Test
  fun `a cohort that ends before it starts is refused by name`() {
    val programId = program()
    val thrown = assertFailsWith<Exception> { insertRaw(programId, first = 2019, last = 2018) }
    assertTrue(
      thrown.message!!.contains("program_money_stats_cohort_years_ordered_check"),
      thrown.message!!,
    )
  }

  @Test
  fun `a duplicate natural key is refused by name`() {
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    val thrown = assertFailsWith<Exception> { insertRaw(programId) }
    assertTrue(thrown.message!!.contains("program_money_stats_unique_idx"), thrown.message!!)
  }

  @Test
  fun `the same measure for a DIFFERENT cohort is a different row, not a collision`() {
    // The natural key includes the cohort years on purpose: a snapshot that
    // re-dates a column adds rows beside the old ones rather than overwriting a
    // cohort nobody re-published.
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    ProgramMoneyDao
      .batchInsert(session, listOf(stat(programId, first = AcademicYear(2019), last = AcademicYear(2020))))
      .getOrThrow()
    assertEquals(2, rowCount())
  }

  @Test
  fun `an unknown measure is refused by name`() {
    val programId = program()
    val thrown = assertFailsWith<Exception> { insertRaw(programId, measure = "debt_to_earnings_ratio") }
    assertTrue(thrown.message!!.contains("program_money_stats_measure_check"), thrown.message!!)
  }

  @Test
  fun `an empty source_variable is refused by name`() {
    val programId = program()
    val thrown = assertFailsWith<Exception> { insertRaw(programId, sourceVariable = "") }
    assertTrue(
      thrown.message!!.contains("program_money_stats_source_variable_nonempty_check"),
      thrown.message!!,
    )
  }

  @Test
  fun `a row about a program that does not exist is refused`() {
    val thrown =
      assertFailsWith<Exception> {
        ProgramMoneyDao.batchInsert(session, listOf(stat(UUID.randomUUID()))).getOrThrow()
      }
    assertTrue(
      thrown.message!!.contains("college_programs") || thrown.message!!.contains("Referenced row not found"),
      thrown.message!!,
    )
  }

  @Test
  fun `a program's rows go when the program goes -- ON DELETE CASCADE`() {
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    connection.prepareStatement("DELETE FROM college_programs WHERE id = ?").use { stmt ->
      stmt.setObject(1, programId)
      stmt.executeUpdate()
    }
    assertEquals(0, rowCount())
  }

  @Test
  fun `the Kotlin measure enum and the SQL CHECK list are the same set, both ways`() {
    // Containment either way is not enough: a member with no CHECK value is a
    // row the database refuses, and a CHECK value with no member is a stored
    // slug nothing reads.
    val definition =
      connection
        .prepareStatement("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")
        .use { stmt ->
          stmt.setString(1, "program_money_stats_measure_check")
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "program_money_stats_measure_check is not defined on this database")
            rs.getString(1)
          }
        }
    val inCheck = Regex("'([a-z0-9_]+)'").findAll(definition).map { it.groupValues[1] }.toSet()
    assertEquals(ProgramMoneyMeasure.entries.map { it.value }.toSet(), inCheck, definition)
    // And every stored slug reads back through the one fromValue companion.
    for (value in inCheck) {
      assertEquals(value, ProgramMoneyMeasure.fromValue(value)?.value, "no ProgramMoneyMeasure reads [$value]")
    }
  }

  @Test
  fun `no measure in this table is a ratio of two others`() {
    // brief 0011 D27, pinned where the vocabulary lives: the earnings column and
    // the debt columns are different cohorts, different publishers and different
    // dollar bases, so a ratio over them is a figure nobody published.
    for (measure in ProgramMoneyMeasure.entries) {
      assertTrue(
        !measure.value.contains("ratio") && !measure.value.contains("per_dollar"),
        "[${measure.value}] names a ratio; no debt-to-earnings figure is ever stored or computed",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // The major-answer read (RFC 231)
  // ---------------------------------------------------------------------------

  @Test
  fun `the major-answer read matches by prefix at bachelor's level and skips other credential levels`() {
    subject("nursing", "Nursing", "51")
    val bachelorCollege = college(ipedsUnitId = 200001)
    val associateCollege = college(ipedsUnitId = 200002)
    val bachelorProgram = programAt(bachelorCollege, cipCode = "5101", credentialLevel = CredentialLevel.BACHELORS.code)
    val associateProgram = programAt(associateCollege, cipCode = "5102", credentialLevel = CredentialLevel.ASSOCIATE.code)
    ProgramMoneyDao
      .batchInsert(session, listOf(stat(bachelorProgram), stat(associateProgram)))
      .getOrThrow()

    val rows =
      ProgramMoneyDao
        .programMoneyBySubject(session, listOf(bachelorCollege, associateCollege), listOf("nursing"))
        .getOrThrow()

    assertEquals(listOf(bachelorCollege), rows.map { it.collegeId }.distinct())
    assertEquals(listOf("nursing"), rows.map { it.subjectSlug }.distinct())
    assertEquals(listOf(ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY), rows.map { it.measure })
  }

  // THE SHAPE THIS NOW GUARANTEES. The assertion below states the TRUE
  // expectation: a 2-digit prefix matches BOTH the 4-digit code and the 6-digit
  // code, so two matched programs at one college are two matches. It was HELD
  // RED while the payload shape was open, because `SELECT DISTINCT` over a
  // projection that did not name the program deduplicated by VALUE -- and these
  // two rows are BYTE-IDENTICAL apart from the program they belong to, so one
  // major silently vanished.
  //
  // It passes now because the read carries the program's IDENTITY (`cip_code`,
  // selected and part of the `DISTINCT ON` key) all the way to the payload's
  // `programs[]`, and nothing is dropped. The assertion, its numbers and its
  // seeded data are untouched; the `cip_code` assertions below are what makes
  // this test evidence of identity rather than of a row count -- two programs
  // whose figures DIFFER would pass on a value-based dedup too.
  @Test
  fun `a 2-digit prefix matches every narrower code and a 6-digit prefix matches only its own detail`() {
    subject("biology", "Biology", "26")
    subject("detail", "A Six-Digit Field", "260707")
    val collegeId = college(ipedsUnitId = 200003)
    val family = programAt(collegeId, cipCode = "2607")
    val detail = programAt(collegeId, cipCode = "260707")
    ProgramMoneyDao
      .batchInsert(session, listOf(stat(family), stat(detail)))
      .getOrThrow()

    val broad =
      ProgramMoneyDao
        .programMoneyBySubject(session, listOf(collegeId), listOf("biology"))
        .getOrThrow()
    assertEquals(2, broad.size, "a 2-digit prefix matches both the 4-digit and the 6-digit code")

    val narrow =
      ProgramMoneyDao
        .programMoneyBySubject(session, listOf(collegeId), listOf("detail"))
        .getOrThrow()
    assertEquals(1, narrow.size, "a 6-digit prefix matches only the code that starts with it")

    // The two seeded rows are byte-identical -- same measure, amount, status,
    // cohort years and source_variable -- and differ ONLY in the program they
    // belong to. A count of two is therefore not enough: assert the two rows are
    // TOLD APART by their program, which is the property a value-based
    // deduplication cannot have.
    assertEquals(
      listOf("2607", "260707"),
      broad.map { it.cipCode.value }.sorted(),
      "each row names the program it is about, so two programs stay two",
    )
    assertEquals(
      listOf("260707"),
      narrow.map { it.cipCode.value },
      "the 6-digit subject's one row is the 6-digit program's",
    )
    assertEquals(
      2,
      broad.map { it.match }.distinct().size,
      "the two rows carry two DIFFERENT SubjectProgramMatch values: [${broad.map { it.match }}]",
    )
    assertEquals(
      listOf(1, 1),
      broad.groupBy { it.match }.values.map { it.size },
      "and one row each: the read answers once per (program, measure)",
    )
  }

  /**
   * The duplicate a prefix union REALLY produces, and the one the old
   * `SELECT DISTINCT` was there for: one program reached through a subject's
   * broad series AND its own narrower family.
   *
   * It must still collapse to one row -- that is one program, one fact -- and it
   * is collapsed by IDENTITY now rather than by value, which is why the two
   * different programs above survive while this one does not double.
   */
  @Test
  fun `one program reached through two of a subject's own prefixes is still one row`() {
    subject("biology", "Biology", "26", "2607")
    val collegeId = college(ipedsUnitId = 200005)
    val programId = programAt(collegeId, cipCode = "2607")
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()

    val rows =
      ProgramMoneyDao
        .programMoneyBySubject(session, listOf(collegeId), listOf("biology"))
        .getOrThrow()

    assertEquals(1, rows.size, "two prefixes reaching one program is one match, not two: [${rows.map { it.cipCode.value }}]")
    assertEquals(
      setOf(SubjectProgramMatch("biology", collegeId, CipCode("2607"))),
      ProgramMoneyDao.bachelorProgramsBySubject(session, listOf(collegeId), listOf("biology")).getOrThrow().keys,
      "and the program question answers the same way",
    )
  }

  /**
   * Q6: two cohorts stored for ONE measure of one program.
   *
   * A SUPPORTED state of the table -- `program_money_stats_unique_idx` keys on
   * (college_program_id, measure, cohort_first_year, cohort_last_year), so a
   * snapshot that re-dates a column adds rows beside the old ones. The read must
   * answer ONCE, with the most recent cohort, and it must do so because the
   * QUERY says so: an unbounded read returned the same measure twice and which
   * one a family saw depended on row order.
   */
  @Test
  fun `two cohorts of one measure answer once, with the most recent cohort's own years`() {
    subject("nursing", "Nursing", "51")
    val collegeId = college(ipedsUnitId = 240001)
    val programId = programAt(collegeId, cipCode = "5101")
    ProgramMoneyDao
      .batchInsert(
        session,
        listOf(
          // The OLDER cohort is written FIRST and carries the older number, so a
          // read that took whatever the table handed back would tend to serve it.
          stat(
            programId,
            first = AcademicYear(2015),
            last = AcademicYear(2016),
            reading = FigureReading.Present(41000, ValueBearingStatus.REPORTED),
          ),
          stat(
            programId,
            first = AcademicYear(2017),
            last = AcademicYear(2018),
            reading = FigureReading.Present(52000, ValueBearingStatus.REPORTED),
          ),
        ),
      ).getOrThrow()
    assertEquals(2, rowCount(), "both cohorts are stored: the natural key admits them")

    val rows =
      ProgramMoneyDao
        .programMoneyBySubject(session, listOf(collegeId), listOf("nursing"))
        .getOrThrow()

    val row = rows.single()
    assertEquals(AcademicYear(2017), row.cohortFirstYear, "the most recent cohort answers")
    assertEquals(AcademicYear(2018), row.cohortLastYear)
    assertEquals(52000, (row.reading as FigureReading.Present).value, "with its OWN figure, never the older cohort's")
  }

  @Test
  fun `the three silence arms come back with their amounts paired`() {
    subject("nursing", "Nursing", "51")
    val collegeId = college(ipedsUnitId = 200004)
    val programId = programAt(collegeId, cipCode = "5101")
    ProgramMoneyDao
      .batchInsert(
        session,
        listOf(
          stat(programId),
          stat(
            programId,
            measure = ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
            first = AcademicYear(2018),
            last = AcademicYear(2019),
            reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
            sourceVariable = "DEBT_ALL_STGP_EVAL_MDN",
          ),
          stat(
            programId,
            measure = ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT,
            first = AcademicYear(2018),
            last = AcademicYear(2019),
            reading = FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
            sourceVariable = "DEBT_ALL_STGP_EVAL_MDN10YRPAY",
          ),
        ),
      ).getOrThrow()

    val rows =
      ProgramMoneyDao
        .programMoneyBySubject(session, listOf(collegeId), listOf("nursing"))
        .getOrThrow()

    assertEquals(3, rows.size)
    val reported = rows.single { it.measure == ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY }
    assertEquals(52000, (reported.reading as FigureReading.Present).value)
    val suppressed = rows.single { it.measure == ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION }
    assertEquals(AbsenceStatus.SUPPRESSED_BY_PUBLISHER, (suppressed.reading as FigureReading.Absent).absence)
    val notReported = rows.single { it.measure == ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT }
    assertEquals(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION, (notReported.reading as FigureReading.Absent).absence)
  }

  @Test
  fun `one read serves two colleges and two subjects`() {
    subject("nursing", "Nursing", "51")
    subject("physics", "Physics", "40")
    val first = college(ipedsUnitId = 300001)
    val second = college(ipedsUnitId = 300002)
    val firstNursing = programAt(first, cipCode = "5101")
    val secondNursing = programAt(second, cipCode = "5102")
    val firstPhysics = programAt(first, cipCode = "4001")
    val secondPhysics = programAt(second, cipCode = "4002")
    ProgramMoneyDao
      .batchInsert(
        session,
        listOf(stat(firstNursing), stat(secondNursing), stat(firstPhysics), stat(secondPhysics)),
      ).getOrThrow()

    val counting = CountingSqlSession(session)
    val rows =
      ProgramMoneyDao
        .programMoneyBySubject(counting, listOf(first, second), listOf("nursing", "physics"))
        .getOrThrow()

    assertEquals(1, counting.prepared.size, "one query for the whole answer, never one per subject or college: ${counting.prepared}")
    assertEquals(4, rows.size)
    assertEquals(setOf("nursing", "physics"), rows.map { it.subjectSlug }.toSet())
    assertEquals(setOf(first, second), rows.map { it.collegeId }.toSet())
  }

  @Test
  fun `a 4-digit prefix matches its own code and its narrower detail, and no sibling family`() {
    subject("family", "One Four-Digit Family", "2607")
    val equalLength = college(ipedsUnitId = 210001)
    val narrower = college(ipedsUnitId = 210002)
    val sibling = college(ipedsUnitId = 210003)
    ProgramMoneyDao
      .batchInsert(
        session,
        listOf(
          stat(programAt(equalLength, cipCode = "2607")),
          stat(programAt(narrower, cipCode = "260707")),
          stat(programAt(sibling, cipCode = "2610")),
        ),
      ).getOrThrow()

    val rows =
      ProgramMoneyDao
        .programMoneyBySubject(session, listOf(equalLength, narrower, sibling), listOf("family"))
        .getOrThrow()

    assertEquals(
      setOf(equalLength, narrower),
      rows.map { it.collegeId }.toSet(),
      "a 4-digit prefix matches the code of its own length and every narrower code under it, and nothing else",
    )
  }

  // ---------------------------------------------------------------------------
  // The program question, asked apart from the money question (RFC 231)
  // ---------------------------------------------------------------------------

  @Test
  fun `the program read answers by prefix and says nothing about whether a figure is stored`() {
    subject("biology", "Biology", "26")
    val collegeId = college(ipedsUnitId = 220001)
    programAt(collegeId, cipCode = "2607")

    assertEquals(
      setOf(SubjectProgramMatch("biology", collegeId, CipCode("2607"))),
      ProgramMoneyDao.bachelorProgramsBySubject(session, listOf(collegeId), listOf("biology")).getOrThrow().keys,
      "the program question is answered by college_programs alone: a school runs the program whether or not " +
        "the publisher gave us a figure for it",
    )
    assertEquals(
      emptyList(),
      ProgramMoneyDao.programMoneyBySubject(session, listOf(collegeId), listOf("biology")).getOrThrow(),
      "the fixture must hold no money row, or the two reads are not being told apart",
    )
  }

  @Test
  fun `the program read matches at bachelor's level only`() {
    subject("nursing", "Nursing", "51")
    val bachelorCollege = college(ipedsUnitId = 220002)
    val associateCollege = college(ipedsUnitId = 220003)
    programAt(bachelorCollege, cipCode = "5101", credentialLevel = CredentialLevel.BACHELORS.code)
    programAt(associateCollege, cipCode = "5102", credentialLevel = CredentialLevel.ASSOCIATE.code)

    assertEquals(
      setOf(SubjectProgramMatch("nursing", bachelorCollege, CipCode("5101"))),
      ProgramMoneyDao
        .bachelorProgramsBySubject(session, listOf(bachelorCollege, associateCollege), listOf("nursing"))
        .getOrThrow()
        .keys,
      "an associate's program is the wrong cohort to lead with, so it is not a match",
    )
  }

  @Test
  fun `one program read serves two colleges and two subjects`() {
    subject("nursing", "Nursing", "51")
    subject("physics", "Physics", "40")
    val first = college(ipedsUnitId = 220004)
    val second = college(ipedsUnitId = 220005)
    programAt(first, cipCode = "5101")
    programAt(second, cipCode = "4001")

    val counting = CountingSqlSession(session)
    val matched =
      ProgramMoneyDao
        .bachelorProgramsBySubject(counting, listOf(first, second), listOf("nursing", "physics"))
        .getOrThrow()
        .keys

    assertEquals(1, counting.prepared.size, "one query for the whole answer, never one per subject or college: ${counting.prepared}")
    assertEquals(
      setOf(SubjectProgramMatch("nursing", first, CipCode("5101")), SubjectProgramMatch("physics", second, CipCode("4001"))),
      matched,
    )
  }

  @Test
  fun `the program read names each matched program by its published title`() {
    subject("biology", "Biology", "26")
    val collegeId = college(ipedsUnitId = 220006)
    SubjectTaxonomyFixture.insertProgram(session, collegeId = collegeId, cipCode = "2601", cipTitle = "Biology, General.")
    SubjectTaxonomyFixture.insertProgram(session, collegeId = collegeId, cipCode = "2613", cipTitle = "Ecology, Evolution, Systematics.")

    assertEquals(
      mapOf(
        SubjectProgramMatch("biology", collegeId, CipCode("2601")) to "Biology, General.",
        SubjectProgramMatch("biology", collegeId, CipCode("2613")) to "Ecology, Evolution, Systematics.",
      ),
      ProgramMoneyDao.bachelorProgramsBySubject(session, listOf(collegeId), listOf("biology")).getOrThrow(),
      "each program carries its own published title, verbatim",
    )
  }

  @Test
  fun `the program read is empty when there is nothing to ask about`() {
    subject("nursing", "Nursing", "51")
    val collegeId = college(ipedsUnitId = 220006)
    programAt(collegeId, cipCode = "5101")

    assertEquals(emptyMap(), ProgramMoneyDao.bachelorProgramsBySubject(session, emptyList(), listOf("nursing")).getOrThrow())
    assertEquals(emptyMap(), ProgramMoneyDao.bachelorProgramsBySubject(session, listOf(collegeId), emptyList()).getOrThrow())
  }

  /**
   * RFC 205 D40 filters `subjects.status` in the SEARCH path, and this read
   * deliberately does not: the slug here is the student's own stored answer,
   * and the deprecated row survives precisely so that student keeps their
   * subject. Asserted rather than left implicit, because the two surfaces
   * answering differently about one stored subject is a decision, not an
   * accident.
   */
  @Test
  fun `a deprecated subject the student already holds still matches its programs`() {
    subject("nursing", "Nursing", "51")
    connection.prepareStatement("UPDATE subjects SET status = ? WHERE slug = ?").use { stmt ->
      stmt.setString(1, SubjectStatus.DEPRECATED.value)
      stmt.setString(2, "nursing")
      assertEquals(
        1,
        stmt.executeUpdate(),
        "the subject this test is named for must really be deprecated, or both assertions below prove the ACTIVE path",
      )
    }
    val collegeId = college(ipedsUnitId = 230001)
    val programId = programAt(collegeId, cipCode = "5101")
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()

    assertEquals(
      setOf(SubjectProgramMatch("nursing", collegeId, CipCode("5101"))),
      ProgramMoneyDao.bachelorProgramsBySubject(session, listOf(collegeId), listOf("nursing")).getOrThrow().keys,
      "a subject the student already chose still names its programs (RFC 205 D40 is about the search)",
    )
    assertEquals(
      listOf(collegeId),
      ProgramMoneyDao
        .programMoneyBySubject(session, listOf(collegeId), listOf("nursing"))
        .getOrThrow()
        .map { it.collegeId },
      "and its figures are still read",
    )
  }
}
