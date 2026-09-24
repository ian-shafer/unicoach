package ed.unicoach.db.dao

import ed.unicoach.db.models.CipPrefix
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CredentialLevel
import ed.unicoach.db.models.NewCollegeProgram
import java.util.UUID

/**
 * The authored subject taxonomy and the programs a subject reaches (RFC 205,
 * RFC 231), for any test whose subject is a CIP prefix match.
 *
 * Published from `:db`'s test fixtures, on the [MoneyVocabularyFixture] and
 * [CodebookReferenceFixture] precedent, because `:db`'s own DAO tests and
 * `:service`'s costs fixture seed the same two tables: the `subjects` write
 * shape is one fact about the schema, not one fact per module. Two copies is
 * two places to fix the day `subjects` gains a column.
 *
 * [SqlSession] is the seam on BOTH methods, as it is on every fixture here:
 * the boundary exists so nothing outside `Database.withConnection` holds the
 * pooled connection, and a fixture that took a raw `Connection` for one of its
 * two writes would have a caller seeding two tables through two resources with
 * no owner for the transaction.
 *
 * It writes `subjects` raw rather than through `CodebooksDao`, exactly as
 * [CodebookReferenceFixture] does: those writes carry provenance and
 * change-detection a fixture has no business faking.
 */
object SubjectTaxonomyFixture {
  /**
   * One authored taxonomy row; [prefixes] are the canonical digits-only CIP
   * prefixes.
   *
   * Each prefix is BOUND, one parameter per element, so the driver owns the
   * encoding: the brace form this used to build by hand (`{a,b}`) is a format
   * whose escaping rules -- a comma, a brace, a quote, a backslash, the literal
   * text `NULL` -- lived in the author's memory, and a prefix carrying one of
   * them silently stored a DIFFERENT array than the caller asked for. The house
   * `TEXT_ARRAY_PARAM` + `jsonbArrayBinder` pair is `internal` to `:db`'s main
   * compilation and unreachable from here, so this binds the elements directly
   * rather than restating that primitive's SQL.
   *
   * Input the fixture cannot honestly encode is REFUSED, not reinterpreted:
   * the shape is the one `subjects_cip_prefixes_format_check` and
   * `subjects_cip_prefixes_nonempty_check` state, and a caller naming the
   * mistake beats a constraint violation a thousand rows later.
   *
   * The per-element shape is READ from [CipPrefix.CANONICAL_REGEX] and not
   * written again here: `:db`'s own main source set already states "a 2-, 4- or
   * 6-digit CIP prefix", publicly and reachable from this fixture, and a second
   * statement of it agrees today and is free to disagree at the next CIP
   * change -- silently accepting rows the store rejects.
   */
  fun insertSubject(
    session: SqlSession,
    slug: String,
    name: String,
    vararg prefixes: String,
  ) {
    require(prefixes.isNotEmpty()) { "a subject carries at least one cip_prefix (subjects_cip_prefixes_nonempty_check): [$slug]" }
    require(prefixes.all { it.matches(CipPrefix.CANONICAL_REGEX) }) {
      "cip_prefixes are 2-, 4- or 6-digit CIP prefixes (subjects_cip_prefixes_format_check); " +
        "got [${prefixes.joinToString(", ")}]"
    }
    val elements = prefixes.indices.joinToString(", ") { "?" }
    session
      .prepareStatement("INSERT INTO subjects (slug, name, cip_prefixes) VALUES (?, ?, ARRAY[$elements]::TEXT[])")
      .use { stmt ->
        stmt.setString(1, slug)
        stmt.setString(2, name)
        prefixes.forEachIndexed { index, prefix -> stmt.setString(index + 3, prefix) }
        stmt.executeUpdate()
      }
  }

  /**
   * One `college_programs` row on [collegeId], returning its id. Bachelor's
   * ([CredentialLevel.BACHELORS]) unless a caller says otherwise, because that
   * is the level the major answer reads.
   */
  fun insertProgram(
    session: SqlSession,
    collegeId: CollegeId,
    cipCode: String,
    cipTitle: String = "Fixture program",
    credentialLevel: Int = CredentialLevel.BACHELORS.code,
  ): UUID =
    CollegesDao
      .upsertProgram(
        session,
        NewCollegeProgram(
          collegeId = collegeId,
          cipCode = cipCode,
          cipTitle = cipTitle,
          credentialLevel = credentialLevel,
        ),
      ).getOrThrow()
      .id
      .value
}
