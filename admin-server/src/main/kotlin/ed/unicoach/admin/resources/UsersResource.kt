package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.admin.render.respondDaoError
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.DisplayName
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.util.Argon2Hasher
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID

/**
 * The `users` ENTITY: the rich spine that exercises every engine pattern. Create
 * hashes the submitted plaintext password and builds the auth method directly
 * (no `AuthService.register`, which also mints sessions). Update is an OCC write
 * carrying the form's version. Delete is soft; undelete restores. Edges weave the
 * embedded student profile, the user's sessions, and the version history.
 */
class UsersResource(
  private val argon2Hasher: Argon2Hasher,
) : AdminResource<User, UserId> {
  override val slug = "user"
  override val title = "User"
  override val kind = AdminKind.ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("id", "ID", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("email", "Email", FieldType.TEXT, editable = true, sensitive = false),
      AdminField("name", "Name", FieldType.TEXT, editable = true, sensitive = false),
      AdminField("displayName", "Display Name", FieldType.TEXT, editable = true, sensitive = false),
      AdminField("passwordHash", "Password Hash", FieldType.TEXT, editable = false, sensitive = true),
      AdminField("isAdmin", "Admin", FieldType.BOOL, editable = true, sensitive = false),
      AdminField("version", "Version", FieldType.INT, editable = false, sensitive = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("deletedAt", "Deleted", FieldType.TIMESTAMP, editable = false, sensitive = false),
    )

  override val createExtraInputs =
    listOf(
      AdminField("password", "Password", FieldType.TEXT, editable = true, sensitive = false),
    )

  override val edges =
    listOf(
      AdminEdge.Embedded("Student profile", StudentsResource),
      AdminEdge.HasMany("Sessions", targetSlug = "session"),
      AdminEdge.History("Version history"),
    )

  override fun rowId(row: User): UserId = row.id

  override fun parseId(raw: String): UserId? = runCatching { UserId(UUID.fromString(raw)) }.getOrNull()

  override fun idToPath(id: UserId): String = id.value.toString()

  override fun isDeleted(row: User): Boolean = row.deletedAt != null

  override fun cells(row: User): Map<String, String> =
    mapOf(
      "id" to row.id.value.toString(),
      "email" to row.email.value,
      "name" to row.name.value,
      "displayName" to (row.displayName?.value ?: ""),
      "isAdmin" to row.isAdmin.toString(),
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
  ): Result<List<User>> = db.withConnection { session -> UsersDao.listAll(session, scope, limit, offset) }

  override suspend fun get(
    db: Database,
    id: UserId,
    includeDeleted: Boolean,
  ): Result<User> = db.withConnection { session -> UsersDao.findById(session, id, includeDeleted) }

  override val create: (suspend (Database, Map<String, String>) -> Result<UserId>) = ::createUser

  override val update: (suspend (Database, UserId, Map<String, String>) -> Result<Unit>) = ::updateUser

  override val delete: (suspend (Database, UserId) -> Result<Unit>) =
    { db, id ->
      db
        .withConnection { session ->
          UsersDao.findById(session, id, includeDeleted = true).mapCatching { user ->
            UsersDao.delete(session, id, user.version).getOrThrow()
          }
        }.map { }
    }

  override val undelete: (suspend (Database, UserId) -> Result<Unit>) =
    { db, id ->
      db
        .withConnection { session ->
          UsersDao.findById(session, id, includeDeleted = true).mapCatching { user ->
            UsersDao.undelete(session, id, user.version).getOrThrow()
          }
        }.map { }
    }

  private suspend fun createUser(
    db: Database,
    form: Map<String, String>,
  ): Result<UserId> {
    val email = EmailAddress.create(form["email"].orEmpty())
    if (email !is ValidationResult.Valid) return Result.failure(IllegalArgumentException("Invalid email."))
    val name = PersonName.create(form["name"].orEmpty())
    if (name !is ValidationResult.Valid) return Result.failure(IllegalArgumentException("Invalid name."))
    val displayNameRaw = form["displayName"].orEmpty()
    val displayName =
      if (displayNameRaw.isBlank()) {
        null
      } else {
        when (val dn = DisplayName.create(displayNameRaw)) {
          is ValidationResult.Valid -> dn.value
          is ValidationResult.Invalid -> return Result.failure(IllegalArgumentException("Invalid display name."))
        }
      }
    val password = form["password"].orEmpty()
    if (password.isBlank()) return Result.failure(IllegalArgumentException("Password is required."))

    val hashStr = argon2Hasher.hash(password)
    val pwdHash =
      when (val h = PasswordHash.create(hashStr)) {
        is ValidationResult.Valid -> h.value
        is ValidationResult.Invalid -> return Result.failure(IllegalArgumentException("Could not hash password."))
      }

    val newUser =
      NewUser(
        email = email.value,
        name = name.value,
        displayName = displayName,
        authMethod = AuthMethod.Password(pwdHash),
        isAdmin = form["isAdmin"] == "true",
      )

    return db
      .withConnection { session -> UsersDao.create(session, newUser) }
      .map { it.id }
  }

  private suspend fun updateUser(
    db: Database,
    id: UserId,
    form: Map<String, String>,
  ): Result<Unit> {
    val formVersion = form["version"]?.toIntOrNull() ?: return Result.failure(IllegalArgumentException("Missing version."))
    val email = EmailAddress.create(form["email"].orEmpty())
    if (email !is ValidationResult.Valid) return Result.failure(IllegalArgumentException("Invalid email."))
    val name = PersonName.create(form["name"].orEmpty())
    if (name !is ValidationResult.Valid) return Result.failure(IllegalArgumentException("Invalid name."))
    val displayNameRaw = form["displayName"].orEmpty()
    val displayName =
      if (displayNameRaw.isBlank()) {
        null
      } else {
        when (val dn = DisplayName.create(displayNameRaw)) {
          is ValidationResult.Valid -> dn.value
          is ValidationResult.Invalid -> return Result.failure(IllegalArgumentException("Invalid display name."))
        }
      }

    return db.withConnection { session ->
      val current = UsersDao.findById(session, id, includeDeleted = true).getOrThrow()
      // Carry the form's version so the DAO's WHERE version = ? enforces OCC.
      val edited =
        current.copy(
          version = formVersion,
          email = email.value,
          name = name.value,
          displayName = displayName,
          isAdmin = form["isAdmin"] == "true",
        )
      UsersDao.update(session, edited).map { }
    }
  }

  /**
   * Owner-nested action endpoints for the embedded student profile. These are
   * action endpoints, not detail pages, so the canonical-routing invariant holds.
   */
  override fun registerExtraRoutes(
    scope: Route,
    db: Database,
  ) {
    scope.post("/$slug/{id}/student") {
      val userId = parseId(call.parameters["id"].orEmpty()) ?: return@post call.respondRedirect("/$slug")
      val form = call.receiveParameters()
      val gradIso = form["expectedHighSchoolGraduationDate"].orEmpty()
      val parsed = PartialDate.parse(gradIso)
      if (parsed !is ValidationResult.Valid) {
        return@post call.respondRedirect("/$slug/${userId.value}")
      }
      db.withConnection { session -> StudentsDao.create(session, NewStudent(userId, parsed.value)) }.fold(
        onSuccess = { call.respondRedirect("/$slug/${userId.value}") },
        onFailure = { call.respondDaoError(it) },
      )
    }

    scope.post("/$slug/{id}/student/update") {
      val userId = parseId(call.parameters["id"].orEmpty()) ?: return@post call.respondRedirect("/$slug")
      val form = call.receiveParameters()
      val gradIso = form["expectedHighSchoolGraduationDate"].orEmpty()
      val parsed = PartialDate.parse(gradIso)
      if (parsed !is ValidationResult.Valid) {
        return@post call.respondRedirect("/$slug/${userId.value}")
      }
      db
        .withConnection { session ->
          StudentsDao.findByUserId(session, userId, includeDeleted = true).mapCatching { existing ->
            StudentsDao.update(session, existing.copy(expectedHighSchoolGraduationDate = parsed.value)).getOrThrow()
          }
        }.fold(
          onSuccess = { call.respondRedirect("/$slug/${userId.value}") },
          onFailure = { call.respondDaoError(it) },
        )
    }

    scope.post("/$slug/{id}/student/delete") {
      val userId = parseId(call.parameters["id"].orEmpty()) ?: return@post call.respondRedirect("/$slug")
      db
        .withConnection { session ->
          StudentsDao.findByUserId(session, userId, includeDeleted = true).mapCatching { existing ->
            StudentsDao.delete(session, existing.id, existing.version).getOrThrow()
          }
        }.fold(
          onSuccess = { call.respondRedirect("/$slug/${userId.value}") },
          onFailure = { call.respondDaoError(it) },
        )
    }
  }

  override suspend fun resolveEdges(
    db: Database,
    row: User,
  ): Result<List<EdgePanel>> {
    val studentPanel = StudentsResource.buildPanel(db, row.id).getOrElse { return Result.failure(it) }

    val sessions =
      db
        .withConnection { session -> SessionsDao.listByUser(session, row.id, limit = 50, offset = 0) }
        .getOrElse { return Result.failure(it) }
    val sessionsPanel =
      EdgePanel.Table(
        label = "Sessions",
        columns = listOf("ID", "User Agent", "Created", "Expires"),
        rows =
          sessions.map { s ->
            EdgePanel.Table.Row(
              href = "/session/${s.id.value}",
              cells =
                listOf(
                  s.id.value.toString(),
                  s.userAgent ?: "",
                  s.createdAt.toString(),
                  s.expiresAt.toString(),
                ),
            )
          },
      )

    val versions =
      db
        .withConnection { session -> UsersDao.listVersions(session, row.id) }
        .getOrElse { return Result.failure(it) }
    val historyPanel =
      EdgePanel.Table(
        label = "Version history",
        columns = listOf("Version", "Email", "Name", "Admin", "Updated", "Deleted"),
        rows =
          versions.map { v ->
            EdgePanel.Table.Row(
              href = null,
              cells =
                listOf(
                  v.version.toString(),
                  v.email.value,
                  v.name.value,
                  v.isAdmin.toString(),
                  v.updatedAt.toString(),
                  v.deletedAt?.toString() ?: "",
                ),
            )
          },
      )

    return Result.success(listOf(studentPanel, sessionsPanel, historyPanel))
  }
}
