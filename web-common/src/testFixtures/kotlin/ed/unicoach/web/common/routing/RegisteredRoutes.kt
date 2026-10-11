package ed.unicoach.web.common.routing

import io.ktor.http.HttpMethod
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RootRouteSelector
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.TrailingSlashRouteSelector

/** One segment of a registered route's path, as Ktor's selector typed it. */
sealed interface PathSegment {
  data class Constant(
    val value: String,
  ) : PathSegment

  data class Parameter(
    val name: String,
  ) : PathSegment

  data class OptionalParameter(
    val name: String,
  ) : PathSegment

  data object Wildcard : PathSegment

  data object Tailcard : PathSegment
}

/** The segment as a reader writes it in a route template. */
val PathSegment.spelling: String
  get() =
    when (this) {
      is PathSegment.Constant -> value
      is PathSegment.Parameter -> "{$name}"
      is PathSegment.OptionalParameter -> "{$name?}"
      PathSegment.Wildcard -> "*"
      PathSegment.Tailcard -> "{...}"
    }

/** The name a parameter segment binds, or null for a segment that binds none. */
val PathSegment.parameterName: String?
  get() =
    when (this) {
      is PathSegment.Parameter -> name
      is PathSegment.OptionalParameter -> name
      is PathSegment.Constant, PathSegment.Wildcard, PathSegment.Tailcard -> null
    }

/**
 * One registered route, as the routing tree describes it: a typed method and
 * the typed segments of its path.
 *
 * Not Ktor's `toString()` rendering of a node (`"/verify-email/(method:GET)"`),
 * which is a foreign library's debug format — undeclared anywhere, and silently
 * different if Ktor ever reformats it.
 */
data class RegisteredRoute(
  val method: HttpMethod,
  val segments: List<PathSegment>,
) {
  /** The route template, for naming a route in a list or a failure message; never parsed. */
  val path: String get() = segments.joinToString(separator = "/", prefix = "/") { it.spelling }

  /** The parameter names the path binds, in path order. */
  val parameterNames: List<String> get() = segments.mapNotNull { it.parameterName }

  /** The route as a hand-written list names it: its method and its template. */
  val named: Pair<HttpMethod, String> get() = method to path

  override fun toString(): String = "${method.value} $path"
}

/**
 * Every method handler Ktor registered under [root], as a typed method and a
 * path.
 *
 * Read from the routing tree rather than from the source: a route added
 * anywhere appears here with no edit, which is the whole point of the guards
 * that consume it. Filtered on [HttpMethodRouteSelector], because
 * `rejectUnsupportedMethods` puts a `handle {}` on path nodes too, so a path
 * node reports a handler as well.
 */
fun registeredRoutes(root: RoutingNode): List<RegisteredRoute> {
  val found = mutableListOf<RegisteredRoute>()

  fun walk(node: RoutingNode) {
    val selector = node.selector
    if (selector is HttpMethodRouteSelector && node.hasHandler()) found += RegisteredRoute(selector.method, pathOf(node))
    node.children.forEach(::walk)
  }

  walk(root)
  return found
}

/** The path a node answers on, assembled from its own chain of selectors up to the root. */
private fun pathOf(node: RoutingNode): List<PathSegment> =
  generateSequence(node) { it.parent }
    .toList()
    .reversed()
    .mapNotNull { ancestor -> pathSegmentOf(ancestor.selector) }
    .filterNot { it == PathSegment.Constant("") }

/**
 * Ktor's internal marker node for a static mount. It is not a public type, so
 * it cannot be named as one — and being matched by NAME is the safe direction:
 * if Ktor renames it, it stops being recognised, falls into the `error` arm
 * below, and the guard says so.
 */
private const val STATIC_CONTENT_SELECTOR = "io.ktor.server.http.content.TailcardSelector"

/**
 * The path segment [selector] contributes, or `null` when it contributes none
 * — the root, a trailing slash, the method (carried TYPED beside the path
 * rather than as a token inside it), and the wrapper node a static mount adds.
 *
 * A selector this `when` does not know is a FAILURE rather than a skip: a
 * skipped one would silently shorten a path, and a path that collapses onto
 * another is a route a coverage guard then counts as covered while nobody
 * looks at it.
 */
private fun pathSegmentOf(selector: RouteSelector): PathSegment? =
  when {
    selector is RootRouteSelector -> {
      null
    }

    selector is TrailingSlashRouteSelector -> {
      null
    }

    selector is HttpMethodRouteSelector -> {
      null
    }

    selector.javaClass.name == STATIC_CONTENT_SELECTOR -> {
      null
    }

    selector is PathSegmentConstantRouteSelector -> {
      PathSegment.Constant(selector.value)
    }

    selector is PathSegmentParameterRouteSelector -> {
      PathSegment.Parameter(selector.name)
    }

    selector is PathSegmentOptionalParameterRouteSelector -> {
      PathSegment.OptionalParameter(selector.name)
    }

    selector is PathSegmentWildcardRouteSelector -> {
      PathSegment.Wildcard
    }

    selector is PathSegmentTailcardRouteSelector -> {
      PathSegment.Tailcard
    }

    else -> {
      error(
        "cannot spell the path of [$selector] ([${selector.javaClass.name}]): " +
          "teach registeredRoutes that selector, or the route it is part of is checked against the wrong path",
      )
    }
  }
