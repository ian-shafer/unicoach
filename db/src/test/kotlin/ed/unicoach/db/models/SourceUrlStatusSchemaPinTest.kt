package ed.unicoach.db.models

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three `source_url_status` slugs are written twice -- once in the schema's
 * CHECK and once in [SourceUrlStatus] -- and pinned against each other here
 * (RFC 200).
 *
 * CLAUDE.md's own-enumeration convention IS two copies: `TEXT` + `CHECK IN
 * (...)` in the schema, and exactly one Kotlin `enum class` with a `fromValue`
 * companion. Nothing in either language ties the lists, so a fourth state added
 * to one side compiles and migrates cleanly and surfaces only as a constraint
 * violation at the next ingest -- or, worse, as a `fromValue` fault at READ
 * time, on a citation a family is waiting for.
 *
 * Asserted by SWEEPING the migration, the shape this repo already uses for
 * files it does not compile. Cheap, and it fires on either side moving.
 */
class SourceUrlStatusSchemaPinTest {
  /** Gradle runs tests with the module directory as the working directory, so `db/` is `.`. */
  private val migration = File("schema/record-source-url-status.sql")

  @Test
  fun `the migration this test pins is where it says it is`() {
    // The control: a regex that matched nothing would otherwise make this
    // whole file fail as "no such file", which reads like an environment fault
    // rather than the drift being guarded.
    assertTrue(migration.isFile, "the pinned migration is not at [${migration.absolutePath}]")
    assertTrue(
      migration.readText().contains("ALTER TABLE source_documents"),
      "read a file that is not the source_url_status migration",
    )
  }

  @Test
  fun `the schema CHECK lists exactly the SourceUrlStatus values, in order`() {
    val listed =
      Regex("""source_url_status IN \(([^)]*)\)""")
        .findAll(migration.readText())
        .map { match -> match.groupValues[1].split(",").map { it.trim().trim('\'') } }
        .toList()

    // ONE membership CHECK, and this test reads THAT one. A second `IN` list
    // over the same column would be a second vocabulary, which is the defect
    // this file exists to catch rather than to average over.
    assertEquals(1, listed.size, "expected exactly one source_url_status IN (...) list: $listed")
    assertEquals(
      SourceUrlStatus.entries.map { it.value },
      listed.single(),
      "the schema CHECK and SourceUrlStatus name different states: a slug the enum knows and the CHECK " +
        "refuses fails at INGEST, and a slug the CHECK stores and the enum cannot read fails at READ",
    )
  }
}
