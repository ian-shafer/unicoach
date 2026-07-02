package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.EdgePanel

/**
 * One trailing "Showing first N — see /{slug} for full list" row for a nested
 * edge panel, appended only when the fetched page filled to [limit] (so more
 * rows may exist). The remaining cells are blank and the row carries no link;
 * the disclosure text points at the canonical top-level [listSlug] list for full
 * enumeration. Shared by the student coaching-memory panels (RFC 77) and the
 * per-convo Turns panel (RFC 81), which differ only in [limit] and [listSlug].
 */
internal fun truncationRow(
  fetched: Int,
  limit: Int,
  columns: Int,
  listSlug: String,
): EdgePanel.Table.Row? =
  if (fetched < limit) {
    null
  } else {
    EdgePanel.Table.Row(
      cells =
        listOf("Showing first $limit — see /$listSlug for full list") +
          List(columns - 1) { "" },
    )
  }
