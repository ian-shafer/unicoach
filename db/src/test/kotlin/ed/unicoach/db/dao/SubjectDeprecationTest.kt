package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSubject
import ed.unicoach.db.models.SubjectStatus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CodebooksDao.reconcileSubjectsNotIn] — deprecation, not deletion (RFC 205
 * D40): the reload path that used to be a bare
 * `DELETE FROM subjects WHERE slug <> ALL (...)` now has to survive the first
 * foreign key onto `subjects`.
 *
 * NO COUNT OF THE TAXONOMY IS ASSERTED ANYWHERE HERE. The fixture authors its
 * own three-subject taxonomy, so the size of `db/data/subjects.json` is
 * irrelevant to whether this suite passes.
 */
class SubjectDeprecationTest {
  companion object {
    /**
     * The real two-digit CIP series for every slug this suite seeds. Never the
     * empty array: `subjects` guards `cip_prefixes` with both
     * `subjects_cip_prefixes_nonempty_check` and
     * `subjects_cip_prefixes_format_check` (0064), so `{}` is refused by the
     * database before any assertion here runs.
     */
    private val CIP_SERIES =
      mapOf(
        "nursing" to "51",
        "biology" to "26",
        "history" to "54",
      )

    private lateinit var connection: Connection

    /** A SECOND connection, for the one test that needs a concurrent writer. */
    private lateinit var otherConnection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
      otherConnection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
      if (::otherConnection.isInitialized && !otherConnection.isClosed) otherConnection.close()
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    otherConnection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE intended_subjects_versions, intended_subjects, " +
          "intended_subject_status_versions, intended_subject_status, money_profiles, students, users, subjects CASCADE",
      )
    }
    seedSubjects("nursing", "biology", "history")
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The suite's OWN taxonomy, written through the loader's own upsert. */
  private fun seedSubjects(vararg slugs: String) {
    slugs.forEach { slug ->
      CodebooksDao
        .upsertSubject(session, NewSubject(slug, slug.replaceFirstChar { it.uppercase() }, listOf(CIP_SERIES.getValue(slug))))
        .getOrThrow()
    }
  }

  private fun createStudent(): UUID {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'sd-$userId@test.com', 'SD User', 'ahash')",
      )
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_on, " +
          "expected_high_school_graduation_precision) " +
          "VALUES ('$studentId', '$userId', DATE '2028-01-01', 'year')",
      )
    }
    return studentId
  }

  /** Runs [body] in one transaction and commits — the boundary a real caller owns. */
  private fun <T> inTransaction(body: () -> T): T {
    connection.autoCommit = false
    return try {
      val result = body()
      connection.commit()
      result
    } catch (e: Exception) {
      // The rollback must not be able to REPLACE the real failure: a throwing
      // cleanup would leave you debugging the cleanup instead of the bug.
      // `Database.withConnection`'s house shape.
      try {
        connection.rollback()
      } catch (rollbackFailure: Exception) {
        e.addSuppressed(rollbackFailure)
      }
      throw e
    } finally {
      connection.autoCommit = true
    }
  }

  private fun exec(sql: String) = connection.createStatement().use { it.execute(sql) }

  private fun scalar(sql: String): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery(sql).use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun text(sql: String): String? =
    connection.createStatement().use { stmt ->
      stmt.executeQuery(sql).use { rs ->
        if (rs.next()) rs.getString(1) else null
      }
    }

  private fun statusOf(slug: String): String? = text("SELECT status FROM subjects WHERE slug = '$slug'")

  private fun updatedAt(slug: String): String? = text("SELECT updated_at::text FROM subjects WHERE slug = '$slug'")

  /** One student who has answered with [slug] — the reference that makes a drop a deprecation. */
  private fun answerWith(
    studentId: UUID,
    slug: String,
  ) = inTransaction {
    exec("INSERT INTO intended_subject_status (student_id, status) VALUES ('$studentId', 'answered')")
    exec("INSERT INTO intended_subjects (student_id, subject_slug) VALUES ('$studentId', '$slug')")
  }

  /** The student REMOVES their answer: the row is SOFT-deleted and the status stops being `answered`. */
  private fun removeAnswer(studentId: UUID) =
    inTransaction {
      // `version = version + 1` on both: `enforce_versioning` is OCC, so an
      // update that does not carry the next version is a 40001, not a write.
      exec(
        "UPDATE intended_subject_status SET status = 'undecided', version = version + 1 " +
          "WHERE student_id = '$studentId'",
      )
      exec(
        "UPDATE intended_subjects SET deleted_at = NOW(), version = version + 1 " +
          "WHERE student_id = '$studentId' AND deleted_at IS NULL",
      )
    }

  private fun reconcile(vararg keptSlugs: String): CodebooksDao.SubjectReconciliation =
    CodebooksDao.reconcileSubjectsNotIn(session, keptSlugs.toList()).getOrThrow()

  // ---------------------------------------------------------------------------
  // Delete or deprecate (D40)
  // ---------------------------------------------------------------------------

  @Test
  fun `a dropped subject nobody references is DELETED and one a student references is DEPRECATED`() {
    val student = createStudent()
    answerWith(student, "nursing")

    // `biology` and `history` both leave the file; only `nursing` is referenced.
    val reconciled = reconcile("nursing")

    assertEquals(2, reconciled.deleted, "the two unreferenced drops go, as they always did")
    assertEquals(0, reconciled.deprecated)
    assertEquals(listOf("nursing"), storedSlugs())

    // And now the referenced one is dropped too: the ingest must still SUCCEED.
    val dropped = reconcile("biology")
    assertEquals(0, dropped.deleted)
    assertEquals(1, dropped.deprecated)
    assertEquals(SubjectStatus.DEPRECATED.value, statusOf("nursing"))
    assertEquals(listOf("nursing"), storedSlugs(), "the row STAYS — the student's answer still points at it")
  }

  @Test
  fun `a subject the student ADDED AND LATER REMOVED still blocks the delete, because the row is only soft-deleted`() {
    val student = createStudent()
    answerWith(student, "nursing")
    removeAnswer(student)
    assertEquals(
      1,
      scalar("SELECT count(*) FROM intended_subjects WHERE subject_slug = 'nursing' AND deleted_at IS NOT NULL"),
      "the removal is a SOFT delete: the row, and therefore the foreign key, is still there",
    )
    assertEquals(
      0,
      scalar("SELECT count(*) FROM intended_subjects WHERE subject_slug = 'nursing' AND deleted_at IS NULL"),
      "and nothing LIVE references it, which is exactly the case a live-only count would get wrong",
    )

    // A reference count narrowed to live rows would classify `nursing` as
    // deletable, issue the DELETE, and kill the whole reload on
    // `violates RESTRICT setting of foreign key constraint
    // intended_subjects_subject_slug_fkey`. This call must SUCCEED.
    val reconciled = reconcile("biology", "history")

    assertEquals(0, reconciled.deleted)
    assertEquals(1, reconciled.deprecated)
    assertEquals(SubjectStatus.DEPRECATED.value, statusOf("nursing"))
    assertEquals(listOf("biology", "history", "nursing"), storedSlugs())
  }

  @Test
  fun `a second reload of the same file reports the same deprecated count and rewrites nothing`() {
    val student = createStudent()
    answerWith(student, "nursing")
    assertEquals(1, reconcile("biology", "history").deprecated)
    val deprecatedAt = updatedAt("nursing")

    val again = reconcile("biology", "history")
    assertEquals(1, again.deprecated, "a slug that is already deprecated is still a deprecated drop, not zero")
    assertEquals(0, again.deleted)
    assertEquals(deprecatedAt, updatedAt("nursing"), "an already-deprecated row must not be rewritten")
  }

  // ---------------------------------------------------------------------------
  // What a deprecated subject is, and is not
  // ---------------------------------------------------------------------------

  @Test
  fun `a deprecated subject still resolves to its name, is absent from the picker, and is absent from the vocabulary`() {
    val student = createStudent()
    answerWith(student, "nursing")
    reconcile("biology", "history")

    // It resolves for the student who already chose it — `subjectNames` is the
    // one read that must see a deprecated row.
    assertEquals(
      mapOf("nursing" to "Nursing"),
      CodebooksDao.subjectNames(session, listOf("nursing")).getOrThrow(),
    )

    // It is offered to nobody: not by the picker's search, not by the read the
    // chat tool's vocabulary is built from.
    assertEquals(
      listOf("biology", "history"),
      CodebooksDao.searchSubjects(session, nameQuery = null, limit = 100).getOrThrow().map { it.slug },
    )
    assertTrue(
      CodebooksDao.searchSubjects(session, nameQuery = "Nursing", limit = 100).getOrThrow().isEmpty(),
      "a deprecated subject must not come back even when the student types its exact name",
    )
    assertEquals(
      listOf("biology", "history"),
      CodebooksDao.subjects(session).getOrThrow().map { it.slug },
    )

    // The loader's report still counts it: the row IS in the table.
    assertEquals(3, CodebooksDao.subjectCount(session).getOrThrow())
  }

  @Test
  fun `a slug that reappears in the file goes back to active, and the round trip leaves no trace`() {
    val student = createStudent()
    answerWith(student, "nursing")
    reconcile("biology", "history")
    assertEquals(SubjectStatus.DEPRECATED.value, statusOf("nursing"))

    // The ordinary upsert restores it — there is no separate un-deprecate path
    // to forget to call.
    assertEquals(
      UpsertOutcome.CHANGED,
      CodebooksDao.upsertSubject(session, NewSubject("nursing", "Nursing", listOf("51"))).getOrThrow(),
    )
    assertEquals(SubjectStatus.ACTIVE.value, statusOf("nursing"))
    assertEquals(
      listOf("biology", "history", "nursing"),
      CodebooksDao.subjects(session).getOrThrow().map { it.slug },
      "it is offered again, exactly as it was before the drop",
    )

    // No trace of a state the taxonomy no longer claims: the next reload of the
    // restored file writes NOTHING, so the round trip is not a permanent
    // "this was once deprecated" mark on the row.
    assertEquals(
      UpsertOutcome.UNCHANGED,
      CodebooksDao.upsertSubject(session, NewSubject("nursing", "Nursing", listOf("51"))).getOrThrow(),
    )
    assertEquals(0, reconcile("nursing", "biology", "history").deprecated)
  }

  // ---------------------------------------------------------------------------
  // The column, and the error path
  // ---------------------------------------------------------------------------

  @Test
  fun `the subjects status CHECK accepts exactly active and deprecated and refuses a third value`() {
    SubjectStatus.entries.forEach { status ->
      exec("UPDATE subjects SET status = '${status.value}' WHERE slug = 'nursing'")
      assertEquals(status.value, statusOf("nursing"), "the CHECK must admit [${status.value}]")
    }
    val thrown = assertFailsWith<PSQLException> { exec("UPDATE subjects SET status = 'retired' WHERE slug = 'nursing'") }
    assertEquals("23514", thrown.sqlState)
    assertEquals("subjects_status_check", thrown.serverErrorMessage?.constraint)
  }

  @Test
  fun `a foreign-key violation from subjects names the slug and its dependants, not a bare SQL state`() {
    val student = createStudent()
    // Nobody references `nursing` when the classification is read, so it is
    // classified deletable — and a student answers between that read and the
    // DELETE. The backstop is the only thing left to say what happened.
    val racingSession =
      object : SqlSession {
        override fun prepareStatement(sql: String): PreparedStatement {
          if (sql.startsWith("DELETE FROM subjects")) answerOnOtherConnection(student, "nursing")
          return connection.prepareStatement(sql)
        }
      }

    val thrown =
      assertFailsWith<SubjectStillReferencedException> {
        CodebooksDao.reconcileSubjectsNotIn(racingSession, listOf("biology", "history")).getOrThrow()
      }
    // The FIELDS, not the prose. The slug and the dependant count are what a
    // caller acts on, so they are asserted as data; grepping the sentence for
    // them would only prove the payload was unstructured.
    assertEquals("nursing", thrown.slug)
    assertEquals(0, thrown.dependants["nursing"], "the count captured BEFORE the racing write, which is what it is for")
    // The SQL state alone is what this path exists to replace: the constraint
    // and the server's own DETAIL ride along, but the SENTENCE names the slug.
    assertEquals("intended_subjects_subject_slug_fkey", thrown.constraint)
    assertEquals(SubjectStatus.ACTIVE.value, statusOf("nursing"), "the failed reload changed nothing")
  }

  /**
   * The count is either KNOWN or absent, never rendered as `0`.
   *
   * `0` is the exact state in which the delete SUCCEEDS, so printing it for a
   * slug this run never classified tells the operator the opposite of what
   * happened. Constructed directly: the sentence is the unit under test here,
   * not the SQL path that reaches it.
   */
  @Test
  fun `a blocked slug the run never classified says so instead of claiming zero dependants`() {
    val unclassified = SubjectStillReferencedException(slug = "nursing", dependants = mapOf("biology" to 0))
    assertTrue(
      unclassified.message!!.contains("did not classify"),
      "an unknown count must be named as unknown: ${unclassified.message}",
    )
    assertFalse(
      unclassified.message!!.contains("referenced by 0"),
      "zero dependants is the state in which the delete would have worked: ${unclassified.message}",
    )

    val known = SubjectStillReferencedException(slug = "nursing", dependants = mapOf("nursing" to 2))
    assertTrue(
      known.message!!.contains("referenced by 2 intended-subject row(s)"),
      "a count this run DID read is still reported: ${known.message}",
    )
  }

  /** The concurrent writer: a student answers on ANOTHER connection, and commits. */
  private fun answerOnOtherConnection(
    studentId: UUID,
    slug: String,
  ) {
    otherConnection.autoCommit = false
    try {
      otherConnection.createStatement().use { stmt ->
        stmt.execute("INSERT INTO intended_subject_status (student_id, status) VALUES ('$studentId', 'answered')")
        stmt.execute("INSERT INTO intended_subjects (student_id, subject_slug) VALUES ('$studentId', '$slug')")
      }
      otherConnection.commit()
    } finally {
      otherConnection.autoCommit = true
    }
  }

  private fun storedSlugs(): List<String> {
    val slugs = mutableListOf<String>()
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT slug FROM subjects ORDER BY slug").use { rs ->
        while (rs.next()) slugs += rs.getString("slug")
      }
    }
    return slugs
  }
}
