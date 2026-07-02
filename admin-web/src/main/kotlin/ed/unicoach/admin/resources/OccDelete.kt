package ed.unicoach.admin.resources

import ed.unicoach.db.Database
import ed.unicoach.db.dao.OccDeletable
import ed.unicoach.db.dao.SoftDeleteFindable
import ed.unicoach.db.models.Id
import ed.unicoach.db.models.Identifiable
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Versioned

/**
 * The "load current version, then OCC delete/undelete" sequence, expressed once
 * against the [db/dao/Dao.kt][SoftDeleteFindable] / [OccDeletable] capability
 * intersection rather than a concrete DAO. Loads the row including soft-deleted
 * ([SoftDeleteScope.ALL]) to confirm existence, then issues the OCC write keyed
 * on that row's version. Both the read and the write run on a single
 * [Database.withConnection] session, identical to the inline read-then-write the
 * per-entity resources perform. Propagates the DAO's `NotFoundException` (unknown
 * id) and `ConcurrentModificationException` (version raced between read and
 * write) unchanged.
 */
internal suspend fun <ID, ROW, DAO> Database.occSoftDelete(
  dao: DAO,
  id: ID,
  deleted: Boolean,
): Result<Unit>
  where ID : Id,
        ROW : Identifiable<ID>,
        ROW : Versioned,
        DAO : SoftDeleteFindable<ROW, ID>,
        DAO : OccDeletable<ROW, ID> =
  withConnection { session ->
    dao.findById(session, id, SoftDeleteScope.ALL).mapCatching { row ->
      if (deleted) {
        dao.delete(session, id, row.version).getOrThrow()
      } else {
        dao.undelete(session, id, row.version).getOrThrow()
      }
    }
  }.map { }
