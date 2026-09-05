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
   * the step that was missing. One spelling of the navigation, so [get] and
   * [requiredProperties] cannot drift in how they report a missing step.
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
