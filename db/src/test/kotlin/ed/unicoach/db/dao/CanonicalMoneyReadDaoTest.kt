package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewPriceFigure
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The read half of the canonical money store (RFC 166, D1): the batched reads
 * of [CanonicalMoneyReadDao] and the [FigureReading.of] decode, driven against
 * rows the REAL writer ([CanonicalMoneyDao]) produced -- a hand-inserted
 * fixture would test the test.
 *
 * The vocabulary is a write precondition of every fact row (P2), so `setUp`
 * seeds it FIRST; a suite that skips it fails on a foreign key that has
 * nothing to do with what it asserts.
 */
class CanonicalMoneyReadDaoTest {
  companion object {
    private const val NATURAL_KEY =
      "college_id=[c0ffee] price_concept=[tuition_and_fees] residency_basis=[in_state] " +
        "arrangement=[not_applicable] academic_year=[2022-23]"

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
        "TRUNCATE TABLE colleges, price_figures, cohort_money_stats, aid_policy_facts, " +
          "residency_bases, arrangements, figure_statuses, price_concepts, income_bands, " +
          "ipeds_regions, us_states, nces_locales CASCADE",
      )
    }
    CodebookReferenceFixture.seed(connection)
    // FIRST, before any fact row: the five vocabulary tables are FKs of every
    // price_figures / cohort_money_stats row (CanonicalMoneyDaoTest:264).
    MoneyVocabularyFixture.seed(connection)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  /** A session that fails the test if it is asked for a statement at all -- the no-query pin. */
  private val refusingSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = error("an empty id list must issue NO query, but asked for [$sql]")
    }

  private fun college(ipedsUnitId: Int = 110100): CollegeId =
    CollegesDao
      .upsert(session, newCollegeFixture(ipedsUnitId))
      .getOrThrow()
      .id

  private fun priceFigure(
    collegeId: CollegeId,
    concept: PriceConcept = PriceConcept.TUITION_AND_FEES,
    residency: ResidencyBasis = ResidencyBasis.IN_STATE,
    arrangement: FigureArrangement = FigureArrangement.NOT_APPLICABLE,
    academicYear: String = "2022-23",
    reading: FigureReading<Int> = FigureReading.Present(11000, ValueBearingStatus.REPORTED),
    sourceVariable: String = "TUITIONFEE_IN",
  ) = NewPriceFigure(
    collegeId = collegeId.value,
    priceConcept = concept,
    residencyBasis = residency,
    arrangement = arrangement,
    academicYear = academicYear,
    reading = reading,
    source = MoneySource.SCORECARD,
    sourceVariable = sourceVariable,
    publisherFlag = null,
  )

  private fun cohortStat(
    collegeId: CollegeId,
    measure: MoneyMeasure = MoneyMeasure.AVG_NET_PRICE,
    incomeBand: IncomeBand? = null,
    vintage: String = "2021-22",
    reading: FigureReading<Double> = FigureReading.Present(18000.0, ValueBearingStatus.REPORTED),
  ) = NewCohortMoneyStat(
    collegeId = collegeId.value,
    measure = measure,
    population = CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
    residencyScope = CohortResidencyScope.IN_STATE_RATE_PAYING,
    aidScope = CohortAidScope.FEDERAL_AID_RECEIVING,
    incomeBand = incomeBand,
    vintage = vintage,
    reading = reading,
    source = MoneySource.SCORECARD,
    sourceVariable = "NPT4_PUB",
  )

  /** One row per status, each in its own academic year so the natural key never collides. */
  private fun readingOf(status: FigureStatus): FigureReading<Int> =
    if (status.valueBearing) {
      FigureReading.Present(11000, ValueBearingStatus.entries.first { it.status == status })
    } else {
      FigureReading.Absent(AbsenceStatus.entries.first { it.status == status })
    }

  // ---------------------------------------------------------------------------
  // The batched read: every row for the ids asked for, and nothing else
  // ---------------------------------------------------------------------------

  @Test
  fun `listPriceFigures returns every row for the ids asked for and nothing else`() {
    val asked = college(110100)
    val alsoAsked = college(110200)
    val unasked = college(110300)
    CanonicalMoneyDao
      .insertPriceFigures(
        session,
        listOf(
          priceFigure(asked, concept = PriceConcept.TUITION_AND_FEES),
          priceFigure(asked, concept = PriceConcept.FEES_ONLY),
          priceFigure(alsoAsked, concept = PriceConcept.TUITION_AND_FEES),
          priceFigure(unasked, concept = PriceConcept.TUITION_AND_FEES),
        ),
      ).getOrThrow()

    val byCollege = CanonicalMoneyReadDao.listPriceFigures(session, listOf(asked, alsoAsked)).getOrThrow().byCollege

    assertEquals(setOf(asked, alsoAsked), byCollege.keys, "the unasked college must not appear")
    assertEquals(
      setOf(PriceConcept.TUITION_AND_FEES, PriceConcept.FEES_ONLY),
      byCollege.getValue(asked).map { it.priceConcept }.toSet(),
    )
    assertEquals(1, byCollege.getValue(alsoAsked).size)
    val row = byCollege.getValue(alsoAsked).single()
    assertEquals(alsoAsked, row.collegeId)
    assertEquals(ResidencyBasis.IN_STATE, row.residencyBasis)
    assertEquals(FigureArrangement.NOT_APPLICABLE, row.arrangement)
    assertEquals("2022-23", row.academicYear)
    assertEquals(MoneySource.SCORECARD, row.source)
    assertEquals("TUITIONFEE_IN", row.sourceVariable)
    assertEquals(null, row.publisherFlag)
    assertEquals(FigureReading.Present(11000, ValueBearingStatus.REPORTED), row.reading)
  }

  @Test
  fun `listCohortStats returns every row for the ids asked for and nothing else`() {
    val asked = college(110100)
    val unasked = college(110200)
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          cohortStat(asked, incomeBand = null),
          cohortStat(asked, incomeBand = IncomeBand.UNDER_30K),
          cohortStat(unasked, incomeBand = null),
        ),
      ).getOrThrow()

    val byCollege = CanonicalMoneyReadDao.listCohortStats(session, listOf(asked)).getOrThrow().byCollege

    assertEquals(setOf(asked), byCollege.keys, "the unasked college must not appear")
    val rows = byCollege.getValue(asked)
    assertEquals(2, rows.size)
    assertEquals(setOf(null, IncomeBand.UNDER_30K), rows.map { it.incomeBand }.toSet())
    val overall = rows.single { it.incomeBand == null }
    assertEquals(MoneyMeasure.AVG_NET_PRICE, overall.measure)
    assertEquals(CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES, overall.population)
    assertEquals(CohortResidencyScope.IN_STATE_RATE_PAYING, overall.residencyScope)
    assertEquals(CohortAidScope.FEDERAL_AID_RECEIVING, overall.aidScope)
    assertEquals("2021-22", overall.vintage)
    assertEquals(FigureReading.Present(18000.0, ValueBearingStatus.REPORTED), overall.reading)
  }

  @Test
  fun `an id with no row is simply absent, and a repeated id is asked for once`() {
    val withRows = college(110100)
    val withNone = college(110200)
    CanonicalMoneyDao.insertPriceFigures(session, listOf(priceFigure(withRows))).getOrThrow()

    val byCollege = CanonicalMoneyReadDao.listPriceFigures(session, listOf(withRows, withRows, withNone)).getOrThrow().byCollege

    assertEquals(setOf(withRows), byCollege.keys)
    assertEquals(1, byCollege.getValue(withRows).size, "a repeated id must not duplicate its rows")
  }

  // ---------------------------------------------------------------------------
  // An empty id list issues NO query (the CollegesDao.listByIds contract)
  // ---------------------------------------------------------------------------

  @Test
  fun `an empty id list short-circuits with no query at all`() {
    assertEquals(emptyMap(), CanonicalMoneyReadDao.listPriceFigures(refusingSession, emptyList()).getOrThrow().byCollege)
    assertEquals(emptyMap(), CanonicalMoneyReadDao.listCohortStats(refusingSession, emptyList()).getOrThrow().byCollege)
  }

  // ---------------------------------------------------------------------------
  // The decode, round-tripped through the real writer
  // ---------------------------------------------------------------------------

  @Test
  fun `every one of the six statuses round-trips through the real writer`() {
    val id = college()
    val years = listOf("2018-19", "2019-20", "2020-21", "2021-22", "2022-23", "2023-24")
    val written = FigureStatus.entries.zip(years)
    CanonicalMoneyDao
      .insertPriceFigures(
        session,
        written.map { (status, year) -> priceFigure(id, academicYear = year, reading = readingOf(status)) },
      ).getOrThrow()

    val byYear =
      CanonicalMoneyReadDao
        .listPriceFigures(session, listOf(id))
        .getOrThrow()
        .byCollege
        .getValue(id)
        .associateBy { it.academicYear }

    assertEquals(6, byYear.size, "all six statuses must be readable")
    for ((status, year) in written) {
      assertEquals(readingOf(status), byYear.getValue(year).reading, "for [$status]")
      assertEquals(status, byYear.getValue(year).reading.status, "for [$status]")
    }
  }

  @Test
  fun `every one of the six statuses round-trips on the cohort table too`() {
    val id = college()
    val years = listOf("2018-19", "2019-20", "2020-21", "2021-22", "2022-23", "2023-24")
    val written = FigureStatus.entries.zip(years)
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        written.map { (status, year) ->
          val reading =
            if (status.valueBearing) {
              FigureReading.Present(18000.5, ValueBearingStatus.entries.first { it.status == status })
            } else {
              FigureReading.Absent(AbsenceStatus.entries.first { it.status == status })
            }
          cohortStat(id, vintage = year, reading = reading)
        },
      ).getOrThrow()

    val byVintage =
      CanonicalMoneyReadDao
        .listCohortStats(session, listOf(id))
        .getOrThrow()
        .byCollege
        .getValue(id)
        .associateBy { it.vintage }

    assertEquals(6, byVintage.size, "all six statuses must be readable")
    for ((status, year) in written) {
      assertEquals(status, byVintage.getValue(year).reading.status, "for [$status]")
      if (status.valueBearing) {
        assertEquals(
          FigureReading.Present(18000.5, ValueBearingStatus.entries.first { it.status == status }),
          byVintage.getValue(year).reading,
        )
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Value-IFF-status, refused twice: by the CHECK, and by the Kotlin decode
  // ---------------------------------------------------------------------------

  @Test
  fun `a hand-written row violating value-IFF-status is refused by the CHECK`() {
    val id = college()
    for ((amount, status) in listOf("11000" to "suppressed_by_publisher", "NULL" to "reported")) {
      val thrown =
        assertFailsWith<Exception>("amount=$amount status=$status") {
          connection
            .prepareStatement(
              "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
                "academic_year, amount_usd, status, source, source_variable) " +
                "VALUES (?, 'tuition_and_fees', 'in_state', 'not_applicable', '2022-23', $amount, " +
                "'$status', 'scorecard', 'TUITIONFEE_IN')",
            ).use { stmt ->
              stmt.setObject(1, id.value)
              stmt.executeUpdate()
            }
        }
      assertTrue(thrown.message!!.contains("price_figures_value_iff_status_check"), thrown.message!!)
    }
  }

  @Test
  fun `the Kotlin decode refuses the same pairing when handed it directly`() {
    // The CHECK is the backstop for a writer that bypasses the sealed type;
    // this is the backstop for a ROW that somehow got past the CHECK.
    val suppressedWithValue =
      assertFailsWith<CorruptPersistedValueException> {
        FigureReading.of(11000, FigureStatus.SUPPRESSED_BY_PUBLISHER, "price_figures.[amount_usd]", NATURAL_KEY)
      }
    assertTrue(suppressedWithValue.message!!.contains("price_figures.[amount_usd]"), suppressedWithValue.message!!)
    assertTrue(suppressedWithValue.message!!.contains(NATURAL_KEY), suppressedWithValue.message!!)

    val reportedWithNone =
      assertFailsWith<CorruptPersistedValueException> {
        FigureReading.of<Int>(null, FigureStatus.REPORTED, "price_figures.[amount_usd]", NATURAL_KEY)
      }
    assertTrue(reportedWithNone.message!!.contains("price_figures.[amount_usd]"), reportedWithNone.message!!)
    assertTrue(reportedWithNone.message!!.contains(NATURAL_KEY), reportedWithNone.message!!)
  }

  // ---------------------------------------------------------------------------
  // An unreadable ROW costs its own row, never the batch
  // ---------------------------------------------------------------------------

  @Test
  fun `a stored status slug no enum reads costs its own row, and every other college still answers`() {
    // `figure_statuses` is a VOCABULARY table: a seventh row can be authored
    // into it without touching Kotlin, and then a fact row can legally point at
    // a slug FigureStatus does not read -- which is what a deploy skew looks
    // like, an OLDER build reading a row a newer loader wrote. Both reads are
    // batched over every college a request selected, so failing the Result
    // would fail the money answer for schools that have nothing wrong with
    // them. The row leaves the batch instead, and is NAMED.
    val readable = college(110100)
    val alsoReadable = college(110200)
    val partlyUnreadable = college(110300)
    CanonicalMoneyDao
      .insertPriceFigures(
        session,
        listOf(
          priceFigure(readable, concept = PriceConcept.TUITION_AND_FEES),
          priceFigure(alsoReadable, concept = PriceConcept.TUITION_AND_FEES),
          priceFigure(partlyUnreadable, concept = PriceConcept.TUITION_AND_FEES),
          priceFigure(partlyUnreadable, concept = PriceConcept.FEES_ONLY),
        ),
      ).getOrThrow()
    connection.createStatement().use {
      it.execute(
        "INSERT INTO figure_statuses (slug, description, value_bearing) " +
          "VALUES ('a_status_nothing_reads', 'authored after the enum', FALSE)",
      )
    }
    connection
      .prepareStatement(
        "UPDATE price_figures SET amount_usd = NULL, status = 'a_status_nothing_reads' " +
          "WHERE college_id = ? AND price_concept = 'tuition_and_fees'",
      ).use { stmt ->
        stmt.setObject(1, partlyUnreadable.value)
        stmt.executeUpdate()
      }

    val read = CanonicalMoneyReadDao.listPriceFigures(session, listOf(readable, alsoReadable, partlyUnreadable)).getOrThrow()

    // 1. The read SUCCEEDS: one unreadable row is a fact about that row.
    assertEquals(
      PriceConcept.TUITION_AND_FEES,
      read.byCollege
        .getValue(readable)
        .single()
        .priceConcept,
      "a college with no bad row must still get its figures",
    )
    assertEquals(1, read.byCollege.getValue(alsoReadable).size, "and so must every other college in the batch")

    // 2. The affected college keeps every row that IS readable; only the one
    //    cell that cannot be read is missing, so its field -- and no other --
    //    degrades to the caller's "we have not collected this" answer.
    assertEquals(
      listOf(PriceConcept.FEES_ONLY),
      read.byCollege.getValue(partlyUnreadable).map { it.priceConcept },
      "only the unreadable row may leave the batch",
    )

    // 3. And it is never a silent drop: the row is carried out, naming the
    //    table, the stored value and the column and row it sat in.
    val skipped = read.unreadable.single()
    assertEquals("price_figures", skipped.table)
    assertEquals("a_status_nothing_reads", skipped.stored)
    // EXACTLY the column and the row's natural key, which is what `location`
    // says it is -- not the exception's rendered sentence. Asserted whole, not
    // by `contains`: a substring check would keep passing if the field silently
    // became a message again, which is the regression this pins.
    assertEquals(
      "price_figures.[status] (row [college_id=[${partlyUnreadable.value}] price_concept=[tuition_and_fees] " +
        "residency_basis=[in_state] arrangement=[not_applicable] academic_year=[2022-23]])",
      skipped.location,
    )
    // And the decode failure travels WHOLE, so the layer that logs can log the
    // throwable itself rather than a sentence about it.
    assertEquals("a_status_nothing_reads", skipped.cause.value)
    assertEquals(skipped.location, skipped.cause.location)
    assertEquals(
      ValidationError.InvalidFormat(expected = "a known FigureStatus value"),
      skipped.cause.error,
      "the structured reason must survive the row boundary, not be recoverable only by parsing prose",
    )
  }

  @Test
  fun `unreadable rows already collected ride out with a read that then fails`() {
    // The batch may still fail for real, mid-iteration -- a connection dropped
    // between two rows. The rows already decoded as unreadable are evidence in
    // hand at that moment, and dropping them leaves the operator with a SQL
    // fault and no trace of the deploy skew that was also present.
    val id = college(110100)
    CanonicalMoneyDao
      .insertPriceFigures(
        session,
        listOf(
          priceFigure(id, concept = PriceConcept.TUITION_AND_FEES),
          priceFigure(id, concept = PriceConcept.FEES_ONLY),
        ),
      ).getOrThrow()
    connection.createStatement().use {
      it.execute(
        "INSERT INTO figure_statuses (slug, description, value_bearing) " +
          "VALUES ('a_status_nothing_reads', 'authored after the enum', FALSE); " +
          "UPDATE price_figures SET amount_usd = NULL, status = 'a_status_nothing_reads'",
      )
    }

    // A real read over real rows, whose ResultSet stops answering after the
    // first row -- the only way to reach the mid-batch fault, since the driver
    // has already fetched everything by the time the mapper runs.
    val failure =
      CanonicalMoneyReadDao
        .listPriceFigures(failingAfterFirstRow(), listOf(id))
        .exceptionOrNull()
    // The fault keeps its OWN type and classification -- the diagnostics ride
    // as suppressed detail rather than wrapping the failure into something a
    // retry policy can no longer recognise.
    assertTrue(
      failure is TransientDatabaseException,
      "a dropped connection stays a transient failed Result: ${failure?.let { it::class }}",
    )
    val carried =
      failure
        .suppressed
        .filterIsInstance<UnreadableRowsBeforeFailureException>()
        .single()
    assertEquals(1, carried.rows.size, "the row decoded before the fault must not be dropped")
    assertEquals("a_status_nothing_reads", carried.rows.single().stored)
  }

  @Test
  fun `a cohort row whose income band no enum reads leaves the batch beside its readable twin`() {
    // The same rule on the other fact table, on a different coded column:
    // `income_bands` is a vocabulary table too, so a band authored after this
    // build was compiled is a legal row this build cannot read.
    val id = college()
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(cohortStat(id, incomeBand = null), cohortStat(id, incomeBand = IncomeBand.UNDER_30K)),
      ).getOrThrow()
    connection.createStatement().use {
      it.execute(
        "INSERT INTO income_bands (slug, min_usd, max_usd, bracket_label, sort_order) " +
          "VALUES ('a_band_nothing_reads', 1, 2, 'authored after the enum', 99); " +
          "UPDATE cohort_money_stats SET income_band = 'a_band_nothing_reads' WHERE income_band = 'under_30k'",
      )
    }

    val read = CanonicalMoneyReadDao.listCohortStats(session, listOf(id)).getOrThrow()

    assertEquals(
      listOf(null),
      read.byCollege.getValue(id).map { it.incomeBand },
      "the readable row must survive its unreadable neighbour",
    )
    val skipped = read.unreadable.single()
    assertEquals("cohort_money_stats", skipped.table)
    assertEquals("a_band_nothing_reads", skipped.stored)
    assertEquals(
      "cohort_money_stats.[income_band] (row [college_id=[${id.value}] measure=[avg_net_price] " +
        "population=[title_iv_aided_undergraduates] residency_scope=[in_state_rate_paying] " +
        "aid_scope=[federal_aid_receiving] income_band=[a_band_nothing_reads] vintage=[2021-22]])",
      skipped.location,
    )
    assertEquals("a_band_nothing_reads", skipped.cause.value)
  }

  @Test
  fun `a cohort value the Int range cannot hold costs its own row, not the batch`() {
    // `cohort_money_stats.value` is an unconstrained NUMERIC, and the USD
    // measures are narrowed to whole dollars above this DAO -- where rounding
    // CLAMPS, so a runaway cell would be shown to a family as $2,147,483,647
    // with full confidence. It is refused here instead, as an ordinary decode
    // failure: one impossible cell may not fail the money answer for every
    // school in the batch.
    val id = college()
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(cohortStat(id, incomeBand = null), cohortStat(id, incomeBand = IncomeBand.UNDER_30K)),
      ).getOrThrow()
    connection.createStatement().use {
      it.execute("UPDATE cohort_money_stats SET value = 4000000000 WHERE income_band = 'under_30k'")
    }

    val read = CanonicalMoneyReadDao.listCohortStats(session, listOf(id)).getOrThrow()

    assertEquals(
      listOf(null),
      read.byCollege.getValue(id).map { it.incomeBand },
      "the readable row must survive a neighbour with no whole-dollar form",
    )
    val skipped = read.unreadable.single()
    assertEquals("cohort_money_stats", skipped.table)
    assertEquals("4.0E9", skipped.stored)
    assertEquals(
      "cohort_money_stats.[value] (row [college_id=[${id.value}] measure=[avg_net_price] " +
        "population=[title_iv_aided_undergraduates] residency_scope=[in_state_rate_paying] " +
        "aid_scope=[federal_aid_receiving] income_band=[under_30k] vintage=[2021-22]])",
      skipped.location,
    )
    assertEquals(
      ValidationError.InvalidFormat(expected = "a finite amount with a whole-dollar form"),
      skipped.cause.error,
    )
  }

  @Test
  fun `a NaN value costs its own row at the measure the store actually admits one`() {
    // Postgres admits 'NaN' in a NUMERIC column, and only the SHARE measures
    // are range-checked (`cohort_money_stats_share_range_check`, 0085: NaN
    // fails `BETWEEN 0 AND 1`). So the reachable non-finite value is at a
    // USD_PER_YEAR measure, where the check does not apply -- and NaN is the
    // case that makes the narrowing above THROW rather than clamp, so it must
    // not be allowed to reach it and fail the whole batch.
    val id = college()
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          cohortStat(id, measure = MoneyMeasure.AVG_NET_PRICE),
          cohortStat(id, measure = MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION),
        ),
      ).getOrThrow()
    connection.createStatement().use {
      it.execute("UPDATE cohort_money_stats SET value = 'NaN' WHERE measure = 'avg_net_price'")
    }

    val read = CanonicalMoneyReadDao.listCohortStats(session, listOf(id)).getOrThrow()

    assertEquals(
      listOf(MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION),
      read.byCollege.getValue(id).map { it.measure },
      "the other dollar row must survive the one that cannot be read",
    )
    val skipped = read.unreadable.single()
    assertEquals("NaN", skipped.stored)
    assertEquals(
      "cohort_money_stats.[value] (row [college_id=[${id.value}] measure=[avg_net_price] " +
        "population=[title_iv_aided_undergraduates] residency_scope=[in_state_rate_paying] " +
        "aid_scope=[federal_aid_receiving] income_band=[overall] vintage=[2021-22]])",
      skipped.location,
    )
    assertEquals(
      ValidationError.InvalidFormat(expected = "a finite amount with a whole-dollar form"),
      skipped.cause.error,
    )
  }

  /**
   * A session over the REAL connection whose [ResultSet] stops answering after
   * the first row. The rows are genuine and the decode is the production one;
   * only the fault is injected, and it arrives exactly where a dropped
   * connection would -- BETWEEN two rows -- which the driver's fetch-everything
   * behaviour makes unreachable any other way.
   */
  private fun failingAfterFirstRow(): SqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        return Proxy.newProxyInstance(
          PreparedStatement::class.java.classLoader,
          arrayOf(PreparedStatement::class.java),
        ) { _, method, args ->
          val answered = method.invoke(statement, *(args ?: emptyArray()))
          if (method.name == "executeQuery") failingResultSet(answered as ResultSet) else answered
        } as PreparedStatement
      }
    }

  private fun failingResultSet(rows: ResultSet): ResultSet {
    var advances = 0
    return Proxy.newProxyInstance(
      ResultSet::class.java.classLoader,
      arrayOf(ResultSet::class.java),
    ) { _, method, args ->
      if (method.name == "next" && advances++ == 1) {
        throw SQLException("the connection dropped between two rows", "08006")
      }
      method.invoke(rows, *(args ?: emptyArray()))
    } as ResultSet
  }
}
