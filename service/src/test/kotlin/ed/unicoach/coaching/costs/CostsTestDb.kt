package ed.unicoach.coaching.costs

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.MoneyVocabularyFixture
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AidForm
import ed.unicoach.db.models.AidFormApplicantGroup
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryEdit
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewAidFormRequirement
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewCohortPopulationCount
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIpeds
import ed.unicoach.db.models.NewPriceFigure
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.ValueBearingStatus
import kotlinx.coroutines.runBlocking

/**
 * The costs tests' own fixture, shared by [CollegeCostServiceTest] and
 * [CollegeCostChatToolTest]: the cost-domain seeders (a college with its
 * published figures, the money-profile answers) and the truncation list this
 * suite needs. The connection, session, statement counter, student and
 * college-list plumbing it shares with the admissions suite live in
 * [CoachingTestDb].
 */
object CostsTestDb {
  val database: Database get() = CoachingTestDb.database

  val sqlSession: SqlSession get() = CoachingTestDb.sqlSession

  /**
   * The one writer both costs test classes seed money-profile answers through
   * -- the real service, so a fixture can never record a state or a band the
   * production write path would have normalised or rejected.
   */
  val moneyProfiles: MoneyProfileService by lazy { MoneyProfileService(database) }

  private var nextIpedsUnitId = 500000

  /** The shared bracket dollar figures (`net_price_per_year_income_q1_usd..q5`) [seedCollege] seeds by default — the one home both test classes read. */
  const val NET_PRICE_PER_YEAR_INCOME_Q1_USD = 9000
  const val NET_PRICE_PER_YEAR_INCOME_Q2_USD = 11000
  const val NET_PRICE_PER_YEAR_INCOME_Q3_USD = 14000
  const val NET_PRICE_PER_YEAR_INCOME_Q4_USD = 17000
  const val NET_PRICE_PER_YEAR_INCOME_Q5_USD = 21000

  /**
   * The shared component figures (RFC 149) [seedCollege] seeds by default -- the
   * one home both test classes read, so an expected arrangement total is
   * written as a sum of these constants rather than as a magic number that
   * silently stops matching the fixture.
   */
  const val HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD = 9000
  const val HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD = 11000
  const val BOOKS_AND_SUPPLIES_PER_YEAR_USD = 1200
  const val OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD = 3000
  const val OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD = 3500
  const val OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD = 2500

  /**
   * The published tuition figures [seedCollege] seeds by default. Named for the
   * same reason the component figures are: an expected arrangement total is a
   * sum of these constants rather than a magic number that quietly stops
   * matching the fixture.
   */
  const val TUITION_AND_FEES_IN_STATE_PER_YEAR_USD = 12000
  const val TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD = 30000

  /**
   * The academic year every canonical price row this fixture writes carries
   * (RFC 166 §3).
   *
   * A named constant, because the year is now DATA that the answer states back:
   * a test asserting `published_price_academic_year` reads this rather than a
   * literal, so a fixture that moves its year cannot leave an assertion quietly
   * describing a year nothing writes.
   */
  val PRICE_YEAR = AcademicYear(2023)

  /**
   * The same year as WORDS -- derived from [PRICE_YEAR], never a second literal
   * beside it (RFC 170 D14): the store carries the start year, and the label is
   * what a surface says back, so an assertion reads the one the payload renders.
   */
  val PRICE_ACADEMIC_YEAR = PRICE_YEAR.label

  /** The vintage every blended cohort row this fixture writes carries -- a year older, as the source publishes it. */
  val BLENDED_YEAR = AcademicYear(2022)

  /** [BLENDED_YEAR] as words, derived for the reason [PRICE_ACADEMIC_YEAR] is. */
  val BLENDED_ACADEMIC_YEAR = BLENDED_YEAR.label

  /**
   * The published cell ids the borrowing fixture records on its rows.
   *
   * A source variable is per FIELD in the real seed (H.511 for the any-loan
   * average, H.512 for the federal one). The fixture writes one id per KIND
   * because nothing this suite asserts reads it, and a per-loan-type table here
   * would be a second, drifting copy of [LoanType]'s own KDoc.
   */
  private const val BORROWING_AVERAGE_VARIABLE: String = "H.511"
  private const val BORROWING_COUNT_VARIABLE: String = "H.501"

  const val SOURCE_URL: String = "https://example.edu/cds-2024-25.pdf"
  const val ARCHIVE_URL: String = "https://www.collegedata.fyi/schools/example/2024-25"

  /** Truncates every table the cost read touches; each test class calls this from `@BeforeEach`. */
  fun reset() {
    CoachingTestDb.truncate(
      // The canonical money store (RFC 158) is where every cost figure this
      // suite asserts now comes from (RFC 166), so both fact tables are part of
      // this fixture and are reset with it. They are named FIRST because they
      // reference `colleges`; the TRUNCATE is CASCADE, so the order is
      // documentation rather than necessity.
      "price_figures",
      "cohort_money_stats",
      "money_profiles",
      "college_list_entries",
      "college_merit_aid",
      "cohort_money_stats",
      "cohort_population_counts",
      "aid_form_requirements",
      "source_documents",
      // The IPEDS attribute row carries the no-dorms flag the cost read joins
      // (RFC 149), so it is part of this suite's fixture and must be reset with it.
      "college_ipeds",
      "colleges",
      "students",
      "users",
    )
    // `colleges.state` and `colleges.locale` are foreign keys into the codebook
    // reference tables since migration 0067, so a college cannot be inserted
    // until those vocabularies exist. They are seeded HERE, idempotently, and
    // not left to whichever other suite happens to run first: the test database
    // is recreated on every `bin/test`, and Gradle skips an up-to-date `:db:test`
    // -- which is exactly the run in which nothing else seeds them and every
    // fixture in this suite fails on a foreign key that has nothing to do with
    // what it asserts.
    CodebookReferenceFixture.seed(sqlSession)
    // The five vocabulary tables are FOREIGN KEYS of every canonical fact row, so
    // seeding them is a WRITE PRECONDITION, not tidiness: a suite that skips it
    // fails on a foreign key that has nothing to do with what it asserts. The
    // fixture is idempotent, so it is safe after every truncate.
    MoneyVocabularyFixture.seed(sqlSession)
  }

  /**
   * One school's Common Data Set aid policy (RFC 170), written as the ingest
   * writes it: the two need figures as cohort statistics, the two headcounts as
   * population counts, and the required forms as requirements -- all citing one
   * `source_documents` row.
   *
   * Every figure is nullable and the form list may be empty, because the
   * interesting cases are exactly the partial ones: a school with an average
   * and no headcounts (so no fully-met share), a school with forms and no
   * figures, and a school with no filing at all (which seeds nothing).
   */
  fun seedAidPolicy(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    averageNeedMet: Double? = 0.943,
    averageNeedBasedGrantUsd: Int? = 18007,
    freshmenAwardedAnyAid: Int? = 800,
    freshmenNeedFullyMet: Int? = 300,
    requiredForms: List<AidForm> = listOf(AidForm.FAFSA, AidForm.CSS_PROFILE),
    /**
     * Forms this school requires of INTERNATIONAL applicants (CDS H7). The
     * domestic read must not surface them, which is the D4 guarantee: a family
     * in the wrong group must never be told to file.
     */
    nonresidentForms: List<AidForm> = emptyList(),
    /**
     * Forms the corpus carries but could not extract (D7): a row with status
     * `not_collected_by_us` and no value. It states OUR gap and is not a
     * requirement, so the read must not list it either.
     */
    notCollectedForms: List<AidForm> = emptyList(),
    sourceUrl: String = SOURCE_URL,
    archiveUrl: String? = ARCHIVE_URL,
  ) {
    val year = AcademicYear(sourceYear)
    val document =
      CoachingTestDb.seedSourceDocument(collegeId, sourceYear, sourceUrl, archiveUrl)
    val stats =
      listOfNotNull(
        averageNeedMet?.let { MoneyMeasure.AVG_NEED_MET_SHARE to it },
        averageNeedBasedGrantUsd?.let { MoneyMeasure.AVG_NEED_BASED_GRANT to it.toDouble() },
      ).map { (measure, value) ->
        NewCohortMoneyStat(
          collegeId = collegeId.value,
          measure = measure,
          // Lines i and k are reported over line e, the freshmen who received
          // need-based scholarship or grant aid -- not over the line-d count
          // below (RFC 170).
          population = CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_NEED_BASED_GRANT,
          residencyScope = CohortResidencyScope.ALL,
          aidScope = CohortAidScope.NEED_BASED_AID_RECEIVING,
          incomeBand = null,
          vintage = year,
          reading = FigureReading.Present(value, ValueBearingStatus.REPORTED),
          source = MoneySource.COMMON_DATA_SET,
          sourceVariable = if (measure == MoneyMeasure.AVG_NEED_MET_SHARE) "H.209" else "H.211",
          sourceDocumentId = document,
        )
      }
    val counts =
      listOfNotNull(
        freshmenAwardedAnyAid?.let { CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_ANY_AID to it },
        freshmenNeedFullyMet?.let { CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_NEED_FULLY_MET to it },
      ).map { (population, headcount) ->
        NewCohortPopulationCount(
          collegeId = collegeId.value,
          population = population,
          residencyBasis = ResidencyBasis.NOT_APPLICABLE,
          arrangement = FigureArrangement.NOT_APPLICABLE,
          vintage = year,
          reading = FigureReading.Present(headcount, ValueBearingStatus.REPORTED),
          source = MoneySource.COMMON_DATA_SET,
          sourceVariable = "H.204",
          sourceDocumentId = document,
        )
      }

    fun requirement(
      form: AidForm,
      group: AidFormApplicantGroup,
      reading: FigureReading<Boolean>,
    ) = NewAidFormRequirement(
      collegeId = collegeId.value,
      form = form,
      applicantGroup = group,
      academicYear = year,
      reading = reading,
      source = MoneySource.COMMON_DATA_SET,
      sourceVariable = "H.801",
      sourceDocumentId = document,
    )

    val required = FigureReading.Present(true, ValueBearingStatus.REPORTED)
    val forms =
      requiredForms.map { requirement(it, AidFormApplicantGroup.DOMESTIC_FIRST_YEAR, required) } +
        nonresidentForms.map {
          requirement(it, AidFormApplicantGroup.NONRESIDENT_FIRST_YEAR, required)
        } +
        notCollectedForms.map {
          requirement(
            it,
            AidFormApplicantGroup.DOMESTIC_FIRST_YEAR,
            FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US),
          )
        }
    CanonicalMoneyDao.insertCohortMoneyStats(sqlSession, stats).getOrThrow()
    CanonicalMoneyDao.insertCohortPopulationCounts(sqlSession, counts).getOrThrow()
    CanonicalMoneyDao.insertAidFormRequirements(sqlSession, forms).getOrThrow()
  }

  /**
   * One school's Common Data Set borrowing block (RFC 175), written as the
   * ingest writes it: an average per loan type as a cohort statistic AT THAT
   * LOAN TYPE'S OWN AID SCOPE, a borrower headcount per loan type as a
   * population count, and the graduating class they are all reported over.
   *
   * Every figure is nullable because the interesting cases are the partial
   * ones: a school that filed federal and no private figure, a borrower count
   * with no graduating class (so an average and no share), and a school with no
   * filing at all (which seeds nothing).
   *
   * The scope is read from [LoanType], not passed in: the fixture must write
   * what the ingest writes, and a test that could choose its own scope would
   * pass while the store held an average over the wrong population.
   */
  fun seedBorrowing(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    graduatingClass: Int? = 1000,
    averageDebtUsdByLoanType: Map<LoanType, Int> = mapOf(LoanType.ANY to 27202, LoanType.FEDERAL to 20747),
    borrowersByLoanType: Map<LoanType, Int> = mapOf(LoanType.ANY to 396, LoanType.FEDERAL to 394),
    /**
     * The loan types this filing ANSWERS and we could not read: a value-less
     * `not_collected_by_us` average and borrower count each, exactly as the
     * ingest writes an unreadable cell (RFC 175 D7).
     *
     * A fixture that wrote NO row instead would be the no-block case wearing
     * this name, and the guard it is meant to exercise would never fire.
     */
    unreadLoanTypes: Set<LoanType> = emptySet(),
    /**
     * The loan types this filing ANSWERS and the PUBLISHER withheld: a
     * value-less `suppressed_by_publisher` average and borrower count each.
     *
     * Not the school's silence and not ours, which is the whole reason the read
     * carries a STATUS and not a boolean -- told as either of the other two, it
     * says something about this school that its filing does not say.
     */
    withheldLoanTypes: Set<LoanType> = emptySet(),
    sourceUrl: String = SOURCE_URL,
    archiveUrl: String? = ARCHIVE_URL,
  ) {
    val year = AcademicYear(sourceYear)
    val document = CoachingTestDb.seedSourceDocument(collegeId, sourceYear, sourceUrl, archiveUrl)

    fun average(
      loanType: LoanType,
      reading: FigureReading<Double>,
    ) = NewCohortMoneyStat(
      collegeId = collegeId.value,
      measure = loanType.debtAverage,
      // Reported over the graduating class, averaged over that loan type's
      // own borrowers -- the two columns say two different things and the
      // fixture states both.
      population = CohortPopulation.GRADUATING_CLASS,
      residencyScope = CohortResidencyScope.ALL,
      aidScope = loanType.aidScope,
      incomeBand = null,
      vintage = year,
      reading = reading,
      source = MoneySource.COMMON_DATA_SET,
      sourceVariable = BORROWING_AVERAGE_VARIABLE,
      sourceDocumentId = document,
    )

    fun count(
      population: CohortPopulation,
      reading: FigureReading<Int>,
    ) = NewCohortPopulationCount(
      collegeId = collegeId.value,
      population = population,
      residencyBasis = ResidencyBasis.NOT_APPLICABLE,
      arrangement = FigureArrangement.NOT_APPLICABLE,
      vintage = year,
      reading = reading,
      source = MoneySource.COMMON_DATA_SET,
      sourceVariable = BORROWING_COUNT_VARIABLE,
      sourceDocumentId = document,
    )

    // OUR gap: the row is there, under the same document, carrying no value.
    val unread = FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US)
    // THE PUBLISHER's: the row is there under the same document and carries no
    // value either, and only the status tells the two apart.
    val withheld = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER)
    val stats =
      averageDebtUsdByLoanType.map { (loanType, amountUsd) ->
        average(loanType, FigureReading.Present(amountUsd.toDouble(), ValueBearingStatus.REPORTED))
      } + unreadLoanTypes.map { average(it, unread) } + withheldLoanTypes.map { average(it, withheld) }
    val counts =
      (
        listOfNotNull(graduatingClass?.let { CohortPopulation.GRADUATING_CLASS to it }) +
          borrowersByLoanType.map { (loanType, headcount) -> loanType.borrowers to headcount }
      ).map { (population, headcount) ->
        count(population, FigureReading.Present(headcount, ValueBearingStatus.REPORTED))
      } + unreadLoanTypes.map { count(it.borrowers, unread) } +
        withheldLoanTypes.map { count(it.borrowers, withheld) }

    CanonicalMoneyDao.insertCohortMoneyStats(sqlSession, stats).getOrThrow()
    CanonicalMoneyDao.insertCohortPopulationCounts(sqlSession, counts).getOrThrow()
  }

  /**
   * One `college_ipeds` row carrying the on-campus housing flag (RFC 149 D-B).
   *
   * [offersHousing] is nullable on purpose: a row that does not report `IC.ROOM`
   * must read exactly like NO row at all, and a fixture that could not express
   * it would leave that equivalence untested. Every other column is an
   * uninteresting minimum -- this seeder exists for one flag.
   */
  fun seedIpedsHousing(
    ipedsUnitId: Int,
    offersHousing: Boolean?,
  ) = CollegeIpedsDao
    .upsert(
      sqlSession,
      NewCollegeIpeds(
        ipedsUnitId = ipedsUnitId,
        surveyYear = 2023,
        cyActive = true,
        deathYear = null,
        closedAt = null,
        newIpedsUnitId = null,
        instLevel = null,
        ugOffer = null,
        sector = null,
        carnegieBasic = null,
        carnegieSize = null,
        cbsa = null,
        relAffil = null,
        hasRotc = null,
        hasStudyAbroad = null,
        disabilityBand = null,
        registeredDisabilityPercent = null,
        offersHousing = offersHousing,
        housingCapacityHeadcount = null,
        applicationFeeUsd = null,
        athleticAssoc = emptyList(),
        footballConf = null,
        testPolicy = null,
      ),
    ).getOrThrow()

  /** One CDS merit-aid row for [collegeId] (RFC 148 D7), through the shared seeder. */
  fun seedMeritAid(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    firstTimeFullTimeFreshmenHeadcount: Int? = 2000,
    noNeedMeritRecipientsHeadcount: Int? = 500,
    noNeedMeritAverageUsd: Int? = 12500,
    sourceUrl: String = SOURCE_URL,
    archiveUrl: String? = ARCHIVE_URL,
  ) = CoachingTestDb.seedMeritAid(
    collegeId,
    sourceYear,
    firstTimeFullTimeFreshmenHeadcount,
    noNeedMeritRecipientsHeadcount,
    noNeedMeritAverageUsd,
    sourceUrl,
    archiveUrl,
  )

// ---------------------------------------------------------------------------
  // The canonical money store (RFC 166): what the cost read actually reads.
  // ---------------------------------------------------------------------------

  /**
   * One reading for a fixture cell: a value when the seeder was given one, and
   * otherwise the SCHOOL's own silence.
   *
   * `not_reported_by_institution` is the right default and not an arbitrary one:
   * every one of this suite's `null` parameters used to mean exactly "this
   * college does not report it", which is what `data_availability` has always
   * said about a null column. A test that wants one of the OTHER five statuses
   * asks for it by name ([seedPriceStatus]).
   */
  private fun readingOf(amountUsd: Int?): FigureReading<Int> =
    amountUsd
      ?.let { FigureReading.Present(it, ValueBearingStatus.REPORTED) }
      ?: FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION)

  private fun readingOf(value: Double?): FigureReading<Double> =
    value
      ?.let { FigureReading.Present(it, ValueBearingStatus.REPORTED) }
      ?: FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION)

  /**
   * Writes one `price_figures` row for [collegeId] through the REAL writer.
   *
   * Public so a test can add a second academic year, a third residency tier or a
   * cell with one of the five other statuses to a college this fixture already
   * seeded -- the shapes the default seeder deliberately does not carry.
   */
  fun seedPriceFigure(
    collegeId: CollegeId,
    concept: PriceConcept,
    residency: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
    arrangement: FigureArrangement = FigureArrangement.NOT_APPLICABLE,
    academicYear: AcademicYear = PRICE_YEAR,
    reading: FigureReading<Int>,
    source: MoneySource = MoneySource.IPEDS_IC_AY,
    sourceVariable: String = "FIXTURE",
    publisherFlag: String? = null,
  ) {
    CanonicalMoneyDao
      .insertPriceFigures(
        sqlSession,
        listOf(
          NewPriceFigure(
            collegeId = collegeId.value,
            priceConcept = concept,
            residencyBasis = residency,
            arrangement = arrangement,
            academicYear = academicYear,
            reading = reading,
            source = source,
            sourceVariable = sourceVariable,
            publisherFlag = publisherFlag,
          ),
        ),
      ).getOrThrow()
  }

  /**
   * One `price_figures` row for [field], written at the address
   * [CostField.figureAddress] gives it.
   *
   * The field-addressed door, and the one this fixture seeds through: WHERE a
   * field lives is stated once, in production, so a fixture row can never sit at
   * a cell the read does not read. The coordinate-taking overload above stays
   * for the tests that deliberately write an address NO field names -- a second
   * population, a corrupt cell -- which is a different thing to say.
   */
  fun seedPriceFigure(
    collegeId: CollegeId,
    field: CostField,
    reading: FigureReading<Int>,
    academicYear: AcademicYear = PRICE_YEAR,
    source: MoneySource = MoneySource.IPEDS_IC_AY,
    sourceVariable: String = "FIXTURE",
    publisherFlag: String? = null,
  ) {
    val address =
      requireNotNull((field.figureAddress as? FigureAddress.Price)?.address) {
        "no `price_figures` cell answers for [${field.wireName}], so this fixture cannot write a price row for it"
      }
    seedPriceFigure(
      collegeId = collegeId,
      concept = address.concept,
      residency = address.residency,
      arrangement = address.arrangement,
      academicYear = academicYear,
      reading = reading,
      source = source,
      sourceVariable = sourceVariable,
      publisherFlag = publisherFlag,
    )
  }

  /** Writes one `cohort_money_stats` row for [collegeId] through the real writer. */
  fun seedCohortStat(
    collegeId: CollegeId,
    measure: MoneyMeasure,
    population: CohortPopulation,
    residencyScope: CohortResidencyScope,
    aidScope: CohortAidScope,
    incomeBand: IncomeBand? = null,
    vintage: AcademicYear?,
    reading: FigureReading<Double>,
    sourceVariable: String = "FIXTURE",
  ) {
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        sqlSession,
        listOf(
          NewCohortMoneyStat(
            collegeId = collegeId.value,
            measure = measure,
            population = population,
            residencyScope = residencyScope,
            aidScope = aidScope,
            incomeBand = incomeBand,
            vintage = vintage,
            reading = reading,
            source = MoneySource.SCORECARD,
            sourceVariable = sourceVariable,
          ),
        ),
      ).getOrThrow()
  }

  /**
   * One `cohort_money_stats` row for [field], written at the measure/population/
   * aid-scope address [CostField.figureAddress] gives it.
   *
   * Only [residencyScope], [incomeBand] and [vintage] are the fixture's own
   * choices: they are facts about the COLLEGE and the SOURCE (a public school's
   * blend is built for in-state-rate-paying undergraduates, an undated measure
   * carries no year), not about which cell answers for a field. The three that
   * ARE the address come from production, so this fixture cannot seed a row the
   * reader will not find -- the failure tier-0's blocker was.
   */
  fun seedCohortStat(
    collegeId: CollegeId,
    field: CostField,
    residencyScope: CohortResidencyScope,
    vintage: AcademicYear?,
    reading: FigureReading<Double>,
    incomeBand: IncomeBand? = null,
    sourceVariable: String = "FIXTURE",
  ) {
    val address =
      requireNotNull((field.figureAddress as? FigureAddress.Cohort)?.address) {
        "no `cohort_money_stats` row answers for [${field.wireName}], so this fixture cannot write a cohort row for it"
      }
    require(incomeBand == null || address.bandSelected) {
      "[${field.wireName}] is not band-selected, so a banded row for it is a row the read would never select: " +
        "band=[${incomeBand?.value}]"
    }
    seedCohortStat(
      collegeId = collegeId,
      measure = address.measure,
      population = address.population,
      residencyScope = residencyScope,
      aidScope = address.aidScope,
      incomeBand = incomeBand,
      vintage = vintage,
      reading = reading,
      sourceVariable = sourceVariable,
    )
  }

  /**
   * The canonical rows behind one fixture college -- the same figures
   * [seedCollege] has always taken as parameters, written where the cost read
   * now looks for them.
   *
   * The addresses mirror `CanonicalMoneyLoader`'s own fill exactly, and the
   * blended cohorts' residency scope is keyed off CONTROL exactly as the loader
   * keys it: a public school's blended figures are built for in-state-rate-paying
   * undergraduates, everybody else's for all of them (RFC 157). A fixture that
   * hand-asserted `in_state_rate_paying` everywhere would make the RFC 157
   * withholding chain untestable at a private school.
   */
  private fun seedCanonicalMoney(
    collegeId: CollegeId,
    control: Int,
    costOfAttendancePerYearUsd: Int?,
    netPricePerYearUsd: Int?,
    netPriceReading: FigureReading<Double>?,
    bandNetPrices: Map<IncomeBand, Int?>,
    tuitionAndFeesInStatePerYearUsd: Int?,
    tuitionAndFeesOutOfStatePerYearUsd: Int?,
    tuitionAndFeesInDistrictPerYearUsd: Int?,
    seedsInDistrictRow: Boolean,
    feesOnlyInStatePerYearUsd: Int?,
    feesOnlyOutOfStatePerYearUsd: Int?,
    feesOnlyInDistrictPerYearUsd: Int?,
    medianDebtAtCompletionUsd: Int?,
    medianEarnings10yAfterEntryUsd: Int?,
    housingAndFoodOnCampusPerYearUsd: Int?,
    housingAndFoodOffCampusPerYearUsd: Int?,
    booksAndSuppliesPerYearUsd: Int?,
    otherExpensesOnCampusPerYearUsd: Int?,
    otherExpensesOffCampusPerYearUsd: Int?,
    otherExpensesWithFamilyPerYearUsd: Int?,
    omittedPriceFields: Set<CostField> = emptySet(),
  ) {
    // Seeded BY FIELD, never by coordinate: [CostField.figureAddress] is the ONE
    // home for where a field lives, so this fixture and the read it feeds cannot
    // hold two spellings of the same address. A hand-typed triple that stopped
    // matching would fail nothing -- the row is written, the read does not find
    // it, and the suite goes green asserting "this school reports nothing" about
    // a school whose price we lost. That silent miss is exactly how tier-0's
    // cohort-address blocker stayed invisible until a human read the loader.
    val priceAmounts: Map<CostField, Int?> =
      mapOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD to tuitionAndFeesInStatePerYearUsd,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD to tuitionAndFeesOutOfStatePerYearUsd,
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD to tuitionAndFeesInDistrictPerYearUsd,
        CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD to feesOnlyInDistrictPerYearUsd,
        CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD to feesOnlyInStatePerYearUsd,
        CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD to feesOnlyOutOfStatePerYearUsd,
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD to housingAndFoodOnCampusPerYearUsd,
        CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD to housingAndFoodOffCampusPerYearUsd,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to booksAndSuppliesPerYearUsd,
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD to otherExpensesOnCampusPerYearUsd,
        CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD to otherExpensesOffCampusPerYearUsd,
        CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD to otherExpensesWithFamilyPerYearUsd,
        // NO with-family housing-and-food entry, ever: no source publishes one,
        // and the `$0` the at-home total carries is unicoach's own assumption
        // (RFC 166 §7) rather than a figure this store holds. Its address is
        // [FigureAddress.AssumedByUnicoach], so it could not be written anyway.
      )

    // The fields NO ROW AT ALL is written for -- a different fact from a row the
    // publisher answered with a silence, and the one the read must tell apart.
    // The in-district pair rides [seedsInDistrictRow] because the ~2,300 IC_PY
    // institutions carry no in-district cell (RFC 166 §4's third case), and a
    // null `fees_only` state figure writes nothing because most of the corpus
    // has no fees cell at all.
    val unwritten: Set<CostField> =
      buildSet {
        addAll(omittedPriceFields)
        if (!seedsInDistrictRow) {
          add(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD)
          add(CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD)
        }
        if (feesOnlyInStatePerYearUsd == null) add(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD)
        if (feesOnlyOutOfStatePerYearUsd == null) add(CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD)
      }

    priceAmounts.forEach { (field, amountUsd) ->
      if (field in unwritten) return@forEach
      seedPriceFigure(collegeId, field, reading = readingOf(amountUsd))
    }

    val blendScope =
      if (control == CONTROL_PUBLIC) CohortResidencyScope.IN_STATE_RATE_PAYING else CohortResidencyScope.ALL
    // Addressed by FIELD, exactly as the price rows above are: the measure, the
    // population and the aid scope come from [CostField.figureAddress]. The
    // scope and the vintage stay the fixture's, because they are facts about
    // this college and this source rather than about where a field lives.
    seedCohortStat(
      collegeId,
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
      blendScope,
      vintage = BLENDED_YEAR,
      reading = readingOf(costOfAttendancePerYearUsd?.toDouble()),
    )
    seedCohortStat(
      collegeId,
      CostField.NET_PRICE,
      blendScope,
      vintage = BLENDED_YEAR,
      reading = netPriceReading ?: readingOf(netPricePerYearUsd?.toDouble()),
    )
    IncomeBand.entries.forEach { band ->
      seedCohortStat(
        collegeId,
        CostField.NET_PRICE,
        blendScope,
        vintage = BLENDED_YEAR,
        reading = readingOf(bandNetPrices[band]?.toDouble()),
        incomeBand = band,
      )
    }
    seedCohortStat(
      collegeId,
      CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      CohortResidencyScope.ALL,
      vintage = null,
      reading = readingOf(medianDebtAtCompletionUsd?.toDouble()),
    )
    seedCohortStat(
      collegeId,
      CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD,
      CohortResidencyScope.ALL,
      vintage = null,
      reading = readingOf(medianEarnings10yAfterEntryUsd?.toDouble()),
    )
  }

  /** The Scorecard CONTROL value meaning "public" -- the one that keys the blended figures' residency scope. */
  const val CONTROL_PUBLIC = 1

  fun createStudent(): StudentId = CoachingTestDb.createStudent("costs")

  /**
   * What IPEDS says about one fixture college's on-campus housing (RFC 149 D-B).
   *
   * FOUR cases, not three, because the read must fold two of them together: a
   * college with NO `college_ipeds` row and one whose row leaves `IC.ROOM`
   * unreported are both "not reported", and a fixture that could only express
   * one of them would leave that equivalence unasserted.
   */
  enum class IpedsHousing(
    /** Whether a `college_ipeds` row is written at all. */
    val seedsRow: Boolean,
    /** The `offers_housing` value that row carries; null is a row that does not report `IC.ROOM`. */
    val offersHousing: Boolean?,
  ) {
    /** No `college_ipeds` row at all -- the ordinary case for most colleges. */
    NO_ROW(seedsRow = false, offersHousing = null),

    /** A row that does not report `IC.ROOM`; must read exactly like [NO_ROW]. */
    UNREPORTED(seedsRow = true, offersHousing = null),

    /** `IC.ROOM = 1`: this school offers on-campus housing. */
    OFFERS(seedsRow = true, offersHousing = true),

    /** `IC.ROOM = 2`: this school has no residence halls. */
    DOES_NOT_OFFER(seedsRow = true, offersHousing = false),
  }

  fun seedCollege(
    name: String,
    state: String = "CA",
    control: Int = 1,
    costOfAttendancePerYearUsd: Int? = 40000,
    netPricePerYearUsd: Int? = 20000,
    /**
     * The overall net-price ROW as the store holds it, when the test is about
     * WHY there is no number rather than about the number.
     *
     * A `null` [netPricePerYearUsd] writes the school's own silence, which is
     * one of six statuses; a cohort figure the Scorecard SUPPRESSED FOR PRIVACY
     * -- the commonest absence in that series -- is a different fact and no
     * `Int?` can say it.
     */
    netPriceReading: FigureReading<Double>? = null,
    netPricePerYearIncomeQ1Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q1_USD,
    netPricePerYearIncomeQ2Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q2_USD,
    netPricePerYearIncomeQ3Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q3_USD,
    netPricePerYearIncomeQ4Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q4_USD,
    netPricePerYearIncomeQ5Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q5_USD,
    tuitionAndFeesInStatePerYearUsd: Int? = TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
    tuitionAndFeesOutOfStatePerYearUsd: Int? = TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
    // The third tier (RFC 166 §4), canonical-only: there is no `colleges` column
    // for it and there never was. Absent by default, because at most institutions
    // there is no in-district row at all -- and that absence is exactly the case
    // the in-district work is most careful about.
    seedsInDistrictRow: Boolean = false,
    tuitionAndFeesInDistrictPerYearUsd: Int? = null,
    // The fees split (RFC 166 §5), canonical-only for the same reason. A null
    // writes NO row rather than a silent one, so an ordinary fixture college
    // carries the payload it carried before these keys existed.
    feesOnlyInStatePerYearUsd: Int? = null,
    feesOnlyOutOfStatePerYearUsd: Int? = null,
    feesOnlyInDistrictPerYearUsd: Int? = null,
    medianDebtAtCompletionUsd: Int? = 23000,
    medianEarnings10yAfterEntryUsd: Int? = 55000,
    // The six published cost components (RFC 149). Defaulted to a complete,
    // internally consistent set so an ordinary fixture college renders all
    // three living arrangements; a test that wants a gap nulls the one part it
    // is about.
    housingAndFoodOnCampusPerYearUsd: Int? = HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
    housingAndFoodOffCampusPerYearUsd: Int? = HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD,
    booksAndSuppliesPerYearUsd: Int? = BOOKS_AND_SUPPLIES_PER_YEAR_USD,
    otherExpensesOnCampusPerYearUsd: Int? = OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
    otherExpensesOffCampusPerYearUsd: Int? = OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
    otherExpensesWithFamilyPerYearUsd: Int? = OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
    // Price cells to write NO row for at all, so a test can then write its own
    // -- a cell at another academic year, or one of the five statuses the
    // parameters above cannot express. A null parameter writes the SCHOOL's
    // silence, which is a row; this writes nothing, which is the only way to
    // leave the address free for [seedPriceFigure] (the writer is a plain
    // INSERT and a second row at one address is a constraint violation).
    omittedPriceFields: Set<CostField> = emptySet(),
    ipedsHousing: IpedsHousing = IpedsHousing.NO_ROW,
  ): CollegeId {
    val ipedsUnitId = nextIpedsUnitId++
    // One seeder call, and the case's own data decides what it writes: three
    // arms saying the same thing differently is how a fourth case comes to be
    // added with the wrong value in it.
    if (ipedsHousing.seedsRow) seedIpedsHousing(ipedsUnitId, ipedsHousing.offersHousing)
    return CollegesDao
      .upsert(
        sqlSession,
        NewCollege(
          ipedsUnitId = ipedsUnitId,
          opeid = "00$ipedsUnitId",
          name = name,
          city = "Townsville",
          state = state,
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = control,
          undergradEnrollmentHeadcount = 5000,
          admissionRateShare = 0.5,
          satAverageEquivalentScore = 1200,
          costOfAttendancePerYearUsd = costOfAttendancePerYearUsd,
          netPricePerYearUsd = netPricePerYearUsd,
          netPricePerYearIncomeQ1Usd = netPricePerYearIncomeQ1Usd,
          netPricePerYearIncomeQ2Usd = netPricePerYearIncomeQ2Usd,
          netPricePerYearIncomeQ3Usd = netPricePerYearIncomeQ3Usd,
          netPricePerYearIncomeQ4Usd = netPricePerYearIncomeQ4Usd,
          netPricePerYearIncomeQ5Usd = netPricePerYearIncomeQ5Usd,
          tuitionAndFeesInStatePerYearUsd = tuitionAndFeesInStatePerYearUsd,
          tuitionAndFeesOutOfStatePerYearUsd = tuitionAndFeesOutOfStatePerYearUsd,
          completionRate150pct4yrShare = 0.7,
          medianEarnings10yAfterEntryUsd = medianEarnings10yAfterEntryUsd,
          medianDebtAtCompletionUsd = medianDebtAtCompletionUsd,
          housingAndFoodOnCampusPerYearUsd = housingAndFoodOnCampusPerYearUsd,
          housingAndFoodOffCampusPerYearUsd = housingAndFoodOffCampusPerYearUsd,
          booksAndSuppliesPerYearUsd = booksAndSuppliesPerYearUsd,
          otherExpensesOnCampusPerYearUsd = otherExpensesOnCampusPerYearUsd,
          otherExpensesOffCampusPerYearUsd = otherExpensesOffCampusPerYearUsd,
          otherExpensesWithFamilyPerYearUsd = otherExpensesWithFamilyPerYearUsd,
          pellShare = 0.4,
          website = "https://test$ipedsUnitId.edu",
        ),
      ).getOrThrow()
      .id
      .also { collegeId ->
        // The canonical rows are where the cost read looks (RFC 166). The
        // `colleges` money columns above are still written and still read by the
        // search index, `similar_colleges` and admin-web -- this slice adds a
        // reader, it removes nothing.
        seedCanonicalMoney(
          collegeId = collegeId,
          control = control,
          costOfAttendancePerYearUsd = costOfAttendancePerYearUsd,
          netPricePerYearUsd = netPricePerYearUsd,
          netPriceReading = netPriceReading,
          bandNetPrices =
            mapOf(
              IncomeBand.UNDER_30K to netPricePerYearIncomeQ1Usd,
              IncomeBand.K30_TO_48K to netPricePerYearIncomeQ2Usd,
              IncomeBand.K48_TO_75K to netPricePerYearIncomeQ3Usd,
              IncomeBand.K75_TO_110K to netPricePerYearIncomeQ4Usd,
              IncomeBand.OVER_110K to netPricePerYearIncomeQ5Usd,
            ),
          tuitionAndFeesInStatePerYearUsd = tuitionAndFeesInStatePerYearUsd,
          tuitionAndFeesOutOfStatePerYearUsd = tuitionAndFeesOutOfStatePerYearUsd,
          tuitionAndFeesInDistrictPerYearUsd = tuitionAndFeesInDistrictPerYearUsd,
          seedsInDistrictRow = seedsInDistrictRow,
          feesOnlyInStatePerYearUsd = feesOnlyInStatePerYearUsd,
          feesOnlyOutOfStatePerYearUsd = feesOnlyOutOfStatePerYearUsd,
          feesOnlyInDistrictPerYearUsd = feesOnlyInDistrictPerYearUsd,
          medianDebtAtCompletionUsd = medianDebtAtCompletionUsd,
          medianEarnings10yAfterEntryUsd = medianEarnings10yAfterEntryUsd,
          housingAndFoodOnCampusPerYearUsd = housingAndFoodOnCampusPerYearUsd,
          housingAndFoodOffCampusPerYearUsd = housingAndFoodOffCampusPerYearUsd,
          booksAndSuppliesPerYearUsd = booksAndSuppliesPerYearUsd,
          otherExpensesOnCampusPerYearUsd = otherExpensesOnCampusPerYearUsd,
          otherExpensesOffCampusPerYearUsd = otherExpensesOffCampusPerYearUsd,
          otherExpensesWithFamilyPerYearUsd = otherExpensesWithFamilyPerYearUsd,
          omittedPriceFields = omittedPriceFields,
        )
        // `search_colleges` reads `college_search_index` (RFC 150 D53), derived
        // state the ingest rebuilds in its own phase; a fixture that writes
        // `colleges` directly has to rebuild it or the college is unsearchable.
        CollegesDao.rebuildSearchIndex(sqlSession).getOrThrow()
      }
  }

  /**
   * The money-profile seeders, here rather than per test class: the cost read
   * is a fold of a college row and a money profile, so both halves of the
   * fixture belong in the same home ([CollegeCostServiceTest] and
   * [CollegeCostChatToolTest] otherwise keep byte-identical copies that drift).
   */
  fun answerBand(
    student: StudentId,
    band: IncomeBand,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Set(band))).getOrThrow()
  }

  fun declineBand(student: StudentId) =
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Decline)).getOrThrow()
    }

  fun answerResidency(
    student: StudentId,
    state: String,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Set(state))).getOrThrow()
  }

  fun declineResidency(student: StudentId) =
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Decline)).getOrThrow()
    }

  /** The family's USUAL living plan (RFC 152) -- the money-profile default, not a claim about any one school. */
  fun answerLivingPlan(
    student: StudentId,
    plan: LivingArrangement,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(living = FieldUpdate.Set(plan))).getOrThrow()
  }

  fun declineLivingPlan(student: StudentId) =
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(living = FieldUpdate.Decline)).getOrThrow()
    }

  fun addToCollegeList(
    student: StudentId,
    collegeId: CollegeId,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
  ) = CoachingTestDb.addToCollegeList(student, collegeId, status)

  /**
   * This school's OWN living plan (RFC 152 D2a) -- the per-college override.
   * Written straight onto the row rather than through the chat tool, so a cost
   * test can state the fixture it is about in one line; the tool's own path is
   * asserted in [ed.unicoach.coaching.collegelist.CollegeListChatToolTest].
   */
  fun setEntryLivingPlan(
    student: StudentId,
    collegeId: CollegeId,
    plan: LivingArrangement?,
  ) {
    val entry =
      CollegeListEntriesDao
        .listActiveByStudent(sqlSession, student)
        .getOrThrow()
        .single { it.collegeId == collegeId }
    CollegeListEntriesDao
      .update(
        sqlSession,
        CollegeListEntryEdit(
          id = entry.id,
          version = entry.version,
          status = entry.status,
          reasons = entry.reasons,
          livingPlan = plan,
        ),
      ).getOrThrow()
  }
}
