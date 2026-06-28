package ed.unicoach.db.models

/**
 * One `*_versions` history row composed from the entity snapshot it records.
 * Every `*_versions` row is a complete snapshot whose columns are exactly the
 * columns the entity mapper already reads, so the snapshot needs no fields
 * beyond the wrapped [entity]: callers reach `updatedAt`, `deletedAt`, and every
 * domain field through `v.entity.*`. The version is the row's `version` column,
 * read by the entity mapper into `entity.version` and re-exposed here through
 * [Versioned] so `v.version` reads the snapshot's version and the type satisfies
 * the [ed.unicoach.db.dao.VersionHistory] element bound. [E] is `out` because a
 * snapshot is read-only.
 */
data class Version<out E : Versioned>(
  val entity: E,
) : Versioned {
  override val version: Int
    get() = entity.version
}
