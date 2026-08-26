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
   * The `components.schemas.[schema].properties.[property]` node, or throws
   * naming the path and the step that was missing.
   */
  fun get(
    schema: String,
    property: String,
  ): JsonNode {
    val path = listOf("components", "schemas", schema, "properties", property)
    return path.fold(document) { parent, key ->
      val child = parent.path(key)
      if (child.isMissingNode) {
        throw AssertionError("[$specFile] has no [${path.joinToString(".")}] — missing at [$key]")
      }
      child
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
    val steps = listOf("paths", path, method, "parameters")
    val parameters =
      steps.fold(document) { parent, key ->
        val child = parent.path(key)
        if (child.isMissingNode) {
          throw AssertionError("[$specFile] has no [${steps.joinToString(".")}] — missing at [$key]")
        }
        child
      }
    return parameters.firstOrNull { it.path("name").asText() == name }
      ?: throw AssertionError("[$specFile] has no parameter [$name] on [paths.$path.$method]")
  }
}
