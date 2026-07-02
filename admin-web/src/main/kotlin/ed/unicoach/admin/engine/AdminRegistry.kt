package ed.unicoach.admin.engine

/**
 * The registry of typed resource descriptors that drives navigation, routing,
 * and rendering. Resources are looked up by their canonical [AdminResource.slug].
 */
class AdminRegistry(
  resources: List<AdminResource<*, *>>,
) {
  private val bySlug: Map<String, AdminResource<*, *>> = resources.associateBy { it.slug }

  /** All registered resources in declaration order. */
  val all: List<AdminResource<*, *>> = resources

  /** Top-level resources: appear in nav and own a `/{slug}` list route. */
  val topLevel: List<AdminResource<*, *>> = resources.filter { it.topLevel }

  fun bySlug(slug: String): AdminResource<*, *>? = bySlug[slug]
}
