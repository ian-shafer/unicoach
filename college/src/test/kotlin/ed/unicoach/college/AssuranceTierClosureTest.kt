package ed.unicoach.college

import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.MoneySource
import org.junit.jupiter.api.Test
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
   * The outcome columns whose date we cite, read off the same registry as the
   * money strings: the transcription and the tier map answer for the UNION of
   * the two registries, never for the money fill alone. The registry owns the
   * set's SIZE -- a count restated here was a count of another file's
   * membership, and it said "three" of a seven-member set.
   */
  private val outcomeVariables: Set<String> = ScorecardInstitutionColumns.OUTCOME_VARIABLES

  /**
   * The whole cited Scorecard key space: money plus outcome, READ off the
   * registry that owns both halves rather than re-joined here.
   */
  private val datedScorecardVariables: Set<String> = ScorecardInstitutionColumns.CITED_VARIABLES

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
      setOf(
        ScorecardInstitutionColumns.ADM_RATE,
        ScorecardInstitutionColumns.C150_4,
        ScorecardInstitutionColumns.UGDS,
        ScorecardInstitutionColumns.OMAWDP8_ALL,
        ScorecardInstitutionColumns.OMAWDP8_PELL_ALL,
        ScorecardInstitutionColumns.OMAWDP8_NOPELL_ALL,
        ScorecardInstitutionColumns.TRANS_4,
      ),
      outcomeVariables,
      "the three outcome columns RFC 226 dates, plus the four RFC 242 adds",
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
    // assertion that caught it, instead of leaving a reader to diff a 31-row
    // registry against a count that went up by one.
    assertEquals(
      datedScorecardVariables - setOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10),
      byTier[AssuranceTier.MANDATORY_SURVEY]?.toSet(),
      "every dated string but the federal loan file and the tax file is the Scorecard re-publishing IPEDS",
    )
    assertTrue(AssuranceTier.VOLUNTARY_SELF_REPORT !in byTier, "the Scorecard publishes nothing self-reported")
  }

  @Test
  fun `the tier map agrees with the fetched data dictionary's SOURCE, column by column`() {
    // RFC 179 D4's evidence, read from the publisher's own sheet as fetched
    // (RFC 274) -- `ScorecardDictionaryTest` holds that file to the digest the
    // fetch recorded, so it cannot be re-sourced by hand.
    val dictionary = SeedScorecardDictionary.dictionary
    for (variable in AssuranceTier.SCORECARD_TIERS.keys) {
      val source = dictionary.sourceOf(variable)
      val expected =
        when (source) {
          "IPEDS" -> AssuranceTier.MANDATORY_SURVEY
          "NSLDS", "Treasury" -> AssuranceTier.ADMINISTRATIVE_RECORD
          else -> error("the data dictionary states an unhandled SOURCE [$source] for [$variable]")
        }
      assertEquals(expected, AssuranceTier.of(MoneySource.SCORECARD, variable, ROW), "dictionary row [$variable]")
    }
    assertEquals(
      datedScorecardVariables - setOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10),
      datedScorecardVariables.filter { dictionary.sourceOf(it) == "IPEDS" }.toSet(),
      "every cited string but the federal loan file and the tax file is sourced to IPEDS by the publisher",
    )
  }

  private companion object {
    /** The locator [AssuranceTier.of] requires: this walk resolves the whole key space, row-less. */
    const val ROW = "the assurance-tier closure walk (the loaders' key space)"
  }
}
