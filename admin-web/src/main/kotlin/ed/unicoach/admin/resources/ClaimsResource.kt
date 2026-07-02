package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ClaimSupportDao
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.SoftDeleteScope
import java.util.UUID

/**
 * The mutable `claims` entity (RFC 66), surfaced read-only (RFC 77). `claims` is
 * revisable in the domain but the admin exposes no writes: all four write
 * handlers are null, so the engine registers no create/edit/delete routes and the
 * detail page renders no Edit/Delete/New affordance. The table carries no
 * `deleted_at`, so `scope`/`includeDeleted` are ignored (same posture as
 * [SessionsResource]/[SystemPromptsResource]).
 *
 * `statement` (up to 2048 chars) is `inList = false` — kept out of the list table
 * but shown in full on detail, the treatment `system_prompts.body` receives.
 */
object ClaimsResource : AdminResource<Claim, ClaimId> {
  override val slug = "claim"
  override val title = "Claim"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "claim"),
      AdminField("studentId", "Student ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
      AdminField("status", "Status", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("kind", "Kind", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("topic", "Topic", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("confidence", "Confidence", FieldType.INT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("origin", "Origin", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("subject", "Subject", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("visibility", "Visibility", FieldType.TEXT, editable = false, sensitive = false, inList = false),
      AdminField("statement", "Statement", FieldType.MULTILINE, editable = false, sensitive = false, inList = false),
      AdminField("supersededById", "Superseded By", FieldType.UUID, editable = false, sensitive = false, inList = false, refSlug = "claim"),
      AdminField("supersededAt", "Superseded", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("retractedAt", "Retracted", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
    )

  override val edges = listOf<AdminEdge>(AdminEdge.HasMany("Supporting observations", targetSlug = "observation"))

  override fun rowId(row: Claim): ClaimId = row.id

  override fun parseId(raw: String): ClaimId? = runCatching { ClaimId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: ClaimId): String = id.value.toString()

  override fun isDeleted(row: Claim): Boolean = false

  override fun cells(row: Claim): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "studentId" to row.studentId.value.toString(),
      "status" to row.status.value,
      "kind" to row.kind.value,
      "topic" to row.topic.value,
      "confidence" to row.confidence.toString(),
      "createdAt" to row.createdAt.toString(),
      "origin" to row.origin.value,
      "subject" to row.subject.value,
      "visibility" to row.visibility.value,
      "statement" to row.statement,
      "supersededById" to (row.supersededById?.value?.toString() ?: ""),
      "supersededAt" to (row.supersededAt?.toString() ?: ""),
      "retractedAt" to (row.retractedAt?.toString() ?: ""),
      "updatedAt" to row.updatedAt.toString(),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<Claim>> = db.withConnection { session -> ClaimsDao.list(session, limit, offset) }

  override suspend fun get(
    db: Database,
    id: ClaimId,
    includeDeleted: Boolean,
  ): Result<Claim> = db.withConnection { session -> ClaimsDao.findById(session, id) }

  override val create: (suspend (Database, Map<String, String>) -> Result<ClaimId>)? = null
  override val update: (suspend (Database, ClaimId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, ClaimId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, ClaimId) -> Result<Unit>)? = null

  /** One panel listing the observations backing this claim; rows link to `/observation/{id}`. */
  override suspend fun resolveEdges(
    db: Database,
    row: Claim,
  ): Result<List<EdgePanel>> {
    val observations =
      db
        .withConnection { session -> ClaimSupportDao.listObservationsForClaim(session, row.id) }
        .getOrElse { return Result.failure(it) }
    return Result.success(listOf(supportingObservationsPanel(observations)))
  }

  /**
   * Pure builder: the "Supporting observations" panel. The observation is the
   * only bridge from a claim to a conversation, so the `Convo` / `Source Request`
   * links ride on the observations already shown (the generalized
   * claim→conversation path). Rows link to `/observation/{id}`, with the
   * conversation cells linking to `/convo/{id}` and `/convo-request/{id}`.
   */
  private fun supportingObservationsPanel(observations: List<Observation>): EdgePanel.Table =
    EdgePanel.Table(
      label = "Supporting observations",
      columns =
        listOf(
          EdgePanel.Table.Column("ID", refSlug = "observation"),
          EdgePanel.Table.Column("Convo", refSlug = "convo"),
          EdgePanel.Table.Column("Source Request", refSlug = "convo-request"),
          EdgePanel.Table.Column("Uttered", FieldType.TIMESTAMP),
          EdgePanel.Table.Column("Quote"),
        ),
      rows =
        observations.map { o ->
          EdgePanel.Table.Row(
            cells =
              listOf(
                o.id.value.toString(),
                o.convoId.value.toString(),
                o.sourceRequestId.value.toString(),
                o.utteredAt.toString(),
                o.quote,
              ),
          )
        },
    )
}
