package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.MoneyProfileEdit
import ed.unicoach.db.models.MoneyProfileId
import ed.unicoach.db.models.MoneyProfileUpsert
import ed.unicoach.db.models.NewMoneyProfile
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Version
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the versioned mutable `money_profiles` entity
 * (RFC 134): one row per student, two tri-state profile fields. Stateless
 * `object`, one [SqlSession] per call, transaction boundaries owned by the
 * caller. Composes the capability interfaces exactly as
 * [CollegeListEntriesDao] does, plus [SoftDeleteListable] for the admin
 * listing surface.
 */
object MoneyProfilesDao :
  SoftDeleteFindable<MoneyProfile, MoneyProfileId>,
  Creatable<NewMoneyProfile, MoneyProfile>,
  Updatable<MoneyProfileEdit, MoneyProfile>,
  OccDeletable<MoneyProfile, MoneyProfileId>,
  SoftDeleteListable<MoneyProfile>,
  VersionHistory<MoneyProfileId, Version<MoneyProfile>> {
  internal fun mapProfile(rs: ResultSet): MoneyProfile {
    val id = MoneyProfileId(UUID.fromString(rs.getString("id")))
    return MoneyProfile(
      id = id,
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      incomeBand = rs.getString("income_band")?.let { parseIncomeBand(it, id) },
      incomeBandStatus = parseStatus(rs.getString("income_band_status"), "income_band_status", id),
      residencyState = rs.getString("residency_state"),
      residencyStatus = parseStatus(rs.getString("residency_status"), "residency_status", id),
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )
  }

  /**
   * Reconstructs a persisted enum string. The DB CHECKs already guarantee a
   * member value is stored, so a null here indicates row corruption, surfaced
   * as a [CorruptPersistedValueException] -- [CollegeListEntriesDao]'s
   * convention for the same scenario.
   */
  private fun parseStatus(
    value: String,
    column: String,
    rowId: MoneyProfileId,
  ): AnswerStatus =
    AnswerStatus.fromValue(value)
      ?: throw CorruptPersistedValueException(
        value,
        ValidationError.InvalidFormat(expected = "a known AnswerStatus value"),
        location = "money_profiles.[$column] (row [${rowId.value}])",
      )

  private fun parseIncomeBand(
    value: String,
    rowId: MoneyProfileId,
  ): IncomeBand =
    IncomeBand.fromValue(value)
      ?: throw CorruptPersistedValueException(
        value,
        ValidationError.InvalidFormat(expected = "a known IncomeBand value"),
        location = "money_profiles.income_band (row [${rowId.value}])",
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
    id: MoneyProfileId,
    scope: SoftDeleteScope,
  ): Result<MoneyProfile> =
    session
      .queryOne(
        "SELECT * FROM money_profiles WHERE id = ?",
        bind = { it.setObject(1, id.value) },
        map = ::mapProfile,
        onNoRow = { NotFoundException("Money profile [${id.value}] not found") },
      ).mapCatching { profile ->
        if (!scope.admits(profile.deletedAt)) {
          throw NotFoundException(
            "Money profile [${id.value}] (student [${profile.studentId.value}]) exists but is " +
              (if (profile.deletedAt == null) "active" else "soft-deleted") + ", outside the requested scope [$scope]",
          )
        }
        profile
      }

  /**
   * The student's single active profile, or [NotFoundException] before the
   * first write. The hot read (REST GET, coach context assembly).
   */
  fun findActiveByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<MoneyProfile> =
    session.queryOne(
      "SELECT * FROM money_profiles WHERE student_id = ? AND deleted_at IS NULL",
      bind = { it.setObject(1, studentId.value) },
      map = ::mapProfile,
      onNoRow = { NotFoundException("No active money profile for student [${studentId.value}]") },
    )

  /** The value column a [MoneyProfileUpsert.FieldWrite] persists: present only for [MoneyProfileUpsert.FieldWrite.Answer]. */
  private fun <T> fieldValue(state: MoneyProfileUpsert.FieldWrite<T>?): T? =
    when (state) {
      is MoneyProfileUpsert.FieldWrite.Answer -> state.value
      MoneyProfileUpsert.FieldWrite.Declined, MoneyProfileUpsert.FieldWrite.Cleared, null -> null
    }

  /** The status column a [MoneyProfileUpsert.FieldWrite] persists; an untouched (null) field inserts as `unanswered`. */
  private fun fieldStatus(state: MoneyProfileUpsert.FieldWrite<*>?): AnswerStatus =
    when (state) {
      is MoneyProfileUpsert.FieldWrite.Answer -> AnswerStatus.ANSWERED
      MoneyProfileUpsert.FieldWrite.Declined -> AnswerStatus.DECLINED
      MoneyProfileUpsert.FieldWrite.Cleared, null -> AnswerStatus.UNANSWERED
    }

  /**
   * Atomic create-or-update of the student's single active profile row: one
   * `INSERT ... ON CONFLICT (student_id) WHERE deleted_at IS NULL DO UPDATE`
   * (Postgres infers the partial unique index `money_profiles_student_active_idx`
   * from the conflict target + predicate), so two concurrent first writes can
   * never race a find-then-insert into a raw uniqueness error -- the loser's
   * INSERT converts into the UPDATE.
   *
   * Untouched fields (a null [MoneyProfileUpsert.FieldWrite]) insert as
   * NULL/'unanswered' and are kept as-is on the update branch via a per-column
   * `CASE WHEN <apply_flag> THEN EXCLUDED.<col> ELSE money_profiles.<col> END`
   * on a bound apply flag. The `DO UPDATE` bumps
   * `version = money_profiles.version + 1` unconditionally -- the OCC-entity
   * convention ([CollegeListEntriesDao]: every UPDATE bumps and logs a history
   * row, even a content-identical write; only reference `colleges` skips
   * no-ops) -- which is also exactly what `enforce_versioning` demands on the
   * UPDATE branch, while the INSERT branch relies on the column DEFAULT 1.
   */
  fun upsertForStudent(
    session: SqlSession,
    input: MoneyProfileUpsert,
  ): Result<MoneyProfile> {
    val sql =
      """
      INSERT INTO money_profiles (student_id, income_band, income_band_status, residency_state, residency_status)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (student_id) WHERE deleted_at IS NULL DO UPDATE SET
        version = money_profiles.version + 1,
        income_band = CASE WHEN ? THEN EXCLUDED.income_band ELSE money_profiles.income_band END,
        income_band_status = CASE WHEN ? THEN EXCLUDED.income_band_status ELSE money_profiles.income_band_status END,
        residency_state = CASE WHEN ? THEN EXCLUDED.residency_state ELSE money_profiles.residency_state END,
        residency_status = CASE WHEN ? THEN EXCLUDED.residency_status ELSE money_profiles.residency_status END
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, input.studentId.value)
        stmt.setStringOrNull(2, fieldValue(input.income)?.value)
        stmt.setString(3, fieldStatus(input.income).value)
        stmt.setStringOrNull(4, fieldValue(input.residency))
        stmt.setString(5, fieldStatus(input.residency).value)
        stmt.setBoolean(6, input.income != null)
        stmt.setBoolean(7, input.income != null)
        stmt.setBoolean(8, input.residency != null)
        stmt.setBoolean(9, input.residency != null)
      },
      map = ::mapProfile,
      mapError = ::mapCreateUpdateError,
    )
  }

  override fun create(
    session: SqlSession,
    input: NewMoneyProfile,
  ): Result<MoneyProfile> =
    session.insertReturning(
      table = "money_profiles",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "income_band" to { stmt, i -> stmt.setStringOrNull(i, input.incomeBand?.value) },
          "income_band_status" to { stmt, i -> stmt.setString(i, input.incomeBandStatus.value) },
          "residency_state" to { stmt, i -> stmt.setStringOrNull(i, input.residencyState) },
          "residency_status" to { stmt, i -> stmt.setString(i, input.residencyStatus.value) },
        ),
      map = ::mapProfile,
      mapError = ::mapCreateUpdateError,
    )

  override fun update(
    session: SqlSession,
    edit: MoneyProfileEdit,
  ): Result<MoneyProfile> =
    session.updateColumnsReturning(
      table = "money_profiles",
      id = edit.id.value,
      currentVersion = edit.version,
      columns =
        linkedMapOf<String, Bind>(
          "income_band" to { stmt, i -> stmt.setStringOrNull(i, edit.incomeBand?.value) },
          "income_band_status" to { stmt, i -> stmt.setString(i, edit.incomeBandStatus.value) },
          "residency_state" to { stmt, i -> stmt.setStringOrNull(i, edit.residencyState) },
          "residency_status" to { stmt, i -> stmt.setString(i, edit.residencyStatus.value) },
        ),
      map = ::mapProfile,
      mapError = ::mapCreateUpdateError,
    )

  override fun delete(
    session: SqlSession,
    id: MoneyProfileId,
    currentVersion: Int,
  ): Result<MoneyProfile> =
    session.softDeleteReturning(
      table = "money_profiles",
      id = id.value,
      currentVersion = currentVersion,
      deleted = true,
      map = ::mapProfile,
      mapError = ::mapCreateUpdateError,
    )

  override fun undelete(
    session: SqlSession,
    id: MoneyProfileId,
    currentVersion: Int,
  ): Result<MoneyProfile> =
    session.softDeleteReturning(
      table = "money_profiles",
      id = id.value,
      currentVersion = currentVersion,
      deleted = false,
      map = ::mapProfile,
      mapError = ::mapCreateUpdateError,
    )

  /**
   * Admin read surface: page every student's money profile newest-first. The
   * [scope] filter is a fixed SQL fragment (no caller data); admin lists
   * default to [SoftDeleteScope.ALL] so soft-deleted rows stay visible.
   */
  override fun list(
    session: SqlSession,
    scope: SoftDeleteScope,
    limit: Int,
    offset: Int,
  ): Result<List<MoneyProfile>> {
    val sql =
      """
      SELECT * FROM money_profiles
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
      map = ::mapProfile,
    )
  }

  /** Admin read surface: a profile's full version history, ascending by version. */
  override fun listVersions(
    session: SqlSession,
    id: MoneyProfileId,
  ): Result<List<Version<MoneyProfile>>> =
    session.queryList(
      "SELECT * FROM money_profiles_versions WHERE id = ? ORDER BY version",
      bind = { it.setObject(1, id.value) },
      map = { Version(mapProfile(it)) },
    )

  /** The `money_profiles.student_id` FK, whose violation is the expected owning-student-vanished outcome. */
  private const val STUDENT_FK_CONSTRAINT = "money_profiles_student_id_fkey"

  /**
   * SQLSTATE discrimination for create/update operations.
   * - `23503` on [STUDENT_FK_CONSTRAINT] (matched on the driver's structured
   *   constraint name, never message text) -> [NotFoundException] carrying the
   *   server DETAIL and the original [SQLException] as cause.
   * - Any other `23503` is an integrity defect, not an expected miss ->
   *   [ConstraintViolationException] with full diagnostics.
   * - `23505`/`23514` -> [ConstraintViolationException] populated with the
   *   violated constraint name and server DETAIL ([CollegeListEntriesDao]'s
   *   precedent) so callers can discriminate the active-uniqueness violation
   *   from the value-iff-answered / format CHECKs.
   */
  private fun mapCreateUpdateError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val serverError = (e as? PSQLException)?.serverErrorMessage
        if (serverError?.constraint == STUDENT_FK_CONSTRAINT) {
          NotFoundException(
            "Owning student not found (money_profiles.student_id FK" +
              (serverError.detail?.let { ": [$it]" } ?: "") + ")",
            cause = e,
          )
        } else {
          ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
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
