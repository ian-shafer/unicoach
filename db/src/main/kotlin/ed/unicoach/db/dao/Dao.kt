package ed.unicoach.db.dao

import ed.unicoach.db.models.Id
import ed.unicoach.db.models.Identifiable
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Versioned

/*
 * Capability interfaces: one interface per operation-capability, composed à la
 * carte per DAO with no welded supertype. They mirror the model-layer taxonomy
 * in `db/models/Entity.kt` (Identifiable, Created, Versioned, SoftDeletable).
 * ROW is bound to Identifiable<ID> only where the interface takes an `id: ID`
 * parameter, coupling the row's id type to the interface's id type; the id-less
 * interfaces leave ROW unbound.
 */

interface Findable<ROW : Identifiable<ID>, ID : Id> {
  fun findById(
    session: SqlSession,
    id: ID,
  ): Result<ROW>
}

interface SoftDeleteFindable<ROW : Identifiable<ID>, ID : Id> {
  fun findById(
    session: SqlSession,
    id: ID,
    scope: SoftDeleteScope,
  ): Result<ROW>

  fun findById(
    session: SqlSession,
    id: ID,
  ): Result<ROW> = findById(session, id, SoftDeleteScope.ACTIVE)
}

interface Listable<ROW> {
  fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<ROW>>
}

interface SoftDeleteListable<ROW> {
  fun list(
    session: SqlSession,
    scope: SoftDeleteScope,
    limit: Int,
    offset: Int,
  ): Result<List<ROW>>
}

// Writes
interface Creatable<NEW, ROW> {
  fun create(
    session: SqlSession,
    input: NEW,
  ): Result<ROW>
}

interface Updatable<EDIT, ROW> {
  fun update(
    session: SqlSession,
    edit: EDIT,
  ): Result<ROW>
}

interface OccDeletable<ROW : Identifiable<ID>, ID : Id> {
  fun delete(
    session: SqlSession,
    id: ID,
    currentVersion: Int,
  ): Result<ROW>

  fun undelete(
    session: SqlSession,
    id: ID,
    currentVersion: Int,
  ): Result<ROW>
}

interface Deletable<ROW : Identifiable<ID>, ID : Id> {
  fun delete(
    session: SqlSession,
    id: ID,
  ): Result<ROW>

  fun undelete(
    session: SqlSession,
    id: ID,
  ): Result<ROW>
}

interface Destroyable<ID : Id> {
  fun destroy(
    session: SqlSession,
    id: ID,
  ): Result<Unit>
}

// History
interface VersionHistory<ID : Id, V : Versioned> {
  fun listVersions(
    session: SqlSession,
    id: ID,
  ): Result<List<V>>
}
