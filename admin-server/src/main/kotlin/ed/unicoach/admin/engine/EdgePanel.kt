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
   * A table of child rows. Each row carries its canonical detail href and the
   * ordered cell values under [columns].
   */
  data class Table(
    override val label: String,
    val columns: List<String>,
    val rows: List<Row>,
  ) : EdgePanel {
    data class Row(
      val href: String?,
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
    val fields: List<Pair<String, String>>,
    val editValues: Map<String, String>,
    val version: Int?,
    val deleted: Boolean,
    val createFields: List<AdminField>,
    val editFields: List<AdminField>,
    val nested: List<EdgePanel>,
  ) : EdgePanel
}
