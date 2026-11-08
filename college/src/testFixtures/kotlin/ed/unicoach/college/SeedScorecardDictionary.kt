package ed.unicoach.college

import ed.unicoach.db.models.ProgramMoneyDollars
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The COMMITTED Scorecard data dictionary (`db/seed/scorecard/`, RFC 274) --
 * the three CSVs `bin/fetch-scorecard` wrote from the publisher's workbook --
 * read through the production reader, for every test that needs a Scorecard
 * year.
 *
 * No test states a Scorecard year: it reads one from here, or derives one from
 * a year read here, so a newer fetched release moves every test with the data
 * instead of failing a literal nobody re-read.
 *
 * The seed directory is found by walking UP from the test's working directory,
 * the [CodebookFixture] precedent, so no test states a depth.
 */
object SeedScorecardDictionary {
  /** Where the committed seed lives, relative to any directory on the walk up. */
  private const val SEED_PATH = "db/seed/scorecard"

  /** The committed seed directory. */
  val directory: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, SEED_PATH) }
      .firstOrNull { File(it, ScorecardDictionarySources.INSTITUTION_COHORTS_FILE).isFile }
      ?: error(
        "no [$SEED_PATH/${ScorecardDictionarySources.INSTITUTION_COHORTS_FILE}] on the walk UP from " +
          "[${File(".").absoluteFile}]: the fetched data dictionary is a committed artifact every Scorecard " +
          "year is read from",
      )

  /** The three committed CSVs, as the ingest is handed them. */
  val sources: ScorecardDictionarySources get() = sourcesIn(directory)

  /** The three CSVs under the fetcher's own names in [directory] -- the seed directory, or a test's copy of it. */
  fun sourcesIn(directory: File): ScorecardDictionarySources {
    fun source(name: String): SourceFile = File(directory, name).let { file -> SourceFile(file, file.path) }
    return ScorecardDictionarySources(
      institutionCohorts = source(ScorecardDictionarySources.INSTITUTION_COHORTS_FILE),
      institutionSources = source(ScorecardDictionarySources.INSTITUTION_SOURCES_FILE),
      fieldOfStudyCohorts = source(ScorecardDictionarySources.FIELD_OF_STUDY_COHORTS_FILE),
    )
  }

  /** The committed dictionary, read once. */
  val dictionary: ScorecardDictionary by lazy { runBlocking { ScorecardDictionary.fromFiles(sources) } }

  /** [variable]'s measure and the dollar year the committed dictionary states for it, in the closed shape the rows carry. */
  fun dollarsOf(variable: ScorecardFieldOfStudyColumns.FieldOfStudyVariable): ProgramMoneyDollars =
    checkNotNull(
      ProgramMoneyDollars.ofOrNull(variable.measure, dictionary.fieldOfStudyCohortOf(variable.sourceVariable).amountUsdYear),
    ) { "the committed dictionary's dollar year for [${variable.sourceVariable}] disagrees with its measure" }
}
