package ed.unicoach.coaching

import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.college.SimilarCollegesTool
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.PriceRuler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The adapter is a verbatim delegate (RFC 153 D72): name/definition come from
 * the wrapped tool unchanged, and execute forwards the input and returns the
 * wrapped result. The registry case is here too, because the failure it guards
 * — two tools advertising one name — is a construction-time `require` that only
 * fires when both are registered together.
 */
class SimilarCollegesChatToolTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  // The delegation, not the vocabulary, is under test: an empty codebook is the
  // honest snapshot of a database whose `codebooks` ingest phase never ran.
  private val service = CollegeSearchService(database)
  private val wrapped = SimilarCollegesTool(service, ed.unicoach.college.Codebook.EMPTY)
  private val moneyProfiles = MoneyProfileService(database)
  private val adapter = SimilarCollegesChatTool(wrapped, moneyProfiles)

  /**
   * Two colleges and a built index. `similar_colleges` refuses an unbuilt index
   * outright, and the residency cases below assert on what a real PEER LIST
   * carries — the ruler it names and the invitation beside it — so the page has
   * to exist. The peer is in ANOTHER state, which is the case the published
   * ruler is for.
   */
  @BeforeEach
  fun buildSearchIndex() {
    CollegesDao.upsert(CoachingTestDb.sqlSession, newCollege(909_092, "Residency Anchor University", "NV")).getOrThrow()
    CollegesDao.upsert(CoachingTestDb.sqlSession, newCollege(909_093, "Residency Peer College", "CA")).getOrThrow()
    CollegesDao.rebuildNameWords(CoachingTestDb.sqlSession).getOrThrow()
    CollegesDao.rebuildSearchIndex(CoachingTestDb.sqlSession).getOrThrow()
  }

  private fun anchorInput() = buildJsonObject { put("name", "Residency Anchor University") }

  @Test
  fun `name and definition come from the wrapped tool`() {
    assertEquals(SimilarCollegesTool.TOOL_NAME, adapter.name)
    assertEquals(wrapped.definition, adapter.definition)
    assertEquals(adapter.name, adapter.definition["name"]!!.toString().trim('"'), "the registry contract")
  }

  @Test
  fun `execute delegates and returns the wrapped tool's object`() =
    runBlocking {
      // A malformed input short-circuits in the wrapped tool's parser (no DB read),
      // so the adapter returning the identical structured error proves delegation.
      val student = CoachingTestDb.createStudent("similar-bad-input")
      val badInput = buildJsonObject { put("not_a_field", "x") }
      assertEquals(wrapped.execute(badInput, null), adapter.execute(student, badInput))
    }

  @Test
  fun `an unscoped dispatch is refused, not answered on the net ruler`() {
    // Student-scoped since RFC 169: the peer list ranks on the family's own
    // price, so a dispatcher that forgot to scope it must say so.
    //
    // A BLOCK body for the reason its twin in `CollegeChatToolTest` carries: an
    // expression body ending on `assertNotNull` returns a value, and a non-Unit
    // @Test is silently never run.
    runBlocking {
      val result = (adapter as ed.unicoach.chat.ChatTool).execute(buildJsonObject { })
      assertNotNull(result["error"], result.toString())
    }
  }

  @Test
  fun `the definition offers no residency input field`() {
    // The residency is READ from the money profile, never asked of the model
    // (RFC 169 §7), so `similar_colleges`' own field set is unchanged.
    val properties =
      adapter.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject
    assertTrue(properties.keys.none { "residency" in it }, properties.keys.toString())
  }

  @Test
  fun `an answered residency puts the peer list on the published ruler`() =
    runBlocking {
      // A2: this adapter's residency read and offer attach had NO test at all —
      // deleting `residency.familyResidencyState` from it stayed green. Both tools must rank on
      // the same ruler for the same family, or a search and a peer list about
      // one school disagree about which price they ranked.
      val student = CoachingTestDb.createStudent("similar-answered")
      moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Set("WA"))).getOrThrow()

      val result = adapter.execute(student, anchorInput())
      assertNull(result["error"], "$result")
      assertEquals(
        PriceRuler.PUBLISHED_PRICE_SORT_WORD,
        result["price_ruler"]!!
          .jsonObject["metric"]!!
          .jsonPrimitive.content,
      )
      assertNull(result[ResidencyOffer.KEY], "an answer closes the topic")
    }

  @Test
  fun `a residency offer never gates a peer list`() =
    runBlocking {
      // UNANSWERED, with a REAL profile row (A3): the offer's rule is keyed on
      // the STATUS, and a student with no row at all takes a different branch.
      val unanswered = CoachingTestDb.createStudent("similar-unanswered")
      moneyProfiles.upsert(unanswered, MoneyProfileUpdate(income = FieldUpdate.Set(IncomeBand.UNDER_30K))).getOrThrow()
      val profile = moneyProfiles.getForStudent(unanswered).getOrThrow()
      assertTrue(profile is GetMoneyProfileResult.Found, "the premise: a row EXISTS for this student")
      assertEquals(
        AnswerStatus.UNANSWERED,
        (profile as GetMoneyProfileResult.Found).profile.residencyStatus,
        "and its residency is unanswered, which is the branch under test",
      )

      val offered = adapter.execute(unanswered, anchorInput())
      assertNull(offered["error"], "$offered")
      assertNotNull(offered["colleges"], "the peer list is returned in full: $offered")
      assertEquals(
        ResidencyOffer.FIELD,
        offered[ResidencyOffer.KEY]!!
          .jsonObject["field"]!!
          .jsonPrimitive.content,
      )
      assertEquals(
        PriceRuler.NET_PRICE_SORT_WORD,
        offered["price_ruler"]!!
          .jsonObject["metric"]!!
          .jsonPrimitive.content,
      )

      // DECLINED: still a full peer list, still the net ruler, and NO offer —
      // the topic is closed for good.
      val declined = CoachingTestDb.createStudent("similar-declined")
      moneyProfiles.upsert(declined, MoneyProfileUpdate(residency = FieldUpdate.Decline)).getOrThrow()
      val quiet = adapter.execute(declined, anchorInput())
      assertNull(quiet["error"], "$quiet")
      assertNotNull(quiet["colleges"], "$quiet")
      assertNull(quiet[ResidencyOffer.KEY], "a decline is permanent")
    }

  @Test
  fun `a residency offer is never attached to a refusal`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("similar-refusal")
      val refusal = adapter.execute(student, buildJsonObject { put("name", "Nonesuch Polytechnic") })
      assertNotNull(refusal["error"], "$refusal")
      assertNull(refusal[ResidencyOffer.KEY], "an invitation on a refusal reads as part of the failure")
    }

  private fun newCollege(
    ipedsUnitId: Int,
    name: String,
    state: String,
  ) = NewCollege(
    ipedsUnitId = ipedsUnitId,
    opeid = null,
    name = name,
    city = "Testville",
    state = state,
    region = null,
    locale = null,
    latitude = null,
    longitude = null,
    control = 1,
    undergradEnrollmentHeadcount = 5000,
    admissionRateShare = 0.5,
    satAverageEquivalentScore = 1200,
    completionRate150pct4yrShare = 0.7,
    website = null,
  )

  @Test
  fun `it registers beside the search tool under a name of its own`() {
    val search = CollegeChatTool(CollegeSearchTool(service, ed.unicoach.college.Codebook.EMPTY), MoneyProfileService(database))
    // A duplicate name is a construction-time `require` failure, so a registry
    // that CONSTRUCTS is the assertion that the two names are distinct.
    val registry = ToolRegistry(listOf(search, adapter))

    assertNotNull(registry.get(SimilarCollegesTool.TOOL_NAME), "the coach can dispatch to it")
    assertSame(adapter.definition, registry.get(SimilarCollegesTool.TOOL_NAME)!!.definition)
    assertEquals(
      listOf(CollegeSearchTool.TOOL_NAME, SimilarCollegesTool.TOOL_NAME),
      registry.definitions().map { it["name"]!!.toString().trim('"') },
      "every turn advertises both, in registration order",
    )
  }
}
