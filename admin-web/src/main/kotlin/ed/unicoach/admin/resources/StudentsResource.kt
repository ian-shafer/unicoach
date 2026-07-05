package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.CustomAction
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.common.json.asJson
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.ExtractionRunsDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId
import ed.unicoach.queue.FitLensPayload
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.SynthesisPayload
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Cap on each coaching-memory panel nested under the student profile (RFC 77),
 * mirroring the sessions panel's `limit = 50`. A student with more memory shows
 * the first page only; full enumeration is via the global `/{slug}` lists.
 */
private const val STUDENT_PANEL_LIMIT = 50

/**
 * Maps the admin engine's `includeDeleted` flag to a [SoftDeleteScope] at the DAO
 * call boundary: `true` admits soft-deleted rows ([SoftDeleteScope.ALL]), `false`
 * restricts to active rows ([SoftDeleteScope.ACTIVE]).
 */
internal fun Boolean.toScope(): SoftDeleteScope = if (this) SoftDeleteScope.ALL else SoftDeleteScope.ACTIVE

/**
 * The `students` profile. A top-level [AdminKind.ENTITY] with a real `/student`
 * list/detail read surface (RFC 100), while staying embedded under
 * [UsersResource] via that resource's [AdminEdge.Embedded] edge — the same
 * dual-registration precedent as [ConvosResource]. Mutations
 * (create/update/delete/undelete) still flow only through the owner-nested
 * endpoints [UsersResource] registers; this resource adds a read/trigger surface
 * only, so all four write handlers stay null.
 *
 * Two per-row [CustomAction]s enqueue the same jobs the automatic paths use —
 * `SYNTHESIZE_STUDENT` and `FIT_LENS` — with no bypass flag, so a click hits the
 * identical freshness gate / circuit-breaker a scheduled run would (the
 * manual-trigger invariant).
 */
class StudentsResource(
  private val queueService: QueueService,
) : AdminResource<Student, StudentId> {
  private val logger = LoggerFactory.getLogger(StudentsResource::class.java)

  override val slug = "student"
  override val title = "Student"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "student"),
      AdminField("userId", "User ID", FieldType.UUID, editable = false, sensitive = false, refSlug = "user"),
      AdminField(
        "expectedHighSchoolGraduationDate",
        "Expected HS Graduation",
        FieldType.TEXT,
        editable = true,
        sensitive = false,
      ),
      AdminField("version", "Version", FieldType.INT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("deletedAt", "Deleted", FieldType.TIMESTAMP, editable = false, sensitive = false),
    )

  override val edges = listOf<AdminEdge>(AdminEdge.History("Version history"))

  /** The single editable field on the embedded create/edit forms. */
  val editableFields: List<AdminField> = fields.filter { it.editable }

  override fun rowId(row: Student): StudentId = row.id

  override fun parseId(raw: String): StudentId? = runCatching { StudentId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: StudentId): String = id.value.toString()

  override fun isDeleted(row: Student): Boolean = row.deletedAt != null

  override fun cells(row: Student): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "userId" to row.userId.value.toString(),
      "expectedHighSchoolGraduationDate" to row.expectedHighSchoolGraduationDate.toIso(),
      "version" to row.version.toString(),
      "createdAt" to row.createdAt.toString(),
      "updatedAt" to row.updatedAt.toString(),
      "deletedAt" to (row.deletedAt?.toString() ?: ""),
    )

  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<Student>> = db.withConnection { session -> StudentsDao.list(session, scope, limit, offset) }

  override suspend fun get(
    db: Database,
    id: StudentId,
    includeDeleted: Boolean,
  ): Result<Student> = db.withConnection { session -> StudentsDao.findById(session, id, includeDeleted.toScope()) }

  override val create: (suspend (Database, Map<String, String>) -> Result<StudentId>)? = null
  override val update: (suspend (Database, StudentId, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, StudentId) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, StudentId) -> Result<Unit>)? = null

  /**
   * The two student-scoped manual triggers (RFC 100). Both are always enabled
   * (`disabledReason = { null }`): there is no in-flight/spam guard because the
   * downstream gates already no-op a redundant run. The [CustomAction.helpText]
   * documents what makes each a no-op; it is static caption text, not a live
   * per-row gate check.
   */
  override val customActions =
    listOf(
      CustomAction<Student>(
        label = "Trigger synthesis",
        pathSuffix = "trigger-synthesis",
        disabledReason = { null },
        helpText =
          "No-ops if nothing has changed in this student's claims or college list " +
            "since the last applied synthesis run (freshness gate).",
      ),
      CustomAction<Student>(
        label = "Trigger fit-lens",
        pathSuffix = "trigger-fit-lens",
        disabledReason = { null },
        helpText =
          "Skipped if too few claims, no changes since the last applied run, or too many " +
            "consecutive failures (circuit breaker).",
      ),
    )

  /**
   * Registers the two trigger routes. Each parses the id (malformed → redirect to
   * the list, per the extra-route convention), enqueues the identical job
   * type/payload the automatic path uses via a fire-and-forget [QueueService]
   * (not session-threaded — not a required enqueue under ASYNC_WORK.md Rule 2),
   * and on success redirects to the row's own detail page — matching
   * [PeriodicJobsResource] (no confirmation banner). A queue failure renders the
   * shared DAO-error page.
   */
  override fun registerExtraRoutes(
    scope: Route,
    db: Database,
  ) {
    scope.post("/$slug/{id}/trigger-synthesis") {
      val id = parseId(call.parameters["id"].orEmpty()) ?: return@post call.respondRedirect("/$slug")
      val payload = SynthesisPayload(id.value.toString()).asJson()
      val result = queueService.enqueue(JobType.SYNTHESIZE_STUDENT, payload)
      call.respondEnqueueOutcome(result, "/$slug/${id.value}", "synthesis", id.value, logger)
    }
    scope.post("/$slug/{id}/trigger-fit-lens") {
      val id = parseId(call.parameters["id"].orEmpty()) ?: return@post call.respondRedirect("/$slug")
      val payload = FitLensPayload(id.value.toString()).asJson()
      val result = queueService.enqueue(JobType.FIT_LENS, payload)
      call.respondEnqueueOutcome(result, "/$slug/${id.value}", "fit-lens", id.value, logger)
    }
  }

  /**
   * Builds the inline embedded panel for a user: the student profile (if any),
   * its edit/create forms, and the nested `students_versions` history table.
   *
   * A missing profile is a successful "no profile yet" panel; a transient DB
   * failure on the profile or version-history load is propagated as a failed
   * [Result] so the detail route renders the correct error page rather than an
   * empty panel that masks the fault.
   */
  suspend fun buildPanel(
    db: Database,
    userId: UserId,
  ): Result<EdgePanel.Embedded> {
    val studentResult =
      db.withConnection { session -> StudentsDao.findByUserId(session, userId, SoftDeleteScope.ALL) }

    val student =
      when (val e = studentResult.exceptionOrNull()) {
        null -> studentResult.getOrThrow()
        is NotFoundException -> null
        else -> return Result.failure(e)
      }

    if (student == null) {
      return Result.success(
        EdgePanel.Embedded(
          label = title,
          ownerSlug = "user",
          ownerId = userId.value.toString(),
          present = false,
          fields = emptyList(),
          editValues = emptyMap(),
          version = null,
          deleted = false,
          createFields = editableFields,
          editFields = editableFields,
          nested = emptyList(),
        ),
      )
    }

    val cells = cells(student)
    val versions =
      db
        .withConnection { session -> StudentsDao.listVersions(session, student.id) }
        .getOrElse { return Result.failure(it) }

    val historyPanel =
      EdgePanel.Table(
        label = "Version history",
        columns =
          listOf(
            EdgePanel.Table.Column("Version"),
            EdgePanel.Table.Column("Graduation"),
            EdgePanel.Table.Column("Updated", FieldType.TIMESTAMP),
            EdgePanel.Table.Column("Deleted", FieldType.TIMESTAMP),
          ),
        rows =
          versions.map { v ->
            EdgePanel.Table.Row(
              cells =
                listOf(
                  v.entity.version.toString(),
                  v.entity.expectedHighSchoolGraduationDate.toIso(),
                  v.entity.updatedAt.toString(),
                  v.entity.deletedAt?.toString() ?: "",
                ),
            )
          },
      )

    // The student's coaching memory (RFC 77): each panel is built by its own
    // helper, which fetches at most STUDENT_PANEL_LIMIT rows and short-circuits
    // (getOrElse { return Result.failure(it) }) on a transient DAO fault so the
    // user page renders the DAO-error page rather than masking the fault.
    val conversationsPanel = buildConversationsPanel(db, student.id).getOrElse { return Result.failure(it) }
    val claimsPanel = buildClaimsPanel(db, student.id).getOrElse { return Result.failure(it) }
    val observationsPanel = buildObservationsPanel(db, student.id).getOrElse { return Result.failure(it) }
    val extractionRunsPanel = buildExtractionRunsPanel(db, student.id).getOrElse { return Result.failure(it) }

    return Result.success(
      EdgePanel.Embedded(
        label = title,
        ownerSlug = "user",
        ownerId = userId.value.toString(),
        present = true,
        fields =
          fields.filterNot { it.sensitive }.map { field ->
            EdgePanel.LabeledCell(
              label = field.label,
              type = field.type,
              refSlug = field.refSlug,
              value = cells[field.name] ?: "",
            )
          },
        editValues = cells,
        version = student.version,
        deleted = student.deletedAt != null,
        createFields = editableFields,
        editFields = editableFields,
        nested = listOf(historyPanel, conversationsPanel, claimsPanel, observationsPanel, extractionRunsPanel),
      ),
    )
  }

  /**
   * The student's conversations panel (first [STUDENT_PANEL_LIMIT]); rows link to
   * `/convo/{id}` (RFC 81). Admin reads pass [ArchiveScope.ALL] /
   * [SoftDeleteScope.ALL] so archived and deleted convos are visible.
   */
  private suspend fun buildConversationsPanel(
    db: Database,
    studentId: StudentId,
  ): Result<EdgePanel.Table> {
    val convos =
      db
        .withConnection { session ->
          ConvosDao.listByStudentWithActivity(
            session,
            studentId,
            ArchiveScope.ALL,
            SoftDeleteScope.ALL,
            STUDENT_PANEL_LIMIT,
            0,
          )
        }.getOrElse { return Result.failure(it) }
    val columns =
      listOf(
        EdgePanel.Table.Column("ID", FieldType.UUID, refSlug = "convo"),
        EdgePanel.Table.Column("Name"),
        EdgePanel.Table.Column("Last Activity", FieldType.TIMESTAMP),
        EdgePanel.Table.Column("Created", FieldType.TIMESTAMP),
        EdgePanel.Table.Column("Archived", FieldType.TIMESTAMP),
        EdgePanel.Table.Column("Deleted", FieldType.TIMESTAMP),
      )
    val rows =
      convos.map { c ->
        EdgePanel.Table.Row(
          cells =
            listOf(
              c.convo.id.value
                .toString(),
              c.convo.name.value,
              c.lastActivityAt?.toString() ?: "",
              c.convo.createdAt.toString(),
              c.convo.archivedAt?.toString() ?: "",
              c.convo.deletedAt?.toString() ?: "",
            ),
        )
      } + listOfNotNull(truncationRow(convos.size, STUDENT_PANEL_LIMIT, columns.size, "convo"))
    return Result.success(EdgePanel.Table(label = "Conversations", columns = columns, rows = rows))
  }

  /** The student's claims panel (first [STUDENT_PANEL_LIMIT]); rows link to `/claim/{id}`. */
  private suspend fun buildClaimsPanel(
    db: Database,
    studentId: StudentId,
  ): Result<EdgePanel.Table> {
    val claims =
      db
        .withConnection { session -> ClaimsDao.listByStudent(session, studentId, STUDENT_PANEL_LIMIT, 0) }
        .getOrElse { return Result.failure(it) }
    val columns =
      listOf(
        EdgePanel.Table.Column("ID", FieldType.UUID, refSlug = "claim"),
        EdgePanel.Table.Column("Status"),
        EdgePanel.Table.Column("Topic"),
        EdgePanel.Table.Column("Confidence"),
        EdgePanel.Table.Column("Created", FieldType.TIMESTAMP),
      )
    val rows =
      claims.map { c ->
        EdgePanel.Table.Row(
          cells =
            listOf(
              c.id.value.toString(),
              c.status.value,
              c.topic.value,
              c.confidence.toString(),
              c.createdAt.toString(),
            ),
        )
      } + listOfNotNull(truncationRow(claims.size, STUDENT_PANEL_LIMIT, columns.size, "claim"))
    return Result.success(EdgePanel.Table(label = "Claims", columns = columns, rows = rows))
  }

  /** The student's observations panel (first [STUDENT_PANEL_LIMIT]); rows link to `/observation/{id}`. */
  private suspend fun buildObservationsPanel(
    db: Database,
    studentId: StudentId,
  ): Result<EdgePanel.Table> {
    val observations =
      db
        .withConnection { session -> ObservationsDao.listByStudent(session, studentId, STUDENT_PANEL_LIMIT, 0) }
        .getOrElse { return Result.failure(it) }
    val columns =
      listOf(
        // BIGINT id — stays TEXT; UUID compaction (RFC 83) applies to UUID columns only
        EdgePanel.Table.Column("ID", refSlug = "observation"),
        EdgePanel.Table.Column("Convo ID", FieldType.UUID, refSlug = "convo"),
        EdgePanel.Table.Column("Uttered", FieldType.TIMESTAMP),
        EdgePanel.Table.Column("Created", FieldType.TIMESTAMP),
      )
    val rows =
      observations.map { o ->
        EdgePanel.Table.Row(
          cells =
            listOf(
              o.id.value.toString(),
              o.convoId.value.toString(),
              o.utteredAt.toString(),
              o.createdAt.toString(),
            ),
        )
      } + listOfNotNull(truncationRow(observations.size, STUDENT_PANEL_LIMIT, columns.size, "observation"))
    return Result.success(EdgePanel.Table(label = "Observations", columns = columns, rows = rows))
  }

  /** The student's extraction-runs panel (first [STUDENT_PANEL_LIMIT]); rows link to `/extraction-run/{id}`. */
  private suspend fun buildExtractionRunsPanel(
    db: Database,
    studentId: StudentId,
  ): Result<EdgePanel.Table> {
    val extractionRuns =
      db
        .withConnection { session -> ExtractionRunsDao.listByStudent(session, studentId, STUDENT_PANEL_LIMIT, 0) }
        .getOrElse { return Result.failure(it) }
    val columns =
      listOf(
        // BIGINT id — stays TEXT; UUID compaction (RFC 83) applies to UUID columns only
        EdgePanel.Table.Column("ID", refSlug = "extraction-run"),
        EdgePanel.Table.Column("Outcome"),
        EdgePanel.Table.Column("Model"),
        EdgePanel.Table.Column("Input Tokens"),
        EdgePanel.Table.Column("Output Tokens"),
        EdgePanel.Table.Column("Created", FieldType.TIMESTAMP),
      )
    val rows =
      extractionRuns.map { r ->
        EdgePanel.Table.Row(
          cells =
            listOf(
              r.id.value.toString(),
              r.outcome.value,
              r.modelResolved ?: "",
              r.inputTokens?.toString() ?: "",
              r.outputTokens?.toString() ?: "",
              r.createdAt.toString(),
            ),
        )
      } + listOfNotNull(truncationRow(extractionRuns.size, STUDENT_PANEL_LIMIT, columns.size, "extraction-run"))
    return Result.success(EdgePanel.Table(label = "Extraction runs", columns = columns, rows = rows))
  }
}
