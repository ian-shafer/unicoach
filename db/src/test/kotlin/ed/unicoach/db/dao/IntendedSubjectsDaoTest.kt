package ed.unicoach.db.dao

import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IntendedSubjectWrite
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The two RFC 205 tables, exercised against real Postgres: the CHECKs and
 * indexes, the version history, the immutability guards, the two DEFERRABLE
 * constraint triggers, and the [IntendedSubjectsDao] write path over them.
 *
 * NO COUNT OF EITHER LIST IS ASSERTED ANYWHERE HERE. The fixture builds its own
 * three-subject taxonomy, so the size of `subjects.json` and the size of the
 * federal programme list are both irrelevant to whether this suite passes — as
 * they should be, since neither is a fact this code depends on.
 */
class IntendedSubjectsDaoTest {
  companion object {
    private lateinit var connection: Connection

    /**
     * The real two-digit CIP series for every slug this suite seeds, so a seeded
     * row is a plausible taxonomy row rather than a shape that only happens to
     * satisfy the CHECK. Health Professions, Biological Sciences, History,
     * Physical Sciences.
     */
    private val CIP_SERIES =
      mapOf(
        "nursing" to "51",
        "biology" to "26",
        "history" to "54",
        "physics" to "40",
      )

    private lateinit var dbConfig: ed.unicoach.db.DatabaseConfig

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = newConnection()
    }

    /**
     * A SECOND, independent connection — the only way to write a genuinely
     * concurrent transaction, since the suite's own [connection] can hold one
     * transaction at a time.
     */
    @JvmStatic
    fun newConnection(): Connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE intended_subjects_versions, intended_subjects, " +
          "intended_subject_status_versions, intended_subject_status, money_profiles, students, users, subjects CASCADE",
      )
    }
    seedSubjects("nursing" to "Nursing", "biology" to "Biology", "history" to "History")
  }

  /**
   * The suite's OWN taxonomy: authored rows, nothing read from `db/data/subjects.json`.
   *
   * `cip_prefixes` carries each subject's REAL two-digit CIP series, not a
   * placeholder and never the empty array: `subjects` guards the column with
   * both `subjects_cip_prefixes_nonempty_check` and
   * `subjects_cip_prefixes_format_check` (0064), so an empty array is refused by
   * the database before any assertion in this suite runs. Nothing here reads the
   * column — it is seeded only because the table requires it to be well formed.
   */
  private fun seedSubjects(vararg rows: Pair<String, String>) {
    connection.prepareStatement("INSERT INTO subjects (slug, name, cip_prefixes) VALUES (?, ?, ?::TEXT[])").use { stmt ->
      rows.forEach { (slug, name) ->
        stmt.setString(1, slug)
        stmt.setString(2, name)
        stmt.setString(3, "{${CIP_SERIES.getValue(slug)}}")
        stmt.addBatch()
      }
      stmt.executeBatch()
    }
  }

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'is-$userId@test.com', 'IS User', 'ahash')",
      )
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_on, " +
          "expected_high_school_graduation_precision, date_of_birth) " +
          "VALUES ('$studentId', '$userId', DATE '2028-01-01', 'year', DATE '${StudentRowFixture.DEFAULT_DATE_OF_BIRTH}')",
      )
    }
    return StudentId(studentId)
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

  /**
   * Removes subjects the way EVERY writer on these tables must: an UPDATE that
   * sets `deleted_at` AND advances `version`. The shared `enforce_versioning`
   * trigger refuses an UPDATE whose `version` is not `OLD.version + 1` with a
   * 40001, so a bare `SET deleted_at = NOW()` is not a removal at all — it is a
   * serialisation failure.
   */
  private fun softDeleteSubjects(where: String) =
    exec("UPDATE intended_subjects SET deleted_at = NOW(), version = version + 1 WHERE $where")

  /**
   * The only well-formed way to give an answer: the status and the subject rows
   * move together, in ONE transaction. `answered` with zero subjects is refused
   * by the deferred agreement trigger, and under auto-commit every statement is
   * its own transaction, so setting the status first and inserting afterwards
   * is not a sequence the database ever permits.
   */
  private fun giveAnswer(
    studentId: StudentId,
    vararg slugs: String,
  ): List<String> =
    inTransaction {
      setStatus(studentId, AnswerStatus.ANSWERED.value)
      slugs.map { insertSubjectRow(studentId, it) }
    }

  private fun scalar(sql: String): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery(sql).use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun insertSubjectRow(
    studentId: StudentId,
    slug: String,
  ): String =
    connection
      .prepareStatement("INSERT INTO intended_subjects (student_id, subject_slug) VALUES (?, ?) RETURNING id")
      .use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setString(2, slug)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getString("id")
        }
      }

  private fun setStatus(
    studentId: StudentId,
    status: String,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO intended_subject_status (student_id, status) VALUES (?, ?)
        ON CONFLICT DO NOTHING
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setString(2, status)
        stmt.executeUpdate()
      }
    connection
      .prepareStatement(
        "UPDATE intended_subject_status SET status = ?, version = version + 1 " +
          "WHERE student_id = ? AND deleted_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, status)
        stmt.setObject(2, studentId.value)
        stmt.executeUpdate()
      }
  }

  // ---------------------------------------------------------------------------
  // Schema
  // ---------------------------------------------------------------------------

  @Test
  fun `the status CHECK accepts exactly the four answer values and refuses a fifth`() {
    val student = createStudent()
    // Each status is written in its OWN transaction, together with the subject
    // rows that status is allowed to hold. The agreement trigger is DEFERRED,
    // so under auto-commit it judges every individual statement: `answered`
    // holds one subject, the other three hold none.
    AnswerStatus.entries.forEach { status ->
      inTransaction {
        setStatus(student, status.value)
        if (status == AnswerStatus.ANSWERED) {
          insertSubjectRow(student, "nursing")
        } else {
          softDeleteSubjects("student_id = '${student.value}' AND deleted_at IS NULL")
        }
      }
      assertEquals(status.value, statusOf(student), "the CHECK must admit [${status.value}]")
    }
    val thrown =
      assertFailsWith<PSQLException> {
        connection
          .prepareStatement(
            "UPDATE intended_subject_status SET status = 'maybe', version = version + 1 WHERE student_id = ?",
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.executeUpdate()
          }
      }

    assertEquals("23514", thrown.sqlState)
    assertEquals("intended_subject_status_status_check", thrown.serverErrorMessage?.constraint)
  }

  @Test
  fun `the foreign key refuses a subject that is not in the taxonomy`() {
    val student = createStudent()
    val thrown = assertFailsWith<PSQLException> { insertSubjectRow(student, "underwater-basket-weaving") }
    assertEquals("23503", thrown.sqlState)
  }

  @Test
  fun `the partial unique index refuses a duplicate active subject and permits re-adding a removed one`() {
    val student = createStudent()
    val firstId =
      inTransaction {
        setStatus(student, AnswerStatus.ANSWERED.value)
        insertSubjectRow(student, "nursing")
      }

    val thrown = assertFailsWith<PSQLException> { insertSubjectRow(student, "nursing") }
    assertEquals("23505", thrown.sqlState)
    assertEquals("intended_subjects_student_subject_active_idx", thrown.serverErrorMessage?.constraint)

    // Remove-then-re-add in ONE transaction: `answered` with zero subjects is
    // refused, and between the two statements that is exactly the state.
    val secondId =
      inTransaction {
        softDeleteSubjects("id = '$firstId'")
        insertSubjectRow(student, "nursing")
      }
    // A NEW row with its own history, never a resurrection of the removed one.
    assertNotEquals(firstId, secondId)
    assertEquals(1, scalar("SELECT version FROM intended_subjects WHERE id = '$secondId'"))
  }

  // ---------------------------------------------------------------------------
  // The history — the college_list_entries pattern
  // ---------------------------------------------------------------------------

  @Test
  fun `an insert and every later update are snapshotted, and a physical delete is refused`() {
    val student = createStudent()
    val id = giveAnswer(student, "nursing").single()
    assertEquals(1, scalar("SELECT count(*) FROM intended_subjects_versions WHERE id = '$id'"))

    // The later UPDATE is snapshotted too — one history row per committed state.
    inTransaction {
      setStatus(student, AnswerStatus.UNDECIDED.value)
      softDeleteSubjects("id = '$id'")
    }
    assertEquals(2, scalar("SELECT count(*) FROM intended_subjects_versions WHERE id = '$id'"))
    assertEquals(2, scalar("SELECT version FROM intended_subjects WHERE id = '$id'"))

    val thrown = assertFailsWith<PSQLException> { exec("DELETE FROM intended_subjects WHERE id = '$id'") }
    assertContains(thrown.message!!, "Physical deletions are strictly blocked")
  }

  @Test
  fun `removing a subject writes a versions row whose deleted_at is SET`() {
    val student = createStudent()
    val id = giveAnswer(student, "nursing").single()
    inTransaction {
      setStatus(student, AnswerStatus.UNDECIDED.value)
      softDeleteSubjects("id = '$id'")
    }

    // The removal ITSELF is in the history, not merely the last state before it.
    assertEquals(
      1,
      scalar("SELECT count(*) FROM intended_subjects_versions WHERE id = '$id' AND deleted_at IS NOT NULL"),
      "the versions table must record the removal, not stop at the row before it",
    )
    assertEquals(2, scalar("SELECT count(*) FROM intended_subjects_versions WHERE id = '$id'"))
  }

  @Test
  fun `a versions row keeps the live row alive - ON DELETE RESTRICT`() {
    val student = createStudent()
    val id = giveAnswer(student, "nursing").single()
    // The physical delete is refused by the trigger first; disabling that guard
    // is not something a caller can do, so the RESTRICT is asserted by the
    // foreign key's own presence on the history table.
    assertEquals(
      1,
      scalar(
        """
        SELECT count(*) FROM information_schema.referential_constraints rc
        JOIN information_schema.table_constraints tc ON tc.constraint_name = rc.constraint_name
        WHERE tc.table_name = 'intended_subjects_versions' AND rc.delete_rule = 'RESTRICT'
        """.trimIndent(),
      ),
      "the history must reference the live row ON DELETE RESTRICT, so it cannot be orphaned",
    )
    assertTrue(scalar("SELECT count(*) FROM intended_subjects_versions WHERE id = '$id'") > 0)
  }

  @Test
  fun `a student's whole plan history is readable from the versions table alone`() {
    val student = createStudent()
    val nursing = giveAnswer(student, "nursing", "biology").first()
    // Biology stays, so `answered` still holds a subject after the removal.
    softDeleteSubjects("id = '$nursing'")

    // "What was she planning on date X" is answerable without touching the live
    // table: every committed state of every row is here, removal included.
    assertEquals(
      3,
      scalar(
        "SELECT count(*) FROM intended_subjects_versions WHERE student_id = '${student.value}'",
      ),
    )
  }

  // ---------------------------------------------------------------------------
  // Immutability
  // ---------------------------------------------------------------------------

  @Test
  fun `student_id and subject_slug are immutable, and deleted_at is not`() {
    val student = createStudent()
    val other = createStudent()
    val id = giveAnswer(student, "nursing").single()

    // Both attempts advance `version` correctly, so the ONLY thing that can
    // refuse them is the column guard.
    val movedStudent =
      assertFailsWith<PSQLException> {
        exec("UPDATE intended_subjects SET student_id = '${other.value}', version = version + 1 WHERE id = '$id'")
      }
    assertContains(movedStudent.message!!, "student_id field is immutable")

    val movedSubject =
      assertFailsWith<PSQLException> {
        exec("UPDATE intended_subjects SET subject_slug = 'biology', version = version + 1 WHERE id = '$id'")
      }
    assertContains(movedSubject.message!!, "subject_slug field is immutable")

    // The guard freezes the identity columns and NOTHING else: removing the
    // subject is exactly the update it must still allow.
    inTransaction {
      setStatus(student, AnswerStatus.UNDECIDED.value)
      softDeleteSubjects("id = '$id'")
    }
    assertEquals(1, scalar("SELECT count(*) FROM intended_subjects WHERE id = '$id' AND deleted_at IS NOT NULL"))
  }

  @Test
  fun `the status table freezes student_id and lets status change`() {
    val student = createStudent()
    val other = createStudent()
    setStatus(student, AnswerStatus.DECLINED.value)

    val thrown =
      assertFailsWith<PSQLException> {
        exec(
          "UPDATE intended_subject_status SET student_id = '${other.value}', version = version + 1 " +
            "WHERE student_id = '${student.value}'",
        )
      }
    assertContains(thrown.message!!, "student_id field is immutable")

    setStatus(student, AnswerStatus.UNDECIDED.value)
    assertEquals(AnswerStatus.UNDECIDED.value, statusOf(student))
  }

  // ---------------------------------------------------------------------------
  // The two deferred invariants
  // ---------------------------------------------------------------------------

  @Test
  fun `a fourth subject in one transaction is refused at COMMIT`() {
    val student = createStudent()
    val thrown =
      assertFailsWith<PSQLException> {
        inTransaction {
          setStatus(student, AnswerStatus.ANSWERED.value)
          insertSubjectRow(student, "nursing")
          insertSubjectRow(student, "biology")
          insertSubjectRow(student, "history")
          seedSubjects("physics" to "Physics")
          insertSubjectRow(student, "physics")
        }
      }
    assertContains(thrown.message!!, "at most ${IntendedSubjectsDao.MAX_SUBJECTS} intended subjects")
  }

  @Test
  fun `answered with zero subjects is refused, and a non-answered status holding a subject is refused`() {
    val student = createStudent()
    val empty =
      assertFailsWith<PSQLException> {
        inTransaction {
          setStatus(student, AnswerStatus.ANSWERED.value)
          insertSubjectRow(student, "nursing")
          softDeleteSubjects("student_id = '${student.value}'")
        }
      }
    assertContains(empty.message!!, "requires at least one subject")

    listOf(AnswerStatus.DECLINED, AnswerStatus.UNDECIDED, AnswerStatus.UNANSWERED).forEach { status ->
      val held =
        assertFailsWith<PSQLException> {
          inTransaction {
            setStatus(student, AnswerStatus.ANSWERED.value)
            insertSubjectRow(student, "biology")
            setStatus(student, status.value)
          }
        }
      assertContains(held.message!!, "only held by an answered status")
    }
  }

  @Test
  fun `a one-transaction swap of one subject for another SUCCEEDS`() {
    val student = createStudent()
    giveAnswer(student, "nursing")
    // Mid-transaction the two tables are legitimately inconsistent; DEFERRABLE
    // INITIALLY DEFERRED is exactly what makes this legal, and an immediate
    // constraint would have refused it.
    inTransaction {
      softDeleteSubjects("student_id = '${student.value}' AND subject_slug = 'nursing'")
      insertSubjectRow(student, "biology")
    }
    assertEquals(
      listOf("biology"),
      IntendedSubjectsDao.listActiveByStudent(session, student).getOrThrow().map { it.slug },
    )
  }

  // ---------------------------------------------------------------------------
  // Money is untouched
  // ---------------------------------------------------------------------------

  @Test
  fun `the database refuses undecided on each of the three money status columns`() {
    val student = createStudent()
    listOf("income_band_status", "residency_status", "living_plan_status").forEach { column ->
      val thrown =
        assertFailsWith<PSQLException> {
          exec(
            "INSERT INTO money_profiles (student_id, $column) " +
              "VALUES ('${student.value}', '${AnswerStatus.UNDECIDED.value}')",
          )
        }
      assertEquals(
        "23514",
        thrown.sqlState,
        "money_profiles.$column must refuse [${AnswerStatus.UNDECIDED.value}]: it is an intended-subject answer",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // The DAO's replace-the-whole-set write
  // ---------------------------------------------------------------------------

  @Test
  fun `a student nobody has asked reads as unanswered with no subjects`() {
    val student = createStudent()
    val answer = IntendedSubjectsDao.readAnswer(session, student).getOrThrow()
    assertEquals(AnswerStatus.UNANSWERED, answer.status)
    assertTrue(answer.subjects.isEmpty())
  }

  @Test
  fun `replacing with subjects stores them with their names, in name order`() {
    val student = createStudent()
    val answer =
      inTransaction {
        IntendedSubjectsDao
          .replaceAnswer(session, student, IntendedSubjectWrite.Subjects(listOf("nursing", "biology")))
          .getOrThrow()
      }
    assertEquals(AnswerStatus.ANSWERED, answer.status)
    assertEquals(listOf("Biology", "Nursing"), answer.subjects.map { it.name })
    assertEquals(listOf("biology", "nursing"), answer.subjects.map { it.slug })
  }

  @Test
  fun `declining after answering drops the rows, and re-answering restores them`() {
    val student = createStudent()
    inTransaction {
      IntendedSubjectsDao.replaceAnswer(session, student, IntendedSubjectWrite.Subjects(listOf("nursing"))).getOrThrow()
    }
    val declined =
      inTransaction { IntendedSubjectsDao.replaceAnswer(session, student, IntendedSubjectWrite.Declined).getOrThrow() }
    assertEquals(AnswerStatus.DECLINED, declined.status)
    assertTrue(declined.subjects.isEmpty())

    val again =
      inTransaction {
        IntendedSubjectsDao
          .replaceAnswer(session, student, IntendedSubjectWrite.Subjects(listOf("nursing")))
          .getOrThrow()
      }
    assertEquals(AnswerStatus.ANSWERED, again.status)
    assertEquals(listOf("nursing"), again.subjects.map { it.slug })
    // The restored subject is a NEW row: the removal's history is untouched.
    assertEquals(2, scalar("SELECT count(*) FROM intended_subjects WHERE student_id = '${student.value}'"))
  }

  @Test
  fun `undecided stores no rows and is not the same state as declined`() {
    val student = createStudent()
    val undecided =
      inTransaction { IntendedSubjectsDao.replaceAnswer(session, student, IntendedSubjectWrite.Undecided).getOrThrow() }
    assertEquals(AnswerStatus.UNDECIDED, undecided.status)
    assertNotEquals(AnswerStatus.DECLINED, undecided.status)
    assertTrue(undecided.subjects.isEmpty())
  }

  @Test
  fun `re-submitting an unchanged answer advances no version and writes no history`() {
    val student = createStudent()
    inTransaction {
      IntendedSubjectsDao.replaceAnswer(session, student, IntendedSubjectWrite.Subjects(listOf("nursing"))).getOrThrow()
    }
    val versionsBefore = scalar("SELECT count(*) FROM intended_subjects_versions WHERE student_id = '${student.value}'")
    val statusVersionsBefore =
      scalar("SELECT count(*) FROM intended_subject_status_versions WHERE student_id = '${student.value}'")
    inTransaction {
      IntendedSubjectsDao.replaceAnswer(session, student, IntendedSubjectWrite.Subjects(listOf("nursing"))).getOrThrow()
    }
    assertEquals(versionsBefore, scalar("SELECT count(*) FROM intended_subjects_versions WHERE student_id = '${student.value}'"))
    assertEquals(
      statusVersionsBefore,
      scalar("SELECT count(*) FROM intended_subject_status_versions WHERE student_id = '${student.value}'"),
      "writing the same status over itself would put a state change in the history that never happened",
    )
  }

  @Test
  fun `the status row is created at unanswered and no write leaves it there`() {
    val student = createStudent()
    inTransaction { IntendedSubjectsDao.replaceAnswer(session, student, IntendedSubjectWrite.Undecided).getOrThrow() }
    assertNotEquals(
      AnswerStatus.UNANSWERED.value,
      statusOf(student),
      "unanswered is only ever the state of a student who was never asked",
    )
  }

  private fun statusOf(studentId: StudentId): String? =
    connection
      .prepareStatement("SELECT status FROM intended_subject_status WHERE student_id = ? AND deleted_at IS NULL")
      .use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("status") else null }
      }

  /**
   * The cap belongs to the DATABASE, not to one careful caller.
   *
   * Two genuinely concurrent transactions, on two independent connections, each
   * add a subject to a student who already holds two. Neither sees the other's
   * uncommitted row, so each passes its own pre-check; the cap trigger is
   * DEFERRED and runs at COMMIT in a fresh snapshot, so whichever commits
   * second sees four rows and is refused. Nothing running on the suite's single
   * shared connection could have shown this — sequential transactions on one
   * connection prove only that a caller counted correctly.
   */
  @Test
  fun `two concurrent writers cannot land four subjects between them`() {
    val student = createStudent()
    seedSubjects("physics" to "Physics")
    giveAnswer(student, "nursing", "biology")

    val second = newConnection()
    try {
      connection.autoCommit = false
      second.autoCommit = false
      insertSubjectRow(student, "history")
      insertSubjectRowOn(second, student, "physics")

      // The first to commit lands its third row; it is within the cap.
      connection.commit()
      val thrown = assertFailsWith<PSQLException> { second.commit() }
      assertContains(thrown.message!!, "at most ${IntendedSubjectsDao.MAX_SUBJECTS} intended subjects")
    } finally {
      connection.autoCommit = true
      second.close()
    }

    assertEquals(
      3,
      scalar(
        "SELECT count(*) FROM intended_subjects " +
          "WHERE student_id = '${student.value}' AND deleted_at IS NULL",
      ),
      "the two writers must not have landed four rows between them",
    )
  }

  /**
   * The FIRST write for one student, from two genuinely concurrent
   * transactions: neither can see the other's uncommitted status row, so the
   * second one collides with the partial unique index
   * `intended_subject_status_student_active_idx`.
   *
   * `ON CONFLICT (student_id) WHERE deleted_at IS NULL DO NOTHING` makes that
   * collision a WAIT — the loser blocks until the winner commits, then locks
   * the winner's row and writes its own status. A `WHERE NOT EXISTS` guard
   * cannot do this: it is judged against the loser's own snapshot, which is
   * exactly the snapshot the winner's row is not in, so the loser used to abort
   * with a `23505` no caller could act on.
   */
  @Test
  fun `two writers creating the first status row for one student do not collide`() {
    val student = createStudent()
    val second = newConnection()
    val secondSession =
      object : SqlSession {
        override fun prepareStatement(sql: String): PreparedStatement = second.prepareStatement(sql)
      }
    try {
      connection.autoCommit = false
      second.autoCommit = false
      // The winner creates the status row and HOLDS it, uncommitted.
      IntendedSubjectsDao.replaceAnswer(session, student, IntendedSubjectWrite.Undecided).getOrThrow()

      var loserFailure: Throwable? = null
      val loser =
        Thread {
          loserFailure =
            IntendedSubjectsDao
              .replaceAnswer(secondSession, student, IntendedSubjectWrite.Declined)
              .exceptionOrNull()
        }
      loser.start()
      // Long enough for the loser to reach the index and block on it; if it
      // arrives after the commit instead, the conflict clause is still what
      // keeps it from failing, so the assertion below holds either way.
      Thread.sleep(500)
      connection.commit()
      loser.join(30_000)
      assertEquals(null, loserFailure, "the second writer must WAIT for the first, not fail")
      second.commit()
    } finally {
      connection.autoCommit = true
      second.close()
    }
    assertEquals(
      AnswerStatus.DECLINED.value,
      statusOf(student),
      "the writer that waited must then have written its own answer over the row it locked",
    )
    assertEquals(
      1,
      scalar("SELECT count(*) FROM intended_subject_status WHERE student_id = '${student.value}' AND deleted_at IS NULL"),
      "one live status row per student, whatever the interleaving",
    )
  }

  /**
   * The lock is PROVEN, not assumed: a `FOR UPDATE` that finds no row holds
   * nothing, and every serialisation claim downstream would be false with no
   * error to show for it.
   *
   * The absent row is injected, because the DAO's own insert makes it
   * unreachable by any honest route — which is the point: if it ever becomes
   * reachable, the write refuses by NAME instead of writing a status onto
   * nothing.
   */
  @Test
  fun `a status row that cannot be locked is refused by name`() {
    val student = createStudent()
    val lostLockSession =
      object : SqlSession {
        override fun prepareStatement(sql: String): PreparedStatement =
          connection.prepareStatement(
            if (sql.contains("FOR UPDATE")) sql.replace("FOR UPDATE", "AND FALSE FOR UPDATE") else sql,
          )
      }
    val thrown =
      assertFailsWith<NotFoundException> {
        inTransaction {
          IntendedSubjectsDao.replaceAnswer(lostLockSession, student, IntendedSubjectWrite.Undecided).getOrThrow()
        }
      }
    assertContains(
      thrown.message!!,
      student.value.toString(),
      message = "the refusal must name the student it could not lock",
    )
    assertEquals(
      null,
      statusOf(student),
      "the refused write wrote nothing: its own status insert rolled back with it",
    )
  }

  private fun insertSubjectRowOn(
    target: Connection,
    studentId: StudentId,
    slug: String,
  ) {
    target.prepareStatement("INSERT INTO intended_subjects (student_id, subject_slug) VALUES (?, ?)").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.setString(2, slug)
      stmt.executeUpdate()
    }
  }
}
