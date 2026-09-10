package ed.unicoach.college

import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FactorRating
import ed.unicoach.db.models.LoanType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CDS seed loader suite (RFC 140), on the scorecard test scaffolding: colleges
 * are seeded from the Scorecard institutions fixture (unit ids 110100/220200/…)
 * so UNITID resolution runs against real `colleges` rows, and the per-test
 * TRUNCATE of `colleges` cascades through the CDS tables' FKs.
 */
class CdsSeedLoaderTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val loader = CdsSeedLoader(database)
  private val meritCsv = fixture("cds-merit-aid-fixture.csv")
  private val factorsCsv = fixture("cds-admission-factors-fixture.csv")
  private val deadlinesCsv = fixture("cds-deadlines-fixture.csv")
  private val aidPolicyCsv = fixture("cds-aid-policy-fixture.csv")

  private fun seedColleges() =
    runBlocking {
      scorecardLoader.load(
        fixture("scorecard-institutions-fixture.csv"),
        fixture("scorecard-fields-empty-fixture.csv"),
      )
      Unit
    }

  /**
   * The repo's committed CDS seed directory, resolved by walking up from the
   * test's working directory (the module dir under Gradle) rather than assuming
   * a fixed depth.
   */
  private val committedSeedDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/cds") }
      .first { it.isDirectory }

  private fun collegeId(ipedsUnitId: Int): CollegeId =
    withSession {
      requireNotNull(CollegesDao.findByIpedsUnitId(it, ipedsUnitId).getOrThrow()).id
    }

  @Test
  fun `loads the four seed files, skipping and counting unknown UNITIDs`() =
    runBlocking {
      seedColleges()
      val result = loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)

      // merit-aid: 2 matched rows, the 999999 row has no college -> skipped.
      assertEquals(2, result.meritAid.upserted)
      assertEquals(0, result.meritAid.changed)
      assertEquals(0, result.meritAid.unchanged)
      assertEquals(1, result.meritAid.skipped)
      assertEquals(2, result.admissionFactors.upserted)
      assertEquals(0, result.admissionFactors.skipped)
      assertEquals(3, result.deadlines.upserted)
      assertEquals(1, result.deadlines.skipped)

      // Spot values: the H2A row lands typed, incl. the null average.
      val coastal = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(110100), 2024).getOrThrow() }
      assertNotNull(coastal)
      assertEquals(2760, coastal.firstTimeFullTimeFreshmenHeadcount)
      assertEquals(358, coastal.noNeedMeritRecipientsHeadcount)
      assertEquals(16112, coastal.noNeedMeritAverageUsd)
      assertEquals("https://coastal.example.edu/cds-2024-25.pdf", coastal.sourceUrl)
      assertEquals("https://www.collegedata.fyi/schools/coastal/2024-25", coastal.archiveUrl)
      val lakeside = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(220200), 2025).getOrThrow() }
      assertNotNull(lakeside)
      assertNull(lakeside.noNeedMeritAverageUsd)

      // Factor grid: rated cells land as enum codes, empty cells as NULL.
      val factors = withSession { CdsAdmissionsDao.findAdmissionFactors(it, collegeId(220200), 2025).getOrThrow() }
      assertNotNull(factors)
      assertEquals(FactorRating.VERY_IMPORTANT, factors.rigor)
      assertEquals(FactorRating.IMPORTANT, factors.testScores)
      assertNull(factors.classRank)
      assertNull(factors.applicantInterest)

      // Deadlines: two rounds for 110100, the rolling flags-only row for 220200.
      val rounds = withSession { CdsAdmissionsDao.listDeadlines(it, collegeId(110100), 2024).getOrThrow() }
      assertEquals(2, rounds.size)
      val ed1 = rounds.first { it.round == ApplicationRound.EARLY_DECISION_1 }
      assertEquals(CdsMonthDay(11, 1), ed1.closing)
      assertEquals(CdsMonthDay(12, 15), ed1.notification)
      val rolling = withSession { CdsAdmissionsDao.listDeadlines(it, collegeId(220200), 2025).getOrThrow() }
      assertEquals(listOf(ApplicationRound.ROLLING), rolling.map { it.round })
      assertTrue(rolling.single().offered)
      assertNull(rolling.single().closing)

      // Coverage is computed from the DB over the matched schools only.
      assertEquals(2, result.coverage.launchSetCount)
      assertEquals(2, result.coverage.meritAidCount)
      assertEquals(2, result.coverage.admissionFactorsCount)
      assertEquals(2, result.coverage.deadlinesFlagsCount)
      assertEquals(1, result.coverage.deadlinesWithDateCount)
      assertEquals(emptyList(), result.coverage.studentListedMissing)
    }

  // ---------------------------------------------------------------------------
  // The aid-policy seed (RFC 170)
  // ---------------------------------------------------------------------------

  @Test
  fun `the need figures land as canonical cohort rows, with the published percent stored as a share`() =
    runBlocking {
      seedColleges()
      val result = loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)

      // Two schools' figures; the 999999 row has no college and is skipped.
      assertEquals(3, result.aidPolicy.cohortMoneyStats)
      assertEquals(2, result.aidPolicy.cohortPopulationCounts)
      assertEquals(listOf(999999), result.aidPolicy.unmatchedIpedsUnitIds)

      // 94.3% of need met is stored as the 0-1 share every other share is
      // stored as, under the population and aid scope that name its cohort.
      val share =
        query(
          "SELECT cms.value, cms.population, cms.aid_scope, cms.vintage, cms.source, cms.source_variable " +
            "FROM cohort_money_stats cms JOIN colleges c ON c.id = cms.college_id " +
            "WHERE c.ipeds_unit_id = 110100 AND cms.measure = 'avg_need_met_share'",
        ) { rs ->
          listOf(
            rs.getBigDecimal(1).toDouble().toString(),
            rs.getString(2),
            rs.getString(3),
            rs.getInt(4).toString(),
            rs.getString(5),
            rs.getString(6),
          )
        }.single()
      assertEquals(
        listOf(
          "0.943",
          "first_time_full_time_freshmen_awarded_need_based_grant",
          "need_based_aid_receiving",
          "2024",
          "common_data_set",
          "H.209",
        ),
        share,
      )

      // The dollar average keeps its cents -- the whole reason get_decimal
      // exists beside get_int.
      assertEquals(
        listOf("18006.5357"),
        query(
          "SELECT cms.value FROM cohort_money_stats cms JOIN colleges c ON c.id = cms.college_id " +
            "WHERE c.ipeds_unit_id = 110100 AND cms.measure = 'avg_need_based_grant'",
        ) { it.getBigDecimal(1).stripTrailingZeros().toPlainString() },
      )

      // The two headcounts are counts, not money: they land in the sibling
      // table, on the explicit not_applicable axes.
      assertEquals(
        listOf(
          listOf("first_time_full_time_freshmen_awarded_any_aid", "800", "not_applicable", "not_applicable"),
          listOf("first_time_full_time_freshmen_need_fully_met", "300", "not_applicable", "not_applicable"),
        ),
        query(
          "SELECT cpc.population, cpc.headcount, cpc.residency_basis, cpc.arrangement " +
            "FROM cohort_population_counts cpc JOIN colleges c ON c.id = cpc.college_id " +
            "WHERE c.ipeds_unit_id = 110100 ORDER BY cpc.population",
        ) { rs -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) },
      )
    }

  @Test
  fun `every aid-policy measure is written against the CDS line it is reported over`() =
    runBlocking {
      seedColleges()
      loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)

      // The CDS reports each H2 line against a NAMED earlier line, and the
      // lines are not interchangeable:
      //   line i (% of need met)     -- "of students who were awarded any need-based aid" = line e
      //   line k (avg need grant)    -- "of those in line e"                              = line e
      //   line h (need fully met)    -- "of students in line d"                           = line d
      // Pointing a figure at the wrong one states an average over a population
      // the school never measured, which no CHECK can catch: both slugs are
      // legal in the column. This is the pin.
      assertEquals(
        listOf(
          "avg_need_based_grant" to "first_time_full_time_freshmen_awarded_need_based_grant",
          "avg_need_met_share" to "first_time_full_time_freshmen_awarded_need_based_grant",
        ),
        query(
          "SELECT DISTINCT measure, population FROM cohort_money_stats WHERE source = 'common_data_set' " +
            "ORDER BY measure",
        ) { rs -> rs.getString(1) to rs.getString(2) },
      )
      assertEquals(
        listOf(
          "first_time_full_time_freshmen_awarded_any_aid",
          "first_time_full_time_freshmen_need_fully_met",
        ),
        query(
          "SELECT DISTINCT population FROM cohort_population_counts WHERE source = 'common_data_set' " +
            "ORDER BY population",
        ) { it.getString(1) },
      )
    }

  @Test
  fun `every borrowing average lands under its own loan type's denominator, and every count under its own cohort`() =
    runBlocking {
      seedColleges()
      loader.load(meritCsv, factorsCsv, deadlinesCsv, fixture("cds-aid-policy-borrowing-fixture.csv"))

      // The pin, both columns at once. The aid scope follows the DENOMINATOR
      // (RFC 175 D4): an average cumulative principal is an average over the
      // borrowers OF THAT LOAN TYPE, while the population is the graduating
      // class the school reports every H4/H5 figure against. Both slugs are
      // legal in either column, so no CHECK can catch a swap -- this is the
      // fourth guard against the defect RFC 148, 162 and 170 each hit.
      assertEquals(
        listOf(
          Triple("any_loan_debt_average", "graduating_class", "loan_receiving"),
          Triple("federal_loan_debt_average", "graduating_class", "federal_loan_borrowing"),
          Triple("private_loan_debt_average", "graduating_class", "private_loan_borrowing"),
        ),
        query(
          "SELECT measure, population, aid_scope FROM cohort_money_stats WHERE source = 'common_data_set' " +
            "ORDER BY measure",
        ) { rs -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) },
      )
      // The loan type rides in the POPULATION for a headcount, because
      // cohort_population_counts' natural key carries no measure column: five
      // borrower counts for one school-year would otherwise collide.
      assertEquals(
        listOf(
          listOf("graduating_class", "1000", "reported"),
          listOf("graduating_class_borrowers_any_loan", "600", "reported"),
          listOf("graduating_class_borrowers_federal_loan", "550", "reported"),
          listOf("graduating_class_borrowers_private_loan", "120", "reported"),
          // D7: the corpus HAS the cell and we could not read it. OUR gap is a
          // valueless ROW; a loan type no source carries gets no row at all.
          listOf("graduating_class_borrowers_state_loan", null, "not_collected_by_us"),
        ),
        query(
          "SELECT population, headcount, status FROM cohort_population_counts WHERE source = 'common_data_set' " +
            "ORDER BY population",
        ) { rs -> listOf(rs.getString(1), rs.getString(2), rs.getString(3)) },
      )
      // Institutional loans are in no row at all: this filing does not report
      // them, which is the school's silence and not a zero.
      assertEquals(
        emptyList(),
        query(
          "SELECT measure FROM cohort_money_stats WHERE measure = 'institutional_loan_debt_average'",
        ) { it.getString(1) },
      )
    }

  @Test
  fun `a fractional average survives ingest, and no loan type is ever summed with another`() =
    runBlocking {
      seedColleges()
      loader.load(meritCsv, factorsCsv, deadlinesCsv, fixture("cds-aid-policy-borrowing-fixture.csv"))

      // The decimal reader, end to end: '38217.2331' is what the corpus
      // publishes and an integer reader would have dropped every one of them.
      assertEquals(
        listOf("38217.2331", "30000.5", "56129.9072"),
        query(
          "SELECT value FROM cohort_money_stats WHERE source = 'common_data_set' ORDER BY measure",
        ) { it.getBigDecimal(1).stripTrailingZeros().toPlainString() },
      )
      // D3: the any-loan figures are the school's OWN H.501/H.511, never our
      // addition of the others. The four typed sets overlap, so a sum would
      // double-count every student who borrowed twice -- and here it would
      // also exceed the graduating class.
      assertEquals(
        listOf("600"),
        query(
          "SELECT headcount FROM cohort_population_counts " +
            "WHERE population = 'graduating_class_borrowers_any_loan'",
        ) { it.getString(1) },
      )
      assertEquals(
        emptyList(),
        query(
          // 550 + 120, and 38217.2331 + 30000.50 + 56129.9072: neither sum is
          // anywhere in the store.
          "SELECT headcount::text FROM cohort_population_counts WHERE headcount = 670 " +
            "UNION ALL SELECT value::text FROM cohort_money_stats WHERE value = 124347.6403",
        ) { it.getString(1) },
      )
    }

  @Test
  fun `a required form lands per applicant group, and OUR gap lands as a valueless row`() =
    runBlocking {
      seedColleges()
      val result = loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)
      assertEquals(4, result.aidPolicy.aidFormRequirements)

      assertEquals(
        listOf(
          listOf("css_profile", "t", "reported", "domestic_first_year_aid_applicants", "H.803"),
          listOf("fafsa", "t", "reported", "domestic_first_year_aid_applicants", "H.801"),
          // D7: the corpus HAS the cell and could not extract it. That is our
          // gap, and it is a row -- distinct from the school's silence, which
          // is no row at all.
          listOf("noncustodial_css_profile", null, "not_collected_by_us", "domestic_first_year_aid_applicants", "H.805"),
        ),
        query(
          "SELECT a.aid_form, a.is_required, a.status, a.applicant_group, a.source_variable " +
            "FROM aid_form_requirements a JOIN colleges c ON c.id = a.college_id " +
            "WHERE c.ipeds_unit_id = 110100 ORDER BY a.aid_form",
        ) { rs -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) },
      )

      // A form this school's CDS does not list gets NO row -- never a stored
      // "not required" (D5).
      assertEquals(
        emptyList(),
        query(
          "SELECT a.aid_form FROM aid_form_requirements a JOIN colleges c ON c.id = a.college_id " +
            "WHERE c.ipeds_unit_id = 110100 AND a.aid_form = 'state_aid_form'",
        ) { it.getString(1) },
      )
    }

  @Test
  fun `the loader refuses to write is_required FALSE, whatever the seed says`() =
    runBlocking {
      seedColleges()
      // D5, pinned at the loader rather than by a CHECK: the column can hold a
      // FALSE the day some source publishes one, but this seed's `false` means
      // the fetcher changed its mind about an unticked box, and storing it
      // would turn "not listed in this school's CDS" into a promise.
      val thrown =
        assertFailsWith<CdsSeedLoader.FormatException> {
          loader.load(
            meritCsv,
            factorsCsv,
            deadlinesCsv,
            fixture("cds-aid-policy-not-required-fixture.csv"),
          )
        }
      val defect = thrown.defect as CdsSeedLoader.Defect.UnknownCode
      assertEquals("value", defect.column)
      assertEquals("false", defect.value)
      assertEquals(listOf("true"), defect.allowed)
      assertEquals(0, withSession { count(it, "aid_form_requirements") })
    }

  @Test
  fun `a fact the canonical store has no home for is a broken seed, not a dropped row`() =
    runBlocking {
      seedColleges()
      val thrown =
        assertFailsWith<CdsSeedLoader.FormatException> {
          loader.load(meritCsv, factorsCsv, deadlinesCsv, fixture("cds-aid-policy-unknown-fact-fixture.csv"))
        }
      val defect = thrown.defect as CdsSeedLoader.Defect.UnknownCode
      // Still the `fact` column, unchanged: the field-id check RFC 179 added
      // runs AFTER the fact lookup precisely so this row keeps failing on the
      // column an operator has to fix. This fixture carries `H.999` too, and a
      // check ordered the other way would report that instead.
      assertEquals("fact", defect.column)
      assertEquals("meets_full_need", defect.value)
    }

  @Test
  fun `a row published under another fact's field id is refused, so the CDS key set is closed at ingest`() =
    runBlocking {
      seedColleges()
      // `source_variable` is read verbatim out of the seed and has no CHECK on
      // any of the three tables it lands in, so this pair was the one
      // `source_variable` key set in the store that nothing closed. An
      // assurance tier is a function of that string (RFC 179), so a figure
      // filed under the wrong field id is a wrong claim about how hard a
      // number is -- refused here rather than stored.
      val thrown =
        assertFailsWith<CdsSeedLoader.FormatException> {
          loader.load(meritCsv, factorsCsv, deadlinesCsv, fixture("cds-aid-policy-wrong-field-id-fixture.csv"))
        }
      val defect = thrown.defect as CdsSeedLoader.Defect.UnknownCode
      assertEquals(
        "source_variable (for fact [avg_need_met_percent])",
        defect.column,
        "the row's own fact is what decided the single accepted id, so the refusal names it",
      )
      assertEquals("H.211", defect.value, "the average GRANT's field id, on the average need MET row")
      assertEquals(listOf("H.209"), defect.allowed, "one allowed id, because the fact decides it")
      assertEquals(0, withSession { count(it, "cohort_money_stats") })
    }

  @Test
  fun `every aid-policy fact names the field id it is published under, and the committed seed agrees`() {
    // The pairing itself is no longer assertable: fact and field id are ONE
    // value ([CdsSeedLoader.AidPolicyCell]), so a fact without an id does not
    // compile. What is still worth walking is the committed seed, so the ids are
    // the ones the corpus actually carries rather than a plausible list.
    val pairs =
      CSVParser
        .parse(
          File(committedSeedDir, "aid_policy.csv"),
          Charsets.UTF_8,
          CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build(),
        ).use { records -> records.map { it.get("fact").trim() to it.get("source_variable").trim() }.toSet() }
    assertEquals(22, pairs.size, "22 facts, each published under exactly one field id")
    assertEquals(
      CdsSeedLoader.AID_POLICY_CELLS.entries
        .map { it.key to it.value.fieldId }
        .toSet(),
      pairs,
    )
  }

  @Test
  fun `the aid-policy rebuild is wholesale, so a re-run neither duplicates nor accumulates`() =
    runBlocking {
      seedColleges()
      val first = loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)
      val second = loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)
      assertEquals(first.aidPolicy, second.aidPolicy)
      assertEquals(3, withSession { count(it, "cohort_money_stats") })
      assertEquals(2, withSession { count(it, "cohort_population_counts") })
      assertEquals(4, withSession { count(it, "aid_form_requirements") })
    }

  @Test
  fun `re-running the load is idempotent -- every row unchanged`() =
    runBlocking {
      seedColleges()
      loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)
      val second = loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)

      assertEquals(0, second.meritAid.upserted)
      assertEquals(0, second.meritAid.changed)
      assertEquals(2, second.meritAid.unchanged)
      assertEquals(2, second.admissionFactors.unchanged)
      assertEquals(3, second.deadlines.unchanged)
      assertEquals(2, withSession { count(it, "college_merit_aid") })
      assertEquals(2, withSession { count(it, "college_admission_factors") })
      assertEquals(3, withSession { count(it, "college_deadlines") })
    }

  @Test
  fun `a changed value updates in place and advances updated_at`() =
    runBlocking {
      seedColleges()
      loader.load(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)
      val before = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(110100), 2024).getOrThrow() }
      assertNotNull(before)

      Thread.sleep(5)
      val result = loader.load(fixture("cds-merit-aid-changed-fixture.csv"), factorsCsv, deadlinesCsv, aidPolicyCsv)
      assertEquals(1, result.meritAid.changed)
      assertEquals(1, result.meritAid.unchanged)

      val after = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(110100), 2024).getOrThrow() }
      assertNotNull(after)
      assertEquals(before.id, after.id)
      assertEquals(17000, after.noNeedMeritAverageUsd)
      assertTrue(after.updatedAt.isAfter(before.updatedAt))
    }

  @Test
  fun `a missing or renamed header column is fatal and named`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(fixture("cds-merit-aid-bad-header-fixture.csv"), factorsCsv, deadlinesCsv, aidPolicyCsv) }
      }
    // Asserted on the structured defect, not the rendered sentence: the fields
    // are the payload, the message is one rendering of them.
    val defect = error.defect as CdsSeedLoader.Defect.HeaderMismatch
    // The RESOLVED path, not a basename: bin/ingest-colleges may hand the
    // loader a temp copy, and a basename cannot be resolved back to it.
    assertEquals(fixture("cds-merit-aid-bad-header-fixture.csv").path, defect.file)
    // `load` is handed bare Files, so the path IS the caller's argument here;
    // the ingest path below proves the operator's own spelling survives.
    assertEquals(defect.file, defect.sourceArg)
    assertEquals(listOf("freshmen_ft_total"), defect.missing)
    assertEquals(listOf("freshmen_ft"), defect.unexpected)
    assertEquals(CdsSeedLoader.MERIT_AID_COLUMNS, defect.expected)
    // Header assertion fires before any write: nothing landed from any file.
    assertEquals(0, withSession { count(it, "college_merit_aid") })
    assertEquals(0, withSession { count(it, "college_admission_factors") })
  }

  @Test
  fun `a rating outside the whitelist codes is fatal, not skipped`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(meritCsv, fixture("cds-admission-factors-junk-fixture.csv"), deadlinesCsv, aidPolicyCsv) }
      }
    val defect = error.defect as CdsSeedLoader.Defect.UnknownCode
    assertEquals(CdsSeedLoader.Table.ADMISSION_FACTORS, defect.table)
    assertEquals("rigor", defect.column)
    assertEquals("Very Important", defect.value)
    assertEquals(FactorRating.entries.map { it.value }, defect.allowed)
  }

  @Test
  fun `a month with no day loads, and does not count as a concrete date`() =
    runBlocking {
      seedColleges()
      // Real CDS reporting: "applications close in March". Stored raw (never
      // interpolated to a day), but the launch-set gate counts only complete
      // month+day dates, so it must not inflate deadlinesWithDateCount.
      val result = loader.load(meritCsv, factorsCsv, fixture("cds-deadlines-half-date-fixture.csv"), aidPolicyCsv)
      assertEquals(1, result.deadlines.upserted)

      val round = withSession { CdsAdmissionsDao.listDeadlines(it, collegeId(110100), 2024).getOrThrow() }.single()
      assertEquals(CdsMonthDay(3, null), round.closing)
      assertNull(round.notification)
      assertEquals(1, result.coverage.deadlinesFlagsCount)
      assertEquals(0, result.coverage.deadlinesWithDateCount)
    }

  @Test
  fun `a day with no month is a broken seed, not a half-date`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(meritCsv, factorsCsv, fixture("cds-deadlines-day-only-fixture.csv"), aidPolicyCsv) }
      }
    val defect = error.defect as CdsSeedLoader.Defect.DayWithoutMonth
    assertEquals(CdsSeedLoader.Table.DEADLINES, defect.table)
    assertEquals("closing_day", defect.dayColumn)
    assertEquals("closing_month", defect.monthColumn)
    assertEquals(15, defect.day)
  }

  @Test
  fun `an impossible calendar date is named by file and line, not left to the DB`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(meritCsv, factorsCsv, fixture("cds-deadlines-impossible-date-fixture.csv"), aidPolicyCsv) }
      }
    // Feb 30 is what a mangled extraction produces. Rejected HERE, with the
    // line and columns, rather than as an anonymous constraint violation.
    val defect = error.defect as CdsSeedLoader.Defect.NotACalendarDate
    assertEquals(CdsSeedLoader.Table.DEADLINES, defect.table)
    assertEquals(1L, defect.line)
    assertEquals("closing_month", defect.monthColumn)
    assertEquals("closing_day", defect.dayColumn)
    assertEquals(2, defect.month)
    assertEquals(30, defect.day)
    assertEquals(0, withSession { count(it, "college_deadlines") })
  }

  @Test
  fun `a ragged row fails as a located seed defect, not a raw parser error`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(fixture("cds-merit-aid-short-row-fixture.csv"), factorsCsv, deadlinesCsv, aidPolicyCsv) }
      }
    // The header assertion proves the column NAMES; a truncated row would
    // otherwise escape as Commons CSV's unlocated IllegalArgumentException.
    val defect = error.defect as CdsSeedLoader.Defect.RowArity
    assertEquals(CdsSeedLoader.Table.MERIT_AID, defect.table)
    assertEquals(1L, defect.line)
    assertEquals(CdsSeedLoader.MERIT_AID_COLUMNS.size, defect.expectedCells)
    assertEquals(3, defect.cells)
  }

  @Test
  fun `a source_year outside the academic years the store admits is a located seed defect`() =
    runBlocking {
      seedColleges()
      // The type carries the SQL domain's range, so a bad year is refused
      // before any write -- and it is refused HERE, with the file, line and
      // column, rather than as an unlocated IllegalArgumentException from a
      // value class three frames down.
      val thrown =
        assertFailsWith<CdsSeedLoader.FormatException> {
          loader.load(fixture("cds-merit-aid-impossible-year-fixture.csv"), factorsCsv, deadlinesCsv, aidPolicyCsv)
        }
      val defect = thrown.defect as CdsSeedLoader.Defect.NotAnAcademicYear
      assertEquals("source_year", defect.column)
      assertEquals(1999, defect.value)
      assertEquals(1L, defect.line)
    }

  @Test
  fun `a DB fault mid-load names the seed row that provoked it`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.LoadException> {
        runBlocking { loader.load(fixture("cds-merit-aid-bad-year-fixture.csv"), factorsCsv, deadlinesCsv, aidPolicyCsv) }
      }
    // A source_year inside the academic_year range but outside cds_source_year
    // (2015..2100) is refused by the DB, not by the loader's own cell checks --
    // so this is the DB-fault path, and it must still carry the row's seed
    // coordinates rather than a bare "Database constraint violation".
    assertEquals(CdsSeedLoader.Table.MERIT_AID, error.table)
    assertEquals(1L, error.line)
    assertEquals(110100, error.ipedsUnitId)
    assertTrue(error.cause is ed.unicoach.db.dao.ConstraintViolationException, "${error.cause}")
  }

  // ---------------------------------------------------------------------------
  // Provenance: the CDS load INSIDE the ingest run (RFC 148, D10)
  // ---------------------------------------------------------------------------

  private val cdsSources =
    CdsSources(source(meritCsv), source(factorsCsv), source(deadlinesCsv), source(aidPolicyCsv))

  /** One full ingest run over the Scorecard fixtures, with the CDS group
   * supplied or omitted — the only two shapes `bin/ingest-colleges` can produce. */
  private fun ingest(cds: CdsSources? = cdsSources): CollegeScorecardLoader.IngestReport =
    runBlocking {
      scorecardLoader.ingest(
        institution = source(fixture("scorecard-institutions-fixture.csv")),
        fields = source(fixture("scorecard-fields-empty-fixture.csv")),
        aliasesFile = source(fixture("college-aliases-fixture.json")),
        cds = cds,
      )
    }

  @Test
  fun `the build row records the CDS sources and row counts`() {
    val report = ingest()
    val load = assertNotNull(report.cds, "the run carries the CDS result it loaded")

    assertEquals(7, report.sources.size, "three Scorecard sources plus the four CDS seed files")
    // Digested the way every other source is: the recorded sha256 is the file's,
    // recomputed here independently rather than read back from the loader.
    val recorded = report.sources.first { it.fileName == meritCsv.name }
    val expectedDigest =
      MessageDigest
        .getInstance("SHA-256")
        .digest(meritCsv.readBytes())
        .joinToString("") { "%02x".format(it) }
    assertEquals(expectedDigest, recorded.sha256)
    assertEquals(meritCsv.length(), recorded.bytes)

    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    // 5: RFC 146 took 3 for the derived name-word rebuild, RFC 148's CDS bump
    // was therefore 4, RFC 150's derived search index is the next number in
    // the sequence, RFC 158's canonical money fill is 6, 7 is RFC 161's second
    // canonical money source together with RFC 162's third, and RFC 169's
    // published-price columns (with the phase reorder that feeds them) are 8.
    assertEquals(
      9,
      row.methodVersion,
      "RFC 176 took the method version to 9: the institution phase writes no money, so `non_null` carries 10 keys, not 28",
    )
    for (file in listOf(meritCsv, factorsCsv, deadlinesCsv, aidPolicyCsv)) {
      assertTrue(row.sources.contains(file.name), "sources names ${file.name}: ${row.sources}")
    }
    assertTrue(row.sources.contains(expectedDigest), "sources carries the CDS digest, not just the name: ${row.sources}")

    // The counts in the row ARE the counts the load reported.
    val rowsIngested = Json.parseToJsonElement(row.rowsIngested).jsonObject
    val cds = rowsIngested.getValue("cds").jsonObject

    fun upserted(table: String): Int =
      cds
        .getValue(table)
        .jsonObject
        .getValue("upserted")
        .jsonPrimitive.int
    // A SET, not a list: `sources`/`rows_ingested` are `jsonb`, which stores
    // object keys in its own normalised order, so key order is not a property
    // this row can carry and asserting it would only pin Postgres's ordering.
    assertEquals(
      setOf("merit_aid", "admission_factors", "deadlines", "aid_policy"),
      cds.keys,
      "one block per CDS seed file",
    )
    // The aid-policy block reports per DESTINATION table, because that seed
    // has no table of its own: its rows are canonical money facts (RFC 170).
    val aidPolicy = cds.getValue("aid_policy").jsonObject
    assertEquals(load.aidPolicy.cohortMoneyStats, aidPolicy.getValue("cohort_money_stat_rows").jsonPrimitive.int)
    assertEquals(
      load.aidPolicy.cohortPopulationCounts,
      aidPolicy.getValue("cohort_population_count_rows").jsonPrimitive.int,
    )
    assertEquals(load.aidPolicy.aidFormRequirements, aidPolicy.getValue("aid_form_requirement_rows").jsonPrimitive.int)
    assertEquals(1, aidPolicy.getValue("skipped").jsonPrimitive.int)
    val meritAid = cds.getValue("merit_aid").jsonObject
    assertEquals(load.meritAid.upserted, upserted("merit_aid"))
    assertEquals(2, upserted("merit_aid"))
    assertEquals(1, meritAid.getValue("skipped").jsonPrimitive.int)
    assertEquals(
      listOf(999999),
      meritAid.getValue("unmatched_ipeds_unit_ids").jsonArray.map { it.jsonPrimitive.int },
      "the seed schools our snapshot lacks are named, not merely counted",
    )
    assertEquals(2, upserted("admission_factors"))
    assertEquals(3, upserted("deadlines"))
    // The rows really landed: the build row describes a load that committed.
    assertEquals(2, withSession { count(it, "college_merit_aid") })
    assertEquals(3, withSession { count(it, "college_deadlines") })
  }

  @Test
  fun `a Scorecard-only run still writes a build row`() {
    val report = ingest(cds = null)
    assertNull(report.cds, "the whole CDS half is one absent value")
    assertEquals(3, report.sources.size)

    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    // Absent, never zero: a run that never read a seed file must not report
    // counts nobody measured.
    assertFalse(row.rowsIngested.contains("\"cds\""), "absent means absent: ${row.rowsIngested}")
    assertFalse(row.rowsIngested.contains("merit_aid"), row.rowsIngested)
    assertEquals(0, withSession { count(it, "college_merit_aid") })
  }

  @Test
  fun `the CDS phase commits before name-words and provenance`() {
    // The one way to observe phase ORDER from outside: hide the provenance
    // table so the last phase fails, and read what had committed. Restored in
    // the finally; the suite is sequential and bin/test recreates the test
    // database per run.
    renameBuildTable("college_index_build", "college_index_build_hidden")
    try {
      val thrown = assertFailsWith<PartialIngestException> { ingest() }
      assertEquals(
        listOf("institutions", "fields", "aliases", "cds", "name-words", "canonical-money", "search-index"),
        thrown.committedPhases,
      )
      assertEquals("provenance", thrown.failedPhase)
      assertEquals(2, withSession { count(it, "college_merit_aid") }, "the cds phase committed before provenance ran")
    } finally {
      renameBuildTable("college_index_build_hidden", "college_index_build")
    }
  }

  /** The one DDL this suite issues, in one place: hiding `college_index_build`
   * is how phase ORDER is observed from outside the run. */
  private fun renameBuildTable(
    from: String,
    to: String,
  ) = withSession { session ->
    session.prepareStatement("ALTER TABLE $from RENAME TO $to").use { it.execute() }
  }

  @Test
  fun `a failed CDS load writes no build row at all`() {
    val buildRowsBefore = withSession { count(it, "college_index_build") }
    // A defect inside a ROW, not in a header: headers are asserted up front (see
    // below), so this is the failure that can only be found mid-load, and it is
    // the one that has to roll all three tables back as a unit.
    val thrown =
      assertFailsWith<PartialIngestException> {
        ingest(cdsSources.copy(admissionFactors = source(fixture("cds-admission-factors-junk-fixture.csv"))))
      }
    assertEquals("cds", thrown.failedPhase)
    assertTrue(thrown.cause is CdsSeedLoader.FormatException, "${thrown.cause}")
    // The CDS load is one transaction, so nothing from any of its three files
    // survives -- and, running before provenance, it left no build row to
    // describe a run that failed.
    assertEquals(0, withSession { count(it, "college_merit_aid") })
    assertEquals(0, withSession { count(it, "college_admission_factors") })
    assertEquals(0, withSession { count(it, "college_deadlines") })
    assertEquals(buildRowsBefore, withSession { count(it, "college_index_build") }, "no build row describes a failed run")
  }

  @Test
  fun `a bad CDS header aborts the run before any phase commits`() {
    // The point of an up-front assertion: the CDS files are the last three of
    // ten, so if their headers were checked only when the cds phase ran, a
    // renamed column would be found AFTER institutions, fields, aliases and the
    // IPEDS phases had each committed their own transaction -- the half-written
    // snapshot the check exists to prevent. It must fail before phase one.
    val buildRowsBefore = withSession { count(it, "college_index_build") }
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        ingest(
          cdsSources.copy(
            meritAid =
              SourceFile(fixture("cds-merit-aid-bad-header-fixture.csv"), "s3://seed/merit-aid.csv"),
          ),
        )
      }
    val defect = error.defect as CdsSeedLoader.Defect.HeaderMismatch
    // The defect carries (path, sourceArg, missing) like the other seven files
    // of the same up-front check: an s3:// argument downloaded to a temp path
    // is only nameable through `sourceArg`.
    assertEquals(fixture("cds-merit-aid-bad-header-fixture.csv").path, defect.file)
    assertEquals("s3://seed/merit-aid.csv", defect.sourceArg)
    assertEquals(listOf("freshmen_ft_total"), defect.missing)
    // Not a PartialIngestException at all: nothing had committed to report.
    assertEquals(0, withSession { count(it, "colleges") }, "the institutions phase must not have run")
    assertEquals(0, withSession { count(it, "college_merit_aid") })
    assertEquals(buildRowsBefore, withSession { count(it, "college_index_build") })
  }

  private data class BuildRow(
    val methodVersion: Int,
    val sources: String,
    val rowsIngested: String,
  )

  private fun buildRow(
    session: ed.unicoach.db.dao.SqlSession,
    id: java.util.UUID,
  ): BuildRow? =
    session
      .prepareStatement("SELECT method_version, sources::text, rows_ingested::text FROM college_index_build WHERE id = ?")
      .use { stmt ->
        stmt.setObject(1, id)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) return null
          BuildRow(rs.getInt(1), rs.getString(2), rs.getString(3))
        }
      }

  @Test
  fun `the committed seed's headers are the headers this loader expects`() {
    // The generator (bin/fetch-cds-seed) owns the header, this loader restates
    // it, and until now the pairing that matters -- committed seed versus loader
    // constants -- was never exercised: a renamed column stayed green in CI and
    // fatalled only at operator ingest. Content-agnostic: whatever is committed
    // is read and compared.
    // Driven off Table.entries with an exhaustive `when`: a seed file added to
    // the loader and forgotten here is a COMPILE error, which is what a fourth
    // file left out of a three-entry map cost -- aid_policy.csv's header
    // contract went unchecked until this was rewritten.
    for (table in CdsSeedLoader.Table.entries) {
      val (name, expected) =
        when (table) {
          CdsSeedLoader.Table.MERIT_AID -> {
            "merit-aid.csv" to CdsSeedLoader.MERIT_AID_COLUMNS
          }

          CdsSeedLoader.Table.ADMISSION_FACTORS -> {
            "admission-factors.csv" to CdsSeedLoader.ADMISSION_FACTORS_COLUMNS
          }

          CdsSeedLoader.Table.DEADLINES -> {
            "deadlines.csv" to CdsSeedLoader.DEADLINES_COLUMNS
          }

          CdsSeedLoader.Table.AID_POLICY -> {
            "aid_policy.csv" to CdsSeedLoader.AID_POLICY_COLUMNS
          }
        }
      val header = File(committedSeedDir, name).useLines { it.first() }.split(",")
      assertEquals(expected, header, "db/seed/cds/$name header drifted from CdsSeedLoader")
    }
  }

  @Test
  fun `every loan type's seed columns route to that loan type's own address, all five of them`() {
    // The routing table is DERIVED from LoanType, so a swapped constant cannot
    // be written -- and this pins the derivation itself over all five members,
    // not the three a CSV fixture happens to carry. An institutional/state
    // swap was green locally and a production corrupt-value fault at read time.
    LoanType.entries.forEach { loanType ->
      assertEquals(
        CdsSeedLoader.AidPolicyFact.Count(loanType.borrowers),
        CdsSeedLoader.AID_POLICY_CELLS["${loanType.slug}_borrower_count"]?.fact,
        "the borrower headcount of [${loanType.slug}] is filed under another cohort",
      )
      assertEquals(
        CdsSeedLoader.AidPolicyFact.Stat(
          loanType.debtAverage,
          CohortPopulation.GRADUATING_CLASS,
          loanType.aidScope,
        ),
        CdsSeedLoader.AID_POLICY_CELLS["${loanType.slug}_debt_avg_usd"]?.fact,
        "the average of [${loanType.slug}] is stored at a denominator that is not its own",
      )
    }
  }

  @Test
  fun `the committed seed writes exactly the facts this loader routes`() {
    // The fetcher's fact vocabulary and the loader's routing table are two
    // lists of the same names in two languages. A fact the seed writes and the
    // loader does not route is a fatal ingest for the operator; a fact the
    // loader routes and the seed never writes is a destination nothing fills.
    // Parsed, not split on commas: a source_url carrying a comma would shift
    // every column and this test would compare the WRONG field -- a wrong
    // answer rather than an error. The module's own CSV reader is on the test
    // classpath for exactly this reason.
    val facts =
      CSVParser
        .parse(
          File(committedSeedDir, "aid_policy.csv"),
          Charsets.UTF_8,
          CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build(),
        ).use { records -> records.map { it.get("fact").trim() }.toSet() }
    assertEquals(CdsSeedLoader.AID_POLICY_CELLS.keys, facts)
  }

  @Test
  fun `the committed seed matches its PROVENANCE manifest`() {
    // PROVENANCE.json exists so the seed is trustworthy and regenerable; its
    // hashes and row counts are a copy of what the CSVs own, so they are
    // recomputed and compared here. A hand edit or a half-regeneration fails.
    val manifest =
      Json.parseToJsonElement(File(committedSeedDir, "PROVENANCE.json").readText()).jsonObject
    val digests = manifest.getValue("sha256").jsonObject
    val rows = manifest.getValue("rows").jsonObject
    assertTrue(digests.isNotEmpty(), "PROVENANCE.json lists no sha256 entries")
    for ((name, digest) in digests) {
      val file = File(committedSeedDir, name)
      val actualDigest =
        MessageDigest
          .getInstance("SHA-256")
          .digest(file.readBytes())
          .joinToString("") { "%02x".format(it) }
      assertEquals(digest.jsonPrimitive.content, actualDigest, "$name: sha256 differs from PROVENANCE.json")
      // Data rows = every non-blank line but the header (the seed is CRLF, so
      // count lines rather than newline bytes).
      val dataRows = file.readLines().count { it.isNotBlank() } - 1
      assertEquals(rows.getValue(name).jsonPrimitive.int, dataRows, "$name: row count differs from PROVENANCE.json")
    }
  }
}
