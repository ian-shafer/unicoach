package ed.unicoach.db.models

/**
 * What one erasure did to one table (RFC 266 §3): rows whose live account key
 * [moved] onto its deleted sibling, rows [erased] in place (personal columns
 * NULL, `deleted_at` stamped), and rows [deleted] outright (brief 0002 D10).
 * One per table the walk touched; a table it left unchanged has none.
 */
data class TableErasure(
  val table: String,
  val moved: Long,
  val erased: Long,
  val deleted: Long,
)
