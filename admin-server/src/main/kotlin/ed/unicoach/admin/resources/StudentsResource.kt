package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.db.Database
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId
import java.util.UUID

/**
 * Maps the admin engine's `includeDeleted` flag to a [SoftDeleteScope] at the DAO
 * call boundary: `true` admits soft-deleted rows ([SoftDeleteScope.ALL]), `false`
 * restricts to active rows ([SoftDeleteScope.ACTIVE]).
 */
internal fun Boolean.toScope(): SoftDeleteScope = if (this) SoftDeleteScope.ALL else SoftDeleteScope.ACTIVE

/**
 * The embedded `students` profile. Per the canonical-routing invariant an
 * EMBEDDED_ENTITY has no standalone list/detail URL; its `get`/`list` members
 * exist so the owner's [AdminEdge.Embedded] edge can render the inline panel, but
 * they are never bound to a route. Mutations flow through owner-nested endpoints
 * registered by [UsersResource].
 */
object StudentsResource : AdminResource<Student, StudentId> {
  override val slug = "student"
  override val title = "Student"
  override val kind = AdminKind.EMBEDDED_ENTITY
  override val topLevel = false

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("userId", "User ID", FieldType.TEXT, editable = false, sensitive = false),
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
  ): Result<List<Student>> = Result.success(emptyList())

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
        columns = listOf("Version", "Graduation", "Updated", "Deleted"),
        rows =
          versions.map { v ->
            EdgePanel.Table.Row(
              href = null,
              cells =
                listOf(
                  v.version.toString(),
                  v.expectedHighSchoolGraduationDate.toIso(),
                  v.updatedAt.toString(),
                  v.deletedAt?.toString() ?: "",
                ),
            )
          },
      )

    return Result.success(
      EdgePanel.Embedded(
        label = title,
        ownerSlug = "user",
        ownerId = userId.value.toString(),
        present = true,
        fields = fields.filterNot { it.sensitive }.map { it.label to (cells[it.name] ?: "") },
        editValues = cells,
        version = student.version,
        deleted = student.deletedAt != null,
        createFields = editableFields,
        editFields = editableFields,
        nested = listOf(historyPanel),
      ),
    )
  }
}
