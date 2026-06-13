package ed.unicoach.db.models

/**
 * Read-time filter for the archive axis of [Convo], the sibling of
 * [SoftDeleteScope]. Selects which rows a listing returns relative to their
 * `archived_at` column.
 *
 * - [UNARCHIVED] — only rows with `archived_at IS NULL`.
 * - [ARCHIVED] — only rows with `archived_at IS NOT NULL`.
 * - [ALL] — no `archived_at` filter.
 */
enum class ArchiveScope {
  UNARCHIVED,
  ARCHIVED,
  ALL,
}
