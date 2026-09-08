package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewCohortPopulationCount
import ed.unicoach.db.models.NewSourceDocument
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.SourceDocumentId
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Common Data Set borrowing read (RFC 175) and, above all, its REFUSALS.
 *
 * The address of every borrowing figure lives in [LoanType] rather than in a
 * `CostField` exhaustive `when`, which trades a compile-time check for these
 * read-time guards. That trade is only sound if the guards actually fire, so
 * each one is driven here with a row the real writer ([CanonicalMoneyDao])
 * produced and then corrupted exactly the way a bad ingest would -- a
 * hand-built read model would test the test.
 */
class BorrowingDaoTest {
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
        "TRUNCATE TABLE colleges, price_figures, cohort_money_stats, cohort_population_counts, " +
          "aid_form_requirements, source_documents, aid_forms, " +
          "residency_bases, arrangements, figure_statuses, price_concepts, income_bands, " +
          "ipeds_regions, us_states, nces_locales CASCADE",
      )
    }
    CodebookReferenceFixture.seed(connection)
    // FIRST, before any fact row: the vocabulary tables are FKs of every
    // cohort_money_stats / cohort_population_counts row.
    MoneyVocabularyFixture.seed(connection)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // The happy path, so no refusal below can pass on an empty read
  // ---------------------------------------------------------------------------

  @Test
  fun `a filing's borrowing block reads back per loan type, each average at its own denominator`() {
    val college = seedBorrowingFiling()

    val borrowing = BorrowingDao.listLatest(session, listOf(college)).getOrThrow().single()

    assertEquals(1000, borrowing.graduatingClass)
    assertEquals(AcademicYear(2024), borrowing.academicYear)
    assertEquals(setOf(LoanType.FEDERAL, LoanType.PRIVATE), borrowing.byLoanType.keys)
    val federal = borrowing.byLoanType.getValue(LoanType.FEDERAL)
    assertEquals(20747, federal.averageDebtUsd)
    val counted = assertIs<BorrowerCounts.Counted>(federal.borrowers)
    assertEquals(394, counted.borrowers)
    assertEquals(1000, counted.graduatingClass)
    assertFalse(borrowing.notReadByUs, "every cell here was read")
  }

  // ---------------------------------------------------------------------------
  // Refusal 1: an average stored at a scope that is not its own denominator
  // ---------------------------------------------------------------------------

  @Test
  fun `an average stored at another scope is refused, never served as this loan type's`() {
    val college = seedBorrowingFiling()
    // The defect RFC 148, 162 and 170 each hit, reaching the store anyway: an
    // average over PRIVATE borrowers filed at 'all' would tell a family that
    // everyone who graduated owed $43,865.
    execute("UPDATE cohort_money_stats SET aid_scope = 'all' WHERE measure = 'private_loan_debt_average'")

    val thrown =
      assertFailsWith<CorruptPersistedValueException> {
        BorrowingDao.listLatest(session, listOf(college)).getOrThrow()
      }
    assertTrue(thrown.message!!.contains("private_loan_debt_average"), thrown.message!!)
    assertTrue(thrown.message!!.contains("aid_scope=[all]"), thrown.message!!)
    assertTrue(thrown.message!!.contains("private_loan_borrowing"), thrown.message!!)
    assertTrue(thrown.message!!.contains(college.value.toString()), thrown.message!!)
  }

  // ---------------------------------------------------------------------------
  // The SCHOOL's own contradiction: an outcome, never an error (D6)
  // ---------------------------------------------------------------------------

  @Test
  fun `a borrower count larger than its graduating class is withheld as an outcome, and denies no other college its answer`() {
    // 14 of 249 live filings report this. It is a property of a self-reported
    // survey, not a corrupt stored value -- and raised as an exception inside
    // this BATCHED read it denied every OTHER college in the list its price
    // answer, which is the one thing debt figures may never do (brief 0003).
    val contradictory = seedBorrowingFiling(ipedsUnitId = 110100, federalBorrowers = 1200)
    val healthy = seedBorrowingFiling(ipedsUnitId = 110200)

    val byCollege =
      BorrowingDao.listLatest(session, listOf(contradictory, healthy)).getOrThrow().associateBy { it.collegeId }

    // The neighbour's answer survives.
    val neighbour = byCollege.getValue(healthy).byLoanType.getValue(LoanType.FEDERAL)
    assertEquals(394, assertIs<BorrowerCounts.Counted>(neighbour.borrowers).borrowers)

    val federal = byCollege.getValue(contradictory).byLoanType.getValue(LoanType.FEDERAL)
    // The average is an independent figure and still stands: it does not
    // divide by this denominator.
    assertEquals(20747, federal.averageDebtUsd)
    // The pair is HELD and never spoken: no share above 100%, and no count.
    val contradiction = assertIs<BorrowerCounts.ContradictsGraduatingClass>(federal.borrowers)
    assertEquals(1200, contradiction.borrowers)
    assertEquals(1000, contradiction.graduatingClass)
  }

  @Test
  fun `a loan type whose only cell is a contradicted pair is not a figure this filing can render`() {
    val college = seedCollege(110100)
    val document = document(college)
    CanonicalMoneyDao
      .insertCohortPopulationCounts(
        session,
        listOf(
          count(college, document, CohortPopulation.GRADUATING_CLASS, FigureReading.Present(1000, ValueBearingStatus.REPORTED)),
          count(college, document, LoanType.STATE.borrowers, FigureReading.Present(1200, ValueBearingStatus.REPORTED)),
        ),
      ).getOrThrow()

    val borrowing = BorrowingDao.listLatest(session, listOf(college)).getOrThrow().single()

    assertFalse(
      borrowing.byLoanType.getValue(LoanType.STATE).hasFact,
      "a contradicted pair is a fact we hold and never one we say",
    )
  }

  // ---------------------------------------------------------------------------
  // Refusal 3: two rows where the read expects one
  // ---------------------------------------------------------------------------

  @Test
  fun `a borrower count at a residency basis this read does not admit is refused, not served`() {
    val college = seedBorrowingFiling()
    // The natural key of cohort_population_counts carries residency_basis and
    // arrangement, and a CDS borrowing headcount is about NEITHER -- the seed
    // writes `not_applicable` for both. A row at any other basis is a corrupt
    // stored value: `singleByKey` only ever refused a SECOND row, so a lone
    // mis-axed row was served as this filing's figure.
    val document = document(college)
    CanonicalMoneyDao
      .insertCohortPopulationCounts(
        session,
        listOf(
          count(
            college,
            document,
            LoanType.FEDERAL.borrowers,
            FigureReading.Present(11, ValueBearingStatus.REPORTED),
            residencyBasis = ResidencyBasis.IN_STATE,
          ),
        ),
      ).getOrThrow()

    val thrown =
      assertFailsWith<CorruptPersistedValueException> {
        BorrowingDao.listLatest(session, listOf(college)).getOrThrow()
      }
    // REFUSED, not merely unmatched: a row this read narrowed away would make
    // the caller say "this filing reports no borrowing" about a filing that
    // has the row.
    assertTrue(thrown.message!!.contains(ResidencyBasis.IN_STATE.value), thrown.message!!)
    assertTrue(thrown.message!!.contains("cohort_population_counts"), thrown.message!!)
  }

  @Test
  fun `a graduating-class row at a residency basis this read does not admit is refused`() {
    val college = seedBorrowingFiling()
    val document = document(college)
    CanonicalMoneyDao
      .insertCohortPopulationCounts(
        session,
        listOf(
          count(
            college,
            document,
            CohortPopulation.GRADUATING_CLASS,
            FigureReading.Present(4, ValueBearingStatus.REPORTED),
            residencyBasis = ResidencyBasis.IN_STATE,
          ),
        ),
      ).getOrThrow()

    // The DENOMINATOR of every share, so the guard that used to catch this as a
    // duplicate now catches it one step earlier -- at the address the row was
    // written to.
    val thrown =
      assertFailsWith<CorruptPersistedValueException> {
        BorrowingDao.listLatest(session, listOf(college)).getOrThrow()
      }
    assertTrue(thrown.message!!.contains(ResidencyBasis.IN_STATE.value), thrown.message!!)
  }

  // ---------------------------------------------------------------------------
  // The three silences the read has to keep apart (D7)
  // ---------------------------------------------------------------------------

  @Test
  fun `a value-less not-collected-by-us cell is OUR gap, and a filing with no borrowing row at all is not`() {
    val unread = seedCollege(110100)
    val noBlock = seedCollege(110200)
    val unreadDocument = document(unread)
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(average(unread, unreadDocument, LoanType.FEDERAL, FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US))),
      ).getOrThrow()
    // A filing we hold that reports no borrowing cell whatsoever: its document
    // exists and one non-borrowing figure dates it.
    val noBlockDocument = document(noBlock)
    CanonicalMoneyDao
      .insertCohortPopulationCounts(
        session,
        listOf(
          count(
            noBlock,
            noBlockDocument,
            CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_ANY_AID,
            FigureReading.Present(500, ValueBearingStatus.REPORTED),
          ),
        ),
      ).getOrThrow()

    val byCollege = BorrowingDao.listLatest(session, listOf(unread, noBlock)).getOrThrow().associateBy { it.collegeId }

    assertEquals(emptyMap(), byCollege.getValue(unread).byLoanType, "a value-less cell renders nothing")
    assertTrue(byCollege.getValue(unread).notReadByUs, "the row IS there and we could not read it (D7)")
    assertEquals(
      setOf(LoanType.FEDERAL),
      byCollege.getValue(unread).notReadLoanTypes,
      "WHICH cell we could not read, so the caller says our gap about that column and no other",
    )
    assertEquals(emptyMap(), byCollege.getValue(noBlock).byLoanType)
    assertFalse(
      byCollege.getValue(noBlock).notReadByUs,
      "a filing with no borrowing row is the school's silence, never ours",
    )
    assertEquals(emptySet(), byCollege.getValue(noBlock).notReadLoanTypes)
  }

  @Test
  fun `a filing read in part names the unread loan type, and keeps the loan type it did read`() {
    // The mixed case, which the filing-wide boolean could not express: this
    // school ANSWERS both columns, we read the federal one and could not read
    // the private one. Told as one flag, the private absence was spoken as the
    // school reporting nothing -- our failure in its mouth (D7).
    val college = seedCollege(110100)
    val document = document(college)
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          average(college, document, LoanType.FEDERAL, FigureReading.Present(20747.0, ValueBearingStatus.REPORTED)),
          average(college, document, LoanType.PRIVATE, FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US)),
        ),
      ).getOrThrow()

    val borrowing = BorrowingDao.listLatest(session, listOf(college)).getOrThrow().single()

    assertEquals(setOf(LoanType.FEDERAL), borrowing.byLoanType.keys, "the readable half is still a figure")
    assertEquals(20747, borrowing.byLoanType.getValue(LoanType.FEDERAL).averageDebtUsd)
    assertEquals(setOf(LoanType.PRIVATE), borrowing.notReadLoanTypes, "and the unread half is named, not counted")
  }

  @Test
  fun `a value-less suppressed cell is the PUBLISHER's silence, and is not our unread gap`() {
    // The status vocabulary has six members and this read used to flatten all
    // of them into one boolean, so a cell the publisher WITHHELD was spoken as
    // the school reporting nothing -- a claim about this school that its own
    // filing does not make.
    val college = seedCollege(110100)
    val document = document(college)
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          average(college, document, LoanType.FEDERAL, FigureReading.Present(20747.0, ValueBearingStatus.REPORTED)),
          average(college, document, LoanType.PRIVATE, FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER)),
          average(college, document, LoanType.STATE, FigureReading.Absent(AbsenceStatus.NOT_APPLICABLE)),
        ),
      ).getOrThrow()

    val borrowing = BorrowingDao.listLatest(session, listOf(college)).getOrThrow().single()

    assertEquals(
      mapOf(
        LoanType.PRIVATE to FigureStatus.SUPPRESSED_BY_PUBLISHER,
        LoanType.STATE to FigureStatus.NOT_APPLICABLE,
      ),
      borrowing.gapStatusByLoanType,
      "each value-less cell keeps the status that says whose silence it is",
    )
    assertEquals(emptySet(), borrowing.notReadLoanTypes, "neither of these is OUR gap")
    assertFalse(borrowing.notReadByUs)
  }

  @Test
  fun `an unread graduating class is OUR gap at the denominator, and leaves the read model`() {
    // H.401 is the denominator of every share, so an unread class cell takes
    // every borrower count with it. Carried out of the read, the caller can say
    // whose gap that is; dropped, the family is told nothing and reads the
    // silence as the school's.
    val college = seedCollege(110100)
    val document = document(college)
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(average(college, document, LoanType.FEDERAL, FigureReading.Present(20747.0, ValueBearingStatus.REPORTED))),
      ).getOrThrow()
    CanonicalMoneyDao
      .insertCohortPopulationCounts(
        session,
        listOf(
          count(
            college,
            document,
            CohortPopulation.GRADUATING_CLASS,
            FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US),
          ),
          count(college, document, LoanType.FEDERAL.borrowers, FigureReading.Present(394, ValueBearingStatus.REPORTED)),
        ),
      ).getOrThrow()

    val borrowing = BorrowingDao.listLatest(session, listOf(college)).getOrThrow().single()

    assertTrue(borrowing.graduatingClassNotReadByUs, "the H.401 cell is answered and we could not read it")
    assertEquals(null, borrowing.graduatingClass)
    assertEquals(20747, borrowing.byLoanType.getValue(LoanType.FEDERAL).averageDebtUsd, "the average still speaks")
  }

  // ---------------------------------------------------------------------------
  // Refusal 4: a stored dollar figure with no whole-dollar form
  // ---------------------------------------------------------------------------

  @Test
  fun `a stored average above the Int range is refused, never wrapped into a negative debt`() {
    // `cohort_money_stats.value` is an unbounded NUMERIC with no range CHECK,
    // and `Math.round` answers a Long whose `toInt()` DROPS the high bits: this
    // exact row was served to a family as -$539,222,987 with a citation beside
    // it.
    val college = seedBorrowingFiling()
    execute("UPDATE cohort_money_stats SET value = 12345678901 WHERE measure = 'federal_loan_debt_average'")

    val thrown =
      assertFailsWith<CorruptPersistedValueException> {
        BorrowingDao.listLatest(session, listOf(college)).getOrThrow()
      }
    assertTrue(thrown.message!!.contains("cohort_money_stats.value"), thrown.message!!)
    assertTrue(thrown.message!!.contains("federal_loan_debt_average"), thrown.message!!)
  }

  @Test
  fun `a negative stored average is refused rather than spoken as money owed`() {
    val college = seedBorrowingFiling()
    execute("UPDATE cohort_money_stats SET value = -4000 WHERE measure = 'federal_loan_debt_average'")

    val thrown =
      assertFailsWith<CorruptPersistedValueException> {
        BorrowingDao.listLatest(session, listOf(college)).getOrThrow()
      }
    assertTrue(thrown.message!!.contains("whole dollars a graduate can owe"), thrown.message!!)
  }

  // ---------------------------------------------------------------------------
  // The citation edge: followed, never re-derived from (college, source, year)
  // ---------------------------------------------------------------------------

  @Test
  fun `a fact whose vintage disagrees with its document's year is still read out of that document`() {
    // The failure schema 0087 was created to end: nothing constrains a fact's
    // `vintage` to equal its document's `academic_year`, and a read that
    // matched the two on a triple dropped the fact on the inner join. The
    // caller then said "we hold no Common Data Set filing for this school"
    // about a filing we do hold -- the sentence D7 forbids.
    val college = seedCollege(110100)
    val document = document(college, AcademicYear(2024))
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          average(
            college,
            document,
            LoanType.FEDERAL,
            FigureReading.Present(20747.0, ValueBearingStatus.REPORTED),
            vintage = AcademicYear(2023),
          ),
        ),
      ).getOrThrow()

    val borrowing = BorrowingDao.listLatest(session, listOf(college)).getOrThrow().single()

    assertEquals(20747, borrowing.byLoanType.getValue(LoanType.FEDERAL).averageDebtUsd)
    assertEquals(
      AcademicYear(2024),
      borrowing.academicYear,
      "the cycle is the DOCUMENT's, because the citation is what a family is shown",
    )
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun execute(sql: String) = connection.createStatement().use { it.execute(sql) }

  private fun seedCollege(ipedsUnitId: Int): CollegeId =
    CollegesDao
      .upsert(session, newCollegeFixture(ipedsUnitId))
      .getOrThrow()
      .id

  private fun document(
    collegeId: CollegeId,
    academicYear: AcademicYear = AcademicYear(2024),
  ): SourceDocumentId =
    SourceDocumentsDao
      .upsert(
        session,
        NewSourceDocument(
          collegeId = collegeId,
          source = MoneySource.COMMON_DATA_SET,
          academicYear = academicYear,
          sourceUrl = "https://example.edu/cds-2024-25.pdf",
          archiveUrl = null,
        ),
      ).getOrThrow()

  /** One school's whole borrowing block, written exactly as the ingest writes it. */
  private fun seedBorrowingFiling(
    ipedsUnitId: Int = 110100,
    graduatingClass: Int = 1000,
    federalBorrowers: Int = 394,
  ): CollegeId {
    val college = seedCollege(ipedsUnitId)
    val document = document(college)
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          average(college, document, LoanType.FEDERAL, FigureReading.Present(20747.0, ValueBearingStatus.REPORTED)),
          average(college, document, LoanType.PRIVATE, FigureReading.Present(43865.0, ValueBearingStatus.REPORTED)),
        ),
      ).getOrThrow()
    CanonicalMoneyDao
      .insertCohortPopulationCounts(
        session,
        listOf(
          count(
            college,
            document,
            CohortPopulation.GRADUATING_CLASS,
            FigureReading.Present(graduatingClass, ValueBearingStatus.REPORTED),
          ),
          count(
            college,
            document,
            LoanType.FEDERAL.borrowers,
            FigureReading.Present(federalBorrowers, ValueBearingStatus.REPORTED),
          ),
        ),
      ).getOrThrow()
    return college
  }

  private fun average(
    collegeId: CollegeId,
    document: SourceDocumentId,
    loanType: LoanType,
    reading: FigureReading<Double>,
    vintage: AcademicYear = AcademicYear(2024),
  ) = NewCohortMoneyStat(
    collegeId = collegeId.value,
    measure = loanType.debtAverage,
    population = CohortPopulation.GRADUATING_CLASS,
    residencyScope = CohortResidencyScope.ALL,
    // The DENOMINATOR, read from the loan type rather than chosen here: a
    // fixture free to pick its own scope would pass while the store held an
    // average over the wrong population.
    aidScope = loanType.aidScope,
    incomeBand = null,
    vintage = vintage,
    reading = reading,
    source = MoneySource.COMMON_DATA_SET,
    sourceVariable = "H.512",
    sourceDocumentId = document,
  )

  private fun count(
    collegeId: CollegeId,
    document: SourceDocumentId,
    population: CohortPopulation,
    reading: FigureReading<Int>,
    residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
  ) = NewCohortPopulationCount(
    collegeId = collegeId.value,
    population = population,
    residencyBasis = residencyBasis,
    arrangement = FigureArrangement.NOT_APPLICABLE,
    vintage = AcademicYear(2024),
    reading = reading,
    source = MoneySource.COMMON_DATA_SET,
    sourceVariable = "H.502",
    sourceDocumentId = document,
  )
}
