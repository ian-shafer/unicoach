package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CommitmentSupportDao
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.SoftDeleteScope
import java.util.UUID

/**
 * The mutable `commitments` entity (RFC 93), surfaced read-only (RFC 77).
 * Mirrors [ClaimsResource]: a status-based mutable entity with no `deleted_at`
 * and no admin writes — all four write handlers are null, so the engine
 * registers no create/edit/delete routes and the detail page renders no
 * Edit/Delete/New affordance. `scope`/`includeDeleted` are ignored (no
 * `deleted_at` column).
 *
 * `statement` (up to 2048 chars) is `inList = false` — kept out of the list
 * table but shown in full on detail.
 */
object CommitmentsResource : AdminResource<Commitment, CommitmentId> {
  override val slug = "commitment"
  override val title = "Commitment"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "commitment"),
      AdminField("studentId", "Student ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
      AdminField("lens", "Lens", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("disclosure", "Disclosure", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("status", "Status", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("statement", "Statement", FieldType.MULTILINE, editable = false, sensitive = false, inList = false),
      AdminField("triggerKind", "Trigger Kind", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("triggerAt", "Trigger At", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("fulfilledAt", "Fulfilled", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField(
        "disclosedInConvoId",
        "Disclosed In Convo",
        FieldType.UUID,
        editable = false,
        sensitive = false,
        inList = false,
        refSlug = "convo",
      ),
      AdminField("droppedAt", "Dropped", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("dropReason", "Drop Reason", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
    )

  override val edges = listOf<AdminEdge>(AdminEdge.HasMany("Supporting claims", targetSlug = "claim"))

  override fun rowId(row: Commitment): CommitmentId = row.id

  override fun parseId(raw: String): CommitmentId? = runCatching { CommitmentId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: CommitmentId): String = id.value.toString()

  override fun isDeleted(row: Commitment): Boolean = false

  override fun cells(row: Commitment): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "lens" to row.lens.value,
      "disclosure" to row.disclosure.value,
      "status" to row.status.value,
      "createdAt" to row.createdAt.toString(),
      "statement" to row.statement,
      "triggerKind" to row.triggerKind.value,
      "triggerAt" to (row.triggerAt?.toString() ?: ""),
      "fulfilledAt" to (row.fulfilledAt?.toString() ?: ""),
      "disclosedInConvoId" to (row.disclosedInConvoId?.value?.toString() ?: ""),
      "droppedAt" to (row.droppedAt?.toString() ?: ""),
      "dropReason" to (row.dropReason ?: ""),
      "updatedAt" to row.updatedAt.toString(),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<Commitment>> = db.withConnection { session -> CommitmentsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: CommitmentId,
    includeDeleted: Boolean,
  ): Result<Commitment> = db.withConnection { session -> CommitmentsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<CommitmentId>)? = null
  override val update: (suspend (Database, CommitmentId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, CommitmentId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, CommitmentId) -> Result<Unit>)? = null

  /** One panel listing the claims cited as basis for this commitment; rows link to `/claim/{id}`. */
  override suspend fun resolveEdges(
    db: Database,
    row: Commitment,
  ): Result<List<EdgePanel>> {
    val claims =
      db
        .withConnection { session -> CommitmentSupportDao.listClaimsForCommitment(session, row.id) }
        .getOrElse { return Result.failure(it) }
    return Result.success(listOf(supportingClaimsPanel(claims)))
  }

  /** Pure builder: the "Supporting claims" panel. Rows link to `/claim/{id}`. */
  private fun supportingClaimsPanel(claims: List<Claim>): EdgePanel.Table =
    EdgePanel.Table(
      label = "Supporting claims",
      columns =
        listOf(
          EdgePanel.Table.Column("ID", refSlug = "claim"),
          EdgePanel.Table.Column("Status"),
          EdgePanel.Table.Column("Topic"),
          EdgePanel.Table.Column("Statement"),
        ),
      rows =
        claims.map { c ->
          EdgePanel.Table.Row(
            cells =
              listOf(
                c.id.value.toString(),
                c.status.value,
                c.topic.value,
                c.statement,
              ),
          )
        },
    )
}
