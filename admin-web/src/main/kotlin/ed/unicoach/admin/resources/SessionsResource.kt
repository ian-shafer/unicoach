package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.SessionId
import ed.unicoach.db.models.SoftDeleteScope
import java.util.UUID

/**
 * The `sessions` entity. No create (rows originate from auth flows) and no update
 * (its DAO mutators are domain-specific lifecycle operations, not field edits).
 * Delete is physical — `sessions` carries no `prevent_physical_delete` trigger.
 * `sessions` has no `deleted_at`, so the `scope` argument is ignored.
 */
object SessionsResource : AdminResource<Session, SessionId> {
  override val slug = "session"
  override val title = "Session"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "session"),
      AdminField("userId", "User ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "user"),
      AdminField("tokenHash", "Token Hash", FieldType.TEXT, editable = false, sensitive = true),
      AdminField("userAgent", "User Agent", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("initialIp", "Initial IP", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("version", "Version", FieldType.INT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("expiresAt", "Expires", FieldType.TIMESTAMP, editable = false, sensitive = false),
    )

  override val edges = listOf<AdminEdge>(AdminEdge.Parent("Owner", targetSlug = "user"))

  override fun rowId(row: Session): SessionId = row.id

  override fun parseId(raw: String): SessionId? = runCatching { SessionId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: SessionId): String = id.value.toString()

  override fun isDeleted(row: Session): Boolean = false

  override fun cells(row: Session): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "userId" to (row.userId?.value?.toString() ?: ""),
      "userAgent" to (row.userAgent ?: ""),
      "initialIp" to (row.initialIp ?: ""),
      "version" to row.version.toString(),
      "createdAt" to row.createdAt.toString(),
      "expiresAt" to row.expiresAt.toString(),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<Session>> = db.withConnection { session -> SessionsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: SessionId,
    includeDeleted: Boolean,
  ): Result<Session> = db.withConnection { session -> SessionsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<SessionId>)? = null
  override val update: (suspend (Database, SessionId, Map<String, String>) -> Result<Unit>)? = null

  override val delete: (suspend (Database, SessionId) -> Result<Unit>) =
    { db, id -> db.withConnection { session -> SessionsDao.destroy(session, id) } }

  override val undelete: (suspend (Database, SessionId) -> Result<Unit>)? = null

  override suspend fun resolveEdges(
    db: Database,
    row: Session,
  ): Result<List<EdgePanel>> {
    val userId = row.userId
    val panel =
      if (userId == null) {
        EdgePanel.ParentAbsent("Owner")
      } else {
        EdgePanel.ParentLink(
          label = "Owner",
          href = "/user/${userId.value}",
          summary = "User ${userId.value}",
        )
      }
    return Result.success(listOf(panel))
  }
}
