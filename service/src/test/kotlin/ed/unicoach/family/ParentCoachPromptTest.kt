package ed.unicoach.family

import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SystemPromptsDao
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The parent coach's prompt (RFC 258 §6), asserted on the body the runtime is
 * SERVED — the migration-seeded `system_prompts` row the pin resolves — never on
 * a seed file's text.
 *
 * `system_prompts` is insert-only and `bin/test` re-migrates before every run,
 * so the catalog is complete and nothing here seeds or truncates it.
 */
class ParentCoachPromptTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }

    /** The opening words of the coach's money-vocabulary paragraph: where the four shared sentences begin. */
    private const val MONEY_VOCABULARY_OPENER = "Talk about money in two parts"

    /** How many sentences, one per line from that opener, the two prompts share verbatim. */
    private const val SHARED_MONEY_SENTENCES = 4

    /** The opening words of the inversion paragraph (brief 0014 D3). */
    private const val INVERSION_OPENER = "When this person asks you to push the student toward a school"

    /** Words that would make a line an instruction to steer the student, unless the line forbids it. */
    private val STEERING_WORDS = Regex("""\b(persuade|pressure|convince|push)""", RegexOption.IGNORE_CASE)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val appConfig =
    ed.unicoach.common.config.AppConfig
      .load("service.conf")
      .getOrThrow()

  private val parent = SupporterCoachingConfig.from(appConfig).getOrThrow()
  private val coach = CoachingConfig.from(appConfig).getOrThrow()

  /** The file a human edits, verbatim; `removeSuffix` drops only the byte that ends its last line. */
  private fun authoredBody(): String = File("../prompts/parent-coach-system-prompt.md").readText().removeSuffix("\n")

  private fun served(
    name: String,
    version: String,
  ): String =
    SystemPromptsDao
      .findByNameAndVersion(session, name, version)
      .getOrElse { fail("service.conf pins system_prompt ($name, $version), which the migration-seeded catalog does not contain", it) }
      .body

  private fun servedParent(): String = served(parent.systemPromptName, parent.systemPromptVersion)

  private fun servedCoach(): String = served(coach.systemPromptName, coach.systemPromptVersion)

  @Test
  fun `the pin resolves to the parent-coach v1 row`() {
    assertEquals("parent-coach", parent.systemPromptName)
    assertEquals("v1", parent.systemPromptVersion)
    assertTrue(servedParent().isNotBlank())
  }

  @Test
  fun `the served parent-coach v1 body equals the prompt file byte for byte`() {
    assertEquals(authoredBody(), served("parent-coach", "v1"))
  }

  @Test
  fun `the four money-vocabulary sentences are byte-identical in the served coach and parent-coach pins`() {
    val coachLines = servedCoach().lines()
    val start = coachLines.indexOfFirst { it.startsWith(MONEY_VOCABULARY_OPENER) }
    assertTrue(start >= 0, "the served coach pin must still open its money vocabulary with [$MONEY_VOCABULARY_OPENER]")
    val shared = coachLines.subList(start, start + SHARED_MONEY_SENTENCES)
    // The four are the vocabulary itself, not whatever happens to follow the opener.
    assertTrue(shared[1].startsWith("Say tuition and fees, never tuition on its own"), shared[1])
    assertTrue(shared[2].startsWith("Grants and scholarships"), shared[2])
    assertTrue(shared[3].startsWith("Every time you mention a loan"), shared[3])

    val parentLines = servedParent().lines()
    shared.forEach { sentence ->
      assertTrue(sentence in parentLines, "the parent-coach pin must carry, as a line of its own, byte for byte: [$sentence]")
    }
  }

  @Test
  fun `the redirect paragraph is served with both of its sources`() {
    val lines = servedParent().lines()
    val start = lines.indexOfFirst { it.startsWith(INVERSION_OPENER) }
    assertTrue(start >= 0, "the served parent prompt must carry the inversion paragraph opening [$INVERSION_OPENER]")
    val paragraph = lines.drop(start).takeWhile { it.isNotBlank() }

    assertTrue(paragraph.first().contains("do not help them do it"), "it refuses the ask before it explains")
    assertTrue(
      paragraph.any { it.contains("Challenge Success") && it.contains("https://challengesuccess.org/") },
      "the selectivity evidence carries its source and link",
    )
    assertTrue(
      paragraph.any { it.contains("New York Times and Morning Consult") },
      "the over-involvement evidence carries its source",
    )
    assertTrue(
      paragraph.any { it.contains("the money, the logistics, and steady emotional support") },
      "it turns the parent to what is theirs to do",
    )
  }

  @Test
  fun `no served sentence instructs the parent to persuade, pressure or direct the student`() {
    val steering = servedParent().lines().filter { STEERING_WORDS.containsMatchIn(it) }
    assertTrue(steering.isNotEmpty(), "positive control: the prompt does name the steering it forbids")

    val licences = steering.filterNot { it.startsWith("Never ") || it.contains("do not help them do it") }
    assertEquals(emptyList(), licences, "every mention of steering the student must forbid it")
  }
}
