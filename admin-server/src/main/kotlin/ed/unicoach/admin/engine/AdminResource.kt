package ed.unicoach.admin.engine

import ed.unicoach.db.Database
import ed.unicoach.db.models.SoftDeleteScope

/**
 * The taxonomy of a resource, determining its allowed operation set and render
 * strategy. Adding a table is cheap because the engine routes/renders purely
 * from the descriptor's [AdminKind] and field/edge declarations.
 */
enum class AdminKind {
  ENTITY,
  EMBEDDED_ENTITY,
  IMMUTABLE_ENTITY,
  LOG,
  NON_ENTITY,
  SUPPORT,
}

/**
 * One descriptor per table. Operations are typed handlers that delegate to the
 * existing typed DAOs; unsupported operations (per [kind]) are null. `ROW` is the
 * domain model and `ID` its id type.
 *
 * Admin reads include soft-deleted rows: [list] passes [SoftDeleteScope.ALL] and
 * [get] passes `includeDeleted = true`. `limit`/`offset` drive paging.
 */
interface AdminResource<ROW, ID> {
  val slug: String
  val title: String
  val kind: AdminKind

  /** Whether the resource appears in nav and has a `/{slug}` list route. */
  val topLevel: Boolean
  val fields: List<AdminField>
  val edges: List<AdminEdge>

  fun rowId(row: ROW): ID

  /** Parse a path segment into the typed id, or null if malformed. */
  fun parseId(raw: String): ID?

  /** Serialize the typed id to its canonical path segment (inverse of [parseId]). */
  fun idToPath(id: ID): String

  /** field name -> rendered cell value for list/detail tables. */
  fun cells(row: ROW): Map<String, String>

  suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<ROW>>

  suspend fun get(
    db: Database,
    id: ID,
    includeDeleted: Boolean,
  ): Result<ROW>

  /** Whether a given row is soft-deleted (drives the "deleted" marker / undelete action). */
  fun isDeleted(row: ROW): Boolean

  /**
   * Extra inputs shown only on the create form and passed through the form map to
   * [create] — for values that are not stored fields (e.g. a plaintext password
   * the handler hashes). Never rendered in detail/edit views. Default: none.
   */
  val createExtraInputs: List<AdminField>
    get() = emptyList()

  /**
   * Per-row action buttons declared by this resource and rendered by the engine
   * after the Edit/Delete/Undelete block. Each entry's [CustomAction.pathSuffix]
   * must have a matching route registered in [registerExtraRoutes]. Default: none.
   */
  val customActions: List<CustomAction<ROW>>
    get() = emptyList()

  val create: (suspend (db: Database, form: Map<String, String>) -> Result<ID>)?
  val update: (suspend (db: Database, id: ID, form: Map<String, String>) -> Result<Unit>)?
  val delete: (suspend (db: Database, id: ID) -> Result<Unit>)?

  /** Soft-delete entities only: restore a soft-deleted row. null = not offered. */
  val undelete: (suspend (db: Database, id: ID) -> Result<Unit>)?

  /**
   * Resolves this row's declared [edges] into flat, render-ready [EdgePanel]s.
   * The resource knows its DAOs and foreign keys; the engine only renders.
   * Default: no panels (resources with edges override).
   */
  suspend fun resolveEdges(
    db: Database,
    row: ROW,
  ): Result<List<EdgePanel>> = Result.success(emptyList())

  /**
   * Hook for owner-nested action endpoints that have no standalone detail page
   * (e.g. an embedded entity's create/update/delete nested under its owner).
   * Invoked under the engine's route scope so the routes sit behind the gate.
   * Default: no extra routes.
   */
  fun registerExtraRoutes(
    scope: io.ktor.server.routing.Route,
    db: Database,
  ) {}
}
