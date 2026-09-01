package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryEdit
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Version
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the versioned mutable `college_list_entries` entity
 * (RFC 91): a student's status and free-text reasons for a specific college.
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. Composes the existing capability interfaces exactly as
 * [StudentsDao] does, plus [SoftDeleteListable] (the [UsersDao] precedent) for
 * the admin listing surface.
 */
object CollegeListEntriesDao :
  SoftDeleteFindable<CollegeListEntry, CollegeListEntryId>,
  Creatable<NewCollegeListEntry, CollegeListEntry>,
  Updatable<CollegeListEntryEdit, CollegeListEntry>,
  OccDeletable<CollegeListEntry, CollegeListEntryId>,
  SoftDeleteListable<CollegeListEntry>,
  VersionHistory<CollegeListEntryId, Version<CollegeListEntry>> {
  internal fun mapEntry(rs: ResultSet): CollegeListEntry =
    CollegeListEntry(
      id = CollegeListEntryId(UUID.fromString(rs.getString("id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      status = parseStatus(rs.getString("status")),
      reasons = rs.getString("reasons"),
      livingPlan =
        rs.getString("living_plan")?.let {
          parseLivingPlan(it, rs.getString("id"))
        },
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )

  /**
   * Reconstructs the status enum from its persisted string. The DB CHECK already
   * guarantees a member value is stored, so a null here indicates row
   * corruption, surfaced as a [CorruptPersistedValueException] (a
   * [ed.unicoach.error.PermanentError]), never a user-facing failure -- matches
   * [SessionsDao]/[StudentsDao]/[UserAuthIdentitiesDao]'s convention for the
   * same scenario.
   */
  private fun parseStatus(value: String): CollegeListEntryStatus =
    CollegeListEntryStatus.fromValue(value)
      ?: throw CorruptPersistedValueException(value, ValidationError.InvalidFormat(expected = "a known CollegeListEntryStatus value"))

  /**
   * Reconstructs the per-college living-plan override (RFC 152). Same
   * convention as [parseStatus]: the `college_list_entries_living_plan_check`
   * CHECK admits exactly the three member values, so an unknown string is row
   * corruption and throws. It is never softened to `null`, which the resolver
   * reads as "no override, use the usual plan" -- that would silently answer
   * this school with the wrong arrangement.
   *
   * It names its column and row, as its money-profile twin
   * (`MoneyProfilesDao.parseLivingPlan`) does: a refusal that says only what the
   * value was leaves an operator with no way to find the row that carries it.
   */
  private fun parseLivingPlan(
    value: String,
    rowId: String,
  ): LivingArrangement =
    LivingArrangement.fromValue(value)
      ?: throw CorruptPersistedValueException(
        value,
        ValidationError.InvalidFormat(expected = "a known LivingArrangement value"),
        location = "college_list_entries.living_plan (row [$rowId])",
      )

  /** Whether a [SoftDeleteScope] admits a row with the given `deletedAt`. */
  private fun SoftDeleteScope.admits(deletedAt: java.time.Instant?): Boolean =
    when (this) {
      SoftDeleteScope.ACTIVE -> deletedAt == null
      SoftDeleteScope.DELETED -> deletedAt != null
      SoftDeleteScope.ALL -> true
    }

  override fun findById(
    session: SqlSession,
    id: CollegeListEntryId,
    scope: SoftDeleteScope,
  ): Result<CollegeListEntry> =
    session
      .queryOne(
        "SELECT * FROM college_list_entries WHERE id = ?",
        bind = { it.setObject(1, id.value) },
        map = ::mapEntry,
      ).mapCatching { entry ->
        if (!scope.admits(entry.deletedAt)) throw NotFoundException()
        entry
      }

  /** Ownership-scoped fetch: a wrong-owner id is NotFoundException, never a separate Forbidden. */
  fun findByIdAndStudent(
    session: SqlSession,
    id: CollegeListEntryId,
    studentId: StudentId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  ): Result<CollegeListEntry> =
    session
      .queryOne(
        "SELECT * FROM college_list_entries WHERE id = ? AND student_id = ?",
        bind = { stmt ->
          stmt.setObject(1, id.value)
          stmt.setObject(2, studentId.value)
        },
        map = ::mapEntry,
      ).mapCatching { entry ->
        if (!scope.admits(entry.deletedAt)) throw NotFoundException()
        entry
      }

  /** The student's active list, ordered created_at, id. The hot read. */
  fun listActiveByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<CollegeListEntry>> =
    session.queryList(
      """
      SELECT * FROM college_list_entries
      WHERE student_id = ? AND deleted_at IS NULL
      ORDER BY created_at, id
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = ::mapEntry,
    )

  override fun create(
    session: SqlSession,
    input: NewCollegeListEntry,
  ): Result<CollegeListEntry> =
    session.insertReturning(
      table = "college_list_entries",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "college_id" to { stmt, i -> stmt.setObject(i, input.collegeId.value) },
          "status" to { stmt, i -> stmt.setString(i, input.status.value) },
          "reasons" to { stmt, i -> stmt.setStringOrNull(i, input.reasons) },
          "living_plan" to { stmt, i -> stmt.setStringOrNull(i, input.livingPlan?.value) },
        ),
      map = ::mapEntry,
      mapError = ::mapCreateUpdateError,
    )

  override fun update(
    session: SqlSession,
    edit: CollegeListEntryEdit,
  ): Result<CollegeListEntry> =
    session.updateColumnsReturning(
      table = "college_list_entries",
      id = edit.id.value,
      currentVersion = edit.version,
      columns =
        linkedMapOf<String, Bind>(
          "status" to { stmt, i -> stmt.setString(i, edit.status.value) },
          "reasons" to { stmt, i -> stmt.setStringOrNull(i, edit.reasons) },
          "living_plan" to { stmt, i -> stmt.setStringOrNull(i, edit.livingPlan?.value) },
        ),
      map = ::mapEntry,
      mapError = ::mapCreateUpdateError,
    )

  override fun delete(
    session: SqlSession,
    id: CollegeListEntryId,
    currentVersion: Int,
  ): Result<CollegeListEntry> =
    session.softDeleteReturning(
      table = "college_list_entries",
      id = id.value,
      currentVersion = currentVersion,
      deleted = true,
      map = ::mapEntry,
      mapError = ::mapCreateUpdateError,
    )

  override fun undelete(
    session: SqlSession,
    id: CollegeListEntryId,
    currentVersion: Int,
  ): Result<CollegeListEntry> =
    session.softDeleteReturning(
      table = "college_list_entries",
      id = id.value,
      currentVersion = currentVersion,
      deleted = false,
      map = ::mapEntry,
      mapError = ::mapCreateUpdateError,
    )

  /**
   * Admin read surface: page every student's college-list entries newest-first.
   * The [scope] filter is a fixed SQL fragment (no caller data); admin lists
   * default to [SoftDeleteScope.ALL] so soft-deleted rows stay visible.
   */
  override fun list(
    session: SqlSession,
    scope: SoftDeleteScope,
    limit: Int,
    offset: Int,
  ): Result<List<CollegeListEntry>> {
    val sql =
      """
      SELECT * FROM college_list_entries
      WHERE ${scope.predicate()}
      ORDER BY created_at DESC, id
      LIMIT ? OFFSET ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapEntry,
    )
  }

  /** Admin read surface: an entry's full version history, ascending by version. */
  override fun listVersions(
    session: SqlSession,
    id: CollegeListEntryId,
  ): Result<List<Version<CollegeListEntry>>> =
    session.queryList(
      "SELECT * FROM college_list_entries_versions WHERE id = ? ORDER BY version",
      bind = { it.setObject(1, id.value) },
      map = { Version(mapEntry(it)) },
    )

  /**
   * SQLSTATE discrimination for create/update operations.
   * - `23503` on `college_id` FK -> [NotFoundException] ("College not found").
   * - `23503` on `student_id` FK -> [NotFoundException] ("Owning student not found").
   * - `23505`/`23514` -> [ConstraintViolationException], populated with the
   *   violated constraint name and server DETAIL line ([CollegesDao]'s
   *   precedent) so the service can discriminate the active-uniqueness
   *   violation (`23505` -> "already on the list") from the `reasons`
   *   length/non-empty CHECK (`23514` -> a validation failure naming
   *   `reasons`) by [ConstraintViolationException.constraint] rather than the
   *   two collapsing into the same outcome.
   */
  private fun mapCreateUpdateError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("college_list_entries_college_id_fkey") -> NotFoundException("College not found")
          message.contains("college_list_entries_student_id_fkey") -> NotFoundException("Owning student not found")
          else -> NotFoundException()
        }
      }

      "23505", "23514" -> {
        val serverError = (e as? PSQLException)?.serverErrorMessage
        ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
