package ed.unicoach.college

import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.MoneySource
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

  /**
   * The three outcome columns RFC 226 dates, read off the same registry as the
   * money strings: the transcription and the tier map now answer for the UNION
   * of the two registries, never for the money fill alone.
   */
  private val outcomeVariables: Set<String> = ScorecardInstitutionColumns.OUTCOME_VARIABLES

  /**
   * The whole dated Scorecard key space: money plus outcome, READ off the
   * registry that owns both halves rather than re-joined here. The dictionary
   * pin reads the same member, so the CSV's key-set contract has one home.
   */
  private val datedScorecardVariables: Set<String> = ScorecardInstitutionColumns.DATED_VARIABLES

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
    // Stem x window year, spelled by the SAME function `CanonicalMoneyLoader`
    // spells a staged row's `source_variable` with -- not a second copy of the
    // rule, which would let this walk stay green over strings the loader no
    // longer writes. The suffix is a POSITION in the file's four-year window
    // and not a year, so bumping `SURVEY_YEAR` does not move this set.
    val variables =
      IpedsChargeVocabulary.CELLS.keys
        .flatMap { stem ->
          IpedsChargeVocabulary.SUFFIX_BY_ACADEMIC_YEAR.keys.map { year ->
            IpedsChargeVocabulary.sourceVariableOf(stem, year)
          }
        }.toSet()
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
      setOf(ScorecardInstitutionColumns.ADM_RATE, ScorecardInstitutionColumns.C150_4, ScorecardInstitutionColumns.UGDS),
      outcomeVariables,
      "the three outcome columns RFC 226 dates",
    )
    assertEquals(
      datedScorecardVariables,
      AssuranceTier.SCORECARD_TIERS.keys,
      "the map answers for exactly the strings we date -- no unread key, no unwritten one",
    )
    val byTier = datedScorecardVariables.groupBy { AssuranceTier.of(MoneySource.SCORECARD, it, ROW) }
    assertEquals(
      setOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10),
      byTier[AssuranceTier.ADMINISTRATIVE_RECORD]?.toSet(),
      "the federal loan file and the tax file, and nothing else the Scorecard relays",
    )
    // The SET, not its size: a string that moves tier is then printed by the
    // assertion that caught it, instead of leaving a reader to diff a 27-row
    // registry against a count that went up by one.
    assertEquals(
      datedScorecardVariables - setOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10),
      byTier[AssuranceTier.MANDATORY_SURVEY]?.toSet(),
      "every dated string but the federal loan file and the tax file is the Scorecard re-publishing IPEDS",
    )
    assertTrue(AssuranceTier.VOLUNTARY_SELF_REPORT !in byTier, "the Scorecard publishes nothing self-reported")
  }

  @Test
  fun `the tier map agrees with the committed data dictionary transcription, column by column`() {
    // The spec's own acceptance criterion, made falsifiable: if NCES re-sources
    // a column, the re-transcription disagrees with the map and this fails --
    // which is the whole point, because the tier is then wrong.
    val dictionary = ScorecardDictionaryTranscription.sourceByVariable

    assertEquals(datedScorecardVariables.size, dictionary.size, "one row per string we date")
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
    // STRINGS, not cells, and the two are different numbers: the nineteen IPEDS
    // cells `AssuranceTier` counts are the sixteen money cells (published as 22
    // strings, because `NPT4` and its five bands are each published twice on
    // `CONTROL`) plus the three outcome cells, which are one string each.
    assertEquals(
      datedScorecardVariables - setOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10),
      dictionary.filterValues { it == "IPEDS" }.keys,
      "22 money strings for sixteen money cells, plus 3 outcome cells: the nineteen IPEDS cells as strings",
    )
  }

  @Test
  fun `the transcription cannot be edited quietly -- its digest is the one recorded beside it`() {
    // Typed here as well as written into FETCH-NOTES.md, so an undocumented edit
    // of the transcription fails a test rather than moving a tier in silence.
    val file = ScorecardDictionaryTranscription.file
    // The module's own hex primitive (`CsvIngestSupport`, `CollegeScorecardIngestTest`),
    // not a per-byte format string: byte-to-hex is an encoding, and the JDK ships
    // the tested one.
    val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    assertEquals(
      DICTIONARY_SHA256,
      digest,
      "file=[${ScorecardDictionaryTranscription.FILE_NAME}] was edited without updating FETCH-NOTES.md",
    )
    assertTrue(
      DICTIONARY_SHA256 in File(ScorecardDictionaryTranscription.directory, "FETCH-NOTES.md").readText(),
      "the hand-authored record in the same directory states the same digest",
    )
  }

  private companion object {
    /** The locator [AssuranceTier.of] requires: this walk resolves the whole key space, row-less. */
    const val ROW = "the assurance-tier closure walk (the loaders' key space)"

    /** The sha256 recorded in `db/seed/scorecard/FETCH-NOTES.md` for the committed transcription. */
    const val DICTIONARY_SHA256 = "2ccc060e06daa912eace96323c4faa54667542fa839c7251208537557675527a"
  }
}
