package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.SpokenAssurance
import ed.unicoach.coaching.costs.spokenLabel
import ed.unicoach.db.models.AssuranceTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Saying a tier ONCE for the set of figures it covers (RFC 191, brief 0009 D3).
 *
 * The grouping is tested here, away from any renderer, because the guarantee is
 * about WHICH figures a sentence claims to describe -- a header that covers a
 * figure of another kind is a worse fault than the repetition this replaces, and
 * it is not visible in a word count.
 */
class AssuranceTierGroupingTest {
  private fun figure(
    college: String,
    field: CostField = CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
    tier: AssuranceTier = AssuranceTier.ADMINISTRATIVE_RECORD,
  ) = CoveredFigure(collegeName = college, field = field, assurance = SpokenAssurance(tier))

  @Test
  fun `one tier over three schools is one sentence, said once`() {
    val groups =
      AssuranceTierGrouping.groupsOf(
        listOf(figure("Lakeshore State University"), figure("Eastbrook University"), figure("Wynnford College")),
      )

    assertEquals(1, groups.size, "three figures of one kind are one sentence: [$groups]")
    assertEquals(
      AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD, FilingLink.LINKED),
      groups.single().statement,
      "and the sentence is [AssuranceTierCopy]'s, byte for byte",
    )
    // Bare, with no school named: every school agrees, so naming them would be
    // three names spent on a distinction there is none of.
    assertEquals("The median federal loan debt:", groups.single().scope)
  }

  @Test
  fun `a mixed-tier page gets two headers, each naming the schools it is true of`() {
    // The failure this test exists for: one header over both figures would tell
    // a family that a college's own unaudited claim is a federal record, or the
    // reverse. Either way a figure is described by a sentence that is false of
    // it.
    val groups =
      AssuranceTierGrouping.groupsOf(
        listOf(
          figure("Lakeshore State University", tier = AssuranceTier.ADMINISTRATIVE_RECORD),
          figure("Wynnford College", tier = AssuranceTier.VOLUNTARY_SELF_REPORT),
        ),
      )

    assertEquals(2, groups.size, "two kinds of number are two sentences: [$groups]")
    val admin = groups.first { it.tier == AssuranceTier.ADMINISTRATIVE_RECORD }
    val voluntary = groups.first { it.tier == AssuranceTier.VOLUNTARY_SELF_REPORT }
    assertEquals("The median federal loan debt at Lakeshore State University:", admin.scope)
    assertEquals("The median federal loan debt at Wynnford College:", voluntary.scope)
    // And neither header can be read as covering the other's figure.
    assertFalse(admin.scope.contains("Wynnford"), "a header may not reach a figure of another kind: [$admin]")
    assertFalse(voluntary.scope.contains("Lakeshore"), "in either direction: [$voluntary]")
  }

  @Test
  fun `every covered figure is named by exactly one group`() {
    // Walked, never a restated list: the assertion is over the INPUT set, so a
    // figure dropped by the grouping fails here rather than silently losing its
    // tier on some page.
    val figures =
      listOf(
        figure("Lakeshore State University", CostField.MEDIAN_DEBT_AT_COMPLETION_USD, AssuranceTier.ADMINISTRATIVE_RECORD),
        figure("Lakeshore State University", CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, AssuranceTier.MANDATORY_SURVEY),
        figure("Wynnford College", CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, AssuranceTier.VOLUNTARY_SELF_REPORT),
      )

    val groups = AssuranceTierGrouping.groupsOf(figures)

    // NAMED, not recomputed: deriving the oracle with the production algorithm
    // would pass on any implementation that is self-consistent, including a
    // wrong one. The published price is on two tiers on this page and the debt
    // figure is on one, and those are facts about the fixture above.
    val ambiguous = setOf(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD)
    figures.forEach { covered ->
      // Case-insensitive on the label: the scope capitalises its FIRST phrase,
      // so a label that happens to open a group reads "The median ..." there and
      // "and the median ..." in another. The name is the same name either way.
      val naming =
        groups.filter { group ->
          group.scope.lowercase().contains(covered.field.spokenLabel.lowercase()) &&
            (covered.field !in ambiguous || group.scope.contains(covered.collegeName))
        }
      assertEquals(1, naming.size, "[${covered.field.wireName}] at [${covered.collegeName}] must be named once: [$groups]")
      assertEquals(
        covered.assurance.tier,
        naming.single().tier,
        "and named by the sentence that is TRUE of it: [${naming.single().scope}]",
      )
    }
    // The ambiguous field is qualified at BOTH of its schools; the unambiguous
    // one stays bare in its own group.
    val survey = groups.first { it.tier == AssuranceTier.MANDATORY_SURVEY }
    assertTrue(survey.scope.contains("at Lakeshore State University"), "a split field names its school: [$survey]")
    assertEquals("The median federal loan debt:", groups.first { it.tier == AssuranceTier.ADMINISTRATIVE_RECORD }.scope)
  }

  @Test
  fun `no group carries a number, a score or a rank`() {
    // RFC 179 D10, at the seam that would be tempted: a grouping knows how many
    // figures are on each tier and must never say so, and the order it returns
    // is first appearance, not a league table.
    val groups =
      AssuranceTierGrouping.groupsOf(
        listOf(
          figure("Wynnford College", tier = AssuranceTier.VOLUNTARY_SELF_REPORT),
          figure("Lakeshore State University", tier = AssuranceTier.ADMINISTRATIVE_RECORD),
          figure("Eastbrook University", tier = AssuranceTier.ADMINISTRATIVE_RECORD),
        ),
      )

    assertEquals(
      listOf(AssuranceTier.VOLUNTARY_SELF_REPORT, AssuranceTier.ADMINISTRATIVE_RECORD),
      groups.map { it.tier },
      "first appearance, not frequency and not the enum's order: the more frequent tier is second here",
    )
    groups.forEach { group ->
      assertFalse(group.scope.any { it.isDigit() }, "no count reaches a scope: [${group.scope}]")
      listOf("strongest", "weakest", "better", "worse", "rank", "score", "rating").forEach { word ->
        assertFalse(group.scope.lowercase().contains(word), "no ranking word: [$word] in [${group.scope}]")
      }
    }
  }

  @Test
  fun `no figures is no groups`() {
    assertTrue(AssuranceTierGrouping.groupsOf(emptyList()).isEmpty(), "a sentence said over nothing describes nothing")
  }

  @Test
  fun `every cost field has a family-readable name in the money vocabulary`() {
    // The label seam is exhaustive so a new field cannot reach a scope unnamed
    // (RFC 191 D6). RFC 141's vocabulary is asserted here rather than trusted.
    CostField.entries.forEach { field ->
      assertTrue(field.spokenLabel.isNotBlank(), "[${field.wireName}] has no spoken label")
      assertFalse(field.spokenLabel.contains("_"), "a wire name reached a family: [${field.spokenLabel}]")
      listOf("room and board", "sticker price", "award", "without need").forEach { banned ->
        assertFalse(
          field.spokenLabel.lowercase().contains(banned),
          "[$banned] is not this product's vocabulary (RFC 141): [${field.spokenLabel}]",
        )
      }
    }
  }
}
