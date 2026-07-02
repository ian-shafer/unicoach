package ed.unicoach.admin.engine

/**
 * The rendered payload of an edge on a detail page, produced by a resource's
 * edge resolver and rendered uniformly by the engine. Keeping resolution typed
 * inside the resource (which knows the DAOs and foreign keys) while the engine
 * renders a flat, type-erased panel preserves the "engine renders from the
 * descriptor" boundary without leaking generics across the router.
 */
sealed interface EdgePanel {
  val label: String

  /** A link to a parent's canonical detail page plus a one-line summary. */
  data class ParentLink(
    override val label: String,
    val href: String,
    val summary: String,
  ) : EdgePanel

  /** A null parent (e.g. an anonymous session): rendered as an absence note. */
  data class ParentAbsent(
    override val label: String,
  ) : EdgePanel

  /**
   * A table of child rows. Each row carries its ordered cell values; each
   * [Column] carries the type and ref-slug so the same `renderCell` governs an
   * edge cell as a detail field cell (RFC 79) — the value as plain text followed
   * by the ref-link glyph, with navigation to a child's detail page being the
   * primary-id column's own refSlug glyph. Cells stay raw, positional strings.
   */
  data class Table(
    override val label: String,
    val columns: List<Column>,
    val rows: List<Row>,
  ) : EdgePanel {
    init {
      // Cells are positional against `columns`, so a row/column count mismatch
      // would silently mis-render (a surplus cell as plain text, a missing cell
      // dropped). Fail fast at construction so the bug surfaces in dev/test.
      rows.forEachIndexed { index, row ->
        require(row.cells.size == columns.size) {
          "EdgePanel.Table '$label' row $index has ${row.cells.size} cells but ${columns.size} columns"
        }
      }
    }

    /**
     * A typed edge-table column. [type] and [refSlug] default to plain text so a
     * label-only column stays terse (`Column("Topic")`); id/timestamp/bool columns
     * set them explicitly.
     */
    data class Column(
      val label: String,
      val type: FieldType = FieldType.TEXT,
      val refSlug: String? = null,
    )

    data class Row(
      val cells: List<String>,
    )
  }

  /**
   * An owned entity rendered inline. When [present] is false the panel offers a
   * create form; otherwise it shows the entity's fields plus edit/delete actions
   * and any nested panels (e.g. the embedded student's version history).
   */
  data class Embedded(
    override val label: String,
    val ownerSlug: String,
    val ownerId: String,
    val present: Boolean,
    val fields: List<LabeledCell>,
    val editValues: Map<String, String>,
    val version: Int?,
    val deleted: Boolean,
    val createFields: List<AdminField>,
    val editFields: List<AdminField>,
    val nested: List<EdgePanel>,
  ) : EdgePanel

  /**
   * A typed label/value pair for an [Embedded] panel's field table, carrying the
   * type and ref-slug so it routes through the same `renderCell` (RFC 79).
   */
  data class LabeledCell(
    val label: String,
    val type: FieldType,
    val refSlug: String?,
    val value: String,
  )
}
