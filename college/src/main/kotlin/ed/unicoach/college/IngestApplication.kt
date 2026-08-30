package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.models.CdsCoverage
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ed.unicoach.college.IngestApplication")

private const val CDS_MERIT_FLAG = "--cds-merit"
private const val CDS_FACTORS_FLAG = "--cds-factors"
private const val CDS_DEADLINES_FLAG = "--cds-deadlines"

private val CDS_FLAGS = listOf(CDS_MERIT_FLAG, CDS_FACTORS_FLAG, CDS_DEADLINES_FLAG)

private const val USAGE =
  "Usage: ingest-colleges <institution.csv> <fields.csv> <aliases.json> " +
    "[--institution-source=ARG] [--fields-source=ARG] [--aliases-source=ARG] " +
    "[$CDS_MERIT_FLAG <merit-aid.csv> $CDS_FACTORS_FLAG <admission-factors.csv> " +
    "$CDS_DEADLINES_FLAG <deadlines.csv>] " +
    "[--hd=HD.csv --ic=IC.csv --adm=adm.csv --completions=C_A.csv --survey-year=YYYY] " +
    "[--hd-source=ARG] [--ic-source=ARG] [--adm-source=ARG] [--completions-source=ARG]"

/** The recognized `--<name>=<value>` provenance flags, keyed by positional index. */
private val SOURCE_FLAGS = listOf("institution-source", "fields-source", "aliases-source")

/**
 * The three CDS admissions seed files (RFC 140), passed as NAMED flags rather
 * than trailing positionals so the file-to-table mapping is self-describing and
 * order-free -- a mis-ordered argv cannot silently load deadlines as merit aid.
 */
internal data class CdsArgs(
  val meritAidCsv: File,
  val admissionFactorsCsv: File,
  val deadlinesCsv: File,
) {
  /**
   * The provenance spelling of the same three files (RFC 148). The CDS flags
   * name files the shell already split into their own argv slots, so the path
   * the caller typed IS the file's path — there is no `--*-source` partner to
   * carry a different original argument.
   */
  val sources: CdsSources
    get() =
      CdsSources(
        meritAid = SourceFile(meritAidCsv, meritAidCsv.path),
        admissionFactors = SourceFile(admissionFactorsCsv, admissionFactorsCsv.path),
        deadlines = SourceFile(deadlinesCsv, deadlinesCsv.path),
      )
}

/**
 * The optional IPEDS file flags (RFC 144, gate-2 D19). All four — plus
 * [SURVEY_YEAR_FLAG] — are required together or not at all: a partial group is a
 * usage error, never a silent partial load.
 */
private val IPEDS_FILE_FLAGS = listOf("hd", "ic", "adm", "completions")

/** Each IPEDS file's optional provenance partner, exactly as the Scorecard trio has. */
private val IPEDS_SOURCE_FLAGS = IPEDS_FILE_FLAGS.map { "$it-source" }

/**
 * The survey year is EXPLICIT, never derived from a filename: a derived year is
 * a silent coercion, and this value is stamped on every row the IPEDS phases
 * write.
 */
private const val SURVEY_YEAR_FLAG = "survey-year"

/**
 * The survey-year domain, owned by [IpedsLoader] with the rest of the 0055
 * mirrors: one declaration, so a CHECK change cannot leave the argv refusal
 * disagreeing with the loader.
 */
private val SURVEY_YEAR_RANGE = IpedsLoader.YEAR_RANGE

private val KNOWN_FLAGS = SOURCE_FLAGS + IPEDS_FILE_FLAGS + IPEDS_SOURCE_FLAGS + SURVEY_YEAR_FLAG

/**
 * The argv grammar's outcome (RFC 139, extended for the CDS group in RFC 140
 * and the IPEDS group in RFC 144): either the resolved sources or a usage
 * refusal. Split out of [main] so the refusals — a repeated flag, an empty
 * flag value, a wrong positional count, a partial CDS or IPEDS group — are
 * directly testable rather than reachable only through `exitProcess`.
 */
internal sealed interface ArgvResult {
  /**
   * The three Scorecard sources, in `SOURCE_FLAGS` order, each paired with the
   * caller's original argument, plus the optional IPEDS group ([ipeds] is null
   * when none of its flags were given).
   */
  data class Ok(
    val sources: List<SourceFile>,
    val cds: CdsArgs? = null,
    val ipeds: IpedsSources? = null,
  ) : ArgvResult

  /** A grammar violation: [message] is logged and the process exits 2. */
  data class Usage(
    val message: String,
  ) : ArgvResult
}

/**
 * Parses `<institution.csv> <fields.csv> <aliases.json>` plus the optional
 * `--<name>=<value>` provenance flags (RFC 139), the optional `--cds-* <path>`
 * seed group (RFC 140), and the optional IPEDS group (RFC 144). Every deviation
 * is a refusal, never a silent coercion: an unknown or valueless flag, a
 * REPEATED flag (last-wins would write provenance the caller never asked for), a
 * flag whose value is blank (present-but-empty is a wrong value, not an absent
 * flag), a positional count other than [SOURCE_FLAGS]`.size`, a PARTIAL CDS
 * group (one table's cycle without its siblings would skew the coverage report),
 * a PARTIAL IPEDS group, an IPEDS `--*-source` naming a file that was not
 * supplied, or a `--survey-year` that is not a plausible year. File existence is
 * checked by the caller; this function touches no disk.
 *
 * Two flag spellings coexist because their sources differ: the `=`-joined flags
 * carry a caller argument that may contain anything, while the CDS flags name
 * files the shell already split into their own argv slots.
 */
internal fun parseArgv(args: Array<String>): ArgvResult {
  val positional = mutableListOf<String>()
  val flags = mutableMapOf<String, String>()
  val cdsArgs = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    if (arg in CDS_FLAGS) {
      val value = args.getOrNull(i + 1) ?: return ArgvResult.Usage("Option [$arg] requires a value. $USAGE")
      if (value.isBlank()) {
        return ArgvResult.Usage("Option [$arg] must have a non-empty value. $USAGE")
      }
      if (cdsArgs.put(arg, value) != null) {
        return ArgvResult.Usage("Option [$arg] was given more than once. $USAGE")
      }
      i += 2
      continue
    }
    i += 1
    // Only `--` spellings are options here: the CDS group's single-dash flags
    // are consumed by bin/ingest-colleges' getopts and never reach the JVM, so
    // a leading `-` at this layer can only be part of a file path.
    if (!arg.startsWith("--")) {
      positional += arg
      continue
    }
    val eq = arg.indexOf('=')
    val name = if (eq >= 0) arg.substring(2, eq) else arg.substring(2)
    if (eq < 0 || name !in KNOWN_FLAGS) {
      return ArgvResult.Usage("Unknown or malformed option [$arg]. $USAGE")
    }
    if (name in flags) {
      return ArgvResult.Usage("Option [--$name] was given more than once. $USAGE")
    }
    val value = arg.substring(eq + 1)
    if (value.isBlank()) {
      return ArgvResult.Usage("Option [--$name] must have a non-empty value. $USAGE")
    }
    flags[name] = value
  }
  if (positional.size != SOURCE_FLAGS.size) {
    return ArgvResult.Usage(USAGE)
  }
  if (cdsArgs.isNotEmpty() && cdsArgs.size != CDS_FLAGS.size) {
    return ArgvResult.Usage(
      "The CDS options are all-or-nothing: pass [${CDS_FLAGS.joinToString("] [")}] together, or none. $USAGE",
    )
  }
  val ipeds =
    when (val group = parseIpedsGroup(flags)) {
      is IpedsGroup.Invalid -> return ArgvResult.Usage(group.message)
      is IpedsGroup.Absent -> null
      is IpedsGroup.Present -> group.sources
    }
  return ArgvResult.Ok(
    sources =
      positional.mapIndexed { i, path ->
        val file = File(path)
        SourceFile(file = file, sourceArg = flags[SOURCE_FLAGS[i]] ?: file.path)
      },
    cds =
      cdsArgs.takeIf { it.isNotEmpty() }?.let { group ->
        CdsArgs(
          meritAidCsv = File(group.getValue(CDS_MERIT_FLAG)),
          admissionFactorsCsv = File(group.getValue(CDS_FACTORS_FLAG)),
          deadlinesCsv = File(group.getValue(CDS_DEADLINES_FLAG)),
        )
      },
    ipeds = ipeds,
  )
}

/** The three outcomes of reading the optional IPEDS flag group out of [parseArgv]'s flag map. */
private sealed interface IpedsGroup {
  data object Absent : IpedsGroup

  data class Present(
    val sources: IpedsSources,
  ) : IpedsGroup

  data class Invalid(
    val message: String,
  ) : IpedsGroup
}

/**
 * Reads the IPEDS group all-or-nothing. Presence is judged on the four file
 * flags AND `--survey-year` together, so omitting any one of the five is a
 * refusal that names exactly which are missing rather than a run that quietly
 * loads four files with a fabricated year.
 */
private fun parseIpedsGroup(flags: Map<String, String>): IpedsGroup {
  val groupFlags = IPEDS_FILE_FLAGS + SURVEY_YEAR_FLAG
  val given = groupFlags.filter { it in flags }
  val danglingSources = IPEDS_SOURCE_FLAGS.filter { it in flags }
  if (given.isEmpty()) {
    if (danglingSources.isNotEmpty()) {
      return IpedsGroup.Invalid(
        "Option(s) ${danglingSources.map { "--$it" }} name a provenance source for an IPEDS file " +
          "that was not supplied. $USAGE",
      )
    }
    return IpedsGroup.Absent
  }
  val missing = groupFlags.filterNot { it in flags }
  if (missing.isNotEmpty()) {
    return IpedsGroup.Invalid(
      "The IPEDS options are all-or-nothing: given ${given.map { "--$it" }}, " +
        "option(s) ${missing.map { "--$it" }} are also required. $USAGE",
    )
  }
  val surveyYear = flags.getValue(SURVEY_YEAR_FLAG).toIntOrNull()
  if (surveyYear == null || surveyYear !in SURVEY_YEAR_RANGE) {
    return IpedsGroup.Invalid(
      "Option [--$SURVEY_YEAR_FLAG] must be a year in [$SURVEY_YEAR_RANGE], " +
        "got [${flags.getValue(SURVEY_YEAR_FLAG)}]. $USAGE",
    )
  }
  val files =
    IPEDS_FILE_FLAGS.map { flag ->
      val file = File(flags.getValue(flag))
      SourceFile(file = file, sourceArg = flags["$flag-source"] ?: file.path)
    }
  return IpedsGroup.Present(
    IpedsSources(
      hd = files[0],
      ic = files[1],
      adm = files[2],
      completions = files[3],
      surveyYear = surveyYear,
    ),
  )
}

/**
 * Every file the run will read, each paired with the ROLE it fills — the flag
 * the operator typed, minus its punctuation. Pure (it touches no disk) so the
 * pairing is directly testable, unlike [requireExistingFiles], which exits the
 * process.
 */
internal fun namedSources(parsed: ArgvResult.Ok): List<Pair<String, SourceFile>> {
  val scorecard = SOURCE_FLAGS.map { it.removeSuffix("-source") }.zip(parsed.sources)
  val ipeds = parsed.ipeds?.let { IPEDS_FILE_FLAGS.zip(it.files) } ?: emptyList()
  // One spelling of the CDS file list ([CdsArgs.sources]), so the files this
  // checks for existence are exactly the ones the run digests.
  // Pairing by NAME, on [CdsSources] itself: the previous positional
  // `CDS_FLAGS.zip(sources.files)` had no size check, so a fourth CDS file
  // would have been dropped from the existence check with nothing failing --
  // the same silent-drop this commit removed from `logCdsRun`.
  val cds = parsed.cds?.sources?.namedFiles ?: emptyList()
  return scorecard + ipeds + cds
}

/** The filesystem probe, kept out of [parseArgv]: it exits the process, so it
 * is called where that effect is visible. */
private fun requireExistingFiles(parsed: ArgvResult.Ok) {
  for ((role, source) in namedSources(parsed)) {
    if (!source.file.isFile) {
      // The role and the caller's ORIGINAL argument, not just the resolved
      // path: bin/ingest-colleges downloads an s3:// source into a mktemp dir,
      // so the path alone names a file the operator never typed and — with
      // seven candidates — does not say which option failed.
      logger.error(
        "Source file not found [role={}] [path={}] [from={}]",
        role,
        source.file.path,
        source.sourceArg,
      )
      kotlin.system.exitProcess(2)
    }
  }
}

/**
 * Operational entry for the re-runnable college ingester: the College Scorecard
 * pair plus curated aliases (RFC 67, provenance + aliases RFC 139) and,
 * optionally, the three CDS admissions seed files (RFC 140). Reads the DB config
 * from the classpath `.conf` files (no new `college.conf`), takes the three
 * source paths from [args] (all three required — `bin/ingest-colleges` supplies
 * the repo default aliases path, so a missing aliases arg here is a loud usage
 * error, never a silently fabricated default), runs
 * [CollegeScorecardLoader.ingest] — which, when the CDS group was passed, loads
 * the seed as one of its phases, before provenance (RFC 148 D10) — prints the
 * human change summary, and reports the CDS numbers the run returned.
 *
 * Failure contract (RFC 139): a CSV missing a required column — or any other
 * failure — aborts with a non-zero exit and NO `college_index_build` row; the
 * build row and summary are success-path only. A failure AFTER a phase has
 * already committed cannot be rolled back, so it is reported loudly instead:
 * `PARTIAL INGEST` names the committed phases and says provenance was not
 * recorded (the ingest is idempotent — re-running completes it).
 *
 * The optional `--institution-source= / --fields-source= / --aliases-source=`
 * flags carry each source's ORIGINAL caller argument (a local path or `s3://`
 * URL) as explicit argv: `bin/ingest-colleges` downloads remote args to a
 * scratch path before the JVM sees them and passes the originals here, so the
 * provenance row records what the caller actually named. A flag left off means
 * the positional path IS the original argument (a direct local invocation); a
 * repeated flag, or one with an empty value, is a usage error (exit 2) — never
 * silently last-wins or defaulted, because the provenance row would then record
 * something the caller did not ask for.
 *
 * This is also the single owner of CDS run reporting: [CdsSeedLoader] returns
 * the per-table numbers and the coverage report through the ingest report, and
 * they are rendered here once — including the identities of the seed UNITIDs
 * that matched no college, at INFO, so recovering them never needs a second
 * ingest.
 *
 * The optional IPEDS group (`--hd/--ic/--adm/--completions/--survey-year`, RFC
 * 144) extends the same run rather than adding a second command (gate-2 D19):
 * given none of the five the run is exactly the RFC 139 one, and given any of
 * them all five are required. Each IPEDS file has the same `--*-source` partner
 * carrying its original argument, and `--survey-year` is explicit because a
 * year derived from a filename is a silent coercion.
 */
fun main(args: Array<String>) {
  val parsed =
    when (val result = parseArgv(args)) {
      is ArgvResult.Usage -> {
        logger.error(result.message)
        kotlin.system.exitProcess(2)
      }

      is ArgvResult.Ok -> {
        result
      }
    }
  requireExistingFiles(parsed)
  val (institution, fields, aliases) = parsed.sources
  val database = openDatabase()

  try {
    val report =
      runBlocking {
        CollegeScorecardLoader(database).ingest(
          institution = institution,
          fields = fields,
          aliasesFile = aliases,
          ipeds = parsed.ipeds,
          cds = parsed.cds?.sources,
        )
      }
    println(report.humanSummary())
    val transientSkips =
      report.colleges.transientSkips + report.programs.transientSkips +
        (report.ipeds?.let { it.attributes.transientSkips + it.census.transientSkips } ?: 0)
    if (transientSkips > 0) {
      logger.warn(
        "[{}] row(s) skipped on transient faults; re-running the ingest may recover them",
        transientSkips,
      )
    }
    // The CDS load itself ran INSIDE the ingest, before the provenance phase
    // (RFC 148 D10): this is the reporting half only, and it reads the result
    // the run recorded rather than performing a second, unrecorded load.
    report.cds?.let(::logCdsRun)
  } catch (e: MissingSourceColumnsException) {
    logger.error(
      "Ingest aborted before any write: source [{}] (from [{}]) is missing required column(s) [{}]",
      e.fileName,
      e.sourceArg,
      e.missing,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: CdsSeedLoader.FormatException) {
    // Reached only from the up-front header assertion, which runs before the
    // first phase commits: a defect found once a phase HAS committed arrives as
    // the PartialIngestException below. So this abort can state the write
    // state, exactly as its siblings do.
    logger.error("Ingest aborted before any write: CDS seed defect [{}]", e.defect, e)
    kotlin.system.exitProcess(1)
  } catch (e: CollegeScorecardLoader.InvalidAliasFileException) {
    logger.error(
      "Ingest aborted before any write: curated aliases file [{}] is invalid: [{}]",
      e.fileName,
      e.detail,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: PartialIngestException) {
    logger.error(
      "PARTIAL INGEST — phase [{}] FAILED, phases {} COMMITTED, no college_index_build row was written, " +
        "provenance was NOT recorded; re-run the ingest to complete it",
      e.failedPhase,
      e.committedPhases,
      e,
    )
    kotlin.system.exitProcess(1)
  } finally {
    database.close()
  }
}

private fun openDatabase(): Database {
  val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
  return Database(DatabaseConfig.from(config).getOrThrow())
}

private fun logCdsRun(result: CdsSeedLoader.LoadResult) {
  // Each summary carries its own table, so the name printed beside a count is
  // that count's name by construction -- never a positional pairing that a
  // reorder would quietly invert.
  for ((table, summary) in result.tableSummaries) {
    logCdsTable(table.logLabel, summary)
  }
  logCdsCoverage(result.coverage)
}

private fun logCdsTable(
  table: String,
  summary: CdsSeedLoader.TableSummary,
) {
  logger.info(
    "CDS ingest [{}]: [upserted={}] [changed={}] [unchanged={}] [skipped={}]",
    table,
    summary.upserted,
    summary.changed,
    summary.unchanged,
    summary.skipped,
  )
  if (summary.unmatchedIpedsUnitIds.isEmpty()) return
  logger.info(
    "CDS ingest [{}]: [skipped={}] seed rows have no college [ipeds_unit_ids={}]",
    table,
    summary.skipped,
    summary.unmatchedIpedsUnitIds,
  )
}

/**
 * The launch-set coverage report (RFC 140), emitted twice on purpose: as log
 * properties, so a run's D8 numbers can be read mechanically out of the JSON log
 * stream like every other CDS run number, and as the plain multi-line block, so
 * an operator run still ends with the figures in a readable shape.
 */
private fun logCdsCoverage(coverage: CdsCoverage) {
  logger.info(
    "CDS coverage: [launch_set={}] [merit_aid={}] [admission_factors={}] " +
      "[deadlines_flags={}] [deadlines_with_date={}] [student_listed_missing={}]",
    coverage.launchSetCount,
    coverage.meritAidCount,
    coverage.admissionFactorsCount,
    coverage.deadlinesFlagsCount,
    coverage.deadlinesWithDateCount,
    coverage.studentListedMissing,
  )
  println(CdsSeedLoader.render(coverage))
}
