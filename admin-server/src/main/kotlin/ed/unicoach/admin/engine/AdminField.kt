package ed.unicoach.admin.engine

/** The render/input strategy for a single field of an admin resource. */
enum class FieldType {
  TEXT,
  MULTILINE,
  INT,
  BOOL,
  TIMESTAMP,
  JSON,
  ENUM,
}

/**
 * A single column of an admin resource: how it renders in views and forms.
 *
 * @property name the form/column key.
 * @property editable false renders the field read-only (id, timestamps, version).
 * @property sensitive true redacts the field in views and omits it from all forms.
 * @property enumValues the allowed values when [type] is [FieldType.ENUM].
 */
data class AdminField(
  val name: String,
  val label: String,
  val type: FieldType,
  val editable: Boolean,
  val sensitive: Boolean,
  val enumValues: List<String> = emptyList(),
)
