package ed.unicoach.rest.models

/** Body of `200 GET /api/v1/colleges` (RFC 137). */
data class CollegeSearchResponse(
  val colleges: List<PublicCollegeSummary>,
)
