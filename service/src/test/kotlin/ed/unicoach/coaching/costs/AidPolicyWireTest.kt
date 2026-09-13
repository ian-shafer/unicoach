package ed.unicoach.coaching.costs

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.admissions.SOURCE_FILE_NOTE_KEY
import ed.unicoach.coaching.admissions.SOURCE_FILE_NOT_FOUND_NOTE
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.coaching.costs.canonical.FilingLink
import ed.unicoach.common.util.Share
import ed.unicoach.db.models.AidForm
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.AssuredFigure
import ed.unicoach.db.models.SourceUrlStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE SURFACE WHERE THE DEFECT WAS LIVE (RFC 200).
 *
 * `AidPolicyWire` emits `AssuranceTierCopy.statementOf(figure.assurance, ...)` and
 * `putCitation` into the SAME object, and every aid-policy figure is a school's
 * own Common Data Set answer -- `voluntary_self_report`, whose byte-frozen
 * sentence ends "We link to the college's own file so you can see it". For
 * fifteen of the 441 documents in the committed corpus that url answers 404, so
 * the coach was being handed the promise beside a dead link. Not a latent
 * defect and not a page-only one: this is the object it was shipped in.
 *
 * The frozen sentence is never REWORDED. The whole third sentence is DELETED
 * for a filing we did not link, which is brief 0009 D1's line -- a restatement
 * may go, a frozen sentence may not move.
 */
class AidPolicyWireTest {
  private fun citation(sourceUrlStatus: SourceUrlStatus) =
    CdsCitation(
      collegeName = "Ashford College",
      sourceYear = 2024,
      url = URL,
      archiveUrl = ARCHIVE_URL,
      sourceUrlStatus = sourceUrlStatus,
    )

  /** One filing with BOTH assured figures, so both statement keys are exercised by every case below. */
  private fun policy(sourceUrlStatus: SourceUrlStatus) =
    AidPolicyPractice(
      averageNeedMet = AssuredFigure(Share.ofRatio(0.94), AssuranceTier.VOLUNTARY_SELF_REPORT),
      averageNeedBasedGrantUsd = AssuredFigure(38000, AssuranceTier.VOLUNTARY_SELF_REPORT),
      fullyMetNeed = null,
      requiredForms = listOf(AidForm.FAFSA),
      notCollectedForms = emptyList(),
      source = citation(sourceUrlStatus),
    )

  private fun sourceOf(payload: JsonObject): JsonObject = payload["source"] as JsonObject

  private fun stringAt(
    payload: JsonObject,
    key: String,
  ): String? = payload[key]?.jsonPrimitive?.content

  @Test
  fun `a filing whose college file is gone is cited without a url, and says why`() {
    val source = sourceOf(AidPolicyWire.objectOf(policy(SourceUrlStatus.NOT_FOUND)))

    // NO url. A url we recorded as a 404 is not data the coach should repeat.
    assertNull(source["url"], "a dead url was handed to the coach: [$source]")
    // The citation itself is unchanged: whose claim it is, and in which cycle.
    assertEquals("Ashford College's 2024-25 Common Data Set", stringAt(source, "cited_as"))
    // SAID, not merely dropped. Omitting the key silently would leave the model
    // free to invent a link to the document it is citing.
    assertEquals(SOURCE_FILE_NOT_FOUND_NOTE, stringAt(source, SOURCE_FILE_NOTE_KEY))
    // `archive_url` is untouched by this RFC (RFC 196 D1): a different document,
    // with a different claim behind it.
    assertEquals(ARCHIVE_URL, stringAt(source, "archive_url"))
  }

  @Test
  fun `the promise to link is not made about a filing we did not link`() {
    val payload = AidPolicyWire.objectOf(policy(SourceUrlStatus.NOT_FOUND))

    listOf(
      AidPolicyWire.NEED_MET_ASSURANCE_STATEMENT_KEY,
      AidPolicyWire.AVERAGE_GRANT_ASSURANCE_STATEMENT_KEY,
    ).forEach { key ->
      val statement = requireNotNull(stringAt(payload, key)) { "[$key] must be emitted with its figure" }
      assertFalse(
        statement.contains(AssuranceTierCopy.SELF_REPORT_LINK_PROMISE.trim()),
        "we promised a link to a file that is gone, under [$key]: [$statement]",
      )
      // DELETED, never reworded: what remains is the frozen copy's first two
      // sentences, byte for byte, and a strict prefix of the frozen literal.
      assertEquals(AssuranceTierCopy.SELF_REPORT_WITHOUT_LINK, statement)
      assertTrue(
        AssuranceTierCopy.statementOf(AssuranceTier.VOLUNTARY_SELF_REPORT, FilingLink.LINKED).startsWith(statement),
        "the shortened sentence must be a strict prefix of the frozen one: [$statement]",
      )
    }
    // The TIER itself is unchanged -- the figure is still a self-report, and
    // only the sentence about linking it is shorter.
    assertEquals(AssuranceTier.VOLUNTARY_SELF_REPORT.value, stringAt(payload, AidPolicyWire.NEED_MET_ASSURANCE_KEY))
  }

  /**
   * A filing we CAN link is byte-identical to what this wire emitted before RFC
   * 200 -- the whole payload, not the two keys the RFC touched.
   *
   * Whole-object equality rather than a key list, because the risk of a change
   * at this seam is a key that quietly stopped being emitted, and a list of the
   * keys somebody remembered would not see it.
   */
  @Test
  fun `a linkable filing is unchanged, whole, for every status that keeps its link`() {
    val found = AidPolicyWire.objectOf(policy(SourceUrlStatus.FOUND))
    val unknown = AidPolicyWire.objectOf(policy(SourceUrlStatus.UNKNOWN))

    // UNKNOWN is "we never asked", or "we asked and got no usable answer": OUR
    // gap, never the school's silence (RFC 166 s6). It links.
    assertEquals(found, unknown, "an unchecked filing must be handed over exactly as a live one is")
    assertEquals(URL, stringAt(sourceOf(found), "url"))
    assertNull(sourceOf(found)[SOURCE_FILE_NOTE_KEY], "a live filing needs no note about a missing file")
    assertEquals(
      AssuranceTierCopy.statementOf(AssuranceTier.VOLUNTARY_SELF_REPORT, FilingLink.LINKED),
      stringAt(found, AidPolicyWire.NEED_MET_ASSURANCE_STATEMENT_KEY),
    )
    // The promise is the point of the frozen sentence, so it is asserted
    // POSITIVELY here: this test would pass on a wire that had dropped the
    // sentence from both branches.
    assertTrue(
      requireNotNull(stringAt(found, AidPolicyWire.AVERAGE_GRANT_ASSURANCE_STATEMENT_KEY))
        .contains(AssuranceTierCopy.SELF_REPORT_LINK_PROMISE.trim()),
      "a filing we DO link must still carry the promise",
    )
    // And the rest of the section is untouched: the figures, their labels and
    // both notes are emitted exactly as before.
    assertEquals(94.0, found[AidPolicyWire.NEED_MET_SHARE_KEY]?.jsonPrimitive?.content?.toDouble())
    assertEquals(38000, found[AidPolicyWire.AVERAGE_GRANT_KEY]?.jsonPrimitive?.content?.toInt())
    assertEquals(AidPolicyWire.FORMS_NOTE, stringAt(found, AidPolicyWire.FORMS_NOTE_KEY))
  }

  private companion object {
    const val URL = "https://example.test/ashford-cds.pdf"
    const val ARCHIVE_URL = "https://archive.example.test/ashford-cds.pdf"
  }
}
