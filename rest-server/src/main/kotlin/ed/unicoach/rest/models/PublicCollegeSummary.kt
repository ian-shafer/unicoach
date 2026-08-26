package ed.unicoach.rest.models

import java.util.UUID

/**
 * One row of the student-facing college search (RFC 137): the id to add with,
 * the name to show, and "City, ST" to disambiguate same-named institutions.
 */
data class PublicCollegeSummary(
  /**
   * Deliberately `id`, NOT `college_id` — do not "unify" it with the
   * `search_colleges` chat tool, which names the same value `college_id` on
   * purpose. The two surfaces solve different problems. Here the hop to
   * [CreateCollegeListEntryRequest.collegeId] runs through a typed client, so
   * a name mismatch is a compile error and the field is simply this object's
   * own identity — `college_id` would be the `college.college_id` stutter the
   * prefix convention reserves for fields naming *another* entity (as
   * [CollegeListEntryResponse.collegeId] does). The chat tool has no compiler
   * on that hop: only a language model copying a key out of JSON, which once
   * failed to find an id at all and invented a slug. Matching the consumer's
   * parameter name there is a mitigation for that hazard, not a style rule.
   */
  val id: UUID,
  val name: String,
  val city: String,
  val state: String,
)
