package ed.unicoach.college

import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.MoneyVocabularyFixture
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [MoneyVocabularyLoader] (RFC 158, D6): the SubjectLoader parse discipline
 * over five sections, the both-ways enum agreement (fatal), and the
 * upsert/delete-not-in load. Plus the P10 pins: income-band slugs and labels
 * agree with [IncomeBand] and `money_profiles`' CHECK list, the living
 * arrangements agree with [LivingArrangement], and `value_bearing` agrees
 * with the literal status lists inside the fact tables' CHECKs.
 */
class MoneyVocabularyLoaderTest : CollegeScorecardTestBase() {
  // First-load INSERT counts are asserted below; a pre-seeded vocabulary
  // would turn them into UNCHANGED (the CodebookLoaderTest override reason).
  override val seedsMoneyVocabulary: Boolean get() = false

  private val loader = MoneyVocabularyLoader(database)

  private fun parse(file: File): MoneyVocabularyLoader.ParsedVocabulary = runBlocking { loader.parse(file) }

  // The fixture already resolves the committed seed (one repo-root walk-up
  // for this file, not a re-typed copy per suite).
  private val committedFile: File get() = MoneyVocabularyFixture.COMMITTED_FILE

  private fun tempFile(content: String): File =
    File.createTempFile("money-vocabulary", ".json").apply {
      deleteOnExit()
      writeText(content)
    }

  /** The committed file with one JSON-level edit applied, for the refusal tests. */
  private fun editedCommitted(edit: (String) -> String): File = tempFile(edit(committedFile.readText()))

  // ---------------------------------------------------------------------------
  // The committed file
  // ---------------------------------------------------------------------------

  @Test
  fun `the committed vocabulary parses and byte-agrees with every enum`() {
    val parsed = parse(committedFile)
    assertEquals(ResidencyBasis.entries.map { it.value }.toSet(), parsed.residencyBases.map { it.slug }.toSet())
    assertEquals(FigureArrangement.entries.map { it.value }.toSet(), parsed.arrangements.map { it.slug }.toSet())
    assertEquals(FigureStatus.entries.map { it.value }.toSet(), parsed.figureStatuses.map { it.slug }.toSet())
    assertEquals(PriceConcept.entries.map { it.value }.toSet(), parsed.priceConcepts.map { it.slug }.toSet())
    assertEquals(IncomeBand.entries.map { it.value }, parsed.incomeBands.sortedBy { it.sortOrder }.map { it.slug })
  }

  @Test
  fun `the income-band labels byte-agree with IncomeBand's bracket text (P10)`() {
    val parsed = parse(committedFile)
    for (band in parsed.incomeBands) {
      assertEquals(IncomeBand.fromValue(band.slug)!!.bracket, band.bracketLabel, "[${band.slug}]")
    }
  }

  @Test
  fun `the living arrangements byte-agree with LivingArrangement`() {
    val parsed = parse(committedFile)
    assertEquals(
      LivingArrangement.entries.map { it.value }.toSet(),
      parsed.arrangements
        .filter { it.isLivingArrangement }
        .map { it.slug }
        .toSet(),
      "the living rows are exactly LivingArrangement's values; not_applicable is the one non-living row",
    )
  }

  // ---------------------------------------------------------------------------
  // Enum agreement is fatal, both ways
  // ---------------------------------------------------------------------------

  @Test
  fun `a seed row with no enum value is fatal`() {
    val file = editedCommitted { it.replace("\"in_district\"", "\"in_county\"") }
    val thrown = assertFailsWith<MoneyVocabularyLoader.InvalidFileException> { parse(file) }
    assertContains(thrown.message!!, "in_county")
    assertContains(thrown.message!!, "no enum value")
  }

  @Test
  fun `an enum value with no seed row is fatal`() {
    val file =
      editedCommitted { text ->
        // Drop the fees_only entry wholesale: PriceConcept.FEES_ONLY then has no
        // seed row, which is the OTHER direction of the agreement.
        val root =
          kotlinx.serialization.json.Json
            .parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val concepts = root.getValue("price_concepts") as kotlinx.serialization.json.JsonArray
        val pruned =
          kotlinx.serialization.json.JsonArray(
            concepts.filterNot {
              ((it as kotlinx.serialization.json.JsonObject).getValue("slug") as kotlinx.serialization.json.JsonPrimitive)
                .content == "fees_only"
            },
          )
        kotlinx.serialization.json
          .JsonObject(root.toMutableMap().apply { put("price_concepts", pruned) })
          .toString()
      }
    val thrown = assertFailsWith<MoneyVocabularyLoader.InvalidFileException> { parse(file) }
    assertContains(thrown.message!!, "fees_only")
    assertContains(thrown.message!!, "no seed row")
  }

  @Test
  fun `a value_bearing flag disagreeing with FigureStatus is fatal`() {
    val file =
      editedCommitted {
        it.replace(
          Regex("(\"slug\": \"suppressed_by_publisher\"[^}]*\"value_bearing\": )false"),
          "$1true",
        )
      }
    val thrown = assertFailsWith<MoneyVocabularyLoader.InvalidFileException> { parse(file) }
    assertContains(thrown.message!!, "suppressed_by_publisher")
    assertContains(thrown.message!!, "value_bearing")
  }

  @Test
  fun `a bracket_label disagreeing with IncomeBand's text is fatal (P10)`() {
    val file = editedCommitted { it.replace("$0 to $30,000", "$0 - $30,000") }
    val thrown = assertFailsWith<MoneyVocabularyLoader.InvalidFileException> { parse(file) }
    assertContains(thrown.message!!, "bracket_label")
  }

  // ---------------------------------------------------------------------------
  // File-shape refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `a surplus or missing top-level section is fatal`() {
    assertFailsWith<MoneyVocabularyLoader.InvalidFileException> {
      parse(editedCommitted { it.replaceFirst("\"residency_bases\"", "\"residency_basis\"") })
    }
  }

  @Test
  fun `a surplus or missing entry key is fatal, naming the section and entry`() {
    val thrown =
      assertFailsWith<MoneyVocabularyLoader.InvalidFileException> {
        parse(editedCommitted { it.replaceFirst("\"description\"", "\"descriptoin\"") })
      }
    assertContains(thrown.message!!, "section [residency_bases]")
    assertContains(thrown.message!!, "entry [0]")
  }

  @Test
  fun `a duplicate slug in a section is fatal`() {
    val thrown =
      assertFailsWith<MoneyVocabularyLoader.InvalidFileException> {
        parse(editedCommitted { it.replaceFirst("\"in_district\"", "\"in_state\"") })
      }
    assertContains(thrown.message!!, "duplicate slug")
  }

  @Test
  fun `malformed JSON and a non-object top level are fatal, naming the file`() {
    assertFailsWith<MoneyVocabularyLoader.InvalidFileException> { parse(tempFile("[")) }
    assertFailsWith<MoneyVocabularyLoader.InvalidFileException> { parse(tempFile("[]")) }
  }

  // ---------------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------------

  @Test
  fun `a first load inserts every row, and an unchanged re-load writes nothing`() {
    val parsed = parse(committedFile)
    val first = runBlocking { loader.load("money-vocabulary.json", parsed) }
    assertEquals(parsed.rows, first.rows)
    assertEquals(parsed.rows, first.inserted)
    assertEquals(0, first.deleted)

    val again = runBlocking { loader.load("money-vocabulary.json", parsed) }
    assertEquals(0, again.inserted)
    assertEquals(0, again.changed)
    assertEquals(parsed.rows, again.unchanged)
    assertEquals(0, again.deleted)
  }

  @Test
  fun `the loaded tables carry exactly the enum vocabularies`() {
    val parsed = parse(committedFile)
    runBlocking { loader.load("money-vocabulary.json", parsed) }
    withSession { session ->
      assertEquals(
        ResidencyBasis.entries.map { it.value }.sorted(),
        CanonicalMoneyDao.vocabularySlugs(session, "residency_bases").getOrThrow(),
      )
      assertEquals(
        FigureStatus.entries.map { it.value }.sorted(),
        CanonicalMoneyDao.vocabularySlugs(session, "figure_statuses").getOrThrow(),
      )
      assertEquals(
        IncomeBand.entries.map { it.value }.sorted(),
        CanonicalMoneyDao.vocabularySlugs(session, "income_bands").getOrThrow(),
      )
    }
  }

  @Test
  fun `retiring a slug still referenced by live fact rows completes in one run`() {
    // Run N left a vocabulary carrying one extra concept AND a live fact row
    // referencing it. Run N+1 loads the committed file, which no longer
    // carries the slug: the delete-not-in must not wedge on the FK (23503).
    // Both fact tables are wholesale-rebuilt by the canonical-money phase
    // later the same run (P12), so the loader clears them first and the
    // table follows the file in ONE run instead of failing forever.
    val parsed = parse(committedFile)
    runBlocking { loader.load("money-vocabulary.json", parsed) }
    runBlocking {
      CollegeScorecardLoader(database).load(
        fixture("scorecard-institutions-fixture.csv"),
        fixture("scorecard-fields-fixture.csv"),
      )
    }
    withSession { session ->
      session
        .prepareStatement(
          "INSERT INTO price_concepts (slug, description, arrangement_varies) " +
            "VALUES ('retired_concept', 'run N leftovers', FALSE)",
        ).use { it.execute() }
      session
        .prepareStatement(
          "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
            "academic_year, amount_usd, status, source, source_variable) " +
            "SELECT id, 'retired_concept', 'in_state', 'not_applicable', '2022-23', 12345, 'reported', " +
            "'scorecard', 'RETIRED' FROM colleges WHERE ipeds_unit_id = 110100",
        ).use { it.execute() }
    }

    val result = runBlocking { loader.load("money-vocabulary.json", parsed) }

    assertEquals(1, result.deleted, "the retired concept is deleted, not wedged behind the FK")
    withSession { session ->
      assertEquals(
        PriceConcept.entries.map { it.value }.sorted(),
        CanonicalMoneyDao.vocabularySlugs(session, "price_concepts").getOrThrow(),
      )
      assertEquals(0, count(session, "price_figures"), "the referencing fact rows were cleared for the same-run rebuild")
    }
  }

  // ---------------------------------------------------------------------------
  // The schema pins (the CHECK lists the enums must agree with)
  // ---------------------------------------------------------------------------

  @Test
  fun `value_bearing agrees with the literal status lists inside both value-iff-status CHECKs`() {
    // A CHECK cannot subquery figure_statuses, so the two value-bearing
    // statuses are restated literally inside each fact table's CHECK. This is
    // the test the migration promises: the flag column and both literal lists
    // name the same statuses.
    val valueBearing =
      FigureStatus.entries
        .filter { it.valueBearing }
        .map { it.value }
        .toSet()
    for (constraint in listOf("price_figures_value_iff_status_check", "cohort_money_stats_value_iff_status_check")) {
      val definition = constraintDefinition(constraint)
      val listed =
        FigureStatus.entries
          .map { it.value }
          .filter { definition.contains("'$it'") }
          .toSet()
      assertEquals(valueBearing, listed, "[$constraint] must name exactly the value-bearing statuses: $definition")
    }
  }

  @Test
  fun `the income-band slugs agree with money_profiles' CHECK list (P10)`() {
    // "Agree" is bidirectional, so this is set EQUALITY, not containment: a
    // stale extra slug in the CHECK -- the drift direction P10 exists to
    // catch -- must fail, exactly as the value_bearing test above does. The
    // seeded income_bands rows are the third party to the agreement.
    val parsed = parse(committedFile)
    runBlocking { loader.load("money-vocabulary.json", parsed) }
    val enumSlugs = IncomeBand.entries.map { it.value }.toSet()
    val definition = constraintDefinition("money_profiles_income_band_check")
    val listed =
      Regex("'([a-z0-9_]+)'")
        .findAll(definition)
        .map { it.groupValues[1] }
        .toSet()
    assertEquals(
      enumSlugs,
      listed,
      "money_profiles_income_band_check must name exactly IncomeBand's values: $definition",
    )
    val seeded = withSession { session -> CanonicalMoneyDao.vocabularySlugs(session, "income_bands").getOrThrow() }
    assertEquals(enumSlugs, seeded.toSet(), "the seeded income_bands slugs must equal IncomeBand's values")
  }

  @Test
  fun `the measure and scope enums agree with cohort_money_stats' CHECK lists`() {
    // "Agree" is bidirectional (the income-band pin's reason): a stale extra
    // slug in a CHECK -- a value stored rows may carry but no enum can read
    // -- must fail, not pass by containment.
    fun listed(constraint: String): Set<String> =
      Regex("'([a-z0-9_]+)'")
        .findAll(constraintDefinition(constraint))
        .map { it.groupValues[1] }
        .toSet()
    assertEquals(MoneyMeasure.entries.map { it.value }.toSet(), listed("cohort_money_stats_measure_check"))
    assertEquals(CohortPopulation.entries.map { it.value }.toSet(), listed("cohort_money_stats_population_check"))
    assertEquals(CohortResidencyScope.entries.map { it.value }.toSet(), listed("cohort_money_stats_residency_scope_check"))
    assertEquals(CohortAidScope.entries.map { it.value }.toSet(), listed("cohort_money_stats_aid_scope_check"))
  }

  private fun constraintDefinition(constraint: String): String =
    withSession { session ->
      session
        .prepareStatement("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")
        .use { stmt ->
          stmt.setString(1, constraint)
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "no constraint named [$constraint]")
            rs.getString(1)
          }
        }
    }
}
