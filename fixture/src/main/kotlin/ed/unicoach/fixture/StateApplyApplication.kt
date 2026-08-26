package ed.unicoach.fixture

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ed.unicoach.fixture.StateApplyApplication")

/**
 * Operational entry for the declarative state applier (RFC 138). Reads the DB
 * config from the classpath `.conf` files (no `fixture.conf`) and the world
 * file path from [args], parses it strictly, and applies it in one
 * all-or-nothing transaction. Invoked via `bin/state-apply [-f] <world.yaml>`
 * (the `-f` reset happens in the wrapper, before this process starts).
 */
fun main(args: Array<String>) {
  if (args.size != 1) {
    logger.error("Usage: state-apply <world.yaml>")
    kotlin.system.exitProcess(2)
  }

  val worldFile = File(args[0])
  if (!worldFile.isFile) {
    logger.error("world file not found [{}]", worldFile.path)
    kotlin.system.exitProcess(2)
  }

  val world =
    try {
      WorldFile.load(worldFile)
    } catch (e: Exception) {
      logger.error("failed to parse world file [{}]: {}", worldFile.path, e.message)
      kotlin.system.exitProcess(1)
    }

  val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
  val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val database = Database(dbConfig)

  try {
    val result =
      runBlocking {
        WorldApplier(database).apply(world)
      }
    for (user in result.users) {
      logger.info(
        "created [{}] verified={} admin={} id=[{}]",
        user.email,
        user.verified,
        user.admin,
        user.id.asString,
      )
    }
    logger.info("state-apply complete: [users={}]", result.users.size)
  } catch (e: Exception) {
    logger.error("state-apply failed; nothing was applied: {}", e.message)
    kotlin.system.exitProcess(1)
  } finally {
    database.close()
  }
  // Explicit exit: password hashing ran on Dispatchers.Crypto, whose fixed
  // thread pool is non-daemon and would otherwise pin this JVM alive after
  // main returns (fine for the servers, fatal for a CLI).
  kotlin.system.exitProcess(0)
}
