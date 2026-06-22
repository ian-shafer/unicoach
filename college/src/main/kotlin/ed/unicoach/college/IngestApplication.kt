package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ed.unicoach.college.IngestApplication")

/**
 * Operational entry for the re-runnable College Scorecard ingester (RFC 67).
 * Reads the DB config from the classpath `.conf` files (no new `college.conf`)
 * and the two CSV paths from [args], runs [CollegeScorecardLoader], and logs the
 * upserted counts. Invoked via `bin/ingest-colleges <institution.csv>
 * <fields.csv>`.
 */
fun main(args: Array<String>) {
  if (args.size != 2) {
    logger.error("Usage: ingest-colleges <institution.csv> <fields.csv>")
    kotlin.system.exitProcess(2)
  }

  val institutionCsv = File(args[0])
  val fieldsCsv = File(args[1])
  for (file in listOf(institutionCsv, fieldsCsv)) {
    if (!file.isFile) {
      logger.error("CSV file not found [{}]", file.path)
      kotlin.system.exitProcess(2)
    }
  }

  val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
  val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val database = Database(dbConfig)

  try {
    val result =
      runBlocking {
        CollegeScorecardLoader(database).load(institutionCsv, fieldsCsv)
      }
    logger.info(
      "Ingest complete: [colleges={}] [programs={}] [transient_skips={}] [permanent_skips={}]",
      result.collegesLoaded,
      result.programsLoaded,
      result.transientSkips,
      result.permanentSkips,
    )
    if (result.transientSkips > 0) {
      logger.warn(
        "[{}] row(s) skipped on transient faults; re-running the ingest may recover them",
        result.transientSkips,
      )
    }
  } finally {
    database.close()
  }
}
