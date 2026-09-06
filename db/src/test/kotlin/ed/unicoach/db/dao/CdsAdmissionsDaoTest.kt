package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AdmissionFactor
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FactorRating
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewCollegeAdmissionFactors
import ed.unicoach.db.models.NewCollegeDeadline
import ed.unicoach.db.models.NewCollegeMeritAid
import ed.unicoach.db.models.NewSourceDocument
import ed.unicoach.db.models.SourceDocumentId
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdsAdmissionsDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
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
    @AfterAll
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
        "TRUNCATE TABLE college_merit_aid, college_admission_factors, college_deadlines, " +
          "source_documents, college_list_entries, students, users, colleges CASCADE",
      )
    }
    // The published state/locale rows `colleges` foreign-keys into since
    // migration 0067. Truncating `colleges` does not empty them, but another
    // suite on this shared database does, so each suite puts them back.
    CodebookReferenceFixture.seed(session)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var ipedsUnitIdCounter = 910000

  private fun createCollege(name: String = "CDS Test College"): CollegeId =
    CollegesDao
      .upsert(session, newCollegeFixture(ipedsUnitIdCounter++, name))
      .getOrThrow()
      .id

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'cds-$userId@test.com', 'CDS User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun listCollege(
    studentId: StudentId,
    collegeId: CollegeId,
  ) {
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO college_list_entries (student_id, college_id) VALUES ('${studentId.value}', '${collegeId.value}')",
      )
    }
  }

  @Test
  fun `no CDS table stores a url, and one document backs all of a school's facts for a cycle`() {
    // RFC 170, D13. The three tables used to carry the SAME filing's two urls,
    // so the copies could disagree and a correction had to be written three
    // times. The columns are gone; the reads join the document instead.
    val urlColumns =
      connection.createStatement().use { stmt ->
        stmt
          .executeQuery(
            "SELECT table_name || '.' || column_name AS col FROM information_schema.columns " +
              "WHERE table_name IN ('college_merit_aid', 'college_admission_factors', 'college_deadlines') " +
              "AND column_name IN ('source_url', 'archive_url') ORDER BY col",
          ).use { rs -> buildList { while (rs.next()) add(rs.getString("col")) } }
      }
    assertEquals(emptyList(), urlColumns, "a url belongs to the document, not to each fact")

    val collegeId = createCollege()
    CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(collegeId)).getOrThrow()
    CdsAdmissionsDao.upsertAdmissionFactors(session, newFactors(collegeId)).getOrThrow()
    CdsAdmissionsDao.upsertDeadline(session, newDeadline(collegeId)).getOrThrow()

    val documents =
      connection.createStatement().use { stmt ->
        stmt
          .executeQuery(
            "SELECT DISTINCT source_document_id FROM (" +
              "SELECT source_document_id FROM college_merit_aid UNION ALL " +
              "SELECT source_document_id FROM college_admission_factors UNION ALL " +
              "SELECT source_document_id FROM college_deadlines) AS all_facts",
          ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
      }
    assertEquals(1, documents.size, "one filing, one document row: $documents")

    // And the read still speaks the url, joined rather than copied.
    val stored = assertNotNull(CdsAdmissionsDao.findMeritAid(session, collegeId, 2024).getOrThrow())
    assertEquals("https://example.edu/cds-2024-25.pdf", stored.sourceUrl)
  }

  /**
   * The `source_documents` row a CDS fact cites (RFC 170, D13). The urls live
   * here, once, so the fixtures below name a document rather than repeating a
   * pair of urls per fact.
   */
  private fun document(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    sourceUrl: String = "https://example.edu/cds-2024-25.pdf",
    archiveUrl: String? = "https://www.collegedata.fyi/schools/example/2024-25",
  ): SourceDocumentId =
    SourceDocumentsDao
      .upsert(
        session,
        NewSourceDocument(
          collegeId = collegeId,
          source = MoneySource.COMMON_DATA_SET,
          academicYear = AcademicYear(sourceYear),
          sourceUrl = sourceUrl,
          archiveUrl = archiveUrl,
        ),
      ).getOrThrow()

  private fun newMeritAid(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    firstTimeFullTimeFreshmenHeadcount: Int? = 2760,
    noNeedMeritRecipientsHeadcount: Int? = 358,
    noNeedMeritAverageUsd: Int? = 16112,
    sourceDocumentId: SourceDocumentId = document(collegeId, sourceYear),
  ) = NewCollegeMeritAid(
    collegeId = collegeId,
    sourceYear = sourceYear,
    firstTimeFullTimeFreshmenHeadcount = firstTimeFullTimeFreshmenHeadcount,
    noNeedMeritRecipientsHeadcount = noNeedMeritRecipientsHeadcount,
    noNeedMeritAverageUsd = noNeedMeritAverageUsd,
    sourceDocumentId = sourceDocumentId,
  )

  private fun newFactors(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    rigor: FactorRating? = FactorRating.VERY_IMPORTANT,
    testScores: FactorRating? = FactorRating.CONSIDERED,
    sourceDocumentId: SourceDocumentId = document(collegeId, sourceYear, archiveUrl = null),
  ) = NewCollegeAdmissionFactors(
    collegeId = collegeId,
    sourceYear = sourceYear,
    rigor = rigor,
    classRank = FactorRating.CONSIDERED,
    gpa = FactorRating.VERY_IMPORTANT,
    testScores = testScores,
    essay = FactorRating.IMPORTANT,
    recommendations = FactorRating.IMPORTANT,
    interview = FactorRating.NOT_CONSIDERED,
    extracurriculars = FactorRating.IMPORTANT,
    talent = FactorRating.CONSIDERED,
    characterQualities = FactorRating.IMPORTANT,
    firstGeneration = FactorRating.CONSIDERED,
    alumniRelation = FactorRating.NOT_CONSIDERED,
    geography = FactorRating.CONSIDERED,
    stateResidency = FactorRating.NOT_CONSIDERED,
    religiousAffiliation = FactorRating.NOT_CONSIDERED,
    volunteerWork = FactorRating.CONSIDERED,
    workExperience = FactorRating.CONSIDERED,
    applicantInterest = null,
    sourceDocumentId = sourceDocumentId,
  )

  private fun newDeadline(
    collegeId: CollegeId,
    round: ApplicationRound = ApplicationRound.EARLY_DECISION_1,
    sourceYear: Int = 2024,
    offered: Boolean = true,
    closing: CdsMonthDay? = CdsMonthDay(11, 1),
    notification: CdsMonthDay? = CdsMonthDay(12, 15),
    sourceDocumentId: SourceDocumentId = document(collegeId, sourceYear, archiveUrl = null),
  ) = NewCollegeDeadline(
    collegeId = collegeId,
    sourceYear = sourceYear,
    round = round,
    offered = offered,
    closing = closing,
    notification = notification,
    sourceDocumentId = sourceDocumentId,
  )

  private fun updatedAt(
    table: String,
    collegeId: CollegeId,
  ): java.sql.Timestamp =
    connection.prepareStatement("SELECT updated_at FROM $table WHERE college_id = ?").use { stmt ->
      stmt.setObject(1, collegeId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getTimestamp("updated_at")
      }
    }

  // ---------------------------------------------------------------------------
  // Merit aid
  // ---------------------------------------------------------------------------

  @Test
  fun `merit aid upsert inserts, then is idempotent, then detects a change`() {
    val collegeId = createCollege()

    assertEquals(UpsertOutcome.INSERTED, CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(collegeId)).getOrThrow())
    val first = CdsAdmissionsDao.findMeritAid(session, collegeId, 2024).getOrThrow()
    assertNotNull(first)
    assertEquals(2760, first.firstTimeFullTimeFreshmenHeadcount)
    assertEquals(358, first.noNeedMeritRecipientsHeadcount)
    assertEquals(16112, first.noNeedMeritAverageUsd)

    // Identical re-upsert: no write, updated_at untouched.
    assertEquals(UpsertOutcome.UNCHANGED, CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(collegeId)).getOrThrow())
    assertEquals(first.updatedAt, CdsAdmissionsDao.findMeritAid(session, collegeId, 2024).getOrThrow()?.updatedAt)

    // A changed value updates in place and bumps updated_at (same-cycle correction).
    Thread.sleep(5)
    assertEquals(
      UpsertOutcome.CHANGED,
      CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(collegeId, noNeedMeritAverageUsd = 17000)).getOrThrow(),
    )
    val changed = CdsAdmissionsDao.findMeritAid(session, collegeId, 2024).getOrThrow()
    assertNotNull(changed)
    assertEquals(first.id, changed.id)
    assertEquals(17000, changed.noNeedMeritAverageUsd)
    assertTrue(changed.updatedAt.isAfter(first.updatedAt))
  }

  @Test
  fun `a new source_year is a new row, not an overwrite`() {
    val collegeId = createCollege()
    CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(collegeId, sourceYear = 2024)).getOrThrow()
    assertEquals(UpsertOutcome.INSERTED, CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(collegeId, sourceYear = 2025)).getOrThrow())
    assertNotNull(CdsAdmissionsDao.findMeritAid(session, collegeId, 2024).getOrThrow())
    assertNotNull(CdsAdmissionsDao.findMeritAid(session, collegeId, 2025).getOrThrow())
  }

  @Test
  fun `merit aid rejects a negative count and a count above the denominator`() {
    val collegeId = createCollege()

    // Each nonneg conjunct driven separately, so a slip in one CHECK arm is caught.
    val negativeRows =
      listOf(
        // count nulled so the count<=total CHECK cannot fire before the nonneg one
        newMeritAid(collegeId, firstTimeFullTimeFreshmenHeadcount = -1, noNeedMeritRecipientsHeadcount = null),
        newMeritAid(collegeId, noNeedMeritRecipientsHeadcount = -1),
        newMeritAid(collegeId, noNeedMeritAverageUsd = -1),
      )
    for (row in negativeRows) {
      val negative = CdsAdmissionsDao.upsertMeritAid(session, row).exceptionOrNull()
      assertTrue(negative is ConstraintViolationException, "negative value accepted: $row")
      assertEquals("college_merit_aid_nonneg_check", negative.constraint)
    }

    val overCount =
      CdsAdmissionsDao
        .upsertMeritAid(session, newMeritAid(collegeId, firstTimeFullTimeFreshmenHeadcount = 100, noNeedMeritRecipientsHeadcount = 101))
        .exceptionOrNull()
    assertTrue(overCount is ConstraintViolationException)
    assertEquals("college_merit_aid_recipients_le_freshmen_check", overCount.constraint)

    // The document is this college's real 2024 filing: the row under test is
    // the one with the impossible SOURCE YEAR, and its citation must not be
    // what fails (the `academic_year` domain would refuse a 1999 document
    // first, which is a different constraint from the one this pins).
    val badYear =
      CdsAdmissionsDao
        .upsertMeritAid(session, newMeritAid(collegeId, sourceYear = 1999, sourceDocumentId = document(collegeId)))
        .exceptionOrNull()
    assertTrue(badYear is ConstraintViolationException)
    assertEquals("cds_source_year_check", badYear.constraint)
  }

  @Test
  fun `merit aid for an absent college maps to a located NotFoundException`() {
    val ghost = CollegeId(UUID.randomUUID())
    // A real document from a real college, so the FK that fails is the FACT
    // row's college -- not its citation's.
    val document = document(createCollege())
    val error =
      CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(ghost, sourceDocumentId = document)).exceptionOrNull()
    assertTrue(error is NotFoundException)
    // The FK failure names the key that failed and keeps the driver's evidence:
    // a constant "Referenced college not found" shared by three tables and
    // thousands of rows is not diagnosable.
    assertTrue(error.message!!.contains(ghost.value.toString()), error.message)
    assertTrue(error.message!!.contains("college_merit_aid"), error.message)
    assertTrue(error.message!!.contains("2024"), error.message)
    assertEquals("college_merit_aid_college_id_fkey", error.constraint)
    assertNotNull(error.cause)
  }

  // ---------------------------------------------------------------------------
  // Admission factors
  // ---------------------------------------------------------------------------

  @Test
  fun `factor grid upsert round-trips ratings and detects changes`() {
    val collegeId = createCollege()

    assertEquals(
      UpsertOutcome.INSERTED,
      CdsAdmissionsDao.upsertAdmissionFactors(session, newFactors(collegeId)).getOrThrow(),
    )
    val first = CdsAdmissionsDao.findAdmissionFactors(session, collegeId, 2024).getOrThrow()
    assertNotNull(first)
    assertEquals(FactorRating.VERY_IMPORTANT, first.rigor)
    assertEquals(FactorRating.CONSIDERED, first.testScores)
    assertNull(first.applicantInterest)

    assertEquals(
      UpsertOutcome.UNCHANGED,
      CdsAdmissionsDao.upsertAdmissionFactors(session, newFactors(collegeId)).getOrThrow(),
    )

    assertEquals(
      UpsertOutcome.CHANGED,
      CdsAdmissionsDao
        .upsertAdmissionFactors(session, newFactors(collegeId, testScores = FactorRating.VERY_IMPORTANT))
        .getOrThrow(),
    )
    assertEquals(
      FactorRating.VERY_IMPORTANT,
      CdsAdmissionsDao.findAdmissionFactors(session, collegeId, 2024).getOrThrow()?.testScores,
    )
  }

  /**
   * The rating columns as the MIGRATION defines them: every column typed with
   * the `cds_factor_rating` domain. Read from the DB rather than restated here,
   * so a nineteenth factor column is exercised the day it lands (and a column
   * that forgot the domain is missing from the list and fails the count check).
   */
  private fun ratingColumns(): List<String> =
    connection.createStatement().use { stmt ->
      stmt
        .executeQuery(
          "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = 'college_admission_factors' AND domain_name = 'cds_factor_rating' " +
            "ORDER BY ordinal_position",
        ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
    }

  @Test
  fun `the rating vocabulary rejects a value outside the enum on every column path`() {
    val collegeId = createCollege()
    // The Kotlin surface cannot produce a bad rating (FactorRating is closed),
    // so the vocabulary is exercised at the SQL level -- the defense the
    // migration itself must carry. Every rating column is driven separately: a
    // column typed TEXT instead of the domain fails exactly here.
    val columns = ratingColumns()
    assertTrue(columns.isNotEmpty(), "no cds_factor_rating columns found on college_admission_factors")
    for (column in columns) {
      val e =
        assertFailsWith<SQLException>("column [$column] accepted a non-whitelist rating") {
          connection.createStatement().use { stmt ->
            stmt.execute(
              "INSERT INTO college_admission_factors (college_id, source_year, $column, source_document_id) " +
                "VALUES ('${collegeId.value}', 2024, 'Very Important', '${document(collegeId).value}')",
            )
          }
        }
      assertTrue(
        e.message!!.contains("cds_factor_rating"),
        "column [$column] failed with the wrong constraint: ${e.message}",
      )
    }
  }

  /**
   * The C7 vocabulary has three parallel statements of itself -- the migration's
   * columns, [AdmissionFactor], and the DAO's bind table -- and a nineteenth
   * factor added to only some of them is silent: an unbound column ingests
   * nothing, an unlisted enum member renders nothing, and both compile clean.
   * They are pinned to each other here, against the columns the DB really has.
   */
  @Test
  fun `the schema columns, AdmissionFactor and the DAO bind table are one vocabulary`() {
    assertEquals(
      AdmissionFactor.entries.map { it.value },
      ratingColumns(),
      "college_admission_factors' rating columns and AdmissionFactor have drifted",
    )
    assertEquals(
      AdmissionFactor.entries.map { it.value },
      CdsAdmissionsDao.FACTOR_COLUMNS.map { it.first },
      "the DAO binds a different set of columns than AdmissionFactor declares",
    )
  }

  @Test
  fun `every factor accessor reads its own column, in both directions`() {
    // Derivation pins the NAMES; this pins the WIRING. Each factor is written
    // as the only VERY_IMPORTANT row in an otherwise CONSIDERED grid, so a
    // swapped pair of accessors -- which no name check can see -- fails here.
    val collegeId = createCollege("Accessor College")
    for (factor in AdmissionFactor.entries) {
      val row = allConsidered(collegeId, except = factor)
      CdsAdmissionsDao.upsertAdmissionFactors(session, row).getOrThrow()
      assertEquals(
        FactorRating.VERY_IMPORTANT,
        CdsAdmissionsDao.FACTOR_COLUMNS.single { it.first == factor.value }.second(row),
        "the bind table reads the wrong column for [${factor.value}]",
      )
      val stored = assertNotNull(CdsAdmissionsDao.findAdmissionFactors(session, collegeId, 2024).getOrThrow())
      assertEquals(
        listOf(factor),
        AdmissionFactor.entries.filter { it.ratingOf(stored) == FactorRating.VERY_IMPORTANT },
        "[${factor.value}] did not round-trip to its own column",
      )
    }
  }

  /** A grid rating every factor CONSIDERED but [except], which is VERY_IMPORTANT. */
  private fun allConsidered(
    collegeId: CollegeId,
    except: AdmissionFactor,
  ): NewCollegeAdmissionFactors {
    fun rating(factor: AdmissionFactor) = if (factor == except) FactorRating.VERY_IMPORTANT else FactorRating.CONSIDERED
    return NewCollegeAdmissionFactors(
      collegeId = collegeId,
      sourceYear = 2024,
      rigor = rating(AdmissionFactor.RIGOR),
      classRank = rating(AdmissionFactor.CLASS_RANK),
      gpa = rating(AdmissionFactor.GPA),
      testScores = rating(AdmissionFactor.TEST_SCORES),
      essay = rating(AdmissionFactor.ESSAY),
      recommendations = rating(AdmissionFactor.RECOMMENDATIONS),
      interview = rating(AdmissionFactor.INTERVIEW),
      extracurriculars = rating(AdmissionFactor.EXTRACURRICULARS),
      talent = rating(AdmissionFactor.TALENT),
      characterQualities = rating(AdmissionFactor.CHARACTER_QUALITIES),
      firstGeneration = rating(AdmissionFactor.FIRST_GENERATION),
      alumniRelation = rating(AdmissionFactor.ALUMNI_RELATION),
      geography = rating(AdmissionFactor.GEOGRAPHY),
      stateResidency = rating(AdmissionFactor.STATE_RESIDENCY),
      religiousAffiliation = rating(AdmissionFactor.RELIGIOUS_AFFILIATION),
      volunteerWork = rating(AdmissionFactor.VOLUNTEER_WORK),
      workExperience = rating(AdmissionFactor.WORK_EXPERIENCE),
      applicantInterest = rating(AdmissionFactor.APPLICANT_INTEREST),
      sourceDocumentId = document(collegeId, archiveUrl = null),
    )
  }

  @Test
  fun `every FactorRating and ApplicationRound member is admitted by the schema`() {
    // The Kotlin enums and the schema's value lists are parallel enumerations
    // of one vocabulary. Driving `entries` through the real write path means a
    // member added without its migration fails here, not as a 23514 at ingest.
    val collegeId = createCollege()
    for (rating in FactorRating.entries) {
      CdsAdmissionsDao
        .upsertAdmissionFactors(session, newFactors(collegeId, rigor = rating))
        .getOrThrow()
    }
    for (round in ApplicationRound.entries) {
      CdsAdmissionsDao.upsertDeadline(session, newDeadline(collegeId, round = round)).getOrThrow()
    }
    assertEquals(
      ApplicationRound.entries.size,
      CdsAdmissionsDao.listDeadlines(session, collegeId, 2024).getOrThrow().size,
    )
  }

  // ---------------------------------------------------------------------------
  // Deadlines
  // ---------------------------------------------------------------------------

  @Test
  fun `deadline upsert keys on (college, year, round) and lists all rounds`() {
    val collegeId = createCollege()

    assertEquals(UpsertOutcome.INSERTED, CdsAdmissionsDao.upsertDeadline(session, newDeadline(collegeId)).getOrThrow())
    assertEquals(
      UpsertOutcome.INSERTED,
      CdsAdmissionsDao
        .upsertDeadline(
          session,
          newDeadline(
            collegeId,
            round = ApplicationRound.REGULAR,
            closing = CdsMonthDay(1, 15),
            notification = null,
          ),
        ).getOrThrow(),
    )
    assertEquals(UpsertOutcome.UNCHANGED, CdsAdmissionsDao.upsertDeadline(session, newDeadline(collegeId)).getOrThrow())
    assertEquals(
      UpsertOutcome.CHANGED,
      CdsAdmissionsDao.upsertDeadline(session, newDeadline(collegeId, closing = CdsMonthDay(11, 15))).getOrThrow(),
    )

    val rounds = CdsAdmissionsDao.listDeadlines(session, collegeId, 2024).getOrThrow()
    assertEquals(2, rounds.size)
    val ed1 = rounds.first { it.round == ApplicationRound.EARLY_DECISION_1 }
    assertEquals(CdsMonthDay(11, 15), ed1.closing)
    assertTrue(ed1.offered)
  }

  @Test
  fun `deadline CHECKs reject a bad round and an out-of-range month`() {
    val collegeId = createCollege()

    val badRound =
      assertFailsWith<SQLException> {
        connection.createStatement().use { stmt ->
          stmt.execute(
            "INSERT INTO college_deadlines (college_id, source_year, round, offered, source_document_id) " +
              "VALUES ('${collegeId.value}', 2024, 'early_bird', true, '${document(collegeId).value}')",
          )
        }
      }
    assertTrue(badRound.message!!.contains("college_deadlines_round_check"))

    // An out-of-range pair can no longer REACH the DB -- CdsMonthDay refuses to
    // construct one -- so the CHECK is driven with raw SQL, the defense against
    // a hand-written INSERT. All four conjuncts driven separately.
    // Each conjunct's day is paired with a valid month so that only the
    // month/day CHECK can be the one that fires.
    val badDates =
      listOf(
        "closing_month" to "13",
        "closing_month, closing_day" to "11, 32",
        "notification_month" to "0",
        "notification_month, notification_day" to "12, 32",
      )
    for ((columns, values) in badDates) {
      val e =
        assertFailsWith<SQLException>("[$columns] = [$values] was accepted") {
          connection.createStatement().use { stmt ->
            stmt.execute(
              "INSERT INTO college_deadlines (college_id, source_year, round, offered, $columns, source_document_id) " +
                "VALUES ('${collegeId.value}', 2024, 'regular', true, $values, '${document(collegeId).value}')",
            )
          }
        }
      assertTrue(
        e.message!!.contains("college_deadlines_month_day_check"),
        "[$columns] failed with the wrong constraint: ${e.message}",
      )
    }
  }

  @Test
  fun `CdsMonthDay refuses a pair that is not a real calendar date`() {
    // The impossible pairs a mangled extraction produces -- caught at
    // construction, so no producer (loader, DAO, future writer) can carry one
    // to the DB and no coach can cite a date that does not exist.
    for ((month, day) in listOf(13 to 1, 0 to 15, 2 to 30, 4 to 31, 9 to 31, 11 to 31, 1 to 32, 1 to 0)) {
      assertFailsWith<IllegalArgumentException>("[$month]/[$day] was accepted") { CdsMonthDay(month, day) }
    }
    // A CDS date carries no year, so Feb 29 is real reporting, as is a
    // month-only date and every month's true last day.
    assertEquals(29, CdsMonthDay(2, 29).day)
    assertNull(CdsMonthDay(2, null).day)
    assertEquals(31, CdsMonthDay(12, 31).day)
    assertEquals(30, CdsMonthDay(4, 30).day)
  }

  @Test
  fun `a month without a day is storable, a day without a month is not`() {
    val collegeId = createCollege()

    // Real CDS reporting: "applications close in March", no day given.
    assertEquals(
      UpsertOutcome.INSERTED,
      CdsAdmissionsDao
        .upsertDeadline(session, newDeadline(collegeId, closing = CdsMonthDay(3, null), notification = null))
        .getOrThrow(),
    )
    val stored = CdsAdmissionsDao.listDeadlines(session, collegeId, 2024).getOrThrow().single()
    assertEquals(CdsMonthDay(3, null), stored.closing)
    assertNull(stored.notification)

    // The inverse is unrepresentable in Kotlin (CdsMonthDay requires a month),
    // so the DB-level guard is driven with raw SQL -- the defense that stops a
    // hand-written INSERT or a future loader from persisting the junk state.
    for (day in listOf("closing_day", "notification_day")) {
      val e =
        assertFailsWith<SQLException>("[$day] without its month was accepted") {
          connection.createStatement().use { stmt ->
            stmt.execute(
              "INSERT INTO college_deadlines (college_id, source_year, round, offered, $day, source_document_id) " +
                "VALUES ('${collegeId.value}', 2024, 'regular', true, 15, '${document(collegeId).value}')",
            )
          }
        }
      assertTrue(
        e.message!!.contains("college_deadlines_day_requires_month_check"),
        "[$day] failed with the wrong constraint: ${e.message}",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Latest-cycle batch reads (RFC 148)
  // ---------------------------------------------------------------------------

  @Test
  fun `the latest cycle is resolved per table per college`() {
    val mixed = createCollege("Mixed Cycle University")
    val other = createCollege("Single Cycle College")

    // The corpus shape S4a produces: the newest document that reports each fact
    // group, so one college holds a 2024 grid beside a 2025 merit row.
    CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(mixed, sourceYear = 2024, noNeedMeritAverageUsd = 1000)).getOrThrow()
    CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(mixed, sourceYear = 2025, noNeedMeritAverageUsd = 2000)).getOrThrow()
    CdsAdmissionsDao.upsertAdmissionFactors(session, newFactors(mixed, sourceYear = 2024)).getOrThrow()
    CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(other, sourceYear = 2024, noNeedMeritAverageUsd = 3000)).getOrThrow()

    val merit = CdsAdmissionsDao.listLatestMeritAid(session, listOf(mixed, other)).getOrThrow()
    assertEquals(2, merit.size)
    val mixedMerit = merit.single { it.collegeId == mixed }
    assertEquals(2025, mixedMerit.sourceYear)
    assertEquals(2000, mixedMerit.noNeedMeritAverageUsd)
    assertEquals(2024, merit.single { it.collegeId == other }.sourceYear)

    // The factor grid resolves independently: still 2024 for the same college.
    val factors = CdsAdmissionsDao.listLatestAdmissionFactors(session, listOf(mixed, other)).getOrThrow()
    assertEquals(2024, factors.single().sourceYear)
    assertEquals(mixed, factors.single().collegeId)
  }

  @Test
  fun `deadlines resolve the latest cycle per round`() {
    val collegeId = createCollege("Deadline University")

    CdsAdmissionsDao
      .upsertDeadline(session, newDeadline(collegeId, round = ApplicationRound.REGULAR, sourceYear = 2024))
      .getOrThrow()
    CdsAdmissionsDao
      .upsertDeadline(
        session,
        newDeadline(collegeId, round = ApplicationRound.REGULAR, sourceYear = 2025, closing = CdsMonthDay(1, 5)),
      ).getOrThrow()
    CdsAdmissionsDao
      .upsertDeadline(
        session,
        newDeadline(collegeId, round = ApplicationRound.EARLY_ACTION, sourceYear = 2024, offered = false),
      ).getOrThrow()

    val rounds = CdsAdmissionsDao.listLatestDeadlines(session, listOf(collegeId)).getOrThrow()
    assertEquals(2, rounds.size)
    val regular = rounds.single { it.round == ApplicationRound.REGULAR }
    assertEquals(2025, regular.sourceYear)
    assertEquals(CdsMonthDay(1, 5), regular.closing)
    // A false flag is a reported fact and survives the read unchanged.
    val early = rounds.single { it.round == ApplicationRound.EARLY_ACTION }
    assertEquals(2024, early.sourceYear)
    assertEquals(false, early.offered)
  }

  @Test
  fun `a college with no row is absent, and an empty id list reads nothing`() {
    val withRow = createCollege("Reports University")
    val withoutRow = createCollege("Silent College")
    CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(withRow)).getOrThrow()

    val merit = CdsAdmissionsDao.listLatestMeritAid(session, listOf(withRow, withoutRow)).getOrThrow()
    assertEquals(listOf(withRow), merit.map { it.collegeId })

    assertTrue(CdsAdmissionsDao.listLatestMeritAid(session, emptyList()).getOrThrow().isEmpty())
    assertTrue(CdsAdmissionsDao.listLatestAdmissionFactors(session, emptyList()).getOrThrow().isEmpty())
    assertTrue(CdsAdmissionsDao.listLatestDeadlines(session, emptyList()).getOrThrow().isEmpty())
  }

  @Test
  fun `a duplicated id is read once`() {
    val collegeId = createCollege("Duplicate Id College")
    CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(collegeId)).getOrThrow()

    val merit = CdsAdmissionsDao.listLatestMeritAid(session, listOf(collegeId, collegeId)).getOrThrow()
    assertEquals(1, merit.size)
  }

  // ---------------------------------------------------------------------------
  // Coverage
  // ---------------------------------------------------------------------------

  @Test
  fun `coverage counts distinct colleges per group and names student-listed gaps`() {
    val covered = createCollege("Covered University")
    val flagsOnly = createCollege("Flags Only College")
    val monthOnly = createCollege("Month Only College")
    val missing = createCollege("Missing From Corpus U")

    CdsAdmissionsDao.upsertMeritAid(session, newMeritAid(covered)).getOrThrow()
    CdsAdmissionsDao.upsertAdmissionFactors(session, newFactors(covered)).getOrThrow()
    CdsAdmissionsDao.upsertDeadline(session, newDeadline(covered)).getOrThrow()
    // flagsOnly: one round row with no date at all.
    CdsAdmissionsDao
      .upsertDeadline(
        session,
        newDeadline(
          flagsOnly,
          round = ApplicationRound.ROLLING,
          closing = null,
          notification = null,
        ),
      ).getOrThrow()
    // monthOnly: a real half-date ("closes in March"). Stored, but it is not a
    // concrete date and must NOT inflate the launch-set gate's number.
    CdsAdmissionsDao
      .upsertDeadline(
        session,
        newDeadline(
          monthOnly,
          round = ApplicationRound.REGULAR,
          closing = CdsMonthDay(3, null),
          notification = null,
        ),
      ).getOrThrow()

    val student = createStudent()
    listCollege(student, covered)
    listCollege(student, missing)

    val coverage = CdsAdmissionsDao.getCoverage(session).getOrThrow()
    assertEquals(3, coverage.launchSetCount)
    assertEquals(1, coverage.meritAidCount)
    assertEquals(1, coverage.admissionFactorsCount)
    assertEquals(3, coverage.deadlinesFlagsCount)
    assertEquals(1, coverage.deadlinesWithDateCount)
    assertEquals(listOf("Missing From Corpus U"), coverage.studentListedMissing)
  }
}
