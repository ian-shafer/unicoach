package ed.unicoach.coaching.testing

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.AutomaticAwardFixture
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Month
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `college_automatic_awards` wire (RFC 239): what the model is actually
 * handed.
 *
 * The BEHAVIOUR is asserted in [AutomaticAwardServiceTest]; this suite asserts
 * the things only the payload can be wrong about -- that one school's rule
 * cannot be read onto another, that each grid's citation is stated once and
 * never per band (RFC 190), that the numbers this tool emits are the ones it
 * declared, and that no sentence anywhere presents our combined figure as one a
 * test publisher computed and reported.
 */
class AutomaticAwardChatToolTest {
  private val tool = AutomaticAwardTestDb.toolAt(AutomaticAwardTestDb.NOON_EASTERN_2026_09_24)

  @BeforeEach
  fun setUp() {
    AutomaticAwardTestDb.reset()
  }

  @Test
  fun `the tool name and the advertised schema are the shared college_ids contract`() {
    assertEquals(AutomaticAwardChatTool.TOOL_NAME, tool.name)
    assertEquals(
      AutomaticAwardChatTool.TOOL_NAME,
      tool.definition
        .getValue("name")
        .jsonPrimitive.content,
    )
    val schema = tool.definition.getValue("input_schema").jsonObject
    assertEquals("object", schema.getValue("type").jsonPrimitive.content)
    assertTrue(
      StudentScopedChatTool.COLLEGE_IDS_FIELD in schema.getValue("properties").jsonObject,
      "the tool must advertise the shared subset filter, and nothing else: [$schema]",
    )
    assertEquals(
      setOf(StudentScopedChatTool.COLLEGE_IDS_FIELD),
      schema
        .getValue("properties")
        .jsonObject.keys,
      "the input is college_ids ONLY -- the same shape as admission_test_timeline",
    )
    assertEquals(
      false,
      schema
        .getValue("additionalProperties")
        .jsonPrimitive.content
        .toBoolean(),
    )
  }

  /**
   * ONE OBJECT PER REQUESTED COLLEGE, EACH CARRYING ITS OWN ANSWER. Two schools
   * with two different answers reach the model as two objects, and neither one's
   * discriminator, citation or rule appears under the other.
   */
  @Test
  fun `one object per requested college, each grid carrying its own answer`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val paying = AutomaticAwardTestDb.seedListedCollege(student, "Paying College")
      val unread = AutomaticAwardTestDb.seedListedCollege(student, "Unread College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(paying), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val payload = tool.execute(student, buildJsonObject {})
      val colleges = payload.getValue("colleges").jsonArray.map { it.jsonObject }

      val byCollege = colleges.associateBy { it.getValue("college_id").jsonPrimitive.content }

      assertEquals(2, colleges.size, "two schools on the list, two objects: [$payload]")
      assertEquals(
        setOf(paying.value.toString(), unread.value.toString()),
        byCollege.keys,
        "each object names the school it answers for",
      )
      val curated = gridsOf(byCollege.getValue(paying.value.toString())).single()
      val never = gridsOf(byCollege.getValue(unread.value.toString())).single()
      assertEquals(
        listOf(AutomaticAwardChatTool.SCORE_BANDS, AutomaticAwardChatTool.NOT_CURATED),
        listOf(curated, never).map { it.getValue("answer").jsonPrimitive.content },
        "the two answers are different words, so the model cannot read one school's rule onto the other",
      )
      assertEquals(AutomaticAwardTestDb.DEFAULT_PUBLISHER, curated.getValue("publisher").jsonPrimitive.content)
      assertEquals(JsonNull, never.getValue("publisher"), "a school we never read cites nobody")
      assertTrue(
        never
          .getValue("why")
          .jsonPrimitive.content
          .contains("gap in OUR data"),
        "and it says whose gap it is: [${never.getValue("why")}]",
      )
    }

  /**
   * A SCHOOL WITH TWO GRIDS RENDERS BOTH, EACH LABELLED. Iowa State and Kansas
   * State are the measured shape: an in-state award gated on a score beside an
   * out-of-state award paid on GPA alone. One object for the school, two grids
   * inside it, each naming whose it is -- and nothing here picks one, because a
   * student's residency for tuition is a status the school determines.
   */
  @Test
  fun `a school with several residency grids renders both, each labelled`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Two Grid University")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, residencyBasis = ResidencyBasis.IN_STATE),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          college,
          residencyBasis = ResidencyBasis.OUT_OF_STATE,
          awardKind = AutomaticAwardKind.GPA_ONLY_GRID,
        ),
        listOf(AutomaticAwardFixture.band(test = null, scoreLow = null, scoreHigh = null, awardPerYearUsd = 4000)),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val payload = payloadFor(student)
      val grids =
        gridsOf(
          payload
            .getValue("colleges")
            .jsonArray
            .single()
            .jsonObject,
        )

      assertEquals(2, grids.size, "one school, two published grids")
      assertEquals(
        listOf(ResidencyBasis.IN_STATE.value, ResidencyBasis.OUT_OF_STATE.value),
        grids.map { it.getValue("residency_basis").jsonPrimitive.content },
        "each grid names whose award it is",
      )
      assertEquals(
        listOf(AutomaticAwardChatTool.SCORE_BANDS, AutomaticAwardChatTool.NO_SCORE_EFFECT),
        grids.map { it.getValue("answer").jsonPrimitive.content },
        "and each carries its OWN answer: a score is money on one of these grids and changes nothing on the other",
      )
      grids.forEach { grid ->
        assertTrue(
          grid
            .getValue("residency_basis_label")
            .jsonPrimitive.content
            .isNotBlank(),
          "the label is what the coach says out loud: [$grid]",
        )
      }

      // ONCE PER GRID, WHICH IS THE BLOCK -- asserted HERE, where a school has
      // two. A grid is one award row with one `source_document_id`, so two
      // residency grids may be two readings of two pages and each states its
      // own; the sibling case can only ever count one, so "once per school" and
      // "once per grid" are the same number there and this is the only shape
      // that can tell them apart. What RFC 190 forbids is a citation repeated
      // BELOW its heading, and the band array is where that would happen.
      assertEquals(
        2,
        payload.toString().split(AutomaticAwardFixture.DEFAULT_SOURCE_URL).size - 1,
        "one citation per published grid, and not one per band: [$payload]",
      )
      assertEquals(
        emptyList(),
        grids
          .mapNotNull { it["bands"] }
          .flatMap { it.jsonArray.map(JsonElement::jsonObject) }
          .filter { band -> CITATION_KEYS.any { it in band } },
        "no band under either grid carries a citation of its own: [$grids]",
      )
    }

  /**
   * CITE ONCE PER BLOCK (RFC 190). The publisher, the page and the reading day
   * are at the HEAD of the grid object and appear NOWHERE under it: a citation
   * repeated on each of seven bands would read as seven separate readings of
   * seven separate pages.
   */
  @Test
  fun `the citation rides once per grid and never on a band`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Cited College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val payload = payloadFor(student)
      val grid =
        gridsOf(
          payload
            .getValue("colleges")
            .jsonArray
            .single()
            .jsonObject,
        ).single()

      assertEquals(AutomaticAwardTestDb.DEFAULT_PUBLISHER, grid.getValue("publisher").jsonPrimitive.content)
      assertEquals(AutomaticAwardFixture.DEFAULT_SOURCE_URL, grid.getValue("source_url").jsonPrimitive.content)
      assertEquals(AutomaticAwardFixture.DEFAULT_CHECKED_ON.toString(), grid.getValue("checked_on").jsonPrimitive.content)
      val bands = grid.getValue("bands").jsonArray.map { it.jsonObject }
      assertEquals(AutomaticAwardTestDb.workedGrid().size, bands.size, "every published cell is on the wire")
      assertEquals(
        emptyList(),
        bands.filter { band -> CITATION_KEYS.any { it in band } },
        "no band carries a citation of its own: [$bands]",
      )
      // The page itself, counted: once for the whole grid, however many cells
      // hang under it. A substring count, because the defect this guards
      // against is a url REPEATED rather than a key named.
      assertEquals(
        1,
        payload.toString().split(AutomaticAwardFixture.DEFAULT_SOURCE_URL).size - 1,
        "the school's page is stated once for the whole block: [$payload]",
      )
    }

  /**
   * THE THIRD STATE, ON THE WIRE, FOR BOTH ENUMS THAT HAVE ONE.
   *
   * `unstated` is the majority of the committed corpus and it is what the coach
   * REFUSES on: the page did not say, so neither answer may be given on our
   * authority. A payload that rendered silence as `not_required`, or that left
   * the `why` empty, would let the model answer a question the school never
   * answered -- and `score_requirement` is the field that decides whether
   * applying test-optional costs the award.
   *
   * Both three-state fields are seeded unstated in one row, because the schema's
   * `iff` ties each coded value to the school's WORDS: an unstated state must
   * carry no quotation, and a quotation rendered beside "we do not know" would
   * be our sentence attributed to the school.
   */
  @Test
  fun `a school that states neither rule says so on the wire and quotes nothing`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Silent Page College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          college,
          scoreRequirement = ScoreRequirement.UNSTATED,
          superscoreRule = ScholarshipSuperscoreRule.UNSTATED,
        ),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grid =
        gridsOf(
          payloadFor(student)
            .getValue("colleges")
            .jsonArray
            .single()
            .jsonObject,
        ).single()

      assertEquals("unstated", grid.getValue("score_requirement").jsonPrimitive.content)
      assertEquals(
        JsonNull,
        grid.getValue("score_requirement_as_published"),
        "the school published no words here, so there are none to quote: [$grid]",
      )
      assertTrue(
        grid
          .getValue("score_requirement_why")
          .jsonPrimitive.content
          .contains("Ask the school's admissions office"),
        "the third state refuses OUT LOUD rather than answering for the page: [$grid]",
      )
      assertEquals("unstated", grid.getValue("superscore_rule").jsonPrimitive.content)
      assertEquals(
        JsonNull,
        grid.getValue("superscore_as_published"),
        "and a rule the page never stated is quoted nowhere: [$grid]",
      )
      assertTrue(
        grid
          .getValue("combination_rule_why")
          .jsonPrimitive.content
          .contains("Ask the school"),
        "a silent combination rule sends the family to the school too: [$grid]",
      )
    }

  /**
   * THE DECLARED NUMBERS, AND NOTHING ELSE (RFC 143's guard, RFC 203's sweep).
   *
   * Unlike `admission_test_timeline`, whose set is EMPTY, this tool's answer IS
   * money -- so the numbers it means to emit are enumerated on the tool and this
   * asserts BOTH directions: no undeclared number reaches the model, and every
   * declared key is actually rendered by a real payload, so the allowlist cannot
   * quietly sanction a field that no longer exists.
   */
  @Test
  fun `the only bare numbers on the wire are the ones this tool declares, and every declared one is rendered`() =
    runTest {
      val student = fullyPopulatedStudent()

      val payload = payloadFor(student)

      assertEquals(
        emptyList(),
        BareSourceCodeGuard
          .listViolations(payload, AutomaticAwardChatTool.NUMBERS_BY_CONTRACT)
          .map(BareSourceCodeGuard::mapMessage),
        "every bare number on this wire is one the tool declared: [$payload]",
      )
      assertEquals(
        emptySet(),
        AutomaticAwardChatTool.NUMBERS_BY_CONTRACT - BareSourceCodeGuard.listNumericFields(payload).toSet(),
        "every field the allowlist sanctions must be in the payload, or the allowlist is sanctioning nothing",
      )
      // The guard must be able to fire, or the assertion above is vacuous.
      assertTrue(
        BareSourceCodeGuard
          .listViolations(buildJsonObject { put("checked_on", 20260924) }, AutomaticAwardChatTool.NUMBERS_BY_CONTRACT)
          .isNotEmpty(),
        "a date arriving as a number must fail this guard",
      )
    }

  /**
   * WE NEVER PRESENT OUR COMBINED FIGURE AS A PUBLISHER'S OWN. ACT's superscore
   * is ACT-computed and ACT-reported; ours is a combination we made from her
   * sections, offered against a school's stated rule.
   *
   * Swept over the WHOLE payload and over the tool's model-facing description,
   * because the description is the other place the coach reads this from -- and
   * it is the place that would teach the coach to say the phrase itself.
   */
  @Test
  fun `no emitted string presents our combined figure as one a test publisher computes`() =
    runTest {
      val student = fullyPopulatedStudent()

      val emitted = payloadFor(student).toString() + tool.definition.toString()

      assertEquals(
        emptyList(),
        FORBIDDEN_PHRASES.filter { emitted.contains(it, ignoreCase = true) },
        "our figure is never named as a publisher's own product",
      )
      val combined = standingOf(student).getValue("combined").jsonObject
      assertEquals(AutomaticAwardChatTool.OUR_COMBINATION_LABEL, combined.getValue("label").jsonPrimitive.content)
      assertEquals(
        true,
        combined
          .getValue("computed_by_us")
          .jsonPrimitive.content
          .toBoolean(),
        "the claim that the arithmetic is ours rides in the same object as the number",
      )
      assertEquals(
        listOf("2026-03", "2026-06"),
        combined.getValue("sittings_used").jsonArray.map { it.jsonPrimitive.content },
        "and it says which sittings each winning section came from",
      )
    }

  /**
   * EVERY GRID CARRIES THE SAME HEAD, ON EVERY ARM, with explicit nulls where we
   * hold nothing.
   *
   * An absent key is a silence the model fills (the `admission_test_timeline`
   * rule, one tool along), and the arm with least to say -- a school we never
   * read -- is exactly where a renderer is tempted to omit.
   */
  @Test
  fun `every arm carries the same head keys, as explicit nulls where we hold nothing`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val grid = AutomaticAwardTestDb.seedListedCollege(student, "Grid College")
      val gpaOnly = AutomaticAwardTestDb.seedListedCollege(student, "GPA Only College")
      val nonePublished = AutomaticAwardTestDb.seedListedCollege(student, "Publishes Nothing College")
      val expired = AutomaticAwardTestDb.seedListedCollege(student, "Stale College")
      AutomaticAwardTestDb.seedListedCollege(student, "Never Read College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(grid), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(gpaOnly, awardKind = AutomaticAwardKind.GPA_ONLY_GRID),
        listOf(AutomaticAwardFixture.band(test = null, scoreLow = null, scoreHigh = null, awardPerYearUsd = 5000)),
      )
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(nonePublished, awardKind = AutomaticAwardKind.NONE_PUBLISHED, scoreDeadline = null),
      )
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(expired, checkedOn = LocalDate.parse("2025-09-01"), expiresOn = LocalDate.parse("2026-07-31")),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grids =
        payloadFor(student)
          .getValue("colleges")
          .jsonArray
          .flatMap { gridsOf(it.jsonObject) }

      assertEquals(
        setOf(
          AutomaticAwardChatTool.SCORE_BANDS,
          AutomaticAwardChatTool.NO_SCORE_EFFECT,
          AutomaticAwardChatTool.NONE_PUBLISHED,
          AutomaticAwardChatTool.EXPIRED,
          AutomaticAwardChatTool.NOT_CURATED,
        ),
        grids.map { it.getValue("answer").jsonPrimitive.content }.toSet(),
        "all five arms render, and no two of them alike",
      )
      grids.forEach { grid ->
        assertEquals(
          emptyList(),
          HEAD_KEYS.filterNot { it in grid },
          "no key may be omitted on any arm: [$grid]",
        )
        assertTrue(
          grid
            .getValue("why")
            .jsonPrimitive.content
            .isNotBlank(),
          "every arm says why it is the arm it is",
        )
      }
      val never = grids.single { it.getValue("answer").jsonPrimitive.content == AutomaticAwardChatTool.NOT_CURATED }
      assertEquals(
        HEAD_KEYS.filter { it != "answer" && it != "why" },
        HEAD_KEYS.filter { it != "answer" && it != "why" && never.getValue(it) == JsonNull },
        "the arm with nothing to cite carries explicit nulls and not a shorter object: [$never]",
      )
      val stale = grids.single { it.getValue("answer").jsonPrimitive.content == AutomaticAwardChatTool.EXPIRED }
      assertEquals("2026-07-31", stale.getValue("expired_on").jsonPrimitive.content)
    }

  /**
   * THE TWO DATE BOUNDS AND A CLOSED REGISTRATION ARE THREE SENTENCES, not one.
   * "Registration for that Saturday has closed", "those scores would arrive
   * after the school's deadline" and "this school stops counting sittings in
   * November" send a family to three different decisions.
   */
  @Test
  fun `the receipt deadline, the last considered sitting and a closed registration do not render the same`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val receipt = AutomaticAwardTestDb.seedListedCollege(student, "Receipt Deadline College")
      val cutOff = AutomaticAwardTestDb.seedListedCollege(student, "Cut Off College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(receipt), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          cutOff,
          scoreDeadline = LocalDate.parse("2027-05-15"),
          lastSittingConsidered =
            LastSittingConsidered.Through(
              PartialDate.YearAndMonth(Year.of(2026), Month.DECEMBER),
              "Only scores through December 2026 will be considered.",
            ),
        ),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grids =
        payloadFor(student)
          .getValue("colleges")
          .jsonArray
          .map { it.jsonObject }
          .associate { it.getValue("college_id").jsonPrimitive.content to gridsOf(it).single() }
      val byDeadline = grids.getValue(receipt.value.toString())
      val byCutOff = grids.getValue(cutOff.value.toString())

      assertEquals(listOf("receipt_deadline"), boundsOf(byDeadline))
      assertEquals(listOf("last_sitting_considered"), boundsOf(byCutOff))
      val deadlineWhy = byDeadline.getValue("bound_by_why").jsonPrimitive.content
      val cutOffWhy = byCutOff.getValue("bound_by_why").jsonPrimitive.content
      assertTrue(deadlineWhy != cutOffWhy, "two different bounds cannot produce one sentence")
      assertTrue(
        cutOffWhy.contains("stops counting sittings"),
        "the cut-off sentence must say the school stopped counting, not that scores arrive late: [$cutOffWhy]",
      )
      assertTrue(
        deadlineWhy.contains("not be with the school in time"),
        "and the receipt sentence must be about arrival: [$deadlineWhy]",
      )
      // The third sentence: a sitting nobody can register for any more, which is
      // neither of the bounds above and is a fact about the publisher's calendar.
      val closed = byDeadline.getValue("closed_sittings").jsonArray.map { it.jsonObject }
      assertTrue(closed.isNotEmpty(), "both October sittings are closed on this clock: [$byDeadline]")
      closed.forEach { sitting ->
        assertEquals(
          false,
          sitting
            .getValue("registration_open")
            .jsonPrimitive.content
            .toBoolean(),
        )
        assertTrue(
          sitting
            .getValue("why")
            .jsonPrimitive.content
            .contains("registration closed on"),
          "a closed sitting says registration closed, and says nothing about a scholarship deadline: [$sitting]",
        )
      }
    }

  /**
   * A CUT-OFF IN WORDS IS QUOTED AND EXCLUDES NOTHING, and the quote is on the
   * wire where the coach can read it out -- not summarised into a date we
   * declined to parse.
   */
  @Test
  fun `a cut-off published in words is quoted on the wire`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "In Words College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          college,
          scoreDeadline = LocalDate.parse("2027-05-15"),
          lastSittingConsidered = LastSittingConsidered.InWords(PER_TEST_CUT_OFF),
        ),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grid =
        gridsOf(
          payloadFor(student)
            .getValue("colleges")
            .jsonArray
            .single()
            .jsonObject,
        ).single()

      assertEquals(PER_TEST_CUT_OFF, grid.getValue("last_sitting_considered_as_published").jsonPrimitive.content)
      assertTrue(
        grid
          .getValue("last_sitting_considered_why")
          .jsonPrimitive.content
          .contains("No sitting above is excluded by it"),
        "the words exclude nothing, and the payload says so: [$grid]",
      )
    }

  /** An id that is not on the student's list is REPORTED, and the rest of the answer still comes back. */
  @Test
  fun `an off-list college id is reported rather than failing the read`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val stranger = AutomaticAwardTestDb.createStudent()
      val notHers = AutomaticAwardTestDb.seedListedCollege(stranger, "Somebody Else's College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(notHers), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val payload =
        tool.execute(
          student,
          buildJsonObject {
            put(
              StudentScopedChatTool.COLLEGE_IDS_FIELD,
              JsonArray(listOf(JsonPrimitive(notHers.value.toString()))),
            )
          },
        )

      assertEquals(
        listOf(notHers.value.toString()),
        payload.getValue("unknown_college_ids").jsonArray.map { it.jsonPrimitive.content },
      )
      assertEquals(
        JsonArray(emptyList()),
        payload.getValue("colleges").jsonArray,
        "no school was selected, so no school is answered -- and the unknown id is not answered as one",
      )
    }

  /**
   * A STUDENT WITH NO STORED SCORE GETS THE WHOLE GRID AND NO STANDING (0001
   * D11). `standings` is an empty array and not an absent key, because an absent
   * key reads as a field the renderer forgot rather than as the statement it is.
   */
  @Test
  fun `a student with no stored score gets every band and an empty standings array`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "No Score College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grid =
        gridsOf(
          payloadFor(student)
            .getValue("colleges")
            .jsonArray
            .single()
            .jsonObject,
        ).single()

      assertEquals(JsonArray(emptyList()), grid.getValue("standings").jsonArray)
      assertEquals(
        AutomaticAwardTestDb.workedGrid().size,
        grid.getValue("bands").jsonArray.size,
        "the school's published grid does not depend on her having a score",
      )
      assertEquals(
        AcademicYear(AutomaticAwardFixture.DEFAULT_ENTRY_YEAR).label,
        grid.getValue("entry_year").jsonPrimitive.content,
        "the entry year is the AcademicYear label and never a bare year",
      )
    }

  /**
   * A STANDING WITH NO FIGURE RENDERS THE REASON, and renders no cell.
   *
   * She stored ACT sections from both sides of ACT's 2025 change and no
   * composite, so there is nothing to read her grid with. The wire must say that
   * in words and leave `figure_read`, `bands_reached` and `next_band` empty --
   * "no cell" and "the lowest cell" are different answers, and a renderer that
   * dropped the standing entirely would say neither.
   */
  @Test
  fun `a standing with no figure carries the named refusal and no band at all`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Sections Only College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.citeActCompositeRule()
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_ENGLISH, 30, MARCH_2026)
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_MATH, 29, MARCH_2026)
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_READING, 28, MARCH_2026)
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_SCIENCE, 25, MARCH_2026)
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_ENGLISH, 31, JUNE_2026)
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_MATH, 30, JUNE_2026)
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_READING, 29, JUNE_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val standing =
        gridsOf(
          payloadFor(student)
            .getValue("colleges")
            .jsonArray
            .single()
            .jsonObject,
        ).single()
          .getValue("standings")
          .jsonArray
          .map { it.jsonObject }
          .single()

      assertEquals("act", standing.getValue("test").jsonPrimitive.content)
      assertEquals(JsonNull, standing.getValue("figure_read"), "no number may be invented for her here")
      assertEquals(JsonNull, standing.getValue("best_single_sitting"))
      assertEquals(JsonNull, standing.getValue("combined"))
      assertEquals(JsonArray(emptyList()), standing.getValue("bands_reached").jsonArray, "no cell, which is not the lowest cell")
      assertEquals(JsonNull, standing.getValue("next_band"))
      val refusal = standing.getValue("combination_refusal").jsonObject
      assertEquals("mixed_act_section_history", refusal.getValue("reason").jsonPrimitive.content)
      assertTrue(
        refusal
          .getValue("why")
          .jsonPrimitive.content
          .contains("NO combined number"),
        "the sentence must say there is no number, rather than leaving the absence to be interpreted: [$refusal]",
      )
      assertTrue(
        standing
          .getValue("figure_read_why")
          .jsonPrimitive.content
          .contains("combination_refusal says why"),
        "and it must point at the reason rather than claiming we hold nothing of hers: [$standing]",
      )
    }

  /**
   * THE WORD-PRICED BAND IS NAMED, AND NO DOLLAR GAP IS INVENTED.
   *
   * The school publishes a cash award and one priced in its own words at the
   * same score. The wire must carry BOTH cells and both step figures as explicit
   * nulls, and the sentence must quote the school -- ranking the words at $0
   * named the school's second-best award as the next one up, which is the
   * headline sentence being wrong about money.
   */
  @Test
  fun `the next band carries every cell at that score, and a word-priced one costs the wire its dollar step`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Words Wire College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.matrixGrid())
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1280, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val next =
        gridsOf(
          payloadFor(student)
            .getValue("colleges")
            .jsonArray
            .single()
            .jsonObject,
        ).single()
          .getValue("standings")
          .jsonArray
          .map { it.jsonObject }
          .single()
          .getValue("next_band")
          .jsonObject

      val bands = next.getValue("bands").jsonArray.map { it.jsonObject }
      assertEquals(
        setOf("Foundation in Excellence", "Presidential"),
        bands.map { it.getValue("award_name").jsonPrimitive.content }.toSet(),
        "both cells at that score ride, so the coach cannot name the cash one as the best: [$bands]",
      )
      assertEquals(
        AutomaticAwardTestDb.TUITION_AS_PUBLISHED,
        bands
          .single { it.getValue("award_per_year_usd") == JsonNull }
          .getValue("award_as_published")
          .jsonPrimitive.content,
        "and the unpriced cell carries the school's own words",
      )
      assertEquals(JsonNull, next.getValue("dollar_step_per_year_usd_low"))
      assertEquals(JsonNull, next.getValue("dollar_step_per_year_usd_high"))
      assertTrue(
        next
          .getValue("why")
          .jsonPrimitive.content
          .contains(AutomaticAwardTestDb.TUITION_AS_PUBLISHED),
        "the sentence quotes the school instead of stating a gap it cannot know: [${next.getValue("why")}]",
      )
    }

  /** Malformed input is a structured refusal the model reads, never a throw (the ChatTool contract). */
  @Test
  fun `an unknown input field is refused as a structured error`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()

      val payload = tool.execute(student, buildJsonObject { put("residency", "in_state") })

      assertTrue(StudentScopedChatTool.isToolRefusal(payload), "an unknown field is a refusal: [$payload]")
    }

  /**
   * THE DESCRIPTION IS SOURCEABLE. Every field it routes the coach through is a
   * field a real payload carries -- a description naming a key the wire does not
   * carry is an instruction to state something no tool can source.
   */
  @Test
  fun `every field the description names is a field the payload carries`() =
    runTest {
      val student = fullyPopulatedStudent()

      val emitted = keysOf(payloadFor(student))

      assertEquals(
        DESCRIBED_FIELDS,
        DESCRIBED_FIELDS.filter { AutomaticAwardChatTool.DESCRIPTION.contains(it) },
        "the description must name every field it routes the coach through",
      )
      assertEquals(
        emptyList(),
        DESCRIBED_FIELDS.filterNot { it in emitted },
        "the description names a field the wire does not carry: emitted [$emitted]",
      )
      assertTrue(
        AutomaticAwardChatTool.DESCRIPTION.contains("from memory"),
        "the description must forbid naming a band, a figure or a deadline from memory",
      )
    }

  /** The payload, on this suite's standing clock. */
  private suspend fun payloadFor(student: StudentId): JsonObject = tool.execute(student, buildJsonObject {})

  /** The one standing of the one school of [fullyPopulatedStudent] -- the SAT one, which carries the combination. */
  private suspend fun standingOf(student: StudentId): JsonObject =
    gridsOf(
      payloadFor(student)
        .getValue("colleges")
        .jsonArray
        .single()
        .jsonObject,
    ).single()
      .getValue("standings")
      .jsonArray
      .map { it.jsonObject }
      .single { it.getValue("test").jsonPrimitive.content == "sat" }

  /**
   * One student whose payload exercises EVERY declared number: two SAT sittings
   * that combine across a band, an ACT history that averages, a next band with a
   * dollar step, and a school that accepts a combination so both of ours are
   * computed.
   */
  private fun fullyPopulatedStudent(): StudentId {
    val student = AutomaticAwardTestDb.createStudent()
    val college = AutomaticAwardTestDb.seedListedCollege(student, "Fully Populated College")
    AutomaticAwardTestDb.seed(
      AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
      AutomaticAwardTestDb.workedGrid(),
    )
    AutomaticAwardTestDb.citeActCompositeRule()
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_READING_WRITING, 640, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_MATH, 600, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_READING_WRITING, 590, JUNE_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_MATH, 660, JUNE_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1250, JUNE_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_ENGLISH, 30, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_MATH, 29, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_READING, 28, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_COMPOSITE, 29, MARCH_2026)
    AutomaticAwardTestDb.seedCommittedCalendar()
    return student
  }

  /** The grids of one college object, named once so no test reaches for the key by hand. */
  private fun gridsOf(college: JsonObject): List<JsonObject> = college.getValue("grids").jsonArray.map { it.jsonObject }

  /** The bounds one grid names, in the order the wire states them. */
  private fun boundsOf(grid: JsonObject): List<String> = grid.getValue("bound_by").jsonArray.map { it.jsonPrimitive.content }

  /** Every key name in [element], at any depth -- the sweep the description assertion reads. */
  private fun keysOf(element: JsonElement): Set<String> =
    when (element) {
      is JsonObject -> element.keys + element.values.flatMap { keysOf(it) }
      is JsonArray -> element.flatMap { keysOf(it) }.toSet()
      is JsonPrimitive -> emptySet()
    }

  private companion object {
    val MARCH_2026: PartialDate = AutomaticAwardTestDb.sitting(2026, Month.MARCH)
    val JUNE_2026: PartialDate = AutomaticAwardTestDb.sitting(2026, Month.JUNE)

    /** UAB's per-test cut-off, in the shape that cannot become one date for both tests. */
    const val PER_TEST_CUT_OFF = "February ACT or March SAT scores will be the final scores considered."

    /** Every key of a grid's HEAD, which every arm carries whatever it has to say. */
    val HEAD_KEYS =
      listOf(
        "answer",
        "residency_basis",
        "residency_basis_label",
        "entry_year",
        "publisher",
        "source_url",
        "archive_url",
        "checked_on",
        "citation_note",
        "why",
      )

    /** The citation's own keys: at the head of a grid, and nowhere under it (RFC 190). */
    val CITATION_KEYS = listOf("publisher", "source_url", "archive_url", "checked_on")

    /**
     * The ways a sentence would hand our arithmetic to a test publisher. ACT's
     * own superscore is ACT-computed and ACT-reported; ours is a combination we
     * made from her sections against a school's stated rule.
     */
    val FORBIDDEN_PHRASES = listOf("ACT Superscore", "ACT's Superscore", "superscored by ACT", "College Board Superscore")

    /** The fields the description routes the coach through, and which the wire must therefore carry. */
    val DESCRIBED_FIELDS =
      listOf(
        "answer",
        "residency_basis",
        "residency_basis_label",
        "publisher",
        "source_url",
        "checked_on",
        "bands",
        "award_per_year_usd",
        "award_as_published",
        "standings",
        "best_single_sitting",
        "combined",
        "combination_refusal",
        "figure_read",
        "figure_read_why",
        "next_band",
        "points_away",
        "dollar_step_per_year_usd_low",
        "dollar_step_per_year_usd_high",
        "last_usable_sitting",
        "bound_by",
        "closed_sittings",
        "last_sitting_considered_as_published",
        "score_requirement",
        "unknown_college_ids",
      )
  }
}
