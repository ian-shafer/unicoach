package ed.unicoach.college

import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.models.NewCipCode
import ed.unicoach.db.models.NewSubject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SubjectLoader] (RFC 150 D49/D50) — invariants only, never a subject count.
 * The taxonomy has no size target and this suite must not invent one.
 */
class SubjectLoaderTest : CollegeScorecardTestBase() {
  private val loader = SubjectLoader(database)

  /** The loader's parse is suspend (it owns its IO dispatcher); every test reads it through here. */
  private fun parse(file: File): List<NewSubject> = runBlocking { loader.parse(file) }

  /**
   * The committed taxonomy and codebook, found by walking up from the test's
   * working directory rather than assuming a fixed depth — the
   * [CodebookLoaderTest] precedent.
   */
  private fun repoFile(relative: String): File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, relative) }
      .first { it.isFile }

  private val committedFile: File get() = repoFile("db/data/subjects.json")

  private fun tempFile(content: String): File =
    File.createTempFile("subjects", ".json").apply {
      deleteOnExit()
      writeText(content)
    }

  private fun seedCipCodes(vararg codes: String) {
    withSession { session ->
      for (code in codes) {
        CodebooksDao.upsertCipCode(session, NewCipCode(code, "Title $code", "Title $code")).getOrThrow()
      }
    }
  }

  private fun load(subjects: List<NewSubject>): SubjectLoader.LoadResult = runBlocking { loader.load("subjects.json", subjects) }

  // ---------------------------------------------------------------------------
  // The committed file
  // ---------------------------------------------------------------------------

  @Test
  fun `the committed subjects file parses`() {
    val subjects = parse(committedFile)
    assertTrue(subjects.isNotEmpty())
  }

  @Test
  fun `every slug in the committed file satisfies the shared slug domain and is unique`() {
    val subjects = parse(committedFile)
    val slugRegex = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    for (subject in subjects) {
      assertTrue(slugRegex.matches(subject.slug), "[${subject.slug}] is not a valid slug")
    }
    assertEquals(subjects.size, subjects.map { it.slug }.toSet().size, "slugs are unique")
  }

  @Test
  fun `every cip_prefix in the committed file is canonical and matches at least one real CIP code`() {
    val subjects = parse(committedFile)
    val canonical = Regex("^([0-9]{2}|[0-9]{4}|[0-9]{6})$")
    for (subject in subjects) {
      for (prefix in subject.cipPrefixes) {
        assertTrue(canonical.matches(prefix), "[${subject.slug}] prefix [$prefix] is not canonical")
      }
    }
    // Measured against the COMMITTED codebook, which is the vocabulary the
    // ingest validates against — the same file, so this test fails the moment
    // an edit to either one breaks the other.
    val codes = committedCipCodes()
    for (subject in subjects) {
      for (prefix in subject.cipPrefixes) {
        assertTrue(
          codes.any { it.startsWith(prefix) },
          "[${subject.slug}] prefix [$prefix] matches no cip_codes row in db/data/codebooks.json",
        )
      }
    }
  }

  /** Every six-digit code the COMMITTED codebook publishes — the vocabulary the ingest validates against. */
  private fun committedCipCodes(): List<String> {
    val root = Json.parseToJsonElement(repoFile("db/data/codebooks.json").readText()).jsonObject
    return root
      .getValue("cip_code")
      .jsonObject
      .getValue("codes")
      .jsonArray
      .map {
        it.jsonObject
          .getValue("code")
          .jsonPrimitive.content
      }
  }

  // ---------------------------------------------------------------------------
  // The fatal validation (D49)
  // ---------------------------------------------------------------------------

  @Test
  fun `a prefix matching zero CIP codes is fatal and names the prefix and the subject`() {
    seedCipCodes("511601", "230101")
    val thrown =
      assertFailsWith<SubjectLoader.UnmatchedCipPrefixException> {
        load(
          listOf(
            NewSubject("nursing", "Nursing", listOf("5116")),
            // 5138 is the RETIRED nursing series: a well-formed prefix that the
            // 2023 vocabulary does not publish. Nothing today would say so.
            NewSubject("nursing-old", "Nursing (old series)", listOf("5138")),
          ),
        )
      }
    assertEquals(listOf("nursing-old" to "5138"), thrown.unmatched)
    assertContains(thrown.message!!, "5138")
    assertContains(thrown.message!!, "nursing-old")
  }

  @Test
  fun `the whole file is validated before the first write`() {
    seedCipCodes("230101")
    assertFailsWith<SubjectLoader.UnmatchedCipPrefixException> {
      load(
        listOf(
          NewSubject("literature", "Literature", listOf("2301")),
          NewSubject("dead", "Dead", listOf("998877")),
        ),
      )
    }
    // The GOOD subject must not have landed: the check runs before the first
    // upsert, so a taxonomy with one dead prefix leaves the table untouched.
    assertEquals(0, withSession { CodebooksDao.subjectCount(it).getOrThrow() })
  }

  // ---------------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------------

  @Test
  fun `the loaded row count equals the file's entry count, and a dropped subject is deleted`() {
    seedCipCodes("230101", "260101")
    val first =
      load(
        listOf(
          NewSubject("literature", "Literature", listOf("2301")),
          NewSubject("biology", "Biology", listOf("2601")),
        ),
      )
    assertEquals(2, first.subjects)
    assertEquals(2, first.inserted)
    assertEquals(0, first.deleted)

    // Re-loading an unchanged taxonomy writes nothing.
    val again =
      load(
        listOf(
          NewSubject("literature", "Literature", listOf("2301")),
          NewSubject("biology", "Biology", listOf("2601")),
        ),
      )
    assertEquals(2, again.unchanged)
    assertEquals(0, again.inserted)

    // A subject removed from the file is removed from the table. It has to be:
    // `subject_slugs` is materialised, so a stale subject would keep matching
    // colleges forever.
    val dropped = load(listOf(NewSubject("literature", "Literature", listOf("2301"))))
    assertEquals(1, dropped.subjects)
    assertEquals(1, dropped.deleted)
    assertEquals(listOf("literature"), withSession { CodebooksDao.subjects(it).getOrThrow() }.map { it.slug })
  }

  @Test
  fun `the load reports how many CIP codes the taxonomy expands to`() {
    seedCipCodes("230101", "230102", "260101")
    val result = load(listOf(NewSubject("literature", "Literature", listOf("2301"))))
    // Printed on every run so a rebuild is visibly a rebuild (D51's risk).
    assertEquals(2, result.expandedCipCodes)
  }

  // ---------------------------------------------------------------------------
  // File-shape refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `a duplicate slug is fatal`() {
    val file =
      tempFile(
        """
        [{"slug":"art","name":"Art","cip_prefixes":["50"]},
         {"slug":"art","name":"Art again","cip_prefixes":["50"]}]
        """.trimIndent(),
      )
    val thrown = assertFailsWith<SubjectLoader.InvalidFileException> { parse(file) }
    assertContains(thrown.message!!, "duplicate slug")
  }

  @Test
  fun `a slug that is not a slug is fatal`() {
    val file = tempFile("""[{"slug":"Fine_Art","name":"Art","cip_prefixes":["50"]}]""")
    assertFailsWith<SubjectLoader.InvalidFileException> { parse(file) }
  }

  @Test
  fun `a surplus or missing key is fatal`() {
    assertFailsWith<SubjectLoader.InvalidFileException> {
      parse(tempFile("""[{"slug":"art","name":"Art","cip_prefixes":["50"],"extra":1}]"""))
    }
    assertFailsWith<SubjectLoader.InvalidFileException> {
      parse(tempFile("""[{"slug":"art","cip_prefixes":["50"]}]"""))
    }
  }

  @Test
  fun `an empty or unreadable cip_prefixes list is fatal`() {
    assertFailsWith<SubjectLoader.InvalidFileException> {
      parse(tempFile("""[{"slug":"art","name":"Art","cip_prefixes":[]}]"""))
    }
    assertFailsWith<SubjectLoader.InvalidFileException> {
      parse(tempFile("""[{"slug":"art","name":"Art","cip_prefixes":["5"]}]"""))
    }
  }

  @Test
  fun `a dotted prefix is canonicalized through the one CipPrefix parser`() {
    val subjects = parse(tempFile("""[{"slug":"art","name":"Art","cip_prefixes":["5.0102"]}]"""))
    assertEquals(listOf("050102"), subjects.single().cipPrefixes)
  }

  @Test
  fun `a non-array top level is fatal`() {
    assertFailsWith<SubjectLoader.InvalidFileException> { parse(tempFile("""{"slug":"art"}""")) }
    assertFailsWith<SubjectLoader.InvalidFileException> { parse(tempFile("not json at all")) }
  }

  @Test
  fun `an EMPTY taxonomy is fatal, because loading it would wipe the table`() {
    // `[]` parses cleanly and every per-entry rule is vacuously satisfied, so
    // the load would upsert nothing and then delete every stored subject —
    // silently disabling every `subject` filter until someone noticed that a
    // word which used to work now returns "not in the loaded taxonomy".
    val thrown = assertFailsWith<SubjectLoader.InvalidFileException> { parse(tempFile("[]")) }
    assertContains(thrown.message!!, "the taxonomy is empty")
  }

  @Test
  fun `the empty-taxonomy guard runs BEFORE any write, so the stored taxonomy survives`() {
    seedCipCodes("050102")
    load(listOf(NewSubject("art", "Art", listOf("050102"))))

    assertFailsWith<SubjectLoader.InvalidFileException> { parse(tempFile("[]")) }

    // The parse is what refuses, and it touches no database — so the taxonomy
    // the last good file loaded is still there, untouched.
    val stored = withSession { session -> CodebooksDao.subjects(session).getOrThrow() }
    assertEquals(listOf("art"), stored.map { it.slug })
  }
}
