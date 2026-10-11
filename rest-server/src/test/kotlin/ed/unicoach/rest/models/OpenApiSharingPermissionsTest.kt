package ed.unicoach.rest.models

import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Enforces the openapi.yaml <-> sharing-permissions coupling (RFC 248): both
 * operations are published, and every published vocabulary equals the Kotlin
 * enum the server reads it with, so adding a permission or a status cannot
 * leave the document stating a list the server no longer serves.
 *
 * No value is written as a literal here; each expectation is read from an enum.
 */
class OpenApiSharingPermissionsTest {
  private val collection = "/api/v1/students/me/sharing-permissions"
  private val item = "$collection/{permission}"

  private val permissions = SharingPermission.entries.map { it.value }
  private val writableStatuses = SharingPermissionStatus.entries.map { it.value }

  @Test
  fun `both operations are published behind the session cookie`() {
    listOf(collection to "get", item to "put").forEach { (path, method) ->
      val security = OpenApiSpec.operation(path, method).path("security")
      assertFalse(security.isMissingNode || security.isEmpty, "[$method $path] must publish its cookieAuth security")
    }
  }

  @Test
  fun `the published permissions are exactly SharingPermission's entries`() {
    val pathEnum =
      OpenApiSpec
        .parameter(item, "put", "permission")
        .path("schema")
        .path("enum")
        .map { it.asText() }
    assertEquals(permissions, pathEnum, "the {permission} path parameter")
    assertEquals(permissions, OpenApiSpec.enumValues("PublicSharingPermission", "permission"))
  }

  @Test
  fun `the published statuses are SharingPermissionStatus's entries, plus not_asked on the read side only`() {
    assertEquals(writableStatuses, OpenApiSpec.enumValues("UpdateSharingPermissionRequest", "status"))
    assertEquals(
      SharingPermissionStatusView.entries.map { it.wire },
      OpenApiSpec.enumValues("PublicSharingPermission", "status"),
    )
  }
}
