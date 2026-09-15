package ed.unicoach.coaching

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.IntendedSubjectsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `update_intended_subjects` chat tool (RFC 205), exercised against real
 * Postgres through the service it shares with REST — the
 * [MoneyProfileChatToolTest] shape.
 *
 * NO COUNT OF THE PUBLISHED TAXONOMY IS ASSERTED ANYWHERE HERE. The fixture
 * seeds its own subjects, so the size of `db/data/subjects.json` is irrelevant
 * to whether this suite passes, and the cap is read from
 * [IntendedSubjectsDao.MAX_SUBJECTS] rather than written out.
 *
 * Every fixture slug is spelled so that it is NOT a case variant of its own
 * name (`general-biology` / "Biology"), which is what makes the "no slug reaches
 * the student" assertion below able to fail.
 */
class IntendedSubjectsChatToolTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    /**
     * The suite's OWN taxonomy: slug, name, and the subject's REAL CIP series,
     * so a seeded row is a plausible taxonomy row rather than a shape that only
     * happens to satisfy `subjects_cip_prefixes_format_check` (0064).
     *
     * "Biology" and "Marine Biology" are both here on purpose: a term that is
     * neither name exactly but sits inside both is the AMBIGUOUS case.
     */
    private val TAXONOMY =
      listOf(
        Triple("registered-nursing", "Nursing", "51"),
        Triple("general-biology", "Biology", "26"),
        Triple("marine-biology", "Marine Biology", "2613"),
        Triple("modern-history", "History", "54"),
        Triple("applied-physics", "Physics", "40"),
      )

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE intended_subjects_versions, intended_subjects, " +
          "intended_subject_status_versions, intended_subject_status, students, users, subjects CASCADE",
      )
    }
    connection.prepareStatement("INSERT INTO subjects (slug, name, cip_prefixes) VALUES (?, ?, ?::TEXT[])").use { stmt ->
      TAXONOMY.forEach { (slug, name, series) ->
        stmt.setString(1, slug)
        stmt.setString(2, name)
        stmt.setString(3, "{$series}")
        stmt.addBatch()
      }
      stmt.executeBatch()
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val vocabulary = SubjectVocabularyService(database)
  private val tool = IntendedSubjectsChatTool(IntendedSubjectsService(database, vocabulary), vocabulary)

  /**
   * The same tool over a pool that is already CLOSED — every read and write
   * through it fails the way a transient outage does, which is what makes the
   * total-contract test able to fail. Lazy, so only the test that needs it pays
   * for the pool.
   */
  private val brokenTool: IntendedSubjectsChatTool by lazy {
    val config =
      ed.unicoach.common.config.AppConfig
        .load("common.conf", "db.conf")
        .getOrThrow()
    val closed = Database(DatabaseConfig.from(config).getOrThrow()).also { it.close() }
    val brokenVocabulary = SubjectVocabularyService(closed)
    IntendedSubjectsChatTool(IntendedSubjectsService(closed, brokenVocabulary), brokenVocabulary)
  }

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'ist-$userId@test.com', 'IST User', 'ahash')",
      )
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_on, " +
          "expected_high_school_graduation_precision) " +
          "VALUES ('$studentId', '$userId', DATE '2028-01-01', 'year')",
      )
    }
    return StudentId(studentId)
  }

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String? = result["error"]?.jsonPrimitive?.content

  private fun answerOf(result: JsonObject): JsonObject = result.getValue("intended_subjects").jsonObject

  private fun namesIn(result: JsonObject): List<String> =
    answerOf(result)["subjects"]!!
      .jsonArray
      .map { it.jsonPrimitive.content }

  /** Every term the tool refused, in the order it read them — the whole report, never just the first. */
  private fun refusedTerms(result: JsonObject): List<String> =
    result[IntendedSubjectsChatTool.UNRESOLVED_KEY]!!
      .jsonArray
      .map { it.jsonObject[IntendedSubjectsChatTool.TERM_KEY]!!.jsonPrimitive.content }

  /** The choices offered FOR ONE refused term: the report is per term, so the coach can attribute them. */
  private fun candidatesFor(
    result: JsonObject,
    term: String,
  ): List<String> {
    val entry =
      result[IntendedSubjectsChatTool.UNRESOLVED_KEY]!!
        .jsonArray
        .map { it.jsonObject }
        .single { it[IntendedSubjectsChatTool.TERM_KEY]!!.jsonPrimitive.content == term }
    return entry[IntendedSubjectsChatTool.CANDIDATES_KEY]!!
      .jsonArray
      .map { it.jsonPrimitive.content }
  }

  private fun storedSlugs(student: StudentId): List<String> =
    IntendedSubjectsDao
      .readAnswer(sqlSession, student)
      .getOrThrow()
      .subjects
      .map { it.slug }

  private fun storedStatus(student: StudentId): AnswerStatus = IntendedSubjectsDao.readAnswer(sqlSession, student).getOrThrow().status

  @Test
  fun `an unambiguous subject name is recorded and echoed in words`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"subjects":["Nursing"]}"""))

      assertNull(errorOf(result), "got $result")
      assertEquals("answered", answerOf(result)["status"]!!.jsonPrimitive.content)
      assertEquals(listOf("Nursing"), namesIn(result))
      assertTrue(
        answerOf(result)["buys"]!!
          .jsonPrimitive.content
          .isNotBlank(),
        "the echo says what the stored answer buys",
      )

      assertEquals(listOf("registered-nursing"), storedSlugs(student))
      assertEquals(AnswerStatus.ANSWERED, storedStatus(student))
    }

  @Test
  fun `an ambiguous term stores nothing and comes back with the candidates and a reason`() =
    runBlocking {
      val student = createStudent()
      // "bio" is no subject's name and sits inside two of them.
      val result = tool.execute(student, input("""{"subjects":["bio"]}"""))

      val reason = errorOf(result)
      assertTrue(reason!!.contains("matches more than one subject"), "got $result")
      assertTrue(reason.contains("nothing was stored"), "the reason must say nothing was stored: $reason")
      assertEquals(listOf("bio"), refusedTerms(result), "the report names the term it is about")
      assertEquals(
        listOf("Biology", "Marine Biology"),
        candidatesFor(result, "bio").sorted(),
        "the choices go back to the coach so it can ask the student which they meant",
      )

      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student), "an unresolved term must store nothing")
      assertEquals(emptyList<String>(), storedSlugs(student))
    }

  @Test
  fun `an unknown term stores nothing and still comes back with choices and a reason`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"subjects":["underwater basket weaving"]}"""))

      val reason = errorOf(result)
      assertTrue(reason!!.contains("is not a subject on the list"), "got $result")
      assertTrue(reason.contains("nothing was stored"), "the reason must say nothing was stored: $reason")
      // "not on the list" with no list is a dead end, so the whole list is offered.
      val candidates = candidatesFor(result, "underwater basket weaving")
      assertTrue(candidates.isNotEmpty(), "a term matching nothing still gets choices")
      assertTrue(
        candidates.all { name -> TAXONOMY.any { it.second == name } },
        "every offered choice is a published subject name: $candidates",
      )

      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student))
      assertEquals(emptyList<String>(), storedSlugs(student))
    }

  @Test
  fun `every unresolvable term is reported at once, not one bad word per turn`() =
    runBlocking {
      val student = createStudent()
      // One ambiguous term, one unknown term, and one that resolves. A coach
      // told only about the first asks the student the same question twice.
      val result = tool.execute(student, input("""{"subjects":["bio","underwater basket weaving","Nursing"]}"""))

      val reason = errorOf(result)
      assertTrue(reason!!.contains("[bio]"), "got $result")
      assertTrue(reason.contains("[underwater basket weaving]"), "the second bad word is named too, got $reason")
      assertEquals(
        listOf("bio", "underwater basket weaving"),
        refusedTerms(result),
        "every unresolvable term is reported, and a resolvable one is not: $result",
      )
      assertEquals(
        listOf("Biology", "Marine Biology"),
        candidatesFor(result, "bio").sorted(),
        "each refused term carries its OWN choices",
      )
      assertTrue(
        candidatesFor(result, "underwater basket weaving").isNotEmpty(),
        "a term matching nothing still gets choices: $result",
      )

      // The write stays all-or-nothing: only the REPORT became complete.
      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student), "one unresolved term stores nothing at all")
      assertEquals(emptyList<String>(), storedSlugs(student))
    }

  @Test
  fun `a wrong-typed field is refused by name rather than read as an absent one`() =
    runBlocking {
      val student = createStudent()
      // A present-but-wrong-type key is NOT silence. Read as absent, this call
      // would have stored "Nursing" as though the student had said only that.
      val result = tool.execute(student, input("""{"subjects":["Nursing"],"undecided":"true"}"""))

      val reason = errorOf(result)
      assertTrue(reason!!.contains(IntendedSubjectsChatTool.UNDECIDED_FIELD), "the refusal names the field, got $result")
      assertTrue(reason.contains("boolean"), "the refusal names the shape expected, got $reason")

      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student), "a malformed call stores nothing")
      assertEquals(emptyList<String>(), storedSlugs(student))

      // A `subjects` that is a string where an array belongs is refused the same
      // way, naming its own field.
      val mistyped = tool.execute(student, input("""{"subjects":"biology"}"""))
      val mistypedReason = errorOf(mistyped)
      assertTrue(
        mistypedReason!!.contains(IntendedSubjectsChatTool.SUBJECTS_FIELD),
        "the refusal names the field, got $mistyped",
      )
      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student))
    }

  @Test
  fun `not knowing yet is recorded as undecided and reported as an answer`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"undecided":true}"""))

      assertNull(errorOf(result), "got $result")
      assertEquals(
        AnswerStatus.UNDECIDED.value,
        answerOf(result)["status"]!!.jsonPrimitive.content,
        "\"I don't know yet\" is an answer, not silence",
      )
      assertEquals(emptyList<String>(), namesIn(result))
      assertFalse(
        answerOf(result)["buys"]!!
          .jsonPrimitive.content
          .contains("declined"),
        "not knowing yet must not be reported as a decline",
      )

      assertEquals(AnswerStatus.UNDECIDED, storedStatus(student))
      assertEquals(emptyList<String>(), storedSlugs(student))
    }

  @Test
  fun `a subject past the cap is refused with a reason and nothing is stored`() =
    runBlocking {
      val student = createStudent()
      val overCap = TAXONOMY.take(IntendedSubjectsDao.MAX_SUBJECTS + 1).map { it.second }
      val json = overCap.joinToString(",", "[", "]") { "\"$it\"" }
      val result = tool.execute(student, input("""{"subjects":$json}"""))

      val reason = errorOf(result)
      assertTrue(
        reason!!.contains("at most ${IntendedSubjectsDao.MAX_SUBJECTS} subjects can be stored"),
        "got $result",
      )
      assertTrue(reason.contains("(got ${overCap.size})"), "the refusal names what was submitted: $reason")

      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student), "a refused call stores nothing")
      assertEquals(emptyList<String>(), storedSlugs(student))
    }

  @Test
  fun `no slug appears in anything the tool gives back`() =
    runBlocking {
      val student = createStudent()
      val results =
        listOf(
          tool.execute(student, input("""{"subjects":["Nursing","Marine Biology"]}""")),
          tool.execute(student, input("""{"look_up":"bio"}""")),
          tool.execute(student, input("""{"subjects":["bio"]}""")),
          tool.execute(student, input("""{"subjects":["underwater basket weaving"]}""")),
          tool.execute(student, input("""{"undecided":true}""")),
          tool.execute(student, input("""{"declined":true}""")),
        )

      // The slug is identity the model never has to speak (D39): the student
      // hears subject names, and a rename must never be visible as a stale word.
      results.forEach { result ->
        val text = result.toString()
        TAXONOMY.forEach { (slug, _, _) ->
          assertFalse(text.contains(slug), "slug [$slug] leaked into the tool result: $text")
        }
      }
      // The names DID reach the student, so the assertion above is not vacuous.
      assertEquals(listOf("Marine Biology", "Nursing"), namesIn(results.first()).sorted())
    }

  @Test
  fun `a declined false is refused rather than dropped, the rule the REST door reads too`() =
    runBlocking {
      val student = createStudent()
      // The doors count the keys that are PRESENT. A sent `false` is therefore a
      // surplus key and a malformed call at BOTH doors — never "leave it alone",
      // which omitting the key already says.
      val result = tool.execute(student, input("""{"subjects":["Nursing"],"declined":false}"""))

      val reason = errorOf(result)
      assertTrue(reason!!.contains("declined"), "the refusal names the offending key, got $result")
      assertTrue(reason.contains("must be true when present"), "got $reason")

      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student), "a refused call stores nothing")
      assertEquals(emptyList<String>(), storedSlugs(student))

      // The same body WITHOUT the surplus key is accepted, so the assertion
      // above is about the key and not about the rest of the call.
      val accepted = tool.execute(student, input("""{"subjects":["Nursing"]}"""))
      assertNull(errorOf(accepted), "got $accepted")
      assertEquals(listOf("registered-nursing"), storedSlugs(student))
    }

  @Test
  fun `a blank look_up is refused instead of being served the whole list`() =
    runBlocking {
      val student = createStudent()
      // Unguarded this became `name ILIKE '%%'` — the whole taxonomy handed back
      // as though the coach had asked for something. The REST door 400s a blank
      // `q` for the same reason, so this door refuses it too.
      listOf("""{"look_up":""}""", """{"look_up":"   "}""").forEach { body ->
        val result = tool.execute(student, input(body))
        val reason = errorOf(result)
        assertTrue(reason != null, "a blank look_up must be refused, got $result for $body")
        assertTrue(reason.contains("look_up"), "the refusal names the field, got $reason")
      }

      // A real term still works, so the guard refuses blankness and not look-ups.
      val served = tool.execute(student, input("""{"look_up":"bio"}"""))
      assertNull(errorOf(served), "got $served")
      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student), "a look-up stores nothing")
    }

  @Test
  fun `an over-long look_up is refused and the refusal names the cap`() =
    runBlocking {
      val student = createStudent()
      // The cap is READ from the vocabulary service, never written out here: a
      // number copied into the test would keep passing after the bound moved.
      val overLong = "x".repeat(SubjectVocabularyService.MAX_QUERY_LENGTH + 1)
      val result = tool.execute(student, input("""{"look_up":"$overLong"}"""))

      val reason = errorOf(result)
      assertTrue(reason != null, "got $result")
      assertTrue(
        reason.contains("${SubjectVocabularyService.MAX_QUERY_LENGTH}"),
        "the refusal names the bound, got $reason",
      )
      assertTrue(reason.contains("got ${overLong.length}"), "the refusal names the observed length, got $reason")

      // Exactly at the cap is accepted, so the bound is the bound and not one off it.
      val atCap = "x".repeat(SubjectVocabularyService.MAX_QUERY_LENGTH)
      assertNull(errorOf(tool.execute(student, input("""{"look_up":"$atCap"}"""))), "a term at the cap is a look-up")
    }

  @Test
  fun `a database failure comes back as an error result rather than a thrown exception`() =
    runBlocking {
      val student = createStudent()
      // A CLOSED pool is the cheapest real failure: every read and write through
      // it fails, exactly as a transient outage mid-conversation would. The
      // ChatTool contract is TOTAL, so the coach gets a sentence it can say
      // rather than a broken turn.
      val write = brokenTool.execute(student, input("""{"undecided":true}"""))
      assertEquals(IntendedSubjectsChatTool.WRITE_FAILED_REASON, errorOf(write), "got $write")

      val read = brokenTool.execute(student, input("""{"look_up":"bio"}"""))
      assertEquals(IntendedSubjectsChatTool.READ_FAILED_REASON, errorOf(read), "got $read")

      val resolveRead = brokenTool.execute(student, input("""{"subjects":["Nursing"]}"""))
      assertEquals(IntendedSubjectsChatTool.READ_FAILED_REASON, errorOf(resolveRead), "got $resolveRead")

      assertEquals(AnswerStatus.UNANSWERED, storedStatus(student), "a failed call stored nothing")
    }
}
