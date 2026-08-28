package ed.unicoach.db.models

/**
 * A College Scorecard `CREDLEV` credential level (RFC 139). The numeric [code]
 * is the Scorecard wire value and lives only here, so no Kotlin caller writes a
 * bare code and no out-of-domain code can be constructed at all — the query
 * boundary needs no range check.
 *
 * The gaps are deliberate: 4 (post-baccalaureate certificate), 6 (post-master's
 * certificate) and 8 (first professional) are real Scorecard codes but are not
 * offered as filters.
 */
enum class CredentialLevel(
  val code: Int,
) {
  CERTIFICATE(1),
  ASSOCIATE(2),
  BACHELORS(3),
  MASTERS(5),
  DOCTORAL(7),
}
