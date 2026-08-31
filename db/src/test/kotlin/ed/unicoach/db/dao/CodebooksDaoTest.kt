package ed.unicoach.db.dao

import ed.unicoach.db.models.JurisdictionKind
import ed.unicoach.db.models.NewAthleticAssociation
import ed.unicoach.db.models.NewCarnegieSizeSetting
import ed.unicoach.db.models.NewCipCode
import ed.unicoach.db.models.NewCodebookSource
import ed.unicoach.db.models.NewIpedsRegion
import ed.unicoach.db.models.NewNcesLocale
import ed.unicoach.db.models.NewUsState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RFC 147 codebook reference tables: the upsert-if-changed three-way split
 * on tables with no `version` column, the shared `slug` domain, the
 * `us_states -> ipeds_regions` foreign key, the always-rewritten provenance row,
 * and the two identifier-parameterised reads (including their allowlist).
 */
class CodebooksDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE colleges, college_ipeds, ipeds_regions, us_states, nces_locales, " +
          "carnegie_2021_basic_classes, carnegie_2021_size_settings, religious_affiliations, " +
          "athletic_associations, football_conferences, admission_test_policies, cip_codes, " +
          "codebook_sources CASCADE",
      )
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun region(
    slug: String = "new-england",
    code: Int = 1,
    name: String = "New England",
  ) = NewIpedsRegion(slug = slug, code = code, name = name, labelRaw = "New England CT ME MA NH RI VT")

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  @Test
  fun `an unchanged re-upsert writes nothing and leaves updated_at alone`() {
    assertEquals(UpsertOutcome.INSERTED, CodebooksDao.upsertIpedsRegion(session, region()).getOrThrow())
    val firstUpdatedAt = updatedAt("ipeds_regions", "slug", "new-england")

    assertEquals(UpsertOutcome.UNCHANGED, CodebooksDao.upsertIpedsRegion(session, region()).getOrThrow())
    assertEquals(firstUpdatedAt, updatedAt("ipeds_regions", "slug", "new-england"))

    assertEquals(
      UpsertOutcome.CHANGED,
      CodebooksDao.upsertIpedsRegion(session, region(name = "New England (relabelled)")).getOrThrow(),
    )
    assertTrue(updatedAt("ipeds_regions", "slug", "new-england") > firstUpdatedAt)
  }

  @Test
  fun `the slug domain rejects a non-slug key`() {
    val failure =
      assertFailsWith<ConstraintViolationException> {
        CodebooksDao.upsertIpedsRegion(session, region(slug = "New England")).getOrThrow()
      }
    assertEquals("slug_check", failure.constraint)
  }

  @Test
  fun `a state must name a region that exists`() {
    val orphan =
      NewUsState(
        uspsCode = "CT",
        name = "Connecticut",
        jurisdictionKind = JurisdictionKind.STATE,
        ipedsRegion = "new-england",
      )
    // A `23503` here is a load-order defect (states before regions), and the
    // house mapping calls an absent parent NotFound, not a constraint violation.
    assertFailsWith<NotFoundException> { CodebooksDao.upsertUsState(session, orphan).getOrThrow() }

    CodebooksDao.upsertIpedsRegion(session, region()).getOrThrow()
    assertEquals(UpsertOutcome.INSERTED, CodebooksDao.upsertUsState(session, orphan).getOrThrow())
  }

  @Test
  fun `a nullable parsed column round-trips as null`() {
    // carnegie_2021_size_settings is where the nullable parsed columns are: the
    // "-2 not classified" row publishes no years, size or residential character,
    // and each has to survive the write as NULL rather than as an empty string.
    val notClassified =
      NewCarnegieSizeSetting(
        slug = "not-classified",
        code = -2,
        years = null,
        size = null,
        residentialCharacter = null,
        name = "Not classified",
        labelRaw = "Not classified",
      )
    CodebooksDao.upsertCarnegieSizeSetting(session, notClassified).getOrThrow()
    assertNull(scalar("SELECT size FROM carnegie_2021_size_settings WHERE code = -2"))
    assertNull(scalar("SELECT residential_character FROM carnegie_2021_size_settings WHERE code = -2"))
  }

  @Test
  fun `the boundary reads return every row, in published-code order`() {
    // The read the word <-> code lookup is built from (RFC 147 D45). Order is
    // part of the contract: it becomes the `region` enum a model reads, and an
    // arbitrary order would make the advertised schema unstable between boots.
    CodebooksDao.upsertIpedsRegion(session, region(slug = "far-west", code = 8, name = "Far West")).getOrThrow()
    CodebooksDao.upsertIpedsRegion(session, region()).getOrThrow()

    val regions = CodebooksDao.ipedsRegions(session).getOrThrow()
    assertEquals(listOf(1, 8), regions.map { it.code })
    assertEquals(listOf("new-england", "far-west"), regions.map { it.slug })
    assertEquals("New England", regions.first().name)
    assertEquals("New England CT ME MA NH RI VT", regions.first().labelRaw)

    CodebooksDao
      .upsertNcesLocale(
        session,
        NewNcesLocale(
          slug = "city-small",
          code = 13,
          type = "city",
          detail = "small",
          name = "City: Small",
          labelRaw = "City: Small",
        ),
      ).getOrThrow()

    val locales = CodebooksDao.ncesLocales(session).getOrThrow()
    assertEquals(1, locales.size)
    assertEquals("city", locales.single().type)
    assertEquals("small", locales.single().detail)
    assertEquals("City: Small", locales.single().name)
  }

  @Test
  fun `the boundary reads are empty, not absent, on a database that never loaded a codebook`() {
    // The state every test database is in, and every fresh deployment before
    // the `codebooks` ingest phase runs. An empty list is a legitimate answer;
    // a failure here would make the boundary un-bootable rather than un-worded.
    assertEquals(emptyList(), CodebooksDao.ipedsRegions(session).getOrThrow())
    assertEquals(emptyList(), CodebooksDao.ncesLocales(session).getOrThrow())
  }

  @Test
  fun `the provenance row is rewritten on every load, sentinels included`() {
    val source =
      NewCodebookSource(
        domain = "ipeds_region",
        source = "IPEDS HD2023 (OBEREG)",
        sourceFile = "HD2023_Stata.zip",
        sourceSha256 = "a".repeat(64),
        sourceVintageYear = 2023,
        nullSentinels = listOf(-1, -3),
      )
    CodebooksDao.upsertSource(session, source).getOrThrow()
    assertEquals(mapOf("ipeds_region" to "a".repeat(64)), CodebooksDao.storedSourceDigests(session).getOrThrow())
    assertEquals("{-1,-3}", scalar("SELECT null_sentinels::text FROM codebook_sources"))
    val firstLoadedAt = scalar("SELECT loaded_at::text FROM codebook_sources")

    CodebooksDao.upsertSource(session, source.copy(sourceSha256 = "b".repeat(64))).getOrThrow()
    assertEquals(mapOf("ipeds_region" to "b".repeat(64)), CodebooksDao.storedSourceDigests(session).getOrThrow())
    // loaded_at moves even though the domain key did not: it records the LOAD,
    // which is the fact this row exists to carry.
    assertTrue(scalar("SELECT loaded_at::text FROM codebook_sources")!! >= firstLoadedAt!!)
  }

  @Test
  fun `a malformed digest is refused by the schema`() {
    val bad =
      NewCodebookSource(
        domain = "ipeds_region",
        source = "IPEDS HD2023 (OBEREG)",
        sourceFile = "HD2023_Stata.zip",
        sourceSha256 = "not-a-digest",
        sourceVintageYear = 2023,
        nullSentinels = emptyList(),
      )
    assertFailsWith<ConstraintViolationException> { CodebooksDao.upsertSource(session, bad).getOrThrow() }
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  @Test
  fun `storedRows carries the code, or null where the key IS the code`() {
    CodebooksDao.upsertIpedsRegion(session, region()).getOrThrow()
    CodebooksDao
      .upsertCipCode(session, NewCipCode(code = "010000", title = "Agriculture, General", labelRaw = "01.0000-Agriculture, General"))
      .getOrThrow()

    assertEquals(
      listOf(StoredCodebookRow("new-england", 1)),
      CodebooksDao.storedRows(session, CodebookTable.IPEDS_REGIONS).getOrThrow(),
    )
    assertEquals(
      listOf(StoredCodebookRow("010000", null)),
      CodebooksDao.storedRows(session, CodebookTable.CIP_CODES).getOrThrow(),
    )
    assertEquals(1, CodebooksDao.rowCount(session, CodebookTable.CIP_CODES).getOrThrow())
  }

  @Test
  fun `deleteRow removes exactly the named key`() {
    CodebooksDao.upsertIpedsRegion(session, region()).getOrThrow()
    CodebooksDao.upsertIpedsRegion(session, region(slug = "far-west", code = 8, name = "Far West")).getOrThrow()

    assertEquals(1, CodebooksDao.deleteRow(session, CodebookTable.IPEDS_REGIONS, "new-england").getOrThrow())
    assertEquals(
      listOf("far-west"),
      CodebooksDao.storedRows(session, CodebookTable.IPEDS_REGIONS).getOrThrow().map { it.key },
    )
    // A key nobody stored is 0 rows, not a failure: the caller decides.
    assertEquals(0, CodebooksDao.deleteRow(session, CodebookTable.IPEDS_REGIONS, "nowhere").getOrThrow())
  }

  @Test
  fun `storedCodeCounts counts scalar and array columns, ignoring nulls`() {
    connection.createStatement().use { stmt ->
      stmt.execute(
        """
        INSERT INTO college_ipeds (ipeds_unit_id, survey_year, cy_active, rel_affil, athletic_assoc)
        VALUES (1, 2023, TRUE, 71, '{1,2}'), (2, 2023, TRUE, 71, '{2}'), (3, 2023, TRUE, NULL, '{}')
        """.trimIndent(),
      )
    }
    assertEquals(
      mapOf("71" to 2),
      CodebooksDao.storedCodeCounts(session, CodeColumn("college_ipeds", "rel_affil")).getOrThrow(),
    )
    assertEquals(
      mapOf("1" to 1, "2" to 2),
      CodebooksDao
        .storedCodeCounts(session, CodeColumn("college_ipeds", "athletic_assoc", isArray = true))
        .getOrThrow(),
    )
  }

  @Test
  fun `storedCodeCounts refuses a column outside the allowlist`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        CodebooksDao.storedCodeCounts(session, CodeColumn("colleges", "name"))
      }
    assertTrue(failure.message!!.contains("colleges.name"), failure.message!!)
  }

  @Test
  fun `an athletic association keeps its ordinal and its verbatim label`() {
    CodebooksDao
      .upsertAthleticAssociation(
        session,
        NewAthleticAssociation(
          slug = "njcaa",
          code = 3,
          sourceVariable = "assoc3",
          name = "Member of National Junior College Athletic Association (NJCAA)",
          labelRaw = "Member of National Junior College  Athletic Association (NJCAA)",
        ),
      ).getOrThrow()
    assertEquals(
      "Member of National Junior College  Athletic Association (NJCAA)",
      scalar("SELECT label_raw FROM athletic_associations WHERE code = 3"),
    )
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun scalar(sql: String): String? =
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getString(1)
      }
    }

  private fun updatedAt(
    table: String,
    keyColumn: String,
    key: String,
  ): String = scalar("SELECT updated_at::text FROM $table WHERE $keyColumn = '$key'")!!
}
