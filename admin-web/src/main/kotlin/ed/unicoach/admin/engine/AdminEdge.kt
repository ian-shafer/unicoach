package ed.unicoach.admin.engine

/**
 * A relationship rendered on a resource's detail page. Each variant maps to one
 * edge renderer. The canonical-routing invariant holds: child rows always link
 * to their own `/{slug}/{id}` detail URL, never a nested path.
 */
sealed interface AdminEdge {
  /** A link + summary of a parent row. */
  data class Parent(
    val label: String,
    val targetSlug: String,
  ) : AdminEdge

  /** An inline table of child rows, each linking to its own detail page. */
  data class HasMany(
    val label: String,
    val targetSlug: String,
  ) : AdminEdge

  /** A read-only version-history panel for this row. */
  data class History(
    val label: String,
  ) : AdminEdge

  /**
   * An owned entity rendered inline as a panel with its own action endpoints.
   * The embedded resource has no standalone URL; its actions nest under the owner.
   */
  data class Embedded(
    val label: String,
    val resource: AdminResource<*, *>,
  ) : AdminEdge
}
