package ed.unicoach.db.dao

import ed.unicoach.db.models.NewAdmissionTestPolicy
import ed.unicoach.db.models.NewAthleticAssociation
import ed.unicoach.db.models.NewCarnegieBasicClass
import ed.unicoach.db.models.NewCarnegieSizeSetting
import ed.unicoach.db.models.NewCipCode
import ed.unicoach.db.models.NewIpedsRegion
import ed.unicoach.db.models.NewNcesLocale
import ed.unicoach.db.models.NewReligiousAffiliation
import java.sql.Connection

/**
 * The shared fixture of the two `college_search_index` suites (RFC 150):
 * `CollegesDaoTest`, which asserts on what a search RETURNS, and
 * `CollegeSearchIndexRebuildTest`, which asserts on what the rebuild WRITES.
 *
 * Both need the same seventeen tables emptied and the same miniature codebook
 * present, and both had their own byte-identical copy of the `TRUNCATE` (four
 * copies in all, `@BeforeEach` and `@AfterAll` in each suite). A table added to
 * the index and to three of the four copies is a suite that starts dirty and
 * fails somewhere else, so the list lives here once.
 */
internal object SearchIndexFixture {
  /**
   * Every table the two suites write, child-first for readability (`CASCADE`
   * makes the order immaterial). `codebook_sources` is last because it is the
   * provenance row the codebook writes carry.
   */
  private val TABLES =
    listOf(
      "colleges",
      "college_programs",
      "college_ipeds",
      "college_programs_census",
      "college_search_index",
      "subjects",
      "ipeds_regions",
      "us_states",
      "nces_locales",
      "carnegie_2021_basic_classes",
      "carnegie_2021_size_settings",
      "religious_affiliations",
      "athletic_associations",
      "football_conferences",
      "admission_test_policies",
      "cip_codes",
      "codebook_sources",
    )

  /**
   * Empties [TABLES].
   *
   * Both suites run this BEFORE each test AND once at the end: the seeded
   * codebook rows are MINIATURES, not `db/data/codebooks.json`, so left behind
   * they would collide with a later suite that loads the real codebook, where
   * the same published code arrives under a different slug and the loader
   * (correctly) refuses.
   */
  fun truncate(connection: Connection) {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE ${TABLES.joinToString(", ")} CASCADE")
    }
  }

  /**
   * The miniature codebook both suites filter and rebuild against: two regions,
   * two locales, a Carnegie class and size, an affiliation, two athletic
   * associations, a test policy, and the four CIP codes the program tests
   * expand prefixes against.
   *
   * It is deliberately ONE set rather than a per-suite subset: two nearly-equal
   * seeds are two things to keep in step, and no row here creates a college, so
   * no count assertion moves.
   *
   * A row is not free, though. The rebuild suite proves an unknown code goes
   * NULL rather than dropping the college, so it names codes by their ABSENCE
   * from this seed — adding one here silently turns such a test into a
   * tautology. Locale 43 is the live example: `CollegesDaoTest` needs it to
   * resolve, so the rebuild suite uses 42 for "unknown". Pick an unseeded code
   * there; do not seed a code a test needs missing.
   */
  fun seedCodebooks(session: SqlSession) {
    CodebooksDao.upsertIpedsRegion(session, NewIpedsRegion("far-west", 8, "Far West", "Far West CA")).getOrThrow()
    CodebooksDao.upsertIpedsRegion(session, NewIpedsRegion("new-england", 1, "New England", "New England")).getOrThrow()
    CodebooksDao
      .upsertNcesLocale(session, NewNcesLocale("city-small", 13, "city", "small", "City: Small", "City: Small"))
      .getOrThrow()
    CodebooksDao
      .upsertNcesLocale(session, NewNcesLocale("rural-remote", 43, "rural", "remote", "Rural: Remote", "Rural: Remote"))
      .getOrThrow()
    CodebooksDao
      .upsertCarnegieBasicClass(
        session,
        NewCarnegieBasicClass("doctoral-very-high", 15, "doctoral", "very high research", "R1", "R1 raw"),
      ).getOrThrow()
    CodebooksDao
      .upsertCarnegieSizeSetting(
        session,
        NewCarnegieSizeSetting("four-year-large-residential", 17, 4, "large", "residential", "Large residential", "raw"),
      ).getOrThrow()
    CodebooksDao.upsertReligiousAffiliation(session, NewReligiousAffiliation("jesuit", 30, "Jesuit", "Jesuit")).getOrThrow()
    CodebooksDao
      .upsertAthleticAssociation(session, NewAthleticAssociation("ncaa", 1, "assoc1", "NCAA", "NCAA raw"))
      .getOrThrow()
    CodebooksDao
      .upsertAthleticAssociation(session, NewAthleticAssociation("naia", 2, "assoc2", "NAIA", "NAIA raw"))
      .getOrThrow()
    CodebooksDao
      .upsertAdmissionTestPolicy(session, NewAdmissionTestPolicy("considered-but-not-required", 5, "Optional", "Optional"))
      .getOrThrow()
    for (
    (code, title) in
    listOf(
      "230101" to "English Language and Literature, General",
      "231303" to "Professional, Technical, Business, and Scientific Writing",
      "260101" to "Biology, General",
      "260702" to "Marine Biology and Biological Oceanography",
    )
    ) {
      CodebooksDao.upsertCipCode(session, NewCipCode(code, title, title)).getOrThrow()
    }
  }
}
