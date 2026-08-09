package ed.unicoach.rest.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Holds `SubscriptionView.status` in the published contract to
 * [SubscriptionStatusView] — the enum Jackson actually renders into that field
 * (RFC 110). The spec restates the status vocabulary rather than deriving it,
 * and nothing else in the default gate opens `api-specs/openapi.yaml`, so
 * without this guard a status added to or removed from the enum leaves the
 * published contract silently stale.
 *
 * The db-side vocabulary needs no assertion here: [SubscriptionStatusView.from]
 * is exhaustive over `SubscriptionStatus`, so membership drift between the two
 * is a compile error, and the storage spellings are held by
 * `subscriptions_status_check` in `db/schema/0042.create-subscriptions.sql`,
 * which `SubscriptionsDaoTest` exercises for every status.
 */
class OpenApiSubscriptionStatusTest {
  @Test
  fun `SubscriptionView status enumerates exactly SubscriptionStatusView's wire strings`() {
    val published = OpenApiSpec.get("SubscriptionView", "status").path("enum").map { it.asText() }
    assertEquals(
      SubscriptionStatusView.entries.map { it.wire },
      published,
      "api-specs/openapi.yaml's SubscriptionView.status enum must list exactly SubscriptionStatusView's wire strings, in declaration order",
    )
  }
}
