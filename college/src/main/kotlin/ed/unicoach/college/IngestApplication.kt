package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ed.unicoach.college.IngestApplication")

private const val USAGE =
  "Usage: ingest-colleges <institution.csv> <fields.csv> <aliases.json> " +
    "[--institution-source=ARG] [--fields-source=ARG] [--aliases-source=ARG]"

/** The recognized `--<name>=<value>` provenance flags, keyed by positional index. */
private val SOURCE_FLAGS = listOf("institution-source", "fields-source", "aliases-source")

/**
 * The argv grammar's outcome (RFC 139): either the three resolved sources or a
 * usage refusal. Split out of [main] so the refusals — a repeated flag, an
 * empty flag value, a wrong positional count — are directly testable rather
 * than reachable only through `exitProcess`.
 */
internal sealed interface ArgvResult {
  /** The three sources, in `SOURCE_FLAGS` order, each paired with the caller's original argument. */
  data class Ok(
    val sources: List<CollegeScorecardLoader.SourceFile>,
  ) : ArgvResult

  /** A grammar violation: [message] is logged and the process exits 2. */
  data class Usage(
    val message: String,
  ) : ArgvResult
}

/**
 * Parses `<institution.csv> <fields.csv> <aliases.json>` plus the optional
 * `--<name>=<value>` provenance flags. Every deviation is a refusal, never a
 * silent coercion: an unknown or valueless flag, a REPEATED flag (last-wins
 * would write provenance the caller never asked for), a flag whose value is
 * blank (present-but-empty is a wrong value, not an absent flag), or a
 * positional count other than [SOURCE_FLAGS]`.size`. File existence is checked
 * by the caller; this function touches no disk.
 */
internal fun parseArgv(args: Array<String>): ArgvResult {
  val positional = mutableListOf<String>()
  val sourceArgs = mutableMapOf<String, String>()
  for (arg in args) {
    if (!arg.startsWith("--")) {
      positional += arg
      continue
    }
    val eq = arg.indexOf('=')
    val name = if (eq >= 0) arg.substring(2, eq) else arg.substring(2)
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
  }
  if (positional.size != SOURCE_FLAGS.size) {
    return ArgvResult.Usage(USAGE)
  }
  return ArgvResult.Ok(
    positional.mapIndexed { i, path ->
      val file = File(path)
      CollegeScorecardLoader.SourceFile(file = file, sourceArg = sourceArgs[SOURCE_FLAGS[i]] ?: file.path)
    },
  )
}

/**
 * Operational entry for the re-runnable College Scorecard ingester (RFC 67,
 * provenance + aliases RFC 139). Reads the DB config from the classpath `.conf`
 * files (no new `college.conf`), takes the two CSV paths and the curated-aliases
 * JSON path from [args] (all three required — `bin/ingest-colleges` supplies the
 * repo default aliases path, so a missing aliases arg here is a loud usage
 * error, never a silently fabricated default), runs
 * [CollegeScorecardLoader.ingest], and prints the human change summary.
 * Invoked via `bin/ingest-colleges <institution.csv> <fields.csv>
 * [aliases.json]`.
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
 */
fun main(args: Array<String>) {
  val (institution, fields, aliases) =
    when (val parsed = parseArgv(args)) {
      is ArgvResult.Usage -> {
        logger.error(parsed.message)
        kotlin.system.exitProcess(2)
      }

      is ArgvResult.Ok -> {
        parsed.sources
      }
    }
  for (source in listOf(institution, fields, aliases)) {
    if (!source.file.isFile) {
      logger.error("Source file not found [{}]", source.file.path)
      kotlin.system.exitProcess(2)
    }
  }

  val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
  val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val database = Database(dbConfig)

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
