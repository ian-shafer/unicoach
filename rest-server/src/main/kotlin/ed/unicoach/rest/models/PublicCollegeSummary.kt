package ed.unicoach.rest.models

import java.util.UUID

/**
 * One row of the student-facing college search (RFC 137): the id to add with,
 * the name to show, and "City, ST" to disambiguate same-named institutions.
 */
data class PublicCollegeSummary(
  val id: UUID,
  val name: String,
  val city: String,
  val state: String,
)
