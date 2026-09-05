package ed.unicoach.vocabulary

import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.UsState
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.JurisdictionKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The served vocabularies (RFC 165). Two things are held here: the SHAPE every
 * registered vocabulary must have — asserted by iterating the registry, so a
 * vocabulary added later is covered the day it is registered — and the
 * AGREEMENT between what is served and what the write paths accept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyServiceTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      // The territory rows and their region go back out: another suite's probe
      // depends on region 9 being absent (see CodebookReferenceFixture).
      if (::connection.isInitialized && !connection.isClosed) {
        CodebookReferenceFixture.removeOtherJurisdictions(connection)
        connection.close()
      }
      if (::database.isInitialized) database.close()
    }
  }

  @BeforeEach
  fun seedJurisdictions() {
    connection.autoCommit = true
    // All 59, not the fixture's usual 51: the residency vocabulary serves the
    // validator's whole set and FAILS on a code with no row.
    CodebookReferenceFixture.seed(connection)
    CodebookReferenceFixture.seedOtherJurisdictions(connection)
  }

  private val service by lazy { VocabularyService(database) }

  /** Every two-letter string the money-profile writer accepts — derived from the parser, not from its set. */
  private val acceptedResidencyCodes: Set<String> =
    ('A'..'Z')
      .flatMap { first -> ('A'..'Z').map { second -> "$first$second" } }
      .mapNotNull { MoneyProfileService.parseResidencyState(it) }
      .toSet()

  @Test
  fun `every registered vocabulary is non-empty, uniquely valued, labelled and snake_case named`() =
    runTest {
      val published = service.publishedVocabularies().getOrThrow()
      assertTrue(published.vocabularies.isNotEmpty(), "the registry publishes at least one vocabulary")
      for (vocabulary in published.vocabularies) {
        assertTrue(
          vocabulary.name.matches(Regex("[a-z][a-z0-9_]*")),
          "vocabulary name [${vocabulary.name}] must be snake_case",
        )
        assertTrue(vocabulary.entries.isNotEmpty(), "[${vocabulary.name}] must have entries")
        val values = vocabulary.entries.map { it.value }
        assertEquals(values.size, values.toSet().size, "[${vocabulary.name}] must not repeat a value")
        for (entry in vocabulary.entries) {
          assertFalse(entry.value.isBlank(), "[${vocabulary.name}] has a blank value")
          assertFalse(entry.label.isBlank(), "[${vocabulary.name}] entry [${entry.value}] has a blank label")
          assertFalse(
            entry.extras.any { it.key.isBlank() || it.value.isBlank() },
            "[${vocabulary.name}] entry [${entry.value}] has a blank extra",
          )
        }
      }
      assertEquals(
        published.vocabularies.map { it.name },
        published.vocabularies.map { it.name }.distinct(),
        "each vocabulary is published under its own name",
      )
      // Declared order is the contract, not an accident of the read.
      assertEquals(
        published.vocabularies.map { vocabulary -> vocabulary.name to vocabulary.entries.map { it.value } },
        service
          .publishedVocabularies()
          .getOrThrow()
          .vocabularies
          .map { v -> v.name to v.entries.map { it.value } },
        "a second read publishes the same vocabularies, in the same order",
      )
    }

  @Test
  fun `the served income bands are IncomeBand's own values and words, in declaration order`() =
    runTest {
      val entries = vocabularyNamed(VocabularyService.INCOME_BANDS).entries
      assertEquals(
        IncomeBand.entries.map { it.value to it.bracket },
        entries.map { it.value to it.label },
        "the picker's words must be the enum's words, in the enum's order",
      )
      assertTrue(entries.all { it.extras.isEmpty() }, "income bands declare no extra keys")
    }

  @Test
  fun `the served residency set is exactly the set parseResidencyState accepts`() =
    runTest {
      val served = vocabularyNamed(VocabularyService.RESIDENCY_STATES).entries.map { it.value }
      // Direction 1: nothing is served that the writer would refuse.
      for (value in served) {
        assertEquals(value, MoneyProfileService.parseResidencyState(value), "served value [$value] must be accepted")
      }
      // Direction 2: nothing the writer accepts is missing from the picker.
      assertEquals(
        acceptedResidencyCodes,
        served.toSet(),
        "the served vocabulary and the accepted set must be the same set",
      )
      assertEquals(59, served.size, "the closed residency set is 59 jurisdictions")
    }

  @Test
  fun `every served residency entry carries a name and a jurisdiction kind, in name order`() =
    runTest {
      val entries = vocabularyNamed(VocabularyService.RESIDENCY_STATES).entries
      for (entry in entries) {
        assertFalse(entry.label.isBlank(), "[${entry.value}] must carry the state's name")
        val kind = entry.extras[VocabularyService.JURISDICTION_KIND]
        assertNotNull(kind, "[${entry.value}] must say what kind of jurisdiction it is")
        assertNotNull(JurisdictionKind.fromValue(kind), "[${entry.value}] kind [$kind] must be a known kind")
      }
      // Name order, asserted at the ends rather than by re-sorting in Kotlin:
      // the DB's collation orders the middle ("Virgin Islands" vs "Virginia"),
      // and a Java-side sort is a second, differently-behaved sorter.
      assertEquals("Alabama", entries.first().label, "the list opens on the first state by name")
      assertEquals("Wyoming", entries.last().label, "the list closes on the last state by name")
      assertNotEquals(
        entries.map { it.value }.sorted(),
        entries.map { it.value },
        "display order is name order, not postal-code order",
      )
      // The classification is the point: a UI that says "state" about Palau is wrong.
      assertEquals(
        "freely-associated-state",
        entries.single { it.value == "PW" }.extras[VocabularyService.JURISDICTION_KIND],
      )
      assertEquals("Palau", entries.single { it.value == "PW" }.label)
    }

  @Test
  fun `an accepted code with no us_states row fails the read rather than shrinking the list`() {
    val rows =
      listOf(
        UsState("CA", "California", JurisdictionKind.STATE),
        UsState("NY", "New York", JurisdictionKind.STATE),
      )
    val complete = VocabularyService.residencyStates(setOf("CA", "NY"), rows)
    assertEquals(2, complete.getOrThrow().entries.size, "every accepted code has a row here")

    val missing = VocabularyService.residencyStates(setOf("CA", "NY", "TX"), rows)
    assertTrue(missing.isFailure, "a code with no us_states row must fail the read")
    assertEquals(
      listOf("TX"),
      assertFailsWith<UnlabelledResidencyCodesException> { missing.getOrThrow() }.missingCodes,
      "the failure carries the offending codes as data",
    )
  }

  @Test
  fun `a us_states row outside the accepted set is not served`() {
    val vocabulary =
      VocabularyService
        .residencyStates(
          setOf("CA"),
          listOf(
            UsState("CA", "California", JurisdictionKind.STATE),
            UsState("ZZ", "Not A Jurisdiction", JurisdictionKind.TERRITORY),
          ),
        ).getOrThrow()
    assertEquals(listOf("CA"), vocabulary.entries.map { it.value }, "the served set is the validator's, not the table's")
    assertNull(MoneyProfileService.parseResidencyState("ZZ"), "the probe code is genuinely not accepted")
  }

  @Test
  fun `version is stable for the same content and changes when any of it changes`() {
    val bands = VocabularyService.incomeBands()
    val baseline = VocabularyService.contentVersion(listOf(bands))
    assertEquals(baseline, VocabularyService.contentVersion(listOf(VocabularyService.incomeBands())))

    val relabelled =
      bands.copy(entries = bands.entries.mapIndexed { i, e -> if (i == 0) e.copy(label = "${e.label} or so") else e })
    assertNotEquals(baseline, VocabularyService.contentVersion(listOf(relabelled)), "a changed label changes the version")

    val reordered = bands.copy(entries = bands.entries.reversed())
    assertNotEquals(baseline, VocabularyService.contentVersion(listOf(reordered)), "display order is part of the content")

    val extraAdded =
      bands.copy(entries = bands.entries.mapIndexed { i, e -> if (i == 0) e.copy(extras = mapOf("k" to "v")) else e })
    assertNotEquals(baseline, VocabularyService.contentVersion(listOf(extraAdded)), "an added extra changes the version")

    assertNotEquals(
      baseline,
      VocabularyService.contentVersion(listOf(bands.copy(name = "other_name"))),
      "the vocabulary's name is part of the content",
    )
  }

  @Test
  fun `the published version is the version of the published content`() =
    runTest {
      val published = service.publishedVocabularies().getOrThrow()
      assertEquals(VocabularyService.contentVersion(published.vocabularies), published.version)
      assertEquals(16, published.version.length, "the hash is a short, readable string")
      assertEquals(published.version, service.publishedVocabularies().getOrThrow().version, "unchanged content, unchanged version")
    }

  @Test
  fun `two documents differing only in where a delimiter-like character falls hash differently`() {
    // The canonical form is length-prefixed, so a character that looks like
    // framing cannot move a field boundary. Under a delimiter-only framing
    // these two documents produce the same bytes and the same version.
    fun version(
      value: String,
      label: String,
    ): String =
      VocabularyService.contentVersion(
        listOf(Vocabulary(name = VocabularyService.INCOME_BANDS, entries = listOf(VocabularyEntry(value, label)))),
      )
    assertNotEquals(
      version("A", "X:Y"),
      version("A:X", "Y"),
      "the length prefix separator inside a label may not forge a field boundary",
    )
    assertNotEquals(
      version("A", "X\u0002Y"),
      version("A\u0002X", "Y"),
      "a control character inside a label may not forge a field boundary",
    )
    assertNotEquals(
      version("A", "2:BC"),
      version("A", "2:B"),
      "a label that looks like its own framing still hashes as its own content",
    )
    val split =
      VocabularyService.contentVersion(
        listOf(
          Vocabulary(
            name = VocabularyService.INCOME_BANDS,
            entries = listOf(VocabularyEntry("A", "X"), VocabularyEntry("Y", "Z")),
          ),
        ),
      )
    val joined =
      VocabularyService.contentVersion(
        listOf(
          Vocabulary(
            name = VocabularyService.INCOME_BANDS,
            entries = listOf(VocabularyEntry("A", "X1:Y1:Z")),
          ),
        ),
      )
    assertNotEquals(split, joined, "entry boundaries are part of the content, not text a label can restate")
  }

  @Test
  fun `an entry may not carry a blank value or a blank label`() {
    val blankValue = assertFailsWith<IllegalArgumentException> { VocabularyEntry("", "California") }
    assertTrue(blankValue.message!!.contains("blank value"), "got [${blankValue.message}]")
    val blankLabel = assertFailsWith<IllegalArgumentException> { VocabularyEntry("CA", " ") }
    assertTrue(
      blankLabel.message!!.contains("CA") && blankLabel.message!!.contains("blank label"),
      "the refusal must name the offending entry, got [${blankLabel.message}]",
    )
  }

  @Test
  fun `an extra may not shadow the universal keys or be blank`() {
    for (reserved in listOf("value", "label")) {
      val refused =
        assertFailsWith<IllegalArgumentException> {
          VocabularyEntry("CA", "California", mapOf(reserved to "x"))
        }
      assertTrue(
        refused.message!!.contains(reserved),
        "the refusal must name the offending key, got [${refused.message}]",
      )
    }
    val blank = assertFailsWith<IllegalArgumentException> { VocabularyEntry("CA", "California", mapOf(" " to "x")) }
    assertTrue(blank.message!!.contains("blank"), "a blank extra key is refused, got [${blank.message}]")
    val blankValue =
      assertFailsWith<IllegalArgumentException> {
        VocabularyEntry("CA", "California", mapOf(VocabularyService.JURISDICTION_KIND to ""))
      }
    assertTrue(
      blankValue.message!!.contains("blank") && blankValue.message!!.contains(VocabularyService.JURISDICTION_KIND),
      "a blank extra value is refused and names its key, got [${blankValue.message}]",
    )
    // The one extra actually served is unaffected.
    assertEquals(
      mapOf(VocabularyService.JURISDICTION_KIND to "state"),
      VocabularyEntry("CA", "California", mapOf(VocabularyService.JURISDICTION_KIND to "state")).extras,
    )
  }

  @Test
  fun `two vocabularies may not be published under one name`() {
    val bands = VocabularyService.incomeBands()
    assertEquals(1, VocabularyService.documentOf(listOf(bands)).vocabularies.size)
    val refused =
      assertFailsWith<IllegalArgumentException> {
        VocabularyService.documentOf(listOf(bands, bands.copy(entries = bands.entries.take(1))))
      }
    assertTrue(
      refused.message!!.contains(VocabularyService.INCOME_BANDS),
      "the refusal must name the duplicate, got [${refused.message}]",
    )
  }

  private suspend fun vocabularyNamed(name: String): Vocabulary =
    service
      .publishedVocabularies()
      .getOrThrow()
      .vocabularies
      .single { it.name == name }
}
