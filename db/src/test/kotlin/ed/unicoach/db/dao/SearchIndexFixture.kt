package ed.unicoach.db.dao

import ed.unicoach.db.models.NewAdmissionTestPolicy
import ed.unicoach.db.models.NewAthleticAssociation
import ed.unicoach.db.models.NewCarnegieBasicClass
import ed.unicoach.db.models.NewCarnegieSizeSetting
import ed.unicoach.db.models.NewCipCode
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
   * The miniature codebook both suites filter and rebuild against: the shared
   * [CodebookReferenceFixture] region/state/locale vocabulary plus a Carnegie
   * class and size, an affiliation, two athletic associations, a test policy,
   * and the four CIP codes the program tests expand prefixes against.
   *
   * The counts of the shared half are NOT restated here — that fixture derives
   * them from `db/data/codebooks.json`, so a number written down here would be a
   * third copy of the vocabulary, wrong the day the codebook is regenerated.
   *
   * It is deliberately ONE set rather than a per-suite subset: two nearly-equal
   * seeds are two things to keep in step, and no row here creates a college, so
   * no count assertion moves.
   *
   * A row is not free, though. The rebuild suite proves an unknown code goes
   * NULL rather than dropping the college, so it names codes by their ABSENCE
   * from this seed — adding one here silently turns such a test into a
   * tautology. Since 0067 that argument only applies to the columns with NO
   * foreign key: `colleges.region` (region 9, which the shared fixture omits
   * for exactly this reason) and the `college_ipeds` code columns.
   * `colleges.state` and `colleges.locale` can no longer HOLD a code the
   * codebook does not name, which is the point of the constraint.
   */
  fun seedCodebooks(session: SqlSession) {
    // The regions, states and locales come from the SHARED fixture, not from
    // two hand-typed rows here: migration 0067 made `us_states` and
    // `nces_locales` a precondition of inserting any college, so every suite in
    // every module needs the same rows and there must be one copy of them. It
    // omits the `other-us-jurisdictions` region on purpose, which is what keeps
    // region 9 usable below as a code no codebook explains.
    CodebookReferenceFixture.seed(session)
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
