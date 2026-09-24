package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.SupporterStudentLinkId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Store for `supporter_student_links` (RFC 221) — the relationship between a
 * supporter and a student, the spine every later slice of brief 0014
 * authorises against.
 *
 * The [CostReportSharesDao] shape: a stateless `object` over [SqlSession], every
 * method returning [Result], plain JDBC, and the caller's transaction.
 *
 * A link row is only ever minted by accepting an invite (D2), and nothing in
 * this slice does that — `family/02/invite-and-accept` is the first caller.
 * Unlinking is a soft delete, so re-linking the same pair later is a NEW row
 * with its own `created_at` and the severed period stays visible.
 */
object SupporterStudentLinksDao : Creatable<NewSupporterStudentLink, SupporterStudentLink> {
  /**
   * The constraint names the `00d`/`00e` trigger guards raise with
   * `USING CONSTRAINT = ...`, restated here because a mapper has to name what it
   * routes on.
   *
   * These are schema-owned names in Kotlin, which is a copy — a rename in the
   * migration without a change here silently reverts all three refusals to a
   * bare [DatabaseException]. `SupporterStudentLinksDaoTest` provokes each one
   * from the real database for exactly that reason.
   */
  private const val NOT_SELF_LINK = "supporter_student_links_not_self_link"
  private const val SUPPORTER_LIVE = "supporter_student_links_supporter_live"
  private const val STUDENT_LIVE = "supporter_student_links_student_live"

  // The PARTIAL unique index that makes one ACTIVE link per pair — the fourth
  // schema-owned name, stated here for the reason the three above are. PRIVATE,
  // like them: a caller racing another accept meets it as an
  // ActiveSupporterLinkException and names the TYPE, so a rename in the
  // migration cannot quietly change what `:service` does.
  private const val ACTIVE_LINK_INDEX = "supporter_student_links_supporter_student_active_idx"

  private fun mapLink(rs: ResultSet): SupporterStudentLink {
    val id = SupporterStudentLinkId(UUID.fromString(rs.getString("id")))
    return SupporterStudentLink(
      id = id,
      supporterId = SupporterId(UUID.fromString(rs.getString("supporter_id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      relationship = parseRelationship(rs.getString("relationship"), id),
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )
  }

  /**
   * Reconstructs the relationship from its persisted string, the
   * [IntendedSubjectsDao] convention: the
   * `supporter_student_links_relationship_check` CHECK already admits exactly
   * the four member values, so an unknown string is row corruption and throws
   * naming the column and the row — never softened to
   * [SupporterRelationship.SUPPORTER], which would tell a family that a
   * grandmother was recorded as nothing in particular.
   *
   * [rowId] is the TYPED id the caller has already built, not the column read a
   * second time: two reads of one column are two chances to name a different
   * row than the one being mapped.
   */
  private fun parseRelationship(
    value: String,
    rowId: SupporterStudentLinkId,
  ): SupporterRelationship =
    SupporterRelationship.fromValue(value)
      ?: throw corruptValue(
        value,
        "SupporterRelationship",
        location = "supporter_student_links.relationship (row [${rowId.asString}])",
      )

  /**
   * Creates an ACTIVE link. The id comes from the column's `uuidv7()` default.
   *
   * `supporter_student_links_supporter_student_active_idx` is PARTIAL, so a
   * second ACTIVE row for the same pair is refused while re-linking a severed
   * pair succeeds — which is the whole point of the index, and the reason this
   * is an insert rather than a resurrection of the soft-deleted row.
   */
  override fun create(
    session: SqlSession,
    input: NewSupporterStudentLink,
  ): Result<SupporterStudentLink> =
    session.insertReturning(
      table = "supporter_student_links",
      columns =
        linkedMapOf<String, Bind>(
          "supporter_id" to { stmt, i -> stmt.setObject(i, input.supporterId.value) },
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "relationship" to { stmt, i -> stmt.setString(i, input.relationship.value) },
        ),
      map = ::mapLink,
      mapError = mapCreateError(input),
    )

  /**
   * The active link for one pair, or null when the two are not linked.
   *
   * Null rather than [NotFoundException]: "these two are not linked" is the
   * ordinary state of almost every pair, and it is exactly the answer an
   * authorisation check asks for.
   */
  fun findActive(
    session: SqlSession,
    supporterId: SupporterId,
    studentId: StudentId,
  ): Result<SupporterStudentLink?> =
    session
      .queryOne(
        """
        SELECT * FROM supporter_student_links
        WHERE supporter_id = ? AND student_id = ? AND deleted_at IS NULL
        """.trimIndent(),
        bind = { stmt ->
          stmt.setObject(1, supporterId.value)
          stmt.setObject(2, studentId.value)
        },
        map = ::mapLink,
        onNoRow = { NotFoundException() },
      ).orNullOnNotFound()

  /**
   * WHICH end of the link a side read filters on: the column, and the id to
   * bind, held TOGETHER in one value that only a typed id can construct.
   *
   * The pair used to be a `(column: String, id: UUID)` parameter list, which
   * erased [SupporterId] and [StudentId] to the same type: `"student_id"` with a
   * supporter's id compiled, ran, and answered an empty list — a wrong answer
   * that looks exactly like a right one. Here the column is not something a call
   * site passes at all.
   */
  private sealed class LinkSide(
    val column: String,
    val id: UUID,
  ) {
    class Supporter(
      id: SupporterId,
    ) : LinkSide("supporter_id", id.value)

    class Student(
      id: StudentId,
    ) : LinkSide("student_id", id.value)
  }

  /** This student's active supporters — the read `supporter_student_links_student_idx` exists for. */
  fun listForStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<SupporterStudentLink>> = listActiveBy(session, LinkSide.Student(studentId))

  /**
   * This supporter's active students — served by the leading column of
   * `supporter_student_links_supporter_student_active_idx`, which is why there
   * is no supporter-only index.
   */
  fun listForSupporter(
    session: SqlSession,
    supporterId: SupporterId,
  ): Result<List<SupporterStudentLink>> = listActiveBy(session, LinkSide.Supporter(supporterId))

  /**
   * The two side reads are ONE query: same predicate, same tie-break, one bound
   * id, and only the column differs. Written twice, `deleted_at IS NULL` or the
   * `ORDER BY` could be corrected on one side and not the other, and the two
   * directions of the same link table would disagree about what "active" means.
   *
   * [LinkSide] owns the column name — a fixed DAO identifier, never caller data
   * — and carries the id that goes with it; only the bound id varies.
   */
  private fun listActiveBy(
    session: SqlSession,
    side: LinkSide,
  ): Result<List<SupporterStudentLink>> =
    session.queryList(
      """
      SELECT * FROM supporter_student_links
      WHERE ${side.column} = ? AND deleted_at IS NULL
      ORDER BY created_at, id
      """.trimIndent(),
      bind = { it.setObject(1, side.id) },
      map = ::mapLink,
    )

  /**
   * Compare-and-swap correction of the relationship word on a LIVE link at
   * [currentVersion], returning the updated row.
   *
   * A correction is a correction, not a new relationship: it is an in-place
   * UPDATE of the SAME row, and the `AFTER UPDATE` snapshot puts the old word in
   * `supporter_student_links_versions`, so "she was recorded as a parent until
   * March" stays answerable.
   *
   * `deleted_at IS NULL` rides in the WHERE, so only a LIVE link is correctable.
   * A severed link is history, and the history table exists so the past is not
   * rewritten; a re-link is a new row and takes the correct word at insert.
   *
   * The two failures are told apart by [occUpdate] rather than collapsed into
   * one null: [NotFoundException] for an id no link has, and
   * [ConcurrentModificationException] for a row that IS there and did not take
   * this write — a stale version, or a link severed since the caller read it.
   *
   * `version = version + 1` is not decoration: `enforce_versioning` REFUSES any
   * UPDATE whose version is not `OLD.version + 1`, with SQLSTATE `40001`.
   */
  fun updateRelationship(
    session: SqlSession,
    id: SupporterStudentLinkId,
    currentVersion: Int,
    relationship: SupporterRelationship,
  ): Result<SupporterStudentLink> =
    session.occUpdate(
      table = "supporter_student_links",
      sql =
        """
        UPDATE supporter_student_links
        SET relationship = ?, version = version + 1
        WHERE id = ? AND version = ? AND deleted_at IS NULL
        RETURNING *
        """.trimIndent(),
      bind = { stmt ->
        stmt.setString(1, relationship.value)
        stmt.setObject(2, id.value)
        stmt.setInt(3, currentVersion)
      },
      idValue = id.value,
      map = ::mapLink,
      mapError = ::mapUpdateError,
    )

  /**
   * Compare-and-swap unlink: stamps `deleted_at` on an ACTIVE link at
   * [currentVersion] and returns the severed row.
   *
   * `deleted_at IS NULL` rides in the WHERE on top of the version check, so a
   * second unlink cannot re-stamp a row that was already severed — which would
   * move the moment access ended. It reports
   * [ConcurrentModificationException]: the row is there and this write did not
   * apply, which is the truth whether somebody else severed it or the caller's
   * version went stale. An id no link has is [NotFoundException] — a different
   * mistake, on the spine that authorises every later slice.
   *
   * An UPDATE, never a DELETE: `prevent_physical_delete` refuses one outright,
   * and the snapshot of the row WITH its `deleted_at` set is what puts the
   * unlink itself into the history.
   */
  fun unlink(
    session: SqlSession,
    id: SupporterStudentLinkId,
    currentVersion: Int,
  ): Result<SupporterStudentLink> =
    session.occUpdate(
      table = "supporter_student_links",
      sql =
        """
        UPDATE supporter_student_links
        SET deleted_at = NOW(), version = version + 1
        WHERE id = ? AND version = ? AND deleted_at IS NULL
        RETURNING *
        """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, id.value)
        stmt.setInt(2, currentVersion)
      },
      idValue = id.value,
      map = ::mapLink,
      mapError = ::mapUpdateError,
    )

  /**
   * The create-path mapping, built from the ids that were written.
   *
   * Both foreign keys are reachable from ordinary input — a supporter or a
   * student that is not there — and each message NAMES the absent id: a
   * constant sentence tells a reader which column failed and leaves them to
   * find the value by hand. The active-pair index and the relationship CHECK
   * arrive as [ConstraintViolationException] carrying the constraint name, so
   * "already linked" is distinguishable from "not a relationship word" without
   * parsing log text.
   *
   * The `00d`/`00e` endpoint guards are mapped too, by [mapGuardRefusal]: they
   * are the write's other three refusals, and an unmapped one is a
   * [DatabaseException] that a caller can only classify by reading English.
   */
  private fun mapCreateError(input: NewSupporterStudentLink): (SQLException) -> Exception =
    { e ->
      mapGuardRefusal(e) ?: mapActiveLinkRefusal(e) ?: mapChildWriteError(
        e,
        mapOf(
          "supporter_student_links_supporter_id_fkey" to
            "No supporter [${input.supporterId.asString}] to link",
          "supporter_student_links_student_id_fkey" to
            "No student [${input.studentId.asString}] to link",
        ),
      )
    }

  /**
   * The one-ACTIVE-link-per-pair refusal, typed. Null when this failure is not
   * that index, so the ordinary SQLSTATE mapping still answers everything else.
   */
  private fun mapActiveLinkRefusal(e: SQLException): Exception? {
    val serverError = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
    if (serverError?.constraint != ACTIVE_LINK_INDEX) return null
    return ActiveSupporterLinkException(e, serverError.constraint, serverError.detail)
  }

  /**
   * The update-path mapping. No foreign key can fire — `supporter_id` and
   * `student_id` are frozen, so neither write changes an endpoint — but the
   * self-link guard is `INSERT OR UPDATE` and stays mapped, because the rule it
   * states does not stop holding on a later write.
   */
  private fun mapUpdateError(e: SQLException): Exception = mapGuardRefusal(e) ?: mapDatabaseError(e)

  /**
   * The three trigger refusals, routed by the CONSTRAINT name the guard raised;
   * null when this failure is not one of them, so a caller can fall through to
   * the ordinary SQLSTATE mapping.
   *
   * On the name, not on the message: all three are `P0001`, so the SQLSTATE
   * alone cannot tell them apart, and the sentence is prose a later migration
   * may reword. The server's own message rides into the typed exception because
   * the guard names BOTH endpoint ids in it.
   *
   * An ABSENT server message is not a second bail-out: the routing depends on
   * the constraint name alone, so such a refusal still gets its type and each
   * exception falls back to its own constant sentence. Bailing out there would
   * silently downgrade a refusal this mapper had already IDENTIFIED to a generic
   * [DatabaseException], which tells a caller less than the raw failure does.
   */
  private fun mapGuardRefusal(e: SQLException): Exception? {
    if (e.sqlState != SQLSTATE_RAISE_EXCEPTION) return null
    val serverError = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
    val constraint = serverError?.constraint ?: return null
    val message = serverError.message
    return when (constraint) {
      NOT_SELF_LINK -> SelfSupporterLinkException(message, e, constraint, serverError.detail)
      SUPPORTER_LIVE -> ClosedSupporterLinkException(message, e, constraint, serverError.detail)
      STUDENT_LIVE -> ClosedStudentLinkException(message, e, constraint, serverError.detail)
      else -> null
    }
  }
}
