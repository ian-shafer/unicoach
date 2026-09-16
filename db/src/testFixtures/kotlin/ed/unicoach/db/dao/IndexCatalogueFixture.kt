package ed.unicoach.db.dao

import java.sql.Connection

/**
 * The catalogue answer to "is this index PARTIAL?", asked by the tests that
 * assert WHY two unique indexes over the same shape differ (RFC 221).
 *
 * It lives here rather than in a test class because it was written twice — once
 * in `SupportersDaoTest` to assert that `supporters_user_id_unique_idx` is
 * TOTAL, once in `SupporterStudentLinksDaoTest` to assert that
 * `supporter_student_links_supporter_student_active_idx` is PARTIAL. The two
 * copies were byte-identical, and the two assertions are deliberately opposite
 * halves of ONE fact, so the reading must not be able to differ between them.
 *
 * No test assertion inside: the fixtures source set has no `kotlin.test`, and a
 * fixture that cannot find the index has not failed an assertion — it has been
 * asked about something that is not there.
 */
object IndexCatalogueFixture {
  /**
   * Whether [indexName] carries a WHERE predicate (`pg_index.indpred`).
   *
   * Throws when no index of that name exists: the `regclass` cast refuses an
   * unknown name outright, and the empty-result branch covers the rest.
   */
  fun isPartial(
    connection: Connection,
    indexName: String,
  ): Boolean {
    connection
      .prepareStatement("SELECT indpred IS NOT NULL AS partial FROM pg_index WHERE indexrelid = ?::regclass")
      .use { stmt ->
        stmt.setString(1, indexName)
        stmt.executeQuery().use { rs ->
          check(rs.next()) { "index [$indexName] must exist" }
          return rs.getBoolean("partial")
        }
      }
  }
}
