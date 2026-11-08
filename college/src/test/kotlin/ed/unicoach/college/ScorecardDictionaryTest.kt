package ed.unicoach.college

import ed.unicoach.college.ScorecardDictionary.Cause
import ed.unicoach.college.ScorecardDictionary.Cohort
import ed.unicoach.common.util.AcademicYear
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader of the fetched Scorecard data dictionary (RFC 274), over the
 * COMMITTED artefacts and over hand-made files that break one rule each.
 *
 * A FILE-only suite: no database. Every year it asserts is READ from the
 * committed dictionary or derived from one read there.
 */
class ScorecardDictionaryTest {
  private val dictionary = SeedScorecardDictionary.dictionary

  @Test
  fun `every variable this corpus dates has a cell in the committed dictionary`() {
    val institution =
      ScorecardInstitutionColumns.DATED_MONEY_VARIABLES + ScorecardInstitutionColumns.OUTCOME_VARIABLES +
        ScorecardScoreBandColumns.VARIABLES
    for (variable in institution) {
      assertTrue(dictionary.institutionCellOf(variable).isNotEmpty(), "[$variable] has an empty cohort cell")
    }
    for (variable in ScorecardFieldOfStudyColumns.LOADED_VARIABLES) {
      assertTrue(dictionary.fieldOfStudyCellOf(variable).isNotEmpty(), "[$variable] has an empty cohort cell")
    }
    for (variable in ScorecardInstitutionColumns.CITED_VARIABLES) {
      assertTrue(dictionary.sourceOf(variable).isNotEmpty(), "[$variable] has no SOURCE")
    }
  }

  @Test
  fun `every dated money column reads as an academic year, the charges as one and the blend one year earlier`() {
    // The publisher's shape, CHECKED rather than assumed: the eight charges are
    // the price list for one year, and COSTT4_A with the net-price family
    // describes the cohort a year before it.
    val years = ScorecardInstitutionColumns.DATED_MONEY_VARIABLES.associateWith { dictionary.academicYearOf(it) }
    val charges =
      listOf(
        ScorecardInstitutionColumns.TUITIONFEE_IN,
        ScorecardInstitutionColumns.TUITIONFEE_OUT,
        ScorecardInstitutionColumns.ROOMBOARD_ON,
        ScorecardInstitutionColumns.ROOMBOARD_OFF,
        ScorecardInstitutionColumns.BOOKSUPPLY,
        ScorecardInstitutionColumns.OTHEREXPENSE_ON,
        ScorecardInstitutionColumns.OTHEREXPENSE_OFF,
        ScorecardInstitutionColumns.OTHEREXPENSE_FAM,
      )
    val chargeYears = charges.map { years.getValue(it) }.toSet()
    assertEquals(1, chargeYears.size, "the eight charges are dated alike: $chargeYears")
    val blendYears = (years.keys - charges.toSet()).map { years.getValue(it) }.toSet()
    assertEquals(1, blendYears.size, "COSTT4_A and the net-price family are dated alike: $blendYears")
    assertEquals(chargeYears.single().firstCalendarYear - 1, blendYears.single().firstCalendarYear)
  }

  @Test
  fun `the three field-of-study variables read as pooled cohorts, earnings with a dollar base and debt without`() {
    for (variable in ScorecardFieldOfStudyColumns.LOADED_VARIABLES) {
      val cohort = dictionary.fieldOfStudyCohortOf(variable)
      assertTrue(cohort.last >= cohort.first, "[$variable] pools [${cohort.first.label}]..[${cohort.last.label}]")
    }
    assertNotNull(dictionary.fieldOfStudyCohortOf(ScorecardFieldOfStudyColumns.EARN_MDN_4YR).amountUsdYear)
    assertNull(dictionary.fieldOfStudyCohortOf(ScorecardFieldOfStudyColumns.DEBT_ALL_STGP_EVAL_MDN).amountUsdYear)
    assertNull(
      dictionary.fieldOfStudyCohortOf(ScorecardFieldOfStudyColumns.DEBT_ALL_STGP_EVAL_MDN10YRPAY).amountUsdYear,
    )
  }

  @Test
  fun `the committed CSVs are the ones the fetch recorded, digest for digest`() {
    // The fetcher writes each CSV and its sha256 together; an edit of a
    // committed CSV by hand -- the transcription this replaced -- fails here.
    val workbook =
      Json
        .parseToJsonElement(File(SeedScorecardDictionary.directory, "PROVENANCE.json").readText())
        .jsonObject
        .getValue("artifacts")
        .jsonObject
        .getValue("CollegeScorecardDataDictionary")
        .jsonObject
    val sheets = workbook.getValue("sheets").jsonObject
    assertEquals(
      SeedScorecardDictionary.sources.files
        .map { it.file.name }
        .toSet(),
      sheets.keys,
    )
    for (source in SeedScorecardDictionary.sources.files) {
      val recorded =
        sheets
          .getValue(source.file.name)
          .jsonObject
          .getValue("sha256")
          .jsonPrimitive.content
      val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.file.readBytes()))
      assertEquals(recorded, actual, "[${source.file.name}] is not the file the fetch recorded")
    }
    assertEquals(
      sheets.values
        .map {
          it.jsonObject
            .getValue("sha256")
            .jsonPrimitive.content
        }.toSet(),
      dictionary.digests.map { it.sha256 }.toSet(),
      "the reader digests exactly the three files the fetch recorded",
    )
  }

  @Test
  fun `the Scorecard's charges sit past IC_AY's staged window, so the two publishers cannot contend`() {
    // RFC 183 D2's check, kept: the precedence story on price cells relies on
    // the Scorecard's charge year being later than every IC_AY year staged.
    val chargeYear = dictionary.academicYearOf(ScorecardInstitutionColumns.TUITIONFEE_IN)
    val ipedsNewestYear = AcademicYear(IpedsChargeVocabulary.SURVEY_YEAR)
    assertTrue(
      chargeYear > ipedsNewestYear,
      "IC_AY stages through [${ipedsNewestYear.label}] and the dictionary dates the charges [${chargeYear.label}]",
    )
  }

  @Test
  fun `a variable the dictionary does not state is refused, naming it and the file`() {
    val refusal =
      assertFailsWith<ScorecardDictionary.VariableNotInDictionaryException> {
        dictionary.academicYearOf("NO_SUCH_VARIABLE")
      }
    assertEquals("NO_SUCH_VARIABLE", refusal.variable)
    assertEquals(ScorecardDictionarySources.INSTITUTION_COHORTS_FILE, refusal.file)
  }

  @Test
  fun `a variable stated twice is refused, never read off the last row`() {
    // `associate` would keep the LAST of two contradictory rows, and the year
    // a family is shown would depend on row order.
    val refusal =
      assertFailsWith<ScorecardDictionary.MalformedDictionaryFileException> {
        read(
          writeDictionary(
            cohorts =
              listOf(
                "TUITIONFEE_IN" to "AcadYr 2030-31, reported in IPEDS DCY2030-31",
                "TUITIONFEE_IN" to "AcadYr 2029-30, reported in IPEDS DCY2029-30",
              ),
          ),
        )
      }
    assertEquals("TUITIONFEE_IN", refusal.variable)
    assertTrue(refusal.detail.contains("twice"), refusal.detail)
  }

  @Test
  fun `a file missing a column the reader reads is refused naming the file and the column`() {
    val sources = writeDictionary()
    sources.institutionSources.file.writeText("VARIABLE NAME,SOURCES\nUNITID,IPEDS\n")
    val refusal = assertFailsWith<ScorecardDictionary.MalformedDictionaryFileException> { read(sources) }
    assertEquals(ScorecardDictionarySources.INSTITUTION_SOURCES_FILE, refusal.file)
    assertNull(refusal.variable, "a missing column is about no row")
    assertTrue(refusal.detail.contains("[SOURCE]"), refusal.detail)
  }

  @Test
  fun `the grammar reads each shape the publisher writes, on the cell's leading clause`() {
    assertEquals(
      Cohort.StatedAcademicYear(AcademicYear(2030)),
      cohortOf("V", "AcadYr 2030-31, reported in IPEDS DCY2031-32"),
    )
    assertEquals(
      Cohort.StatedEnteringAcademicYear("AY 2030-31 cohort", AcademicYear(2030)),
      cohortOf("V", "AY 2030-31 cohort, reported in IPEDS DCY2031-32"),
    )
    assertEquals(
      Cohort.StatedTerm("Fall 2030 or AcadYr 2030-31 cohort (differs by institutional calendar)", 2030),
      cohortOf(
        "V",
        "Fall 2030 or AcadYr 2030-31 cohort (differs by institutional calendar), reported in IPEDS DCY2031-32",
      ),
    )
    val debt = cohortOf("V", "NSLDS pooled AY2030-31, AY2031-32 cohort")
    assertEquals(Cohort.PooledAcademicYears(AcademicYear(2030), AcademicYear(2031), null, "NSLDS pooled AY2030-31, AY2031-32 cohort"), debt)
    val earnings =
      assertIs<Cohort.PooledAcademicYears>(
        cohortOf(
          "V",
          "Treasury AY2030-2031, AY2031-2032 pooled earnings cohort measured in CY2035, CY2036, " +
            "inflation adjusted to 2037 dollars",
        ),
      )
    assertEquals(AcademicYear(2030), earnings.first)
    assertEquals(AcademicYear(2031), earnings.last)
    assertEquals(2037, assertNotNull(earnings.amountUsdYear).year)
    assertEquals(Cohort.NoAcademicYear, cohortOf("V", ""))
  }

  @Test
  fun `each unreadable shape is refused with its own cause, naming the variable and the cell`() {
    val cases =
      mapOf(
        // The `AY` arm: the closing word dropped, and a four-digit second year.
        "AY 2030-31" to Cause.WRONG_CELL_SHAPE,
        "AY 2030-2031 cohort" to Cause.LABEL_NOT_SPOKEN_THAT_WAY,
        // The `AcadYr` arm: a label that names no year, and one out of range.
        "AcadYr twenty-thirty" to Cause.LABEL_HAS_NO_YEAR,
        "AcadYr 1990-91" to Cause.YEAR_REJECTED,
        // The term arm: a second year glued on, and a two-digit year.
        "Fall 2030-31" to Cause.NOT_AN_ENTERING_TERM,
        "Fall 30" to Cause.NOT_AN_ENTERING_TERM,
        // A pooled cell in neither pooled shape, and a span that runs backwards.
        "NSLDS FY2030, FY2031 pooled cohorts" to Cause.NOT_A_POOLED_COHORT,
        "NSLDS pooled AY2031-32, AY2030-31 cohort" to Cause.LABEL_NOT_SPOKEN_THAT_WAY,
        // A pooled label that parses to the right year in a spelling the publisher never writes.
        "NSLDS pooled AY+2030-31, AY2031-32 cohort" to Cause.LABEL_NOT_SPOKEN_THAT_WAY,
        "NSLDS pooled AY02030-31, AY2031-32 cohort" to Cause.LABEL_NOT_SPOKEN_THAT_WAY,
        "NSLDS pooled AY2030-31, AY+2031-32 cohort" to Cause.LABEL_NOT_SPOKEN_THAT_WAY,
        // No shape at all.
        "Autumn 2030" to Cause.UNKNOWN_SHAPE,
      )
    for ((cell, cause) in cases) {
      val refusal = assertFailsWith<ScorecardDictionary.UnreadableCohortCellException> { cohortOf("V", cell) }
      assertEquals(cell, refusal.cell, "the refusal carries the cell it refused")
      assertEquals("V", refusal.variable, "and the variable it was asked about")
      assertEquals(cause, refusal.read.cause, "[$cell] is refused for its OWN reason: [${refusal.message}]")
    }
  }

  @Test
  fun `a year its type refuses keeps the type's exception as the cause`() {
    val refusal = assertFailsWith<ScorecardDictionary.UnreadableCohortCellException> { cohortOf("V", "AcadYr 1990-91") }
    assertEquals(refusal.read.rejection, refusal.cause)
    assertIs<IllegalArgumentException>(refusal.cause)
  }

  @Test
  fun `a cell read as the wrong period kind is refused naming the variable and the cell`() {
    // A pooled administrative cell asked for as a collection year, and a term
    // asked for as one: both readable, neither the year a price is for.
    val sources =
      writeDictionary(
        cohorts =
          listOf(
            "GRAD_DEBT_MDN" to "NSLDS pooled AY2030-31, AY2031-32 cohort",
            "ADM_RATE" to "Fall 2030, reported in IPEDS DCY2031-32",
          ),
      )
    val written = read(sources)
    for (variable in listOf("GRAD_DEBT_MDN", "ADM_RATE")) {
      val refusal = assertFailsWith<ScorecardDictionary.UnexpectedCohortShapeException> { written.academicYearOf(variable) }
      assertEquals(variable, refusal.variable)
      assertEquals(written.institutionCellOf(variable), refusal.cell)
    }
    assertFailsWith<ScorecardDictionary.UnexpectedCohortShapeException> { written.enteringPeriodOf("GRAD_DEBT_MDN") }
  }

  private fun read(sources: ScorecardDictionarySources): ScorecardDictionary = runBlocking { ScorecardDictionary.fromFiles(sources) }

  private fun cohortOf(
    variable: String,
    cell: String,
  ): Cohort = ScorecardCohortGrammar.read(variable, cell).orThrow()

  /** Three dictionary CSVs in a fresh directory, carrying [cohorts] and nothing else of note. */
  private fun writeDictionary(cohorts: List<Pair<String, String>> = listOf("UNITID" to "AcadYr 2030-31")): ScorecardDictionarySources {
    val directory = createTempDirectory("scorecard-dictionary").toFile().apply { deleteOnExit() }
    val cohortsFile = File(directory, ScorecardDictionarySources.INSTITUTION_COHORTS_FILE)
    cohortsFile.writeText(
      "variable,\"Cohort/Measure in \"\"Most Recent\"\" Datafile\"\n" +
        cohorts.joinToString("") { (variable, cell) -> "$variable,\"$cell\"\n" },
    )
    File(directory, ScorecardDictionarySources.INSTITUTION_SOURCES_FILE).writeText("VARIABLE NAME,SOURCE\nUNITID,IPEDS\n")
    File(directory, ScorecardDictionarySources.FIELD_OF_STUDY_COHORTS_FILE)
      .writeText("Variable Name,FieldOfStudyMostRecent datafile\nUNITID,AcadYr 2030-31\n")
    return SeedScorecardDictionary.sourcesIn(directory)
  }
}
