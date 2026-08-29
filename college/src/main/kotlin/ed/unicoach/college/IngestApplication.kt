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
    "$CDS_DEADLINES_FLAG <deadlines.csv>]"

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
)

/**
 * The argv grammar's outcome (RFC 139, extended for the CDS group in RFC 140):
 * either the resolved sources (plus the optional CDS trio) or a usage refusal.
 * Split out of [main] so the refusals — a repeated flag, an empty flag value, a
 * wrong positional count, a partial CDS group — are directly testable rather
 * than reachable only through `exitProcess`.
 */
internal sealed interface ArgvResult {
  /** The three sources, in `SOURCE_FLAGS` order, each paired with the caller's original argument. */
  data class Ok(
    val sources: List<CollegeScorecardLoader.SourceFile>,
    val cds: CdsArgs?,
  ) : ArgvResult

  /** A grammar violation: [message] is logged and the process exits 2. */
  data class Usage(
    val message: String,
  ) : ArgvResult
}

/**
 * Parses `<institution.csv> <fields.csv> <aliases.json>` plus the optional
 * `--<name>=<value>` provenance flags (RFC 139) and the optional
 * `--cds-* <path>` seed group (RFC 140). Every deviation is a refusal, never a
 * silent coercion: an unknown or valueless flag, a REPEATED flag (last-wins
 * would write provenance the caller never asked for), a flag whose value is
 * blank (present-but-empty is a wrong value, not an absent flag), a positional
 * count other than [SOURCE_FLAGS]`.size`, or a PARTIAL CDS group (one table's
 * cycle without its siblings would skew the coverage report). File existence is
 * checked by the caller; this function touches no disk.
 *
 * The two flag spellings differ because their sources do: the provenance flags
 * carry a caller argument that may contain anything (`--name=value` keeps it
 * one argv token), while the CDS flags name files the shell already split.
 */
internal fun parseArgv(args: Array<String>): ArgvResult {
  val positional = mutableListOf<String>()
  val sourceArgs = mutableMapOf<String, String>()
  val cdsArgs = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg in CDS_FLAGS -> {
        val value = args.getOrNull(i + 1) ?: return ArgvResult.Usage("Option [$arg] requires a value. $USAGE")
        if (value.isBlank()) {
          return ArgvResult.Usage("Option [$arg] must have a non-empty value. $USAGE")
        }
        if (cdsArgs.put(arg, value) != null) {
          return ArgvResult.Usage("Option [$arg] was given more than once. $USAGE")
        }
        i += 2
      }

      // Anything option-SHAPED is rejected, not just the `--` spelling: every
      // positional here is a file path and a path never begins with `-`, so a
      // single-dash typo must fail as an unknown option rather than slipping
      // through to die later as "Source file not found [-m]".
      arg.startsWith("-") -> {
        val eq = arg.indexOf('=')
        val name = if (arg.startsWith("--")) (if (eq >= 0) arg.substring(2, eq) else arg.substring(2)) else ""
        if (eq < 0 || name !in SOURCE_FLAGS) {
          return ArgvResult.Usage("Unknown or malformed option [$arg]. $USAGE")
        }
        if (name in sourceArgs) {
          return ArgvResult.Usage("Option [--$name] was given more than once. $USAGE")
        }
        val value = arg.substring(eq + 1)
        if (value.isBlank()) {
          return ArgvResult.Usage("Option [--$name] must have a non-empty value. $USAGE")
        }
        sourceArgs[name] = value
        i += 1
      }

      else -> {
        positional += arg
        i += 1
      }
    }
  }
  if (positional.size != SOURCE_FLAGS.size) {
    return ArgvResult.Usage(USAGE)
  }
  if (cdsArgs.isNotEmpty() && cdsArgs.size != CDS_FLAGS.size) {
    return ArgvResult.Usage(
      "The CDS options are all-or-nothing: pass [${CDS_FLAGS.joinToString("] [")}] together, or none. $USAGE",
    )
  }
  return ArgvResult.Ok(
    sources =
      positional.mapIndexed { i, path ->
        val file = File(path)
        CollegeScorecardLoader.SourceFile(file = file, sourceArg = sourceArgs[SOURCE_FLAGS[i]] ?: file.path)
      },
    cds =
      cdsArgs.takeIf { it.isNotEmpty() }?.let { flags ->
        CdsArgs(
          meritAidCsv = File(flags.getValue(CDS_MERIT_FLAG)),
          admissionFactorsCsv = File(flags.getValue(CDS_FACTORS_FLAG)),
          deadlinesCsv = File(flags.getValue(CDS_DEADLINES_FLAG)),
        )
      },
  )
}

/** The filesystem probe, kept out of [parseArgv]: it exits the process, so it
 * is called where that effect is visible. */
private fun requireExistingFiles(parsed: ArgvResult.Ok) {
  val files =
    parsed.sources.map { it.file } +
      listOfNotNull(parsed.cds?.meritAidCsv, parsed.cds?.admissionFactorsCsv, parsed.cds?.deadlinesCsv)
  for (file in files) {
    if (!file.isFile) {
      logger.error("Source file not found [{}]", file.path)
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
 * [CollegeScorecardLoader.ingest], prints the human change summary, and then —
 * when the CDS group was passed — loads the seed and reports its numbers.
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
 * the per-table numbers and the coverage report, and they are rendered here
 * once — including the identities of the seed UNITIDs that matched no college,
 * at INFO, so recovering them never needs a second ingest.
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
        )
      }
    println(report.humanSummary())
    if (report.colleges.transientSkips + report.programs.transientSkips > 0) {
      logger.warn(
        "[{}] row(s) skipped on transient faults; re-running the ingest may recover them",
        report.colleges.transientSkips + report.programs.transientSkips,
      )
    }
    parsed.cds?.let { cds ->
      logCdsRun(
        runBlocking { CdsSeedLoader(database).load(cds.meritAidCsv, cds.admissionFactorsCsv, cds.deadlinesCsv) },
      )
    }
  } catch (e: CollegeScorecardLoader.MissingSourceColumnsException) {
    logger.error(
      "Ingest aborted before any write: source [{}] (from [{}]) is missing required column(s) [{}]",
      e.fileName,
      e.sourceArg,
      e.missing,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: CollegeScorecardLoader.InvalidAliasFileException) {
    logger.error(
      "Ingest aborted before any write: curated aliases file [{}] is invalid: [{}]",
      e.fileName,
      e.detail,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: CollegeScorecardLoader.PartialIngestException) {
    logger.error(
      "PARTIAL INGEST — phases {} COMMITTED, no college_index_build row was written, " +
        "provenance was NOT recorded; re-run the ingest to complete it",
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
  for ((table, summary) in listOf(
    "merit aid" to result.meritAid,
    "admission factors" to result.admissionFactors,
    "deadlines" to result.deadlines,
  )) {
    logCdsTable(table, summary)
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
  if (summary.unmatchedUnitIds.isEmpty()) return
  logger.info(
    "CDS ingest [{}]: [skipped={}] seed rows have no college [unit_ids={}]",
    table,
    summary.skipped,
    summary.unmatchedUnitIds,
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
