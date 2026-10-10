package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AwardGuarantee
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FirstChoiceRule
import ed.unicoach.db.models.NationalMeritAward
import ed.unicoach.db.models.NationalMeritAwardId
import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.NationalMeritClaim
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `college_national_merit_awards`, its DAO and every CHECK on it (RFC 243,
 * slice `test/08/the-psat-is-money`), against the real database.
 *
 * THE MIGRATION IS UNDER TEST HERE TOO. `bin/test` re-inits and migrates the
 * test database before Gradle runs, so a suite that connects at all has proved
 * `create-college-national-merit-awards.sql` applies to a freshly recreated
 * database.
 *
 * EVERY CHECK IS PROVED BY A FAILED WRITE and never by reading the DDL
 * ([AutomaticAwardsDaoTest]'s idiom): the database is what a migration, a
 * generated seed and `psql` all write through, and none of those typechecks.
 *
 * THE SWEEP HAS TWO BASE ROWS, and that is the table's own shape rather than a
 * convenience. A paying row and a verified-absence row share almost no cells --
 * four CHECKs exist to keep them apart -- so a case about the absence rules
 * that started from a paying row would violate three constraints at once and
 * the sweep would report whichever PostgreSQL evaluated first.
 *
 * It does NOT save and restore the corpus, unlike [NationalMeritCyclesDaoTest]:
 * this corpus is loaded by the INGEST run and not by a migration (Route B), so
 * a freshly migrated table is empty and emptying it between cases leaves the
 * database as `db-reset` left it. `external_documents` is shared with every
 * other cited corpus and is never emptied here.
 */
class NationalMeritAwardsDaoTest {
  companion object {
    private lateinit var connection: Connection

    /** The school every case writes about, keyed as IPEDS keys one. */
    private const val UNIT_ID = 100663

    /** A second school, for the batched read and for "a school with no award row". */
    private const val OTHER_UNIT_ID = 100706

    private const val SOURCE_URL = NationalMeritFixture.DEFAULT_AWARD_SOURCE_URL

    /** A second page, for the archive citation. */
    private const val ARCHIVE_URL = "https://web.archive.org/web/2026/example-national-merit-award"

    /**
     * A LEGAL PAYING row as SQL literals, which every bad paying row in this
     * suite is one override away from: a case that had to restate twenty-one
     * columns would hide which column it was actually about.
     */
    private val AWARD_COLUMNS: Map<String, String> =
      linkedMapOf(
        "college_id" to "(SELECT id FROM colleges WHERE ipeds_unit_id = $UNIT_ID)",
        "entry_year" to "2027",
        "residency_basis" to "'not_applicable'",
        "qualifying_standing" to "'finalist'",
        "award_kind" to "'automatic'",
        "award_name" to "'Example Merit Package'",
        "award_per_year_usd" to "20000",
        "award_total_usd" to "80000",
        "award_years" to "4",
        "award_as_published" to "'Finalists receive tuition, housing and a stipend.'",
        "absence_as_published" to "NULL",
        "first_choice_required" to "TRUE",
        "first_choice_deadline" to "DATE '2027-05-01'",
        "first_choice_as_published" to "'Name us your first choice with NMSC by May 1.'",
        "apply_deadline" to "DATE '2026-12-01'",
        "apply_deadline_as_published" to "'Apply for admission by December 1.'",
        "publisher" to "'${NationalMeritFixture.DEFAULT_PUBLISHER}'",
        "source_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')",
        "archive_document_id" to "NULL",
        "checked_on" to "DATE '2026-09-25'",
        "expires_on" to "DATE '2027-07-31'",
      )

    /**
     * A LEGAL VERIFIED-ABSENCE row: we read this school's page and it publishes
     * no National Merit award at all. Every award-shaped cell is NULL, which is
     * what `..._none_published_is_empty_check` says in SQL.
     */
    private val ABSENCE_COLUMNS: Map<String, String> =
      AWARD_COLUMNS +
        mapOf(
          "qualifying_standing" to "NULL",
          "award_kind" to "'none_published'",
          "award_name" to "NULL",
          "award_per_year_usd" to "NULL",
          "award_total_usd" to "NULL",
          "award_years" to "NULL",
          "award_as_published" to "NULL",
          "absence_as_published" to "'We do not offer a National Merit scholarship.'",
          "first_choice_required" to "NULL",
          "first_choice_deadline" to "NULL",
          "first_choice_as_published" to "NULL",
          "apply_deadline" to "NULL",
          "apply_deadline_as_published" to "NULL",
        )

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
        connection.createStatement().use { stmt -> stmt.execute("DELETE FROM college_national_merit_awards") }
        connection.close()
      }
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var college: CollegeId = CollegeId(UUID.randomUUID())
  private var otherCollege: CollegeId = CollegeId(UUID.randomUUID())

  @BeforeEach
  fun setup() {
    connection.autoCommit = true
    // The colleges go because every award row foreign-keys one and this suite
    // creates its own.
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE colleges, college_national_merit_awards CASCADE")
    }
    // `colleges.state`/`locale` key into the codebooks (0067) and
    // `residency_basis` keys into the authored money vocabulary (RFC 158 D4):
    // both are preconditions of writing a row here, and an earlier suite in the
    // same Gradle invocation may have truncated either.
    CodebookReferenceFixture.seed(session)
    MoneyVocabularyFixture.seed(session)
    // The documents the literal-SQL rows cite. Created and never deleted:
    // external_documents is shared by every cited corpus, so a suite that
    // emptied it would take their citations with it. It states its evidence
    // like every other writer since RFC 220.
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO external_documents (url, url_status, url_checked_on) VALUES " +
          "('$SOURCE_URL', 'found', DATE '2026-09-25'), ('$ARCHIVE_URL', 'found', DATE '2026-09-25') " +
          "ON CONFLICT (url) DO NOTHING",
      )
    }
    college = seedCollege(UNIT_ID, "Example State University")
    otherCollege = seedCollege(OTHER_UNIT_ID, "Second State University")
  }

  // ---------------------------------------------------------------------------
  // Every CHECK, proved by a failed write
  // ---------------------------------------------------------------------------

  @Test
  fun `every CHECK on the award table refuses its own bad row, by name`() {
    val cases =
      listOf(
        // Our own enumerations (TEXT + CHECK IN, CLAUDE.md).
        "college_national_merit_awards_kind_check" to (AWARD_COLUMNS to mapOf("award_kind" to "'guaranteed'")),
        "college_national_merit_awards_standing_check" to
          (AWARD_COLUMNS to mapOf("qualifying_standing" to "'winner'")),
        // A STANDING IS NOT A SCORE AND A ROW STATES WHICH ONE IT PAYS. Both
        // directions, because this is an IFF: a paying row with no standing
        // cannot say what it pays for, and a standing on a school that
        // publishes nothing is a claim about an award that does not exist.
        "college_national_merit_awards_standing_iff_award_check" to
          (AWARD_COLUMNS to mapOf("qualifying_standing" to "NULL")),
        "college_national_merit_awards_standing_iff_award_check" to
          (ABSENCE_COLUMNS to mapOf("qualifying_standing" to "'finalist'")),
        // in_district and unknown are LEGAL members of residency_bases -- the
        // FK resolves -- and are refused here on RFC 239's argument: a
        // community-college tier and an IPEDS headcount category are not things
        // a university publishes a freshman award against.
        "college_national_merit_awards_residency_basis_check" to
          (AWARD_COLUMNS to mapOf("residency_basis" to "'in_district'")),
        "college_national_merit_awards_residency_basis_check" to
          (AWARD_COLUMNS to mapOf("residency_basis" to "'unknown'")),
        // EVERY CLAIM CARRIES THE PAGE'S OWN SENTENCE, AND THE TWO KINDS NEVER
        // CROSS. Oklahoma publishes $153,450 and Texas Tech publishes no number
        // at all, so the words are what every paying row must have; and an
        // absence quote on a paying row would render "we do not offer this"
        // under a six-figure package.
        "college_national_merit_awards_claim_has_words_check" to
          (AWARD_COLUMNS to mapOf("award_as_published" to "NULL")),
        "college_national_merit_awards_claim_has_words_check" to
          (AWARD_COLUMNS to mapOf("absence_as_published" to "'We do not offer a National Merit scholarship.'")),
        "college_national_merit_awards_absence_has_words_check" to
          (ABSENCE_COLUMNS to mapOf("absence_as_published" to "NULL")),
        "college_national_merit_awards_absence_has_words_check" to
          (ABSENCE_COLUMNS to mapOf("award_as_published" to "'Finalists receive tuition.'")),
        // A VERIFIED ABSENCE STATES NOTHING ELSE -- no name, no money, no
        // deadline, no first-choice rule, because there is no award for any of
        // them to describe.
        "college_national_merit_awards_none_published_is_empty_check" to
          (ABSENCE_COLUMNS to mapOf("award_name" to "'Example Merit Package'")),
        "college_national_merit_awards_none_published_is_empty_check" to
          (ABSENCE_COLUMNS to mapOf("award_per_year_usd" to "20000")),
        "college_national_merit_awards_none_published_is_empty_check" to
          (ABSENCE_COLUMNS to mapOf("award_total_usd" to "80000")),
        "college_national_merit_awards_none_published_is_empty_check" to
          (ABSENCE_COLUMNS to mapOf("award_years" to "4")),
        "college_national_merit_awards_none_published_is_empty_check" to
          (
            ABSENCE_COLUMNS to
              mapOf(
                "first_choice_required" to "TRUE",
                "first_choice_as_published" to "'Name us your first choice with NMSC.'",
              )
          ),
        "college_national_merit_awards_none_published_is_empty_check" to
          (
            ABSENCE_COLUMNS to
              mapOf(
                "apply_deadline" to "DATE '2026-12-01'",
                "apply_deadline_as_published" to "'Apply for admission by December 1.'",
              )
          ),
        // Zero is not an award, and no school pays a package over seven years.
        "college_national_merit_awards_money_positive_check" to
          (AWARD_COLUMNS to mapOf("award_per_year_usd" to "0")),
        "college_national_merit_awards_money_positive_check" to
          (AWARD_COLUMNS to mapOf("award_total_usd" to "-1")),
        "college_national_merit_awards_money_positive_check" to (AWARD_COLUMNS to mapOf("award_years" to "7")),
        // FIRST CHOICE IS A COMMITMENT AND NOT A DETAIL: naming a college first
        // choice with NMSC is an act with consequences elsewhere. A requirement
        // with no words is our claim rather than theirs, words with no
        // requirement are a rule the row denies having, and a deadline on a
        // school that does not require it is a date with nothing to be the
        // deadline of.
        "college_national_merit_awards_first_choice_trio_check" to
          (AWARD_COLUMNS to mapOf("first_choice_as_published" to "NULL")),
        "college_national_merit_awards_first_choice_trio_check" to
          (AWARD_COLUMNS to mapOf("first_choice_required" to "NULL")),
        "college_national_merit_awards_first_choice_trio_check" to
          (AWARD_COLUMNS to mapOf("first_choice_required" to "FALSE")),
        "college_national_merit_awards_apply_deadline_pair_check" to
          (AWARD_COLUMNS to mapOf("apply_deadline_as_published" to "NULL")),
        // BOTH DEADLINES BELONG TO THE APPLICATION CYCLE THAT ENDS IN THE ENTRY
        // YEAR, and both ends of both bounds are executed. The LOWER bound is
        // the half that earns its keep: a 2026-06-30 date under a Fall-2027 key
        // is LAST cycle's deadline, which is the one mistake that makes this
        // table worse than no table, because a family acts on it.
        "college_national_merit_awards_deadlines_in_cycle_check" to
          (AWARD_COLUMNS to mapOf("apply_deadline" to "DATE '2026-06-30'")),
        "college_national_merit_awards_deadlines_in_cycle_check" to
          (AWARD_COLUMNS to mapOf("apply_deadline" to "DATE '2027-10-01'")),
        "college_national_merit_awards_deadlines_in_cycle_check" to
          (AWARD_COLUMNS to mapOf("first_choice_deadline" to "DATE '2026-06-30'")),
        "college_national_merit_awards_deadlines_in_cycle_check" to
          (AWARD_COLUMNS to mapOf("first_choice_deadline" to "DATE '2027-10-01'")),
        // An empty as_published is a value-shaped absence, and a quote rendered
        // from one is a claim with no words behind it.
        "college_national_merit_awards_words_not_blank_check" to (AWARD_COLUMNS to mapOf("publisher" to "'   '")),
        "college_national_merit_awards_words_not_blank_check" to
          (AWARD_COLUMNS to mapOf("award_as_published" to "''")),
        "college_national_merit_awards_words_not_blank_check" to (AWARD_COLUMNS to mapOf("award_name" to "'  '")),
        "college_national_merit_awards_words_not_blank_check" to
          (AWARD_COLUMNS to mapOf("first_choice_as_published" to "''")),
        "college_national_merit_awards_words_not_blank_check" to
          (AWARD_COLUMNS to mapOf("apply_deadline_as_published" to "''")),
        "college_national_merit_awards_words_not_blank_check" to
          (ABSENCE_COLUMNS to mapOf("absence_as_published" to "''")),
        // An archived address that IS the page is the same link twice.
        "college_national_merit_awards_archive_differs_check" to
          (
            AWARD_COLUMNS to
              mapOf("archive_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')")
          ),
        // The boundary, not a comfortable failure: a row that expires the day
        // it was read is never live for a moment. Auburn is why the reading day
        // is on every row -- it cut a $44,000 award to residents only.
        "college_national_merit_awards_expiry_after_checked_check" to
          (AWARD_COLUMNS to mapOf("expires_on" to "DATE '2026-09-25'")),
      )

    // Every case is run and every mismatch reported, so one failing CHECK does
    // not hide the thirty behind it.
    val mismatches =
      cases.mapNotNull { (expected, case) ->
        val (base, overrides) = case
        val actual = violatedConstraint(base + overrides)
        if (actual == expected) null else "overrides $overrides expected [$expected], got [$actual]"
      }
    assertEquals(emptyList<String>(), mismatches, "a CHECK did not refuse the row it exists to refuse")
    assertEquals(0, countAwards(), "no refused row may be left behind")
  }

  /**
   * Both rows the sweep overrides ARE legal -- the assertion that makes every
   * line above mean something.
   *
   * Without it, a base row that no longer inserts (a renamed column, a CHECK
   * tightened past it) would leave every case failing on ITS OWN constraint,
   * and the sweep would report whichever one PostgreSQL happened to evaluate
   * first for rows that were never about it.
   */
  @Test
  fun `both rows the cases override are themselves accepted`() {
    assertNull(violatedConstraint(AWARD_COLUMNS), "the paying fixture row must be inside every CHECK")
    // The absence row is the same school, entry year and residency with a NULL
    // standing, so it does not collide with the paying row above.
    assertNull(violatedConstraint(ABSENCE_COLUMNS), "the absence fixture row must be inside every CHECK")
    assertEquals(2, countAwards())
  }

  /**
   * THE NATURAL KEY IS `NULLS NOT DISTINCT`, which is the half a default
   * PostgreSQL unique index gets wrong: `qualifying_standing` is NULL on every
   * verified-absence row, and under the default rule two NULLs never collide --
   * so a school could be recorded as publishing nothing TWICE, with two
   * different reading days, and a reader would have no way to say which is the
   * answer.
   */
  @Test
  fun `the one verified-absence row per school, year and residency cannot be typed twice`() {
    assertNull(violatedConstraint(ABSENCE_COLUMNS))

    assertEquals(
      "college_national_merit_awards_natural_key",
      violatedConstraint(ABSENCE_COLUMNS + mapOf("checked_on" to "DATE '2026-09-20'")),
      "two absence rows for one school, entry year and residency must collide",
    )
    assertEquals(1, countAwards())
  }

  /**
   * The ONE state the read model still refuses at runtime, asserted BESIDE the
   * database's own refusal rather than instead of it.
   *
   * Two guarantees, not one: the CHECK is what a migration, a generated seed
   * and `psql` write through, and the `require` is what a renderer reading a
   * reconstructed row gets. A row built in a fixture never reaches a CHECK
   * until it is written, which is the case this half covers.
   *
   * THE OTHER FIVE MIRRORS ARE GONE BECAUSE THE STATES ARE GONE.
   * `..._standing_iff_award_check`, `..._claim_has_words_check`,
   * `..._absence_has_words_check` and `..._none_published_is_empty_check` are
   * the two arms of [NationalMeritClaim] -- a standing, words, figures, a name
   * and a deadline exist on [NationalMeritClaim.Paying] and nowhere else, so
   * none of those rows can be WRITTEN DOWN in Kotlin, let alone constructed;
   * `..._first_choice_trio_check` is the two arms of [FirstChoiceRule], and
   * `..._apply_deadline_pair_check` is [ApplyDeadline] holding its date and its
   * sentence together. A test for a state the compiler refuses would not
   * compile either, which is the point.
   *
   * [NewNationalMeritAward] carries NO `require` -- by design, because the
   * ingest loader's job is to hand the database a bad row's LOCATION rather
   * than to fail to hold it -- so the case here is the READ model.
   */
  @Test
  fun `the read model refuses the expiry the schema refuses, one layer earlier, and names the CHECK`() {
    val thrown = assertFailsWith<IllegalArgumentException> { readAward(expiresOn = LocalDate.of(2026, 9, 25)) }

    assertTrue(
      thrown.message?.contains("college_national_merit_awards_expiry_after_checked_check") == true,
      "a model refusal must name the CHECK it mirrors, so a reader who hits one in a log finds the other half " +
        "of the guarantee: [${thrown.message}]",
    )
  }

  // ---------------------------------------------------------------------------
  // The DAO
  // ---------------------------------------------------------------------------

  @Test
  fun `a paying award round-trips through the DAO with its words, its rule and its dates`() {
    val written = NationalMeritFixture.seedAward(session, NationalMeritFixture.award(college))
    assertEquals(UpsertOutcome.INSERTED, written.outcome)

    val read = NationalMeritAwardsDao.listByColleges(session, listOf(college)).getOrThrow().single()

    assertEquals(written.id, read.id)
    val claim = assertIs<NationalMeritClaim.Paying>(read.claim, "a row that pays reads back as the paying arm")
    assertEquals(NationalMeritAwardKind.AUTOMATIC, claim.awardKind)
    assertEquals(AwardGuarantee.AUTOMATIC, claim.guarantee)
    assertEquals(QualifyingStanding.FINALIST, claim.standing)
    assertEquals(20_000, claim.perYearUsd)
    assertEquals("Finalists receive tuition, housing and a stipend.", claim.asPublished)
    val firstChoice = assertIs<FirstChoiceRule.Required>(assertNotNull(claim.firstChoice))
    assertEquals(LocalDate.of(2027, 5, 1), firstChoice.deadline)
    assertEquals(NationalMeritFixture.DEFAULT_AWARD_SOURCE_URL, read.sourceUrl)
    assertEquals(LocalDate.of(2026, 9, 25), read.checkedOn)
  }

  /**
   * THE VERIFIED ABSENCE IS AN ANSWER, and it is a different answer from "we
   * have not looked". Three schools in the curated set publish nothing, and a
   * coach that could not tell those apart from an unread school would either
   * invent a package or refuse one that exists.
   */
  @Test
  fun `a verified absence round-trips as an absence, with the sentence that proves it`() {
    NationalMeritFixture.seedAward(session, NationalMeritFixture.absence(college))

    val read = NationalMeritAwardsDao.listByColleges(session, listOf(college)).getOrThrow().single()

    val claim = assertIs<NationalMeritClaim.NonePublished>(read.claim, "an absence reads back as the absence arm")
    assertEquals(NationalMeritAwardKind.NONE_PUBLISHED, claim.awardKind)
    assertEquals(
      "We do not offer a National Merit scholarship.",
      claim.asPublished,
      "and the sentence that proves it is the arm's whole content: there is no standing, no words for an award " +
        "and no rule for this claim to carry",
    )
  }

  /**
   * MAINE IS WHY THE STANDING IS IN THE KEY: it publishes a Semifinalist row
   * AND a Finalist row, and a key without the standing would make the second
   * overwrite the first.
   */
  @Test
  fun `one school may publish a row per standing`() {
    NationalMeritFixture.seedAward(
      session,
      NationalMeritFixture.award(college, qualifyingStanding = QualifyingStanding.FINALIST),
    )
    NationalMeritFixture.seedAward(
      session,
      NationalMeritFixture.award(
        college,
        qualifyingStanding = QualifyingStanding.SEMIFINALIST,
        awardPerYearUsd = 5_000,
        awardTotalUsd = 20_000,
        awardAsPublished = "Semifinalists receive tuition.",
      ),
    )

    val standings =
      NationalMeritAwardsDao
        .listByColleges(session, listOf(college))
        .getOrThrow()
        .map { it.paying().standing }

    assertEquals(listOf(QualifyingStanding.FINALIST, QualifyingStanding.SEMIFINALIST), standings)
  }

  /**
   * AUBURN IS WHY RESIDENCY IS IN THE KEY: it cut a $44,000 award to Alabama
   * residents only. Both rows are stored and BOTH are returned, because the
   * service scopes the answer from the money profile -- a DAO that dropped the
   * other residency's row could not tell a non-resident "this award exists and
   * is not yours" apart from "this school publishes nothing".
   */
  @Test
  fun `a school may publish one award in state and another out of state, and both are returned`() {
    NationalMeritFixture.seedAward(
      session,
      NationalMeritFixture.award(college, residencyBasis = ResidencyBasis.IN_STATE, awardPerYearUsd = 11_000),
    )
    NationalMeritFixture.seedAward(
      session,
      NationalMeritFixture.award(
        college,
        residencyBasis = ResidencyBasis.OUT_OF_STATE,
        awardPerYearUsd = 2_000,
        awardTotalUsd = 8_000,
        awardAsPublished = "Non-residents receive a smaller award.",
      ),
    )

    val rows = NationalMeritAwardsDao.listByColleges(session, listOf(college)).getOrThrow()

    assertEquals(
      listOf(ResidencyBasis.IN_STATE to 11_000, ResidencyBasis.OUT_OF_STATE to 2_000),
      rows.map { it.residencyBasis to it.paying().perYearUsd },
    )
  }

  @Test
  fun `listByColleges answers only the schools it was asked about, and nothing for an empty list`() {
    NationalMeritFixture.seedAward(session, NationalMeritFixture.award(college))
    NationalMeritFixture.seedAward(session, NationalMeritFixture.award(otherCollege))

    assertEquals(
      listOf(college),
      NationalMeritAwardsDao.listByColleges(session, listOf(college)).getOrThrow().map { it.collegeId },
    )
    assertEquals(
      emptyList<NationalMeritAward>(),
      NationalMeritAwardsDao.listByColleges(session, emptyList()).getOrThrow(),
      "a query for nothing has nothing to answer, and `IN ()` is not valid SQL",
    )
  }

  /**
   * A re-ingest of an UNCHANGED corpus writes nothing and does not advance
   * `updated_at` -- so a run summary that says "nothing changed" is a
   * measurement rather than a guess.
   */
  @Test
  fun `a re-ingest of an unchanged row writes nothing, and a corrected one changes it in place`() {
    val first = NationalMeritFixture.seedAward(session, NationalMeritFixture.award(college))
    val writtenAt = updatedAtOf(first.id)

    val again = NationalMeritFixture.seedAward(session, NationalMeritFixture.award(college))
    assertEquals(UpsertOutcome.UNCHANGED, again.outcome)
    assertEquals(first.id, again.id, "the id comes back whatever the outcome")
    assertEquals(writtenAt, updatedAtOf(first.id), "an unchanged re-ingest does not advance updated_at")

    val corrected =
      NationalMeritFixture.seedAward(
        session,
        NationalMeritFixture.award(college, awardPerYearUsd = 21_000),
      )
    assertEquals(UpsertOutcome.CHANGED, corrected.outcome)
    assertEquals(first.id, corrected.id)
    assertEquals(1, countAwards())
    assertEquals(
      21_000,
      NationalMeritAwardsDao
        .listByColleges(session, listOf(college))
        .getOrThrow()
        .single()
        .paying()
        .perYearUsd,
    )
  }

  @Test
  fun `deleteExcept prunes the rows a re-ingest did not write`() {
    val kept = NationalMeritFixture.seedAward(session, NationalMeritFixture.award(college))
    NationalMeritFixture.seedAward(session, NationalMeritFixture.award(otherCollege))

    val deleted = NationalMeritAwardsDao.deleteExcept(session, listOf(kept.id)).getOrThrow()

    assertEquals(1, deleted)
    assertEquals(
      listOf(kept.id),
      NationalMeritAwardsDao
        .listByColleges(session, listOf(college, otherCollege))
        .getOrThrow()
        .map { it.id },
    )
  }

  /**
   * AN EMPTY CORPUS IS A DEFECT TO BE NAMED, NOT A DELETE-ALL, and the refusal
   * is a TYPE rather than a sentence: the ingest loader must tell this from any
   * other write failure to report its own located defect.
   */
  @Test
  fun `a prune with nothing kept is refused by name, and deletes nothing`() {
    NationalMeritFixture.seedAward(session, NationalMeritFixture.award(college))

    val failure = NationalMeritAwardsDao.deleteExcept(session, emptyList()).exceptionOrNull()

    assertTrue(
      failure is EmptyNationalMeritPruneException,
      "a caller must route on the type, never on the message: got [$failure]",
    )
    assertEquals(1, countAwards(), "a refused prune deletes nothing")
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun seedCollege(
    ipedsUnitId: Int,
    name: String,
  ): CollegeId =
    CollegesDao
      .upsert(session, newCollegeFixture(ipedsUnitId, name))
      .getOrThrow()
      .id

  /**
   * Writes one row of this suite's table from literal SQL and answers with the
   * constraint PostgreSQL refused it with ([violatedConstraintOf]).
   *
   * IT GOES AROUND THE MODELS ON PURPOSE. [NationalMeritClaim]'s arms,
   * [FirstChoiceRule]'s arms and the read model's expiry `require` make these
   * rows unconstructable in Kotlin, which is the point of them -- and it is
   * exactly why the CHECKs still have to be EXECUTED: the database is what a
   * migration, a generated seed and `psql` all write through, and none of those
   * typechecks.
   */
  private fun violatedConstraint(columns: Map<String, String>): String? =
    violatedConstraintOf(connection, "college_national_merit_awards", columns)

  /**
   * The paying claim on a read row, for the cases about a cell only a paying
   * row has.
   *
   * It ASSERTS the arm rather than casting: a row seeded as paying and read
   * back as an absence is a mapper defect, and this is the case that would see
   * it first.
   */
  private fun NationalMeritAward.paying(): NationalMeritClaim.Paying = assertIs(claim)

  /**
   * One reconstructed award, for the one runtime refusal the read model still
   * carries.
   *
   * It takes only what that case varies, because the crossed claims it used to
   * build are states [NationalMeritClaim] cannot express.
   */
  private fun readAward(expiresOn: LocalDate): NationalMeritAward =
    NationalMeritAward(
      id = NationalMeritAwardId(UUID.randomUUID()),
      collegeId = college,
      entryYear = AcademicYear(NationalMeritFixture.DEFAULT_ENTRY_YEAR),
      residencyBasis = ResidencyBasis.NOT_APPLICABLE,
      claim =
        NationalMeritClaim.Paying(
          guarantee = AwardGuarantee.AUTOMATIC,
          standing = QualifyingStanding.FINALIST,
          asPublished = "Finalists receive tuition, housing and a stipend.",
          awardName = NationalMeritFixture.DEFAULT_AWARD_NAME,
          perYearUsd = null,
          totalUsd = null,
          years = null,
          firstChoice = null,
          applyDeadline = null,
        ),
      publisher = NationalMeritFixture.DEFAULT_PUBLISHER,
      sourceUrl = NationalMeritFixture.DEFAULT_AWARD_SOURCE_URL,
      archiveUrl = null,
      checkedOn = LocalDate.of(2026, 9, 25),
      expiresOn = expiresOn,
    )

  private fun updatedAtOf(id: NationalMeritAwardId): OffsetDateTime =
    connection.prepareStatement("SELECT updated_at FROM college_national_merit_awards WHERE id = ?").use { stmt ->
      stmt.setObject(1, id.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getObject("updated_at", OffsetDateTime::class.java)
      }
    }

  private fun countAwards(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM college_national_merit_awards").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
