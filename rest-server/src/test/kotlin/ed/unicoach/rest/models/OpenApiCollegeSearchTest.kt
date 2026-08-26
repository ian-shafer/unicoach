package ed.unicoach.rest.models

import ed.unicoach.college.CollegeSearchService
import ed.unicoach.rest.routing.CollegeRouteHandler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Enforces the openapi.yaml <-> college-search coupling (RFC 137): the
 * published `q` bound, `limit` default, and clamp range must equal the
 * constants the server actually applies. Nothing else in the default gate
 * opens openapi.yaml for this operation (`bin/test-fuzz` runs only when the
 * spec is staged), so without this guard a bump of either side leaves the
 * other quietly stale.
 */
class OpenApiCollegeSearchTest {
  @Test
  fun `openapi's q maxLength matches CollegeSearchService's MAX_QUERY_LENGTH`() {
    val published = OpenApiSpec.parameter("/api/v1/colleges", "get", "q").path("schema").path("maxLength")
    assertEquals(
      CollegeSearchService.MAX_QUERY_LENGTH,
      published.intValue(),
      "openapi.yaml's q.maxLength must equal CollegeSearchService.MAX_QUERY_LENGTH; found [$published]",
    )
  }

  @Test
  fun `openapi's limit default and clamp range match the server constants`() {
    val limit = OpenApiSpec.parameter("/api/v1/colleges", "get", "limit")
    assertEquals(
      CollegeRouteHandler.DEFAULT_LIMIT,
      limit.path("schema").path("default").intValue(),
      "openapi.yaml's limit.default must equal CollegeRouteHandler.DEFAULT_LIMIT",
    )
    assertEquals(
      "Maximum results; clamped server-side to " +
        "${CollegeSearchService.MIN_LIMIT}..${CollegeSearchService.MAX_LIMIT}.",
      limit.path("description").asText(),
      "openapi.yaml's limit.description must state the CollegeSearchService clamp range",
    )
  }
}
