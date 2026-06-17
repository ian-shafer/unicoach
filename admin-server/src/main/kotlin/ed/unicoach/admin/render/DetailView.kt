package ed.unicoach.admin.render

import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr

/**
 * Renders a resource detail page: the field table (sensitive fields redacted),
 * the create/edit/delete/undelete actions permitted by the descriptor, and one
 * panel per resolved [edges].
 */
fun <ROW, ID> MAIN.renderDetail(
  resource: AdminResource<ROW, ID>,
  row: ROW,
  edges: List<EdgePanel>,
) {
  val id = resource.rowId(row)
  val idPath = resource.idToPath(id)
  val cells = resource.cells(row)
  val deleted = resource.isDeleted(row)

  h1 {
    +"${resource.title} $idPath"
    if (deleted) {
      +" "
      span("deleted-badge") { +"deleted" }
    }
  }

  table {
    resource.fields.forEach { field ->
      tr {
        th { +field.label }
        td {
          if (field.sensitive) {
            +"••• (redacted)"
          } else {
            +(cells[field.name] ?: "")
          }
        }
      }
    }
  }

  div {
    if (resource.update != null) {
      a(href = "/${resource.slug}/$idPath/edit") { +"Edit" }
      +" "
    }
    if (resource.delete != null && !deleted) {
      actionButton("/${resource.slug}/$idPath/delete", "Delete")
    }
    if (resource.undelete != null && deleted) {
      actionButton("/${resource.slug}/$idPath/undelete", "Undelete")
    }
  }

  edges.forEach { panel -> renderEdgePanel(panel) }
}

/** A single-button POST form, used for delete/undelete and nested actions. */
fun FlowContent.actionButton(
  action: String,
  label: String,
) {
  form(action = action, method = FormMethod.post) {
    button(type = ButtonType.submit) { +label }
  }
}

private fun FlowContent.renderEdgePanel(panel: EdgePanel) {
  div("panel") {
    h2 { +panel.label }
    when (panel) {
      is EdgePanel.ParentLink ->
        p {
          a(href = panel.href) { +panel.summary }
        }

      is EdgePanel.ParentAbsent ->
        p { +"(none)" }

      is EdgePanel.Table -> renderTablePanel(panel)

      is EdgePanel.Embedded -> renderEmbeddedPanel(panel)
    }
  }
}

private fun FlowContent.renderTablePanel(panel: EdgePanel.Table) {
  if (panel.rows.isEmpty()) {
    p { +"(none)" }
    return
  }
  table {
    tr { panel.columns.forEach { th { +it } } }
    panel.rows.forEach { row ->
      tr {
        row.cells.forEachIndexed { index, cell ->
          td {
            if (index == 0 && row.href != null) {
              a(href = row.href) { +cell }
            } else {
              +cell
            }
          }
        }
      }
    }
  }
}

private fun FlowContent.renderEmbeddedPanel(panel: EdgePanel.Embedded) {
  if (!panel.present) {
    p { +"No ${panel.label} yet." }
    renderForm(
      action = "/${panel.ownerSlug}/${panel.ownerId}/student",
      editableFields = panel.createFields,
      values = emptyMap(),
      submitLabel = "Create ${panel.label}",
    )
    return
  }

  if (panel.deleted) {
    p("deleted") { +"(soft-deleted)" }
  }
  table {
    panel.fields.forEach { (label, value) ->
      tr {
        th { +label }
        td { +value }
      }
    }
  }
  renderForm(
    action = "/${panel.ownerSlug}/${panel.ownerId}/student/update",
    editableFields = panel.editFields,
    values = panel.editValues,
    version = panel.version,
    submitLabel = "Save ${panel.label}",
  )
  if (!panel.deleted) {
    actionButton("/${panel.ownerSlug}/${panel.ownerId}/student/delete", "Delete ${panel.label}")
  }
  panel.nested.forEach { nested ->
    div("panel") {
      h3 { +nested.label }
      when (nested) {
        is EdgePanel.Table -> renderTablePanel(nested)
        else -> {}
      }
    }
  }
}
