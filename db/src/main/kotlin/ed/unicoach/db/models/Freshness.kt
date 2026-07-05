package ed.unicoach.db.models

import java.time.Instant

/**
 * The newest [Updated.updatedAt] across the elements, or null when the iterable
 * is empty. The reflection passes (synthesis, fit-lens) compute a model's
 * freshness as the max `updated_at` over its [Updated] inputs (active claims and
 * college-list entries) to no-op an unchanged model before spending tokens.
 */
fun Iterable<Updated>.latestUpdatedAt(): Instant? = maxOfOrNull { it.updatedAt }
