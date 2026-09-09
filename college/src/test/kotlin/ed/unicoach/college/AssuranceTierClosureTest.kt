package ed.unicoach.college

import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.MoneySource
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Totality of [AssuranceTier] over the key space the loaders can actually write
 * (RFC 179), and the falsifiable pin on the Scorecard half of it.
 *
 * Here rather than in `:db` because the vocabularies that DEFINE the key space
 * live here: [SfaVariables], [IpedsChargeVocabulary] and
 * [ScorecardInstitutionColumns] are this module's, and every set below is
 * DERIVED from them rather than re-typed. A test that re-typed the 154 strings
 * would pass while the loader wrote a 155th.
 *
 * `source_variable` is an open `TEXT` column on all four fact tables -- no
 * domain CHECK anywhere -- so nothing in the schema or the compiler makes these
 * walks unnecessary.
 */
class AssuranceTierClosureTest {
  /**
   * The repo's committed Scorecard seed directory, resolved by walking up from
   * the test's working directory rather than assuming a fixed depth -- the walk
   * `CdsSeedLoaderTest` already uses for the CDS seed.
   */
  private val committedScorecardDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/scorecard") }
      .first { it.isDirectory }

  /**
   * The Scorecard strings the canonical fill can write, READ OFF THE LOADER'S
   * OWN REGISTRY rather than re-typed here.
   *
   * This test used to hand-list them, which made the closure it asserts a
   * coincidence: a thirteenth column added to `mapScorecardPrices` or
   * `mapScorecardStats` would have left every assertion below green and then
   * thrown at read time, because the list this compared the tier map against was
   * not the set the loader writes. It is now the same object the fill is bound
   * to ([ScorecardInstitutionColumns.loadedVariableOf]), so "every source_variable
   * the loader can write resolves to exactly one tier" holds by construction.
   */
  private val scorecardVariables: Set<String> = ScorecardInstitutionColumns.LOADED_VARIABLES

  @Test
  fun `every IPEDS SFA variable the loader stages resolves, and to the compelled-survey tier`() {
    val variables = SfaVariables.ALL.toSet()
    assertEquals(82, variables.size, "the SFA staging vocabulary is 82 names; a change here moves the key space")
    for (variable in variables) {
      assertEquals(
        AssuranceTier.MANDATORY_SURVEY,
        AssuranceTier.of(MoneySource.IPEDS_SFA, variable, ROW),
        "SFA variable [$variable]",
      )
    }
  }

  @Test
  fun `every IC_AY charge variable the loader can build resolves, and to the compelled-survey tier`() {
    // Stem x suffix, exactly as `CanonicalMoneyLoader` builds the string. The
    // suffix is a POSITION in the file's four-year window and not a year, so
    // bumping `SURVEY_YEAR` does not move this set.
    val variables =
      IpedsChargeVocabulary.CELLS.keys
        .flatMap { stem -> IpedsChargeVocabulary.SUFFIX_BY_ACADEMIC_YEAR.values.map { suffix -> "$stem$suffix" } }
        .toSet()
    assertEquals(48, variables.size, "twelve stems over a four-year window")
    for (variable in variables) {
      assertEquals(
        AssuranceTier.MANDATORY_SURVEY,
        AssuranceTier.of(MoneySource.IPEDS_IC_AY, variable, ROW),
        "IC_AY variable [$variable]",
      )
    }
  }

  @Test
  fun `every Scorecard string the loader writes resolves, and the two administrative records are the two`() {
    assertEquals(24, scorecardVariables.size, "eight prices and sixteen cohort cells, as the registry expands them")
    assertEquals(
      scorecardVariables,
      AssuranceTier.SCORECARD_TIERS.keys,
      "the map answers for exactly the strings the loader writes -- no unread key, no unwritten one",
    )
    val byTier = scorecardVariables.groupBy { AssuranceTier.of(MoneySource.SCORECARD, it, ROW) }
    assertEquals(
      setOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10),
      byTier[AssuranceTier.ADMINISTRATIVE_RECORD]?.toSet(),
      "the federal loan file and the tax file, and nothing else the Scorecard relays",
    )
    assertEquals(22, byTier[AssuranceTier.MANDATORY_SURVEY]?.size, "the other 22 strings are re-published IPEDS")
    assertTrue(AssuranceTier.VOLUNTARY_SELF_REPORT !in byTier, "the Scorecard publishes nothing self-reported")
  }

  @Test
  fun `the tier map agrees with the committed data dictionary transcription, column by column`() {
    // The spec's own acceptance criterion, made falsifiable: if NCES re-sources
    // a column, the re-transcription disagrees with the map and this fails --
    // which is the whole point, because the tier is then wrong.
    val dictionary =
      CSVParser
        .parse(
          File(committedScorecardDir, DICTIONARY_FILE),
          Charsets.UTF_8,
          CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build(),
        ).use { records -> records.associate { it.get("variable_name").trim() to it.get("source").trim() } }

    assertEquals(scorecardVariables.size, dictionary.size, "one row per string the loader writes")
    assertEquals(AssuranceTier.SCORECARD_TIERS.keys, dictionary.keys, "the transcription and the map name one set")
    for ((variable, source) in dictionary) {
      // The publisher's own SOURCE column decides the tier: IPEDS means the
      // college filed a compelled survey and the Scorecard relayed it; NSLDS
      // and Treasury are federal files about students, kept for another purpose.
      val expected =
        when (source) {
          "IPEDS" -> AssuranceTier.MANDATORY_SURVEY
          "NSLDS", "Treasury" -> AssuranceTier.ADMINISTRATIVE_RECORD
          else -> error("the dictionary transcription states an unhandled SOURCE [$source] for [$variable]")
        }
      assertEquals(expected, AssuranceTier.of(MoneySource.SCORECARD, variable, ROW), "dictionary row [$variable]")
    }
    assertEquals(22, dictionary.values.count { it == "IPEDS" }, "sixteen IPEDS cells, published as 22 strings")
  }

  @Test
  fun `the transcription cannot be edited quietly -- its digest is the one recorded beside it`() {
    // Typed here as well as written into FETCH-NOTES.md, so an undocumented edit
    // of the transcription fails a test rather than moving a tier in silence.
    val file = File(committedScorecardDir, DICTIONARY_FILE)
    // The module's own hex primitive (`CsvIngestSupport`, `CollegeScorecardIngestTest`),
    // not a per-byte format string: byte-to-hex is an encoding, and the JDK ships
    // the tested one.
    val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    assertEquals(DICTIONARY_SHA256, digest, "file=[$DICTIONARY_FILE] was edited without updating FETCH-NOTES.md")
    assertTrue(
      DICTIONARY_SHA256 in File(committedScorecardDir, "FETCH-NOTES.md").readText(),
      "the hand-authored record in the same directory states the same digest",
    )
  }

  private companion object {
    /** The locator [AssuranceTier.of] requires: this walk resolves the whole key space, row-less. */
    const val ROW = "the assurance-tier closure walk (the loaders' key space)"

    const val DICTIONARY_FILE = "dictionary-variable-sources.csv"

    /** The sha256 recorded in `db/seed/scorecard/FETCH-NOTES.md` for the committed transcription. */
    const val DICTIONARY_SHA256 = "fbabaaa33ed47383c82f999277183c0d8c8c9479eec0572e67e81ec6a9022a2e"
  }
}
