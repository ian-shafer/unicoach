package ed.unicoach.college

import ed.unicoach.college.CsvIngestSupport.StatusfulCell
import ed.unicoach.college.CsvIngestSupport.statusfulDoubleCell
import ed.unicoach.college.CsvIngestSupport.statusfulDoubleCellInDomain
import ed.unicoach.college.CsvIngestSupport.statusfulIntCell
import ed.unicoach.college.CsvIngestSupport.statusfulIntCellInDomain
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The RFC 158 status-preserving readers: a suppression sentinel -- `PS`, the
 * spelling the Scorecard writes today, and the archived `PrivacySuppressed`
 * (RFC 206) -- keeps its own reading, blank and the publisher null sentinels
 * read as not-reported, a parseable value reads as reported, and a
 * domain-coerced value keeps mechanism A's tally while landing as
 * not-reported. Each absence is counted on its own arm of [CellAbsenceTally].
 * The pre-existing readers
 * (`intOrNull` and friends) are deliberately NOT re-tested here: their
 * collapse is pinned behavior this RFC must not change.
 */
class CsvIngestStatusfulCellTest {
  /**
   * A one-cell record whose header IS [column], defaulting to the placeholder
   * name `COL`. `stringOrNull` refuses a column the record does not map (RFC
   * 139), so a test that reads a real Scorecard column name has to spell that
   * name in the header too.
   */
  private fun record(
    value: String,
    column: String = "COL",
  ): CSVRecord =
    CSVFormat.RFC4180
      .builder()
      .setHeader(column)
      .setSkipHeaderRecord(true)
      .build()
      .parse((column + "\n" + value + "\n").reader())
      .records
      .single()

  @Test
  fun `a parseable value reads as Reported`() {
    val tally = CellAbsenceTally()
    assertEquals(StatusfulCell.Reported(11000), statusfulIntCell(record("11000"), "COL", tally))
    assertEquals(StatusfulCell.Reported(0.42), statusfulDoubleCell(record("0.42"), "COL", tally))
    assertEquals(emptyMap(), tally.suppressedByPublisher)
  }

  @Test
  fun `PS -- the sentinel the Scorecard actually writes -- keeps its own reading`() {
    // The live spelling: `PS` is what every suppressed cell in the committed
    // institution file carries, and reading it as NotReported is what told
    // 1,228 families "the school did not report this" (RFC 206).
    assertEquals(StatusfulCell.SuppressedByPublisher, statusfulIntCell(record("PS"), "COL", CellAbsenceTally()))
    assertEquals(StatusfulCell.SuppressedByPublisher, statusfulDoubleCell(record("PS"), "COL", CellAbsenceTally()))
  }

  @Test
  fun `PrivacySuppressed keeps its own reading instead of collapsing to null`() {
    assertEquals(
      StatusfulCell.SuppressedByPublisher,
      statusfulIntCell(record("PrivacySuppressed"), "COL", CellAbsenceTally()),
    )
    assertEquals(
      StatusfulCell.SuppressedByPublisher,
      statusfulDoubleCell(record("PrivacySuppressed"), "COL", CellAbsenceTally()),
    )
  }

  @Test
  fun `a blank cell and the publisher null sentinels read as NotReported`() {
    for (cell in listOf("", "  ", "NULL", "NA")) {
      assertEquals(StatusfulCell.NotReported, statusfulIntCell(record(cell), "COL", CellAbsenceTally()), "for [$cell]")
    }
  }

  @Test
  fun `the three silences stay three -- a near miss is never read as a suppression`() {
    // Membership in SUPPRESSION_SENTINELS is EXACT (RFC 206). A set that
    // case-folded or matched a prefix would turn a legitimate value into a
    // publisher suppression, which is this defect pointing the other way.
    for (cell in listOf("NA", "NULL", "", "  ", "ps", "Ps", "PSX", "P", "suppressed", "12A")) {
      val tally = CellAbsenceTally()
      assertEquals(StatusfulCell.NotReported, statusfulIntCell(record(cell), "COL", tally), "for [$cell]")
      assertEquals(StatusfulCell.NotReported, statusfulDoubleCell(record(cell), "COL", tally), "for [$cell]")
      assertEquals(emptyMap(), tally.suppressedByPublisher, "for [$cell]")
    }
  }

  @Test
  fun `a domain-coerced value lands as NotReported and keeps mechanism A's tally`() {
    val cost = "COSTT4_A"
    val tally = CellAbsenceTally()
    assertEquals(
      StatusfulCell.NotReported,
      statusfulIntCellInDomain(record("-5", cost), cost, 0, Int.MAX_VALUE, tally),
    )
    assertEquals(mapOf(cost to 1), tally.coercedToNull)

    val pell = "PCTPELL"
    val doubleTally = CellAbsenceTally()
    assertEquals(
      StatusfulCell.NotReported,
      statusfulDoubleCellInDomain(record("1.5", pell), pell, 0.0, 1.0, doubleTally),
    )
    assertEquals(mapOf(pell to 1), doubleTally.coercedToNull)
  }

  @Test
  fun `an in-domain value passes the domain reader untallied`() {
    val cost = "COSTT4_A"
    val tally = CellAbsenceTally()
    assertEquals(
      StatusfulCell.Reported(29000),
      statusfulIntCellInDomain(record("29000", cost), cost, 0, Int.MAX_VALUE, tally),
    )
    assertEquals(emptyMap(), tally.coercedToNull)
    assertEquals(emptyMap(), tally.suppressedByPublisher)
  }

  @Test
  fun `a suppressed cell tallies as suppressed, never as a coercion, for both spellings`() {
    // The two arms of CellAbsenceTally are different facts about the column
    // (RFC 206): a suppressed cell has no value to be out of domain, so it
    // passes the domain reader through and is counted as a SUPPRESSION --
    // never as mechanism A's coercion, and the coercion map stays empty.
    for (sentinel in listOf("PS", "PrivacySuppressed")) {
      val tally = CellAbsenceTally()
      assertEquals(
        StatusfulCell.SuppressedByPublisher,
        statusfulIntCellInDomain(record(sentinel), "COL", 0, Int.MAX_VALUE, tally),
        "for [$sentinel]",
      )
      // Keyed by the source column the publisher withheld -- the one name
      // both arms of the tally use.
      assertEquals(mapOf("COL" to 1), tally.suppressedByPublisher, "for [$sentinel]")
      assertEquals(emptyMap(), tally.coercedToNull, "for [$sentinel]")
    }
  }

  @Test
  fun `an out-of-domain value tallies the other way, on the same tally object`() {
    // One object, two arms, one pass: the suppression and the coercion are
    // counted under their own column names and never bleed into each other.
    val debt = "GRAD_DEBT_MDN"
    val cost = "COSTT4_A"
    val tally = CellAbsenceTally()
    statusfulIntCellInDomain(record("PS", debt), debt, 0, Int.MAX_VALUE, tally)
    statusfulIntCellInDomain(record("-5", cost), cost, 0, Int.MAX_VALUE, tally)
    assertEquals(mapOf(debt to 1), tally.suppressedByPublisher)
    assertEquals(mapOf(cost to 1), tally.coercedToNull)
  }

  @Test
  fun `a suppression is counted even where the column has no domain to check`() {
    // The net-price shape (RFC 206): a negative net price is valid, so those
    // cells are read with NO domain. Counting suppressions in the domain
    // reader would leave every suppression they ever carry -- the archived
    // files spell one, and the committed fixture keeps it -- out of a
    // per-column figure that reads as complete.
    val netPrice = "NPT43_PUB"
    val tally = CellAbsenceTally()
    assertEquals(StatusfulCell.SuppressedByPublisher, statusfulIntCell(record("PS", netPrice), netPrice, tally))
    assertEquals(mapOf(netPrice to 1), tally.suppressedByPublisher)
    assertEquals(emptyMap(), tally.coercedToNull)
  }

  @Test
  fun `the publisher's null sentinels are NAMED, so an unknown token is not silence`() {
    // RFC 206: `NULL` and `NA` are the spellings of school silence this seam
    // expects. Any OTHER present token is one nobody taught it -- which is all
    // `PS` ever was -- and it is logged by name instead of being passed off as
    // silence. The STATUS is the same either way, so what this pins is the
    // vocabulary, not the reading.
    assertEquals(setOf("NULL", "NA"), CsvIngestSupport.NULL_SENTINELS)
    assertEquals(
      emptySet(),
      CsvIngestSupport.NULL_SENTINELS intersect CsvIngestSupport.SUPPRESSION_SENTINELS,
      "a token cannot be both school silence and a publisher suppression",
    )
    for (token in listOf("PSX", "suppressed", "12A")) {
      assertFalse(token in CsvIngestSupport.NULL_SENTINELS, "for [$token]")
      assertEquals(
        StatusfulCell.NotReported,
        statusfulIntCell(record(token), "COL", CellAbsenceTally()),
        "for [$token]",
      )
    }
  }
}
