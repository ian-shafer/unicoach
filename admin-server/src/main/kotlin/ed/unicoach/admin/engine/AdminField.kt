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

  /**
   * A canonical UUID string value that renders compacted (RFC 83): an ellipsis
   * plus its tail, with the full value reachable via a hover `title` and a
   * click-to-copy button. Orthogonal to [AdminField.refSlug] — `refSlug` controls
   * the trailing navigation glyph, [UUID] controls value compaction; a column may
   * set one, both, or neither. A short `BIGINT` id stays [TEXT] and is never
   * compacted.
   */
  UUID,
}

/**
 * A single column of an admin resource: how it renders in views and forms.
 *
 * @property name the form/column key.
 * @property editable false renders the field read-only (id, timestamps, version).
 * @property sensitive true redacts the field in views and omits it from all forms.
 * @property inList false omits the field from the list table (e.g. a field too
 *   large for a list cell); detail and form rendering are unaffected. Orthogonal
 *   to [sensitive], which also removes a field from forms and detail.
 * @property enumValues the allowed values when [type] is [FieldType.ENUM].
 * @property refSlug when non-null, marks this column as an entity reference whose
 *   value links to that resource's detail page (RFC 79). The primary id sets its
 *   own slug; a foreign-key column sets the referenced slug. A slug not registered
 *   as an admin resource renders the value with no link.
 */
data class AdminField(
  val name: String,
  val label: String,
  val type: FieldType,
  val editable: Boolean,
  val sensitive: Boolean,
  val inList: Boolean = true,
  val enumValues: List<String> = emptyList(),
  val refSlug: String? = null,
)
