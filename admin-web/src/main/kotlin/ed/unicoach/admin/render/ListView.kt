package ed.unicoach.admin.render

import ed.unicoach.admin.engine.AdminResource
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr

/**
 * Renders a resource's list view: a table whose every cell renders through
 * `renderCell` (the typed value followed by its ref-link glyph), plus prev/next
 * pager links. Navigation to a row's detail page is the primary-id column's own
 * refSlug glyph (`/{slug}/{id}`); the first cell of a deleted row also appends a
 * `deleted` badge. The engine fetches `limit + 1` rows; [hasNext] reflects
 * whether the surplus row existed (and that surplus row is dropped from [rows]
 * before this call).
 */
fun <ROW, ID> MAIN.renderList(
  resource: AdminResource<ROW, ID>,
  rows: List<ROW>,
  offset: Int,
  pageSize: Int,
  hasNext: Boolean,
  display: AdminDisplay,
) {
  h1 { +resource.title }
  if (resource.create != null) {
    div { a(href = "/${resource.slug}/new") { +"+ New ${resource.title}" } }
  }

  val columns = resource.fields.filter { it.inList && !it.sensitive }

  table {
    tr {
      columns.forEach { field -> th { +field.label } }
    }
    rows.forEach { row ->
      val cells = resource.cells(row)
      val deleted = resource.isDeleted(row)
      tr {
        columns.forEachIndexed { index, field ->
          val value = cells[field.name] ?: ""
          td {
            renderCell(value, field.type, field.refSlug, display)
            if (index == 0 && deleted) {
              +" "
              span("deleted-badge") { +"deleted" }
            }
          }
        }
      }
    }
  }

  div("pager") {
    if (offset > 0) {
      val prev = (offset - pageSize).coerceAtLeast(0)
      a(href = "/${resource.slug}?offset=$prev") { +"« Previous" }
    }
    if (hasNext) {
      a(href = "/${resource.slug}?offset=${offset + pageSize}") { +"Next »" }
    }
  }
}
