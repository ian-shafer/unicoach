package ed.unicoach.admin.render

import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.FieldType
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.textArea

/**
 * Renders a field-typed input pre-filled from [values]. `JSON`/`MULTILINE` use a
 * textarea, `BOOL` a checkbox, `ENUM` a select, and the rest a typed text input.
 * Read-only (non-editable) and sensitive fields are never emitted here.
 */
private fun FlowContent.renderInput(
  field: AdminField,
  current: String?,
) {
  div {
    label { +field.label }
    when (field.type) {
      FieldType.MULTILINE, FieldType.JSON ->
        textArea {
          name = field.name
          +(current ?: "")
        }

      FieldType.BOOL ->
        checkBoxInput {
          name = field.name
          value = "true"
          checked = current == "true"
        }

      FieldType.ENUM ->
        select {
          name = field.name
          field.enumValues.forEach { v ->
            option {
              value = v
              if (v == current) selected = true
              +v
            }
          }
        }

      FieldType.INT ->
        input(type = InputType.number, name = field.name) {
          value = current ?: ""
        }

      else ->
        input(type = InputType.text, name = field.name) {
          value = current ?: ""
        }
    }
  }
}

/**
 * A create or edit form posting to [action]. Editable, non-sensitive fields are
 * emitted as typed inputs; on edit, the current `version` rides along in a hidden
 * field to drive OCC. Extra hidden inputs (e.g. a plaintext-password field on
 * create) may be appended via [extra].
 */
fun FlowContent.renderForm(
  action: String,
  editableFields: List<AdminField>,
  values: Map<String, String>,
  version: Int? = null,
  submitLabel: String = "Save",
  extra: (FlowContent.() -> Unit)? = null,
) {
  form(action = action, method = FormMethod.post) {
    if (version != null) {
      hiddenInput {
        name = "version"
        value = version.toString()
      }
    }
    editableFields
      .filter { it.editable && !it.sensitive }
      .forEach { field -> renderInput(field, values[field.name]) }
    extra?.invoke(this)
    button(type = ButtonType.submit) { +submitLabel }
  }
}
