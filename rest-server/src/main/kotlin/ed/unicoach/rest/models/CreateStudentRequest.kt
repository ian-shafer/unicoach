package ed.unicoach.rest.models

data class CreateStudentRequest(
  val expectedHighSchoolGraduationDate: String,
  /**
   * An ISO full date, `YYYY-MM-DD`. Required by the API contract but NULLABLE in
   * Kotlin and given no default: the serializer would turn a missing
   * non-nullable field into a deserialization failure, and a default would
   * silently invent an age for an older client that omits the field. Null
   * reaches `StudentService` and comes back as a 400 `validation_error` naming
   * `dateOfBirth` (RFC 222).
   */
  val dateOfBirth: String?,
)
