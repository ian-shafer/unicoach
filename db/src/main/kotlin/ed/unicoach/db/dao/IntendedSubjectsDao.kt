package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IntendedSubject
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.IntendedSubjectId
import ed.unicoach.db.models.IntendedSubjectName
import ed.unicoach.db.models.IntendedSubjectWrite
import ed.unicoach.db.models.StudentId
import java.sql.ResultSet
import java.util.UUID

/**
 * Data-access layer over the two tables that hold what a student wants to study
 * (RFC 205): `intended_subject_status` (one row per student — HOW they
 * answered) and `intended_subjects` (the SET of subjects they named, capped at
 * three). Stateless `object`, one [SqlSession] per call, transaction boundaries
 * owned by the caller — the [CollegeListEntriesDao] shape.
 *
 * It exposes ONE read and ONE write, because the two tables only mean anything
 * together: a zero-length subject set does not say which of `unanswered`,
 * `declined` and `undecided` was given. [readAnswer] returns both halves;
 * [replaceAnswer] replaces both halves in one transaction.
 *
 * There is deliberately no add-one, no remove-one and no clear (D17).
 */
object IntendedSubjectsDao {
  /**
   * The cap the schema's deferred `trigger_05_enforce_intended_subjects_cap`
   * enforces, stated once so a caller can refuse with a REASON instead of
   * letting a constraint violation escape.
   *
   * The trigger is not made redundant by this: it is the backstop a SECOND
   * concurrent writer cannot get past, and this constant is the courtesy that
   * turns the common case into a 400 the student can act on.
   */
  const val MAX_SUBJECTS: Int = 3

  internal fun mapSubject(rs: ResultSet): IntendedSubject =
    IntendedSubject(
      id = IntendedSubjectId(UUID.fromString(rs.getString("id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      slug = rs.getString("subject_slug"),
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )

  /**
   * Reconstructs the answer status from its persisted string, the
   * [CollegeListEntriesDao.mapEntry] convention: the
   * `intended_subject_status_status_check` CHECK already admits exactly the four
   * member values, so an unknown string is row corruption and throws naming the
   * column and the row — never softened to [AnswerStatus.UNANSWERED], which
   * would tell a student who answered that nobody ever asked.
   */
  private fun parseStatus(
    value: String,
    rowId: String,
  ): AnswerStatus =
    AnswerStatus.fromValue(value)
      ?: throw CorruptPersistedValueException(
        value,
        ValidationError.InvalidFormat(expected = "a known AnswerStatus value"),
        location = "intended_subject_status.status (row [$rowId])",
      )

  /**
   * A student's whole answer.
   *
   * A student with no status row at all has never been asked, which is
   * [AnswerStatus.UNANSWERED] with no subjects — not a missing row the caller
   * has to interpret, and not a 404. `unanswered` IS the answer of a student
   * nobody has asked, so this read is total.
   *
   * The names are resolved HERE, by joining `subjects` at read time, so a
   * taxonomy rename never touches a student's row and a DEPRECATED subject the
   * student already holds still resolves to its name (D40). The join is an
   * INNER join and is safe as one: `intended_subjects.subject_slug` references
   * `subjects(slug)` ON DELETE RESTRICT, so the row it needs cannot be missing.
   */
  fun readAnswer(
    session: SqlSession,
    studentId: StudentId,
  ): Result<IntendedSubjectAnswer> =
    readStatus(session, studentId).mapCatching { status ->
      val names = readSubjectNames(session, studentId).getOrThrow()
      IntendedSubjectAnswer(studentId = studentId, status = status, subjects = names)
    }

  /** The status alone; [AnswerStatus.UNANSWERED] when the student has no row. */
  private fun readStatus(
    session: SqlSession,
    studentId: StudentId,
  ): Result<AnswerStatus> =
    session
      .queryOne(
        """
        SELECT id, status FROM intended_subject_status
        WHERE student_id = ? AND deleted_at IS NULL
        """.trimIndent(),
        bind = { it.setObject(1, studentId.value) },
        map = { rs -> parseStatus(rs.getString("status"), rs.getString("id")) },
      ).orNullOnNotFound()
      .map { it ?: AnswerStatus.UNANSWERED }

  /** The student's active subjects with their names, in name order — what a person reads. */
  private fun readSubjectNames(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<IntendedSubjectName>> =
    session.queryList(
      """
      SELECT i.subject_slug AS slug, s.name AS name
      FROM intended_subjects i
      JOIN subjects s ON s.slug = i.subject_slug
      WHERE i.student_id = ? AND i.deleted_at IS NULL
      ORDER BY s.name, i.subject_slug
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = { rs -> IntendedSubjectName(slug = rs.getString("slug"), name = rs.getString("name")) },
    )

  /** The student's active subjects as rows — the history-bearing read a test and an audit use. */
  fun listActiveByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<IntendedSubject>> =
    session.queryList(
      """
      SELECT * FROM intended_subjects
      WHERE student_id = ? AND deleted_at IS NULL
      ORDER BY subject_slug
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = ::mapSubject,
    )

  /**
   * Replaces a student's WHOLE answer, in the caller's transaction.
   *
   * The first thing it does is take the student's `intended_subject_status` row
   * `FOR UPDATE`, creating it if it is not there. That lock is the whole reason
   * the status row is written before the subjects: two concurrent writers
   * otherwise both read "two rows, room for one more" and land four between
   * them, and the deferred cap trigger would then fail ONE of them at COMMIT
   * with a constraint violation rather than serialising them. With the lock
   * they serialise, and the trigger is left as the backstop it is meant to be.
   *
   * The subject set is replaced, not edited: every active row the write does not
   * keep is SOFT-deleted (an `UPDATE` setting `deleted_at`, which the versioning
   * trigger counts as a version and the log trigger snapshots, so the removal
   * itself is in the history), and every named slug the student does not already
   * hold is INSERTED as a new row with `version = 1`. A slug the student already
   * holds is left completely alone — re-submitting an unchanged answer advances
   * no version and writes no history.
   *
   * Mid-transaction the two tables are legitimately inconsistent while a student
   * swaps one subject for another; both invariants are `DEFERRABLE INITIALLY
   * DEFERRED` constraint triggers judged at COMMIT, which is what makes that
   * legal.
   */
  fun replaceAnswer(
    session: SqlSession,
    studentId: StudentId,
    write: IntendedSubjectWrite,
  ): Result<IntendedSubjectAnswer> =
    runCatching {
      lockStatusRow(session, studentId).getOrThrow()
      writeStatus(session, studentId, statusFor(write)).getOrThrow()
      setActiveSubjects(session, studentId, wantedSlugs(write))
      readAnswer(session, studentId).getOrThrow()
    }

  /**
   * HOW the student answered, for the status row. Exhaustive over the write's
   * three cases, so a fourth one does not compile rather than defaulting to a
   * state nobody chose.
   */
  private fun statusFor(write: IntendedSubjectWrite): AnswerStatus =
    when (write) {
      is IntendedSubjectWrite.Subjects -> AnswerStatus.ANSWERED
      IntendedSubjectWrite.Declined -> AnswerStatus.DECLINED
      IntendedSubjectWrite.Undecided -> AnswerStatus.UNDECIDED
    }

  /**
   * WHICH subjects the write leaves the student holding. `declined` and
   * `undecided` both name none — the difference between them lives in the
   * status row, never in the length of this list.
   */
  private fun wantedSlugs(write: IntendedSubjectWrite): List<String> =
    when (write) {
      is IntendedSubjectWrite.Subjects -> write.slugs.distinct()
      IntendedSubjectWrite.Declined -> emptyList()
      IntendedSubjectWrite.Undecided -> emptyList()
    }

  /**
   * Moves the student's active subject rows to exactly [wanted]: soft-delete
   * what is held and no longer named, insert what is named and not held, and
   * leave an unchanged slug completely alone so re-submitting the same answer
   * advances no version and writes no history.
   *
   * Throws the first failure; the caller runs inside `runCatching`.
   */
  private fun setActiveSubjects(
    session: SqlSession,
    studentId: StudentId,
    wanted: List<String>,
  ) {
    val held = listActiveByStudent(session, studentId).getOrThrow().map { it.slug }.toSet()
    (held - wanted.toSet()).forEach { slug -> softDeleteSubject(session, studentId, slug).getOrThrow() }
    wanted.filterNot { it in held }.forEach { slug -> insertSubject(session, studentId, slug).getOrThrow() }
  }

  /**
   * Takes the student's status row `FOR UPDATE`, creating it at `unanswered`
   * first if it is absent.
   *
   * The insert is `ON CONFLICT DO NOTHING`, and its conflict target is the
   * PARTIAL unique index `intended_subject_status_student_active_idx` — hence
   * the `WHERE deleted_at IS NULL` on the conflict clause, which is how
   * PostgreSQL is told to infer a partial index rather than a full one. So two
   * writers racing to create the first row for one student do not both succeed;
   * the loser falls through to the lock and waits. A `WHERE NOT EXISTS` guard
   * would NOT do this: it is judged against this transaction's snapshot only,
   * so both writers pass it and the loser aborts with a bare `23505`.
   *
   * The `FOR UPDATE` result is then INSPECTED rather than assumed. The row has
   * to be there — the insert above created it if it was absent — so anything
   * but exactly one row means no lock is held, and every serialisation claim
   * the rest of this DAO makes would be false with no error to show for it.
   */
  private fun lockStatusRow(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Unit> =
    session
      .execute(
        """
        INSERT INTO intended_subject_status (student_id, status)
        VALUES (?, '${AnswerStatus.UNANSWERED.value}')
        ON CONFLICT (student_id) WHERE deleted_at IS NULL DO NOTHING
        """.trimIndent(),
      ) { stmt ->
        stmt.setObject(1, studentId.value)
      }.mapCatching {
        val locked =
          session
            .queryList(
              """
              SELECT id FROM intended_subject_status
              WHERE student_id = ? AND deleted_at IS NULL
              FOR UPDATE
              """.trimIndent(),
              bind = { it.setObject(1, studentId.value) },
              map = { rs -> rs.getString("id") },
            ).getOrThrow()
        if (locked.size != 1) {
          throw NotFoundException(
            "Expected exactly one live intended_subject_status row to lock for student " +
              "[${studentId.value}]; found [${locked.size}]",
          )
        }
      }

  /**
   * Writes the status, suppressing an unchanged write.
   *
   * The suppression is not an optimisation: `enforce_versioning` counts every
   * committed UPDATE as a version and the log trigger snapshots it, so writing
   * `declined` over `declined` would put a fictional state change in the
   * history of an answer that did not change.
   *
   * `version = version + 1` is not decoration: `enforce_versioning` REFUSES an
   * UPDATE whose `version` is not `OLD.version + 1` with a 40001, so every
   * UPDATE on these two tables must advance it. This write is not OCC-guarded
   * on a caller-supplied version because the row is already held `FOR UPDATE`
   * by [lockStatusRow] for the length of the transaction.
   */
  private fun writeStatus(
    session: SqlSession,
    studentId: StudentId,
    status: AnswerStatus,
  ): Result<Int> =
    session.execute(
      """
      UPDATE intended_subject_status SET status = ?, version = version + 1
      WHERE student_id = ? AND deleted_at IS NULL AND status IS DISTINCT FROM ?
      """.trimIndent(),
    ) { stmt ->
      stmt.setString(1, status.value)
      stmt.setObject(2, studentId.value)
      stmt.setString(3, status.value)
    }

  /**
   * Removes one subject: an UPDATE setting `deleted_at`, never a DELETE.
   *
   * `prevent_physical_delete` refuses a physical delete outright, and that is
   * the point — "what was she planning in March" has to stay answerable. The
   * `AFTER UPDATE` log trigger snapshots the row WITH its `deleted_at` set, so
   * the history records the removal itself and not merely the last state before
   * it.
   *
   * It advances `version` for the same reason [writeStatus] does:
   * `enforce_versioning` refuses any UPDATE that does not.
   */
  private fun softDeleteSubject(
    session: SqlSession,
    studentId: StudentId,
    subjectSlug: String,
  ): Result<Int> =
    session.execute(
      """
      UPDATE intended_subjects SET deleted_at = NOW(), version = version + 1
      WHERE student_id = ? AND subject_slug = ? AND deleted_at IS NULL
      """.trimIndent(),
    ) { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.setString(2, subjectSlug)
    }

  /**
   * Adds one subject as a NEW row.
   *
   * Re-adding a subject the student removed earlier is deliberately a new row
   * with `version = 1` and its own history, never a resurrection of the soft-
   * deleted one — the partial unique index is what makes that possible, and a
   * resurrected row would have a history saying it was removed and then
   * silently un-removed.
   */
  private fun insertSubject(
    session: SqlSession,
    studentId: StudentId,
    subjectSlug: String,
  ): Result<IntendedSubject> =
    session.insertReturning(
      table = "intended_subjects",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, studentId.value) },
          "subject_slug" to { stmt, i -> stmt.setString(i, subjectSlug) },
        ),
      map = ::mapSubject,
      // NOT the default ::mapDatabaseError. Both of this table's guards are
      // reachable from ordinary input: the RESTRICT foreign key when a slug is
      // not (or is no longer) a subject, and the partial unique index when the
      // same subject is added twice while live. The default mapper turns either
      // into `DatabaseException("General database error")` with `constraint` and
      // `detail` both null, which is exactly the bare SQL state RFC 205 D40 says
      // this slice must stop producing. `college_list_entries`, the table this
      // one is copied from, names its constraints on every write for the same
      // reason.
      mapError = { e ->
        mapChildWriteError(
          e,
          mapOf(
            "intended_subjects_subject_slug_fkey" to
              "subject [$subjectSlug] is not a subject on the list",
            "intended_subjects_student_id_fkey" to
              "no student [${studentId.value}] to record intended subjects for",
          ),
        )
      },
    )
}
