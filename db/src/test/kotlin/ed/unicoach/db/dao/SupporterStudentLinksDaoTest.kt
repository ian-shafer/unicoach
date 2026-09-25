package ed.unicoach.db.dao

import ed.unicoach.db.models.LinkedStudent
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.SupporterStudentLinkId
import ed.unicoach.db.models.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-DB coverage for `supporter_student_links` (RFC 221), on the
 * [CostReportSharesDaoTest] pattern: one connection in @BeforeAll, a truncate
 * before each test, [StudentRowFixture] for students and a hand-written `users`
 * insert.
 *
 * The many-to-many shape and the re-link are proven by BEHAVIOUR, not by reading
 * the DDL back. The two catalogue assertions here say WHY the two unique indexes
 * differ, so a later "consistency" change to either one fails loudly.
 */
class SupporterStudentLinksDaoTest {
  companion object {
    private lateinit var connection: Connection

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
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE supporter_student_links, supporters, students, users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  /**
   * [name] is a parameter because `users.name` is the ONLY student column the
   * roster read ([SupporterStudentLinksDao.listActiveLinkedStudents]) returns,
   * so a test that cannot vary it cannot tell one student's name from another's.
   */
  private fun createUser(name: String = "Link User"): UserId {
    val userId = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, ?, 'ahash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "link-$userId@test.com")
        stmt.setString(3, name)
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  private fun createStudent(userId: UserId = createUser()): StudentId {
    val studentId = UUID.randomUUID()
    StudentRowFixture.insert(connection, studentId, userId.value)
    return StudentId(studentId)
  }

  private fun createSupporter(userId: UserId = createUser()): Supporter = SupportersDao.create(session, NewSupporter(userId)).getOrThrow()

  private fun link(
    supporter: SupporterId,
    student: StudentId,
    relationship: SupporterRelationship = SupporterRelationship.PARENT,
  ) = SupporterStudentLinksDao.create(session, NewSupporterStudentLink(supporter, student, relationship))

  /**
   * Closes a student through the sanctioned writer, at the version the row
   * actually holds.
   *
   * Read, never a literal `1`: `students.version` is another table's token, and
   * a test that hard-codes it passes only for as long as nothing has touched
   * the fixture row — after which `delete` fails as a stale write and the test
   * fails for a reason that has nothing to do with links.
   */
  private fun closeStudent(studentId: StudentId) {
    val current = StudentsDao.findById(session, studentId).getOrThrow()
    StudentsDao.delete(session, studentId, current.version).getOrThrow()
  }

  /**
   * Closes a `users` row through the sanctioned writer, at the version the row
   * actually holds — [closeStudent]'s reason, on the other table.
   *
   * The `students` row is left LIVE on purpose: this is the state the roster's
   * `u.deleted_at IS NULL` predicate exists for, and closing both would prove
   * the student predicate again instead.
   */
  private fun closeUser(userId: UserId) {
    val current = UsersDao.findById(session, userId).getOrThrow()
    UsersDao.delete(session, userId, current.version).getOrThrow()
  }

  private fun deletedAtOf(id: UUID): Instant? {
    connection.prepareStatement("SELECT deleted_at FROM supporter_student_links WHERE id = ?").use { stmt ->
      stmt.setObject(1, id)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next(), "the row must be there")
        return rs.getTimestamp("deleted_at")?.toInstant()
      }
    }
  }

  /** The stored word and version, read straight from the table — severed rows included. */
  private fun relationshipAndVersionOf(id: UUID): Pair<String, Int> {
    connection.prepareStatement("SELECT relationship, version FROM supporter_student_links WHERE id = ?").use { stmt ->
      stmt.setObject(1, id)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next(), "the row must be there")
        return rs.getString("relationship") to rs.getInt("version")
      }
    }
  }

  /** Advances the row one version, so the caller's original version is stale. */
  private fun updateRelationship(
    link: SupporterStudentLink,
    relationship: SupporterRelationship,
  ): SupporterStudentLink = SupporterStudentLinksDao.updateRelationship(session, link.id, link.version, relationship).getOrThrow()

  /**
   * Many-to-many in both directions, proven by behaviour rather than read off
   * the schema: one student with two supporters, one supporter with two
   * students, and a severed link in neither list.
   */
  @Test
  fun `a student has many supporters and a supporter has many students`() {
    val studentA = createStudent()
    val studentB = createStudent()
    val supporter1 = createSupporter()
    val supporter2 = createSupporter()
    val supporter3 = createSupporter()

    val oneA = link(supporter1.id, studentA, SupporterRelationship.PARENT).getOrThrow()
    val twoA = link(supporter2.id, studentA, SupporterRelationship.GRANDPARENT).getOrThrow()
    val oneB = link(supporter1.id, studentB, SupporterRelationship.GUARDIAN).getOrThrow()
    val severed = link(supporter3.id, studentA, SupporterRelationship.SUPPORTER).getOrThrow()
    SupporterStudentLinksDao.unlink(session, severed.id, severed.version).getOrThrow()

    assertEquals(
      setOf(oneA.id, twoA.id),
      SupporterStudentLinksDao
        .listForStudent(session, studentA)
        .getOrThrow()
        .map { it.id }
        .toSet(),
      "a student's supporters, and never the severed one",
    )
    assertEquals(
      setOf(oneA.id, oneB.id),
      SupporterStudentLinksDao
        .listForSupporter(session, supporter1.id)
        .getOrThrow()
        .map { it.id }
        .toSet(),
      "a supporter's students",
    )
    assertEquals(
      emptyList(),
      SupporterStudentLinksDao.listForSupporter(session, supporter3.id).getOrThrow(),
      "an unlinked supporter holds nothing",
    )
    assertEquals(
      SupporterRelationship.GRANDPARENT,
      assertNotNull(SupporterStudentLinksDao.findActive(session, supporter2.id, studentA).getOrThrow()).relationship,
      "the relationship word round-trips",
    )
  }

  /**
   * The reason the active index is PARTIAL (D2): unlink is a soft delete and
   * re-linking must succeed. This test FAILS against a total unique index, which
   * is exactly what it is for. The re-link is a NEW row with its own
   * `created_at`, so the severed period stays visible instead of being erased by
   * resurrecting the old one.
   */
  @Test
  fun `unlink then re-link succeeds and only the new row is active`() {
    val student = createStudent()
    val supporter = createSupporter()
    val first = link(supporter.id, student).getOrThrow()

    val severed = SupporterStudentLinksDao.unlink(session, first.id, first.version).getOrThrow()
    assertNotNull(severed.deletedAt, "unlink stamps deleted_at")
    assertNull(SupporterStudentLinksDao.findActive(session, supporter.id, student).getOrThrow())

    val second = link(supporter.id, student, SupporterRelationship.GUARDIAN).getOrThrow()

    assertNotEquals(first.id, second.id, "a re-link is a NEW row, never a resurrected one")
    assertNull(second.deletedAt)
    assertEquals(1, second.version, "the new row starts its own history")
    assertEquals(
      listOf(second.id),
      SupporterStudentLinksDao.listForStudent(session, student).getOrThrow().map { it.id },
      "only the new row is active",
    )
    assertNotNull(deletedAtOf(first.id.value), "the severed row is kept, not replaced")
  }

  @Test
  fun `a second active link for the same pair is refused`() {
    val student = createStudent()
    val supporter = createSupporter()
    link(supporter.id, student).getOrThrow()

    val second = link(supporter.id, student)

    assertTrue(second.isFailure, "one ACTIVE link per (supporter, student), got [$second]")
    assertEquals(
      "supporter_student_links_supporter_student_active_idx",
      (second.exceptionOrNull() as? ConstraintViolationException)?.constraint,
      "Expected the active-pair index to refuse it, got [${second.exceptionOrNull()}]",
    )
  }

  /**
   * `relationship` is not part of identity: a mother recorded as `parent` and
   * then corrected to `guardian` is one relationship throughout, and a key that
   * included the word would let the correction exist twice.
   */
  @Test
  fun `two links for the same pair differing only in relationship are refused`() {
    val student = createStudent()
    val supporter = createSupporter()
    link(supporter.id, student, SupporterRelationship.PARENT).getOrThrow()

    val second = link(supporter.id, student, SupporterRelationship.GUARDIAN)

    assertTrue(second.isFailure, "the word does not make a second link, got [$second]")
    assertEquals(
      "supporter_student_links_supporter_student_active_idx",
      (second.exceptionOrNull() as? ConstraintViolationException)?.constraint,
    )
  }

  /**
   * The behavioural tests above prove re-linking works; this one states WHY,
   * against the catalogue, so a later "consistency" change to a total index
   * fails loudly instead of quietly breaking it. The opposite half — that
   * `supporters_user_id_unique_idx` is TOTAL — belongs to the table that owns
   * it and is asserted in [SupportersDaoTest].
   */
  @Test
  fun `the active-link index is partial`() {
    assertTrue(
      IndexCatalogueFixture.isPartial(connection, "supporter_student_links_supporter_student_active_idx"),
      "the active-pair index must be partial on deleted_at IS NULL, or re-linking becomes impossible",
    )
  }

  @Test
  fun `a relationship is corrected in place and the history holds both words`() {
    val student = createStudent()
    val supporter = createSupporter()
    val created = link(supporter.id, student, SupporterRelationship.PARENT).getOrThrow()

    val corrected =
      SupporterStudentLinksDao
        .updateRelationship(session, created.id, created.version, SupporterRelationship.GRANDPARENT)
        .getOrThrow()

    assertEquals(created.id, corrected.id, "a correction is the SAME row")
    assertEquals(SupporterRelationship.GRANDPARENT, corrected.relationship)
    assertEquals(created.version + 1, corrected.version, "version = version + 1 is not optional")

    val history = mutableListOf<String>()
    connection
      .prepareStatement(
        "SELECT relationship FROM supporter_student_links_versions WHERE id = ? ORDER BY version",
      ).use { stmt ->
        stmt.setObject(1, created.id.value)
        stmt.executeQuery().use { rs -> while (rs.next()) history.add(rs.getString("relationship")) }
      }

    assertEquals(listOf("parent", "grandparent"), history, "the old word stays readable in the history")
  }

  @Test
  fun `a stale version loses on updateRelationship`() {
    val student = createStudent()
    val supporter = createSupporter()
    val created = link(supporter.id, student, SupporterRelationship.PARENT).getOrThrow()
    val current = updateRelationship(created, SupporterRelationship.GUARDIAN)

    assertIs<ConcurrentModificationException>(
      SupporterStudentLinksDao
        .updateRelationship(session, created.id, created.version, SupporterRelationship.SUPPORTER)
        .exceptionOrNull(),
      "a stale version is a lost race, not an absent row",
    )

    val live = assertNotNull(SupporterStudentLinksDao.findActive(session, supporter.id, student).getOrThrow())
    assertEquals(SupporterRelationship.GUARDIAN, live.relationship, "the stale correction changed nothing")
    assertEquals(current.version, live.version)
  }

  @Test
  fun `a stale version loses on unlink`() {
    val student = createStudent()
    val supporter = createSupporter()
    val created = link(supporter.id, student, SupporterRelationship.PARENT).getOrThrow()
    val current = updateRelationship(created, SupporterRelationship.GUARDIAN)

    assertIs<ConcurrentModificationException>(
      SupporterStudentLinksDao.unlink(session, created.id, created.version).exceptionOrNull(),
      "a stale version cannot unlink, and loses as a race rather than as an absence",
    )

    val live = assertNotNull(SupporterStudentLinksDao.findActive(session, supporter.id, student).getOrThrow())
    assertNull(live.deletedAt, "the stale unlink severed nothing")
    assertEquals(current.version, live.version)
  }

  /**
   * A second unlink reports nothing rather than re-severing: re-stamping
   * `deleted_at` would move the moment access ended.
   */
  @Test
  fun `a second unlink reports no rows and does not restamp deleted_at`() {
    val student = createStudent()
    val supporter = createSupporter()
    val created = link(supporter.id, student).getOrThrow()

    val severed = SupporterStudentLinksDao.unlink(session, created.id, created.version).getOrThrow()
    val stampedAt = assertNotNull(deletedAtOf(created.id.value))

    assertIs<ConcurrentModificationException>(
      SupporterStudentLinksDao.unlink(session, severed.id, severed.version).exceptionOrNull(),
      "an already-severed link is a race somebody else won",
    )
    assertEquals(stampedAt, deletedAtOf(created.id.value), "the first unlink's timestamp stands")
  }

  /**
   * The other half: a wrong id is NOT a lost race. Both writes sit on the spine
   * every later slice authorises against, and a caller that cannot tell "you
   * named a link that does not exist" from "somebody beat you to it" retries
   * forever or gives up wrongly.
   */
  @Test
  fun `unlink and updateRelationship report an id no link has as not found`() {
    val absent = SupporterStudentLinkId(UUID.randomUUID())

    assertIs<NotFoundException>(
      SupporterStudentLinksDao.unlink(session, absent, 1).exceptionOrNull(),
      "no such link is not a concurrency failure",
    )
    assertIs<NotFoundException>(
      SupporterStudentLinksDao
        .updateRelationship(session, absent, 1, SupporterRelationship.GUARDIAN)
        .exceptionOrNull(),
      "no such link is not a concurrency failure",
    )
  }

  /**
   * A severed link is history, and the history table exists so the past is not
   * rewritten. A re-link is a new row and takes the correct word at insert, so
   * a correction has no business reaching a row whose `deleted_at` is set.
   */
  @Test
  fun `updateRelationship refuses a severed link`() {
    val student = createStudent()
    val supporter = createSupporter()
    val created = link(supporter.id, student, SupporterRelationship.PARENT).getOrThrow()
    val severed = SupporterStudentLinksDao.unlink(session, created.id, created.version).getOrThrow()

    assertIs<ConcurrentModificationException>(
      SupporterStudentLinksDao
        .updateRelationship(session, severed.id, severed.version, SupporterRelationship.GUARDIAN)
        .exceptionOrNull(),
      "a correction applies to a LIVE link only; the row is there, so it is not a NotFound",
    )

    assertEquals(
      "parent" to severed.version,
      relationshipAndVersionOf(created.id.value),
      "the severed row's word and version stand exactly as they were severed",
    )
  }

  @Test
  fun `an update that does not bump version is refused`() {
    val student = createStudent()
    val supporter = createSupporter()
    val created = link(supporter.id, student).getOrThrow()

    val failure =
      assertFailsWith<SQLException> {
        connection.prepareStatement("UPDATE supporter_student_links SET deleted_at = NOW() WHERE id = ?").use { stmt ->
          stmt.setObject(1, created.id.value)
          stmt.executeUpdate()
        }
      }

    assertEquals("40001", failure.sqlState, "enforce_versioning refuses an UPDATE that does not advance version")
    assertNotNull(
      SupporterStudentLinksDao.findActive(session, supporter.id, student).getOrThrow(),
      "the refused update must change nothing",
    )
  }

  @Test
  fun `a physical delete is refused`() {
    val student = createStudent()
    val supporter = createSupporter()
    val created = link(supporter.id, student).getOrThrow()

    val failure =
      assertFailsWith<SQLException> {
        connection.prepareStatement("DELETE FROM supporter_student_links WHERE id = ?").use { stmt ->
          stmt.setObject(1, created.id.value)
          stmt.executeUpdate()
        }
      }

    assertEquals("P0001", failure.sqlState, "links are soft-deleted, never removed")
    assertNotNull(
      SupporterStudentLinksDao.findActive(session, supporter.id, student).getOrThrow(),
      "the row must still be there",
    )
  }

  /**
   * A student is not their own supporter. The rule spans two tables, so it is
   * the `trigger_00d` guard rather than a CHECK, and it is enforced here rather
   * than in the invite path so the SECOND writer has it too.
   */
  @Test
  fun `a self-link is refused`() {
    val user = createUser()
    val student = createStudent(user)
    val supporter = createSupporter(user)

    val result = link(supporter.id, student)

    val refusal =
      assertIs<SelfSupporterLinkException>(
        result.exceptionOrNull(),
        "a student may not be their own supporter, got [$result]",
      )
    assertEquals(
      "supporter_student_links_not_self_link",
      refusal.constraint,
      "all three guard refusals are P0001; the CONSTRAINT name is what tells them apart",
    )
    assertTrue(
      refusal.message?.contains(supporter.id.asString) == true &&
        refusal.message?.contains(student.asString) == true,
      "the refusal names both endpoints, got [${refusal.message}]",
    )
    assertEquals(emptyList(), SupporterStudentLinksDao.listForStudent(session, student).getOrThrow())
  }

  @Test
  fun `supporter_id and student_id are immutable`() {
    val student = createStudent()
    val otherStudent = createStudent()
    val supporter = createSupporter()
    val otherSupporter = createSupporter()
    val created = link(supporter.id, student).getOrThrow()

    val movedSupporter =
      assertFailsWith<SQLException> {
        connection
          .prepareStatement("UPDATE supporter_student_links SET supporter_id = ?, version = version + 1 WHERE id = ?")
          .use { stmt ->
            stmt.setObject(1, otherSupporter.id.value)
            stmt.setObject(2, created.id.value)
            stmt.executeUpdate()
          }
      }
    assertEquals("P0001", movedSupporter.sqlState, "a different pair is a different link")

    val movedStudent =
      assertFailsWith<SQLException> {
        connection
          .prepareStatement("UPDATE supporter_student_links SET student_id = ?, version = version + 1 WHERE id = ?")
          .use { stmt ->
            stmt.setObject(1, otherStudent.value)
            stmt.setObject(2, created.id.value)
            stmt.executeUpdate()
          }
      }
    assertEquals("P0001", movedStudent.sqlState, "a different pair is a different link")

    val unchanged = assertNotNull(SupporterStudentLinksDao.findActive(session, supporter.id, student).getOrThrow())
    assertEquals(created.id, unchanged.id, "neither refused update moved the link")
    assertEquals(created.version, unchanged.version)
  }

  /**
   * A fifth word is refused by the NAMED check, so the constraint a later
   * DOMAIN retyping has to drop is the one the failure names.
   */
  @Test
  fun `a relationship word outside the four is refused by the named check`() {
    val student = createStudent()
    val supporter = createSupporter()

    val failure =
      assertFailsWith<SQLException> {
        connection
          .prepareStatement(
            "INSERT INTO supporter_student_links (supporter_id, student_id, relationship) VALUES (?, ?, 'cousin')",
          ).use { stmt ->
            stmt.setObject(1, supporter.id.value)
            stmt.setObject(2, student.value)
            stmt.executeUpdate()
          }
      }

    assertEquals("23514", failure.sqlState)
    assertEquals(
      "supporter_student_links_relationship_check",
      (failure as org.postgresql.util.PSQLException)
        .serverErrorMessage
        ?.constraint,
    )
    assertEquals(emptyList(), SupporterStudentLinksDao.listForStudent(session, student).getOrThrow())
  }

  /** Every [SupporterRelationship] member is a word the CHECK admits, proven by inserting it. */
  @Test
  fun `every SupporterRelationship value is accepted`() {
    val supporter = createSupporter()

    SupporterRelationship.entries.forEach { relationship ->
      // A NEW student every iteration: one supporter may hold only one ACTIVE
      // link per student, so reusing one would fail on the active-pair unique
      // index and report a uniqueness problem for a test about the vocabulary.
      val student = createStudent()
      val created = link(supporter.id, student, relationship).getOrThrow()
      assertEquals(relationship, created.relationship, "[${relationship.value}] must be a value the CHECK admits")
    }

    assertEquals(
      SupporterRelationship.entries.size,
      SupporterStudentLinksDao.listForSupporter(session, supporter.id).getOrThrow().size,
      "one live link per member value",
    )
  }

  /** A word neither list has resolves to nothing — pure Kotlin, no row required. */
  @Test
  fun `an unknown relationship word maps to null`() {
    assertNull(SupporterRelationship.fromValue("cousin"), "a word the vocabulary does not have resolves to nothing")
  }

  /**
   * The enum and the CHECK are ONE vocabulary in two places, and this is the
   * test that fails when EITHER list grows and the other does not.
   *
   * Walking the enum and inserting each word only closes one direction: a word
   * added to the constraint with no enum member passes such a test untouched,
   * and `fromValue` then throws on a row the database was happy to store. So the
   * constraint's own definition is read back from `pg_constraint` — and the
   * WHOLE rendered definition is compared, built here from
   * [SupporterRelationship.entries].
   *
   * Whole string, not a word set scraped out of it: the version this replaced
   * pulled quoted words out with `Regex("'([^']*)'")`, a hand-rolled SQL
   * string-literal parser that is both the only thing holding the two lists
   * together and blind to everything around the words. An extra
   * `OR relationship = 'cousin'` arm, or a predicate rewritten over another
   * column entirely, yields the same word set and passes. Rendering the
   * expectation from the enum also fixes the ORDER, which a set comparison
   * threw away.
   */
  @Test
  fun `the relationship CHECK and SupporterRelationship hold the same words`() {
    val definition =
      connection
        .prepareStatement(
          """
          SELECT pg_get_constraintdef(oid) AS def
          FROM pg_constraint
          WHERE conrelid = 'supporter_student_links'::regclass
            AND conname = 'supporter_student_links_relationship_check'
          """.trimIndent(),
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "the named relationship CHECK must exist")
            rs.getString("def")
          }
        }

    // The migration writes `relationship IN ('parent', ...)`; Postgres stores and
    // renders that predicate as `= ANY (ARRAY[...])` with each word cast to
    // ::text. This is the SAME constraint in the server's own spelling, not a
    // mismatch with the DDL, and pg_get_constraintdef returns only that
    // spelling — so the expectation has to be built in it.
    val expected =
      SupporterRelationship.entries.joinToString(", ", prefix = "CHECK ((relationship = ANY (ARRAY[", postfix = "])))") {
        "'${it.value}'::text"
      }

    assertEquals(
      expected,
      definition,
      "the CHECK and the enum are one vocabulary, and the CHECK admits nothing else",
    )
  }

  /**
   * Both foreign keys are mapped by NAME in [SupporterStudentLinksDao], and a
   * name Postgres does not generate is a branch that can never run. The only
   * way to prove the literal matches the constraint is to force the violation.
   */
  @Test
  fun `a link to a supporter that is not there reports the named foreign key`() {
    val student = createStudent()
    val absent = SupporterId(UUID.randomUUID())

    val failure =
      assertIs<NotFoundException>(
        link(absent, student).exceptionOrNull(),
        "Expected NotFoundException",
      )

    assertEquals(
      "No supporter [${absent.asString}] to link",
      failure.message,
      "the mapped message names the id that was not there, not a constant sentence",
    )
    assertEquals("supporter_student_links_supporter_id_fkey", failure.constraint)
  }

  @Test
  fun `a link to a student that is not there reports the named foreign key`() {
    val supporter = createSupporter()
    val absent = StudentId(UUID.randomUUID())

    val failure =
      assertIs<NotFoundException>(
        link(supporter.id, absent).exceptionOrNull(),
        "Expected NotFoundException",
      )

    assertEquals(
      "No student [${absent.asString}] to link",
      failure.message,
      "the mapped message names the id that was not there, not a constant sentence",
    )
    assertEquals("supporter_student_links_student_id_fkey", failure.constraint)
  }

  /**
   * A foreign key proves the supporter row EXISTS; it says nothing about whether
   * that supporter is still live. Both endpoints soft-delete, so without the
   * `00d` guard a link to an account closed years ago is representable — and
   * every later slice of brief 0014 authorises against these rows.
   */
  @Test
  fun `a link to a closed supporter is refused`() {
    val student = createStudent()
    val supporter = createSupporter()
    SupportersDao.delete(session, supporter.id, supporter.version).getOrThrow()

    val result = link(supporter.id, student)

    val refusal =
      assertIs<ClosedSupporterLinkException>(
        result.exceptionOrNull(),
        "a closed supporter is not linkable, got [$result]",
      )
    assertEquals(
      "supporter_student_links_supporter_live",
      refusal.constraint,
      "the closed-supporter rule has its own constraint name, not a shared P0001",
    )
    assertTrue(
      refusal.message?.contains(supporter.id.asString) == true &&
        refusal.message?.contains(student.asString) == true,
      "the refusal names both endpoints, got [${refusal.message}]",
    )
  }

  @Test
  fun `a link to a closed student is refused`() {
    val student = createStudent()
    val supporter = createSupporter()
    closeStudent(student)

    val result = link(supporter.id, student)

    val refusal =
      assertIs<ClosedStudentLinkException>(
        result.exceptionOrNull(),
        "a closed student is not linkable, got [$result]",
      )
    assertEquals(
      "supporter_student_links_student_live",
      refusal.constraint,
      "the closed-student rule has its own constraint name, not a shared P0001",
    )
    assertTrue(
      refusal.message?.contains(supporter.id.asString) == true &&
        refusal.message?.contains(student.asString) == true,
      "the refusal names both endpoints, got [${refusal.message}]",
    )
  }

  /**
   * The liveness half is ON INSERT ONLY, and this is the test that holds it
   * there. A student closing their account is exactly when the supporters who
   * were linked to them need severing; a guard that fired on UPDATE too would
   * refuse that unlink and strand the row, live forever, with no write left that
   * could clean it up.
   */
  @Test
  fun `a link whose student is closed can still be unlinked`() {
    val student = createStudent()
    val supporter = createSupporter()
    val created = link(supporter.id, student).getOrThrow()

    closeStudent(student)

    val severed = SupporterStudentLinksDao.unlink(session, created.id, created.version).getOrThrow()
    assertNotNull(severed.deletedAt, "unlinking a link to a closed student must still stamp deleted_at")
    assertEquals(emptyList(), SupporterStudentLinksDao.listForSupporter(session, supporter.id).getOrThrow())
  }
  // --- The roster read (RFC 241) -------------------------------------------

  /** A student whose own account carries [name], which is what the roster renders. */
  private fun createStudentNamed(name: String): StudentId = createStudent(createUser(name))

  private fun roster(supporterId: SupporterId): List<LinkedStudent> =
    SupporterStudentLinksDao.listActiveLinkedStudents(session, supporterId).getOrThrow()

  @Test
  fun `a supporter with no links reads an empty roster`() {
    val supporter = createSupporter()

    assertEquals(
      emptyList(),
      roster(supporter.id),
      "a parent who has linked nobody is an empty list, not a failed read",
    )
  }

  /**
   * The projection, whole: the link, the student, the relationship word and the
   * name — which comes from `users.name`, because `students` has no name column
   * at all.
   */
  @Test
  fun `the roster names the student and carries the link and the relationship`() {
    val supporter = createSupporter()
    val student = createStudentNamed("Ava Okafor")
    val created = link(supporter.id, student, SupporterRelationship.GRANDPARENT).getOrThrow()

    val entry = roster(supporter.id).single()

    assertEquals(created.id, entry.linkId)
    assertEquals(student, entry.studentId)
    assertEquals(SupporterRelationship.GRANDPARENT, entry.relationship)
    assertEquals("Ava Okafor", entry.studentName.value, "the display name comes from the student's users row")
  }

  @Test
  fun `a severed link is not on the roster`() {
    val supporter = createSupporter()
    val kept = createStudentNamed("Kept Student")
    val severedStudent = createStudentNamed("Severed Student")
    val keptLink = link(supporter.id, kept).getOrThrow()
    val severed = link(supporter.id, severedStudent).getOrThrow()

    SupporterStudentLinksDao.unlink(session, severed.id, severed.version).getOrThrow()

    assertEquals(
      listOf(keptLink.id),
      roster(supporter.id).map { it.linkId },
      "unlinking removes the student from the roster, and leaves the other one",
    )
  }

  /**
   * The link is still ACTIVE here — the liveness guard fires on INSERT only, so
   * closing an account leaves its links standing. The roster has to filter the
   * STUDENT itself, or a parent keeps reading the name of a deleted account.
   */
  @Test
  fun `a link whose student is closed is not on the roster`() {
    val supporter = createSupporter()
    val student = createStudentNamed("Closed Student")
    val created = link(supporter.id, student).getOrThrow()

    closeStudent(student)

    assertNull(deletedAtOf(created.id.value), "the link itself is still active — the student is what closed")
    assertEquals(emptyList(), roster(supporter.id), "a closed student is off every supporter's roster")
  }

  /**
   * The third predicate, held on its own. Production closes the `students` row
   * and the `users` row in ONE transaction
   * (`StudentService.deleteStudentAndAccount`), so this state does not occur
   * today — which is exactly why the read has to be tested for it rather than
   * trusted to another module's transaction. `family/09` adds a deletion path,
   * and the day a `users` row closes alone, the name on it must not still be
   * rendered on a parent's home.
   */
  @Test
  fun `a soft-deleted users row keeps its student off the roster`() {
    val supporter = createSupporter()
    val userId = createUser("Closed Account")
    val student = createStudent(userId)
    val created = link(supporter.id, student).getOrThrow()
    assertEquals(listOf(created.id), roster(supporter.id).map { it.linkId }, "the roster starts with the link on it")

    closeUser(userId)

    assertNull(deletedAtOf(created.id.value), "the link is still active — the user account is what closed")
    assertNull(
      StudentsDao.findById(session, student).getOrThrow().deletedAt,
      "and the students row is still LIVE: the users predicate is what must answer here, not the student one",
    )
    assertEquals(emptyList(), roster(supporter.id), "a closed account's name is off every supporter's roster")
  }

  @Test
  fun `another supporter's links are never on the roster`() {
    val mine = createSupporter()
    val theirs = createSupporter()
    val myStudent = createStudentNamed("My Student")
    val theirStudent = createStudentNamed("Their Student")
    val myLink = link(mine.id, myStudent).getOrThrow()
    link(theirs.id, theirStudent).getOrThrow()

    assertEquals(listOf(myLink.id), roster(mine.id).map { it.linkId })
    assertEquals(
      listOf("My Student"),
      roster(mine.id).map { it.studentName.value },
      "the join must not widen past the supporter the read was asked about",
    )
  }

  /**
   * Oldest first, by the LINK's `created_at` — the order a switcher renders, so
   * the student a parent linked first stays where they were when a second is
   * added.
   */
  @Test
  fun `the roster is ordered by the link's creation, oldest first`() {
    val supporter = createSupporter()
    val first = link(supporter.id, createStudentNamed("First Linked")).getOrThrow()
    val second = link(supporter.id, createStudentNamed("Second Linked")).getOrThrow()
    val third = link(supporter.id, createStudentNamed("Third Linked")).getOrThrow()

    assertEquals(
      listOf(first.id, second.id, third.id),
      roster(supporter.id).map { it.linkId },
      "the roster follows the links' creation order",
    )
    assertEquals(
      listOf("First Linked", "Second Linked", "Third Linked"),
      roster(supporter.id).map { it.studentName.value },
      "each row's name belongs to its own link, not to the row beside it",
    )
  }
}
