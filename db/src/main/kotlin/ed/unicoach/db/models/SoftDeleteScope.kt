package ed.unicoach.db.models

/**
 * Read-time companion to the [SoftDeletable] capability. Selects which rows a
 * read returns relative to their `deleted_at` column. Domain-agnostic so any DAO
 * over a soft-deletable entity can reuse it.
 *
 * - [ACTIVE] — only rows with `deleted_at IS NULL`.
 * - [DELETED] — only rows with `deleted_at IS NOT NULL`.
 * - [ALL] — no `deleted_at` filter.
 */
enum class SoftDeleteScope {
  ACTIVE,
  DELETED,
  ALL,
}
