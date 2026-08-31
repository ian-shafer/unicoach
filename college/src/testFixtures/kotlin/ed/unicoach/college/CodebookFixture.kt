package ed.unicoach.college

import ed.unicoach.db.Database
import java.io.File

/**
 * Loads the REAL committed codebook (`db/data/codebooks.json`) into a test
 * database and hands back the [Codebook] the boundary reads from it (RFC 147).
 *
 * A test fixture rather than a hand-written map on purpose: the whole point of
 * D45 is that the region and locale words come from the loaded reference
 * tables, so a test that typed its own two rows would be exercising a
 * vocabulary the product does not have. It is shared across modules because the
 * two boundaries that speak the vocabulary — `search_colleges` in `:college`
 * and the fit lens's `record_college_query` in `:service` — must be tested
 * against the same one.
 *
 * [load] runs ONCE PER CALL, and callers put it in a field initializer, so under
 * JUnit 5's default per-method lifecycle it runs once per TEST METHOD. That is
 * deliberate and it is not an accident of the comment: `CollegeScorecardTestBase`
 * truncates the eleven reference tables between tests, so a fixture that loaded
 * only the first time would hand later tests an empty database. What is cached
 * is the expensive half that cannot go stale — reading, verifying and
 * artifact-hashing the file — leaving each call with only the ~2,000
 * change-suppressed upserts, which after the first call write nothing at all.
 */
object CodebookFixture {
  /**
   * The committed codebook, found by walking up from the test's working
   * directory (the module dir under Gradle) rather than assuming a fixed depth
   * — the `CodebookLoaderTest`/`CdsSeedLoaderTest` precedent. Named if absent,
   * rather than a bare NoSuchElementException out of a field initializer.
   */
  val COMMITTED_FILE: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/data/codebooks.json") }
      .firstOrNull { it.isFile }
      ?: error("db/data/codebooks.json was not found above [${File(".").absolutePath}]")

  /**
   * The parse, done once for the whole JVM. `CodebookLoader.parse` touches no
   * database — it reads the file, verifies every layer, and hashes the source
   * artifacts — so which loader instance produced it is immaterial, and the
   * result cannot go stale between tests.
   */
  private var parsed: CodebookLoader.ParsedCodebook? = null

  @Synchronized
  private fun parsedCodebook(loader: CodebookLoader): CodebookLoader.ParsedCodebook =
    parsed ?: loader.parse(SourceFile(COMMITTED_FILE, COMMITTED_FILE.path)).also { parsed = it }

  /** Loads every domain into [database] and returns the boundary's snapshot. */
  suspend fun load(database: Database): Codebook {
    val loader = CodebookLoader(database)
    loader.load(parsedCodebook(loader))
    return Codebook.load(database).getOrThrow()
  }
}
