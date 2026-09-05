package ed.unicoach.rest.models

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

/**
 * The parsed `api-specs/openapi.yaml`, for the build guards that hold the
 * published contract to the Kotlin declarations it restates.
 *
 * The spec is located by the `unicoach.openapi.file` system property that
 * `rest-server/build.gradle.kts` sets from Gradle's own `rootProject`, so no
 * guard has to rediscover the repository layout and there is only one spelling
 * of the path. Guards navigate the parsed document rather than matching text,
 * so they bind to the schema they actually mean — a second schema that happens
 * to restate the same literal cannot satisfy them — and survive reformatting.
 */
object OpenApiSpec {
  private const val SPEC_FILE_PROPERTY = "unicoach.openapi.file"

  private val specFile: File =
    File(
      checkNotNull(System.getProperty(SPEC_FILE_PROPERTY)) {
        "System property [$SPEC_FILE_PROPERTY] is unset; rest-server/build.gradle.kts sets it on every Test task"
      },
    )

  private val document: JsonNode = ObjectMapper(YAMLFactory()).readTree(specFile)

  /**
   * Walks [path] from the document root, or throws naming the whole path and
   * the step that was missing. One spelling of the navigation, so every
   * accessor below cannot drift in how it reports a missing step.
   */
  private fun navigate(path: List<String>): JsonNode =
    path.fold(document) { parent, key ->
      val child = parent.path(key)
      if (child.isMissingNode) {
        throw AssertionError("[$specFile] has no [${path.joinToString(".")}] — missing at [$key]")
      }
      child
    }

  /**
   * The `components.schemas.[schema].properties.[property]` node, or throws
   * naming the path and the step that was missing.
   */
  fun get(
    schema: String,
    property: String,
  ): JsonNode = navigate(listOf("components", "schemas", schema, "properties", property))

  /**
   * The whole `components.schemas.[name]` node — what a guard over a schema's
   * own keywords (`required`, `additionalProperties`) needs, which the
   * property-shaped [get] cannot reach.
   */
  fun schema(name: String): JsonNode = navigate(listOf("components", "schemas", name))

  /** The `paths.[path].[method]` operation node, for guards over `security` and the published statuses. */
  fun operation(
    path: String,
    method: String,
  ): JsonNode = navigate(listOf("paths", path, method))

  /**
   * The property names in `components.schemas.[schema].required`, or throws naming the path
   * and the step that was missing. A schema that publishes no `required` at
   * all is a different document from one that publishes an empty list, so the
   * missing node is an error rather than an empty result.
   *
   * The node's SHAPE is checked too: `asText()` maps a non-array, or a
   * non-textual element, to a plausible-looking wrong list, which would let a
   * malformed spec satisfy a guard. A wrong shape is an error here instead.
   */
  fun requiredProperties(schema: String): List<String> {
    val path = listOf("components", "schemas", schema, "required")
    val node = navigate(path)
    if (!node.isArray) {
      throw AssertionError("[$specFile] has a non-array [${path.joinToString(".")}]: [$node]")
    }
    return node.map { element ->
      if (!element.isTextual) {
        throw AssertionError("[$specFile] has a non-string entry in [${path.joinToString(".")}]: [$element]")
      }
      element.asText()
    }
  }

  /**
   * The strings published as the `enum` of [schema]'s [property], or throws
   * naming the path and the step that was missing. A property that publishes
   * no `enum` is a different document from one publishing an empty list, so
   * the missing node is an error rather than an empty result.
   *
   * The property is looked up on the schema's own `properties` and, failing
   * that, on each `allOf` branch's, so a specialization composed from a shared
   * schema is read the same way as a flat one.
   *
   * The node's SHAPE is checked, exactly as in [requiredProperties]: `asText()`
   * maps a non-array, or a non-textual element, to a plausible-looking wrong
   * list, which would let a malformed spec satisfy a guard.
   */
  fun enumValues(
    schema: String,
    property: String,
  ): List<String> {
    val where = "components.schemas.$schema..properties.$property.enum"
    val node = declaredProperty(schema, property).path("enum")
    if (node.isMissingNode) {
      throw AssertionError("[$specFile] has no [$where] — the property publishes no enum")
    }
    if (!node.isArray) {
      throw AssertionError("[$specFile] has a non-array [$where]: [$node]")
    }
    return node.map { element ->
      if (!element.isTextual) {
        throw AssertionError("[$specFile] has a non-string entry in [$where]: [$element]")
      }
      element.asText()
    }
  }

  /**
   * [schema]'s [property], declared exactly once — on the schema's own
   * `properties` or on one `allOf` branch's. Two declarations are an ambiguous
   * document, not a choice this accessor may make silently: a guard that read
   * the branch nobody meant would pass against the wrong list.
   */
  private fun declaredProperty(
    schema: String,
    property: String,
  ): JsonNode {
    val root = navigate(listOf("components", "schemas", schema))
    val declarations =
      (listOf(root) + root.path("allOf"))
        .map { node -> node.path("properties").path(property) }
        .filter { !it.isMissingNode }
    if (declarations.size != 1) {
      throw AssertionError(
        "[$specFile] declares [$property] on [components.schemas.$schema] [${declarations.size}] times " +
          "across its own properties and its allOf branches; exactly one is required",
      )
    }
    return declarations.single()
  }

  /**
   * The parameter named [name] on `paths.[path].[method]`, or throws naming
   * the operation and the step that was missing. The schema-shaped [get]
   * cannot reach operation parameters, so guards over query-parameter bounds
   * come through here.
   */
  fun parameter(
    path: String,
    method: String,
    name: String,
  ): JsonNode {
    val parameters = navigate(listOf("paths", path, method, "parameters"))
    return parameters.firstOrNull { it.path("name").asText() == name }
      ?: throw AssertionError("[$specFile] has no parameter [$name] on [paths.$path.$method]")
  }
}
