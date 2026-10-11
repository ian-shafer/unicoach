package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.LinkSide
import ed.unicoach.db.models.LinkedStudent
import ed.unicoach.db.models.LinkedSupporter
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.SupporterStudentLinkId
import ed.unicoach.db.models.UnlinkOutcome
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
   * The column a [LinkSide] names, and the id it binds — a fixed DAO
   * identifier, never caller data. The side is the only way to reach either, so
   * `"student_id"` with a supporter's id is not something a call site can write.
   */
  private val LinkSide.column: String
    get() =
      when (this) {
        is LinkSide.Student -> "student_id"
        is LinkSide.Supporter -> "supporter_id"
      }

  private val LinkSide.boundId: UUID
    get() =
      when (this) {
        is LinkSide.Student -> id.value
        is LinkSide.Supporter -> id.value
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
   * [LinkSide] decides the column name — a fixed DAO identifier, never caller
   * data — and carries the id that goes with it; only the bound id varies.
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
      bind = { it.setObject(1, side.boundId) },
      map = ::mapLink,
    )

  /**
   * This supporter's ACTIVE links with the display name of the student each one
   * points at (RFC 241) — the roster a parent's home surface is built from.
   *
   * ONE statement, not [listForSupporter] plus a lookup per row: `StudentsDao`
   * has no batch read, so the composed form is N+1 on a list rendered at every
   * app launch. The leading column of
   * `supporter_student_links_supporter_student_active_idx` serves the filter.
   *
   * The load-bearing reason it is its own read, though, is [LinkedStudent]: the
   * projection cannot leak the student's `dateOfBirth` or expected graduation
   * date, because it selects neither and the type holds neither. See that
   * type's KDoc.
   *
   * `deleted_at IS NULL` is asserted on the LINK, on the STUDENT and on the
   * USER. Closing an account severs its links and soft-deletes its role rows and
   * its `users` row in one transaction (`AccountClosureService`), so the last two
   * predicates are redundant for a close — and they are what keeps this read's
   * answer correct for any other path that closes one row on its own. A boundary
   * belongs in the statement that admits the rows, not in an argument about
   * another module's transaction. The join to `users` is INNER because
   * `students.user_id` is a foreign key — a student with no user row is not a
   * state this read may invent a name for.
   *
   * The tie-break is [listActiveBy]'s: `created_at` alone is not a total order
   * inside one transaction, where every row takes the same `NOW()`.
   */
  fun listActiveLinkedStudents(
    session: SqlSession,
    supporterId: SupporterId,
  ): Result<List<LinkedStudent>> =
    session.queryList(
      """
      SELECT l.id AS link_id, l.student_id, l.relationship, u.name AS student_name
      FROM supporter_student_links l
      JOIN students s ON s.id = l.student_id
      JOIN users u ON u.id = s.user_id
      WHERE l.supporter_id = ?
        AND l.deleted_at IS NULL
        AND s.deleted_at IS NULL
        AND u.deleted_at IS NULL
      ORDER BY l.created_at, l.id
      """.trimIndent(),
      bind = { it.setObject(1, supporterId.value) },
      map = ::mapLinkedStudent,
    )

  /**
   * The roster projection. The columns are ALIASED in the query above and read
   * back by those aliases, because three tables are joined and `id` belongs to
   * all three.
   *
   * A corrupt `relationship` throws here exactly as it does in [mapLink] — the
   * CHECK admits only the four words, so an unknown one is corruption and not a
   * row to soften — and a corrupt `users.name` throws for the same reason
   * `UsersDao` lets its own reconstruction fail: `users_name_length_check`
   * already refuses blank and over-long names.
   */
  private fun mapLinkedStudent(rs: ResultSet): LinkedStudent {
    val linkId = SupporterStudentLinkId(UUID.fromString(rs.getString("link_id")))
    val rawName = rs.getString("student_name")
    return LinkedStudent(
      linkId = linkId,
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      relationship = parseRelationship(rs.getString("relationship"), linkId),
      studentName = parseName(rawName, "users.name (student of link [${linkId.asString}])"),
    )
  }

  /** One row of [listActiveSupportersOf]; an invalid stored name is corruption, as in [mapLinkedStudent]. */
  private fun mapLinkedSupporter(rs: ResultSet): LinkedSupporter {
    val linkId = SupporterStudentLinkId(UUID.fromString(rs.getString("link_id")))
    return LinkedSupporter(
      linkId = linkId,
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      name = parseName(rs.getString("supporter_name"), "users.name (supporter of link [${linkId.asString}])"),
    )
  }

  /**
   * A stored `users.name` back into a [PersonName], for every projection that
   * renders one. `users_name_length_check` already refuses blank and over-long
   * names, so an invalid one is corruption and throws naming [location].
   */
  private fun parseName(
    rawName: String,
    location: String,
  ): PersonName =
    when (val name = PersonName.create(rawName)) {
      is ValidationResult.Valid -> {
        name.value
      }

      // The validator already said WHY — blank, or over-long — so its own
      // [ValidationError] travels out instead of the synthesised "a known
      // value" sentence [corruptValue] is for: an operator reading the log
      // can tell a blank stored name from a 300-character one.
      is ValidationResult.Invalid -> {
        throw CorruptPersistedValueException(value = rawName, error = name.error, location = location)
      }
    }

  /**
   * The ACTIVE adults on [studentIds]' links, each by the name on their own
   * account, oldest link first (RFC 249) — the ONE read that names a connected
   * adult: the student's own list, and the co-supporters on a supporter's home,
   * which passes its roster's students and drops its own links.
   *
   * The three `deleted_at` predicates are on the LINK, the SUPPORTER and the
   * supporter's USER, because closing either account leaves its links standing
   * (the liveness guard fires on INSERT only), and a closed adult's name must not
   * be listed as somebody who can see a student. Served by
   * `supporter_student_links_student_idx`.
   *
   * [LinkedSupporter] cannot carry an email: this read selects none.
   */
  fun listActiveSupportersOf(
    session: SqlSession,
    studentIds: Collection<StudentId>,
  ): Result<List<LinkedSupporter>> {
    val ids = IdList(studentIds.map { it.value })
    if (ids.isEmpty) return Result.success(emptyList())
    return session.queryList(
      """
      SELECT l.id AS link_id, l.student_id, u.name AS supporter_name
      FROM supporter_student_links l
      JOIN supporters sup ON sup.id = l.supporter_id
      JOIN users u ON u.id = sup.user_id
      WHERE l.student_id IN (${ids.placeholders})
        AND l.deleted_at IS NULL
        AND sup.deleted_at IS NULL
        AND u.deleted_at IS NULL
      ORDER BY l.created_at, l.id
      """.trimIndent(),
      bind = { stmt -> ids.bindFrom(stmt, 1) },
      map = ::mapLinkedSupporter,
    )
  }

  /** This one student's active adults — the student's own list. */
  fun listActiveSupportersOf(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<LinkedSupporter>> = listActiveSupportersOf(session, listOf(studentId))

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
   * Severs [linkId] when [side] holds it (RFC 249): stamps `deleted_at` on the
   * ACTIVE link whose [LinkSide] column is the caller's id.
   *
   * The side IS the authorisation. It rides in the same WHERE that severs the
   * row, so there is no read-then-write in which a link could change hands, and
   * "not yours", "never existed" and "already severed" all match nothing and are
   * one [UnlinkOutcome.NotFound] — a door on this cannot be used to probe ids.
   *
   * No version check: the write has one target state, severed, so a stale
   * version would refuse nothing worth refusing. Two concurrent disconnects
   * resolve to one [UnlinkOutcome.Unlinked] and one [UnlinkOutcome.NotFound],
   * and `deleted_at IS NULL` keeps the second from re-stamping the moment access
   * ended. `version = version + 1` is still required: `enforce_versioning`
   * refuses any UPDATE that does not advance it.
   *
   * An UPDATE, never a DELETE: `prevent_physical_delete` refuses one outright,
   * and the snapshot of the row WITH its `deleted_at` set is what puts the
   * unlink itself into the history. [UnlinkOutcome.Unlinked] carries that row.
   *
   * `id` is the primary key, so more than one severed row is not an answer: it
   * fails the call rather than being reported as either outcome.
   */
  fun unlink(
    session: SqlSession,
    linkId: SupporterStudentLinkId,
    side: LinkSide,
  ): Result<UnlinkOutcome> =
    sever(session, side, linkId).mapCatching { severed ->
      when (severed.size) {
        0 -> UnlinkOutcome.NotFound
        1 -> UnlinkOutcome.Unlinked(severed.single())
        else -> error("unlink of link [${linkId.asString}] severed [${severed.size}] rows; supporter_student_links.id is the primary key")
      }
    }

  /**
   * Severs EVERY active link [side] holds (RFC 252) — the account close's
   * write, one statement per side. Returns the severed rows, so the caller can
   * tell each other party the link ended; empty when [side] held none.
   *
   * The same [sever] write as [unlink], without the id. The `AFTER UPDATE`
   * snapshot writes each sever into `supporter_student_links_versions`, exactly
   * as for one unlink.
   */
  fun severAll(
    session: SqlSession,
    side: LinkSide,
  ): Result<List<SupporterStudentLink>> = sever(session, side, linkId = null)

  /**
   * The ONE sever write, shared by [unlink] (narrowed to [linkId]) and
   * [severAll] (every link [side] holds), so a close and a disconnect cannot
   * store different severed rows. The side rides in the WHERE that severs;
   * `deleted_at IS NULL` keeps an already-severed link's moment from being
   * re-stamped, and `version = version + 1` satisfies `enforce_versioning`.
   */
  private fun sever(
    session: SqlSession,
    side: LinkSide,
    linkId: SupporterStudentLinkId?,
  ): Result<List<SupporterStudentLink>> =
    session.queryList(
      """
      UPDATE supporter_student_links
      SET deleted_at = NOW(), version = version + 1
      WHERE ${side.column} = ? AND deleted_at IS NULL${if (linkId != null) " AND id = ?" else ""}
      RETURNING *
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, side.boundId)
        if (linkId != null) stmt.setObject(2, linkId.value)
      },
      map = ::mapLink,
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
   * `student_id` are frozen, so no update changes an endpoint — but the
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
