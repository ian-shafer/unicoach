package ed.unicoach.admin.render

import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.EdgePanel
import ed.unicoach.admin.engine.FieldType
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
  display: AdminDisplay,
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
            renderCell(cells[field.name] ?: "", field.type, field.refSlug, display)
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

  // Descriptor-declared custom actions render after the Edit/Delete/Undelete
  // block above and before the edge panels below (the page's action region).
  resource.customActions.forEach { action ->
    actionButton(
      action = "/${resource.slug}/$idPath/${action.pathSuffix}",
      label = action.label,
      disabledReason = action.disabledReason(row),
    )
  }

  edges.forEach { panel -> renderEdgePanel(panel, display) }
}

/**
 * A single-button POST form, used for delete/undelete, nested actions, and
 * descriptor-declared custom actions. When [disabledReason] is null the submit
 * button is enabled; when non-null the button carries the HTML `disabled`
 * attribute and a `title` set to the reason. Single source of truth: enabled iff
 * [disabledReason] is null.
 */
fun FlowContent.actionButton(
  action: String,
  label: String,
  disabledReason: String? = null,
) {
  form(action = action, method = FormMethod.post) {
    button(type = ButtonType.submit) {
      if (disabledReason != null) {
        attributes["disabled"] = "disabled"
        attributes["title"] = disabledReason
      }
      +label
    }
  }
}

private fun FlowContent.renderEdgePanel(
  panel: EdgePanel,
  display: AdminDisplay,
) {
  div("panel") {
    h2 { +panel.label }
    when (panel) {
      is EdgePanel.ParentLink -> {
        p {
          a(href = panel.href) { +panel.summary }
        }
      }

      is EdgePanel.ParentAbsent -> {
        p { +"(none)" }
      }

      is EdgePanel.Table -> {
        renderTablePanel(panel, display)
      }

      is EdgePanel.Embedded -> {
        renderEmbeddedPanel(panel, display)
      }
    }
  }
}

private fun FlowContent.renderTablePanel(
  panel: EdgePanel.Table,
  display: AdminDisplay,
) {
  if (panel.rows.isEmpty()) {
    p { +"(none)" }
    return
  }
  table {
    tr { panel.columns.forEach { th { +it.label } } }
    panel.rows.forEach { row ->
      tr {
        row.cells.forEachIndexed { index, cell ->
          val column = panel.columns.getOrNull(index)
          td {
            renderCell(cell, column?.type ?: FieldType.TEXT, column?.refSlug, display)
          }
        }
      }
    }
  }
}

private fun FlowContent.renderEmbeddedPanel(
  panel: EdgePanel.Embedded,
  display: AdminDisplay,
) {
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
    panel.fields.forEach { cell ->
      tr {
        th { +cell.label }
        td { renderCell(cell.value, cell.type, cell.refSlug, display) }
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
        is EdgePanel.Table -> {
          renderTablePanel(nested, display)
        }

        // Only a Table is supported as a nested panel today; the remaining
        // subtypes are named (not an `else`) so adding an EdgePanel variant is a
        // compile error here rather than a silent drop.
        is EdgePanel.ParentLink,
        is EdgePanel.ParentAbsent,
        is EdgePanel.Embedded,
        -> {}
      }
    }
  }
}
