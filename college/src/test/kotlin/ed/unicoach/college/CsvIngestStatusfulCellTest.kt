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

/**
 * The RFC 158 status-preserving readers: `PrivacySuppressed` keeps its own
 * reading, blank and the publisher null sentinels read as not-reported, a
 * parseable value reads as reported, and a domain-coerced value keeps
 * mechanism A's tally while landing as not-reported. The pre-existing readers
 * (`intOrNull` and friends) are deliberately NOT re-tested here: their
 * collapse is pinned behavior this RFC must not change.
 */
class CsvIngestStatusfulCellTest {
  private fun record(value: String): CSVRecord =
    CSVFormat.RFC4180
      .builder()
      .setHeader("COL")
      .setSkipHeaderRecord(true)
      .build()
      .parse(("COL\n" + value + "\n").reader())
      .records
      .single()

  @Test
  fun `a parseable value reads as Reported`() {
    assertEquals(StatusfulCell.Reported(11000), statusfulIntCell(record("11000"), "COL"))
    assertEquals(StatusfulCell.Reported(0.42), statusfulDoubleCell(record("0.42"), "COL"))
  }

  @Test
  fun `PrivacySuppressed keeps its own reading instead of collapsing to null`() {
    assertEquals(StatusfulCell.SuppressedByPublisher, statusfulIntCell(record("PrivacySuppressed"), "COL"))
    assertEquals(StatusfulCell.SuppressedByPublisher, statusfulDoubleCell(record("PrivacySuppressed"), "COL"))
  }

  @Test
  fun `a blank cell and the publisher null sentinels read as NotReported`() {
    for (cell in listOf("", "  ", "NULL", "NA")) {
      assertEquals(StatusfulCell.NotReported, statusfulIntCell(record(cell), "COL"), "for [$cell]")
    }
  }

  @Test
  fun `a domain-coerced value lands as NotReported and keeps mechanism A's tally`() {
    val coercions = mutableMapOf<String, Int>()
    assertEquals(
      StatusfulCell.NotReported,
      statusfulIntCellInDomain(record("-5"), "COL", 0, Int.MAX_VALUE, "amount_usd", coercions),
    )
    assertEquals(mapOf("amount_usd" to 1), coercions)

    val doubleCoercions = mutableMapOf<String, Int>()
    assertEquals(
      StatusfulCell.NotReported,
      statusfulDoubleCellInDomain(record("1.5"), "COL", 0.0, 1.0, "pell_share", doubleCoercions),
    )
    assertEquals(mapOf("pell_share" to 1), doubleCoercions)
  }

  @Test
  fun `an in-domain value passes the domain reader untallied`() {
    val coercions = mutableMapOf<String, Int>()
    assertEquals(
      StatusfulCell.Reported(29000),
      statusfulIntCellInDomain(record("29000"), "COL", 0, Int.MAX_VALUE, "amount_usd", coercions),
    )
    assertEquals(emptyMap(), coercions)
  }

  @Test
  fun `suppression is not coerced away by the domain reader`() {
    // A suppressed cell has no value to be out of domain; the domain reader
    // must pass the suppression through untallied, never rewrite it.
    val coercions = mutableMapOf<String, Int>()
    assertEquals(
      StatusfulCell.SuppressedByPublisher,
      statusfulIntCellInDomain(record("PrivacySuppressed"), "COL", 0, Int.MAX_VALUE, "amount_usd", coercions),
    )
    assertEquals(emptyMap(), coercions)
  }
}
