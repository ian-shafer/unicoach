package ed.unicoach.college

import ed.unicoach.db.dao.CollegeTuitionExchangesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.newCollegeFixture
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.ExchangeSelection
import ed.unicoach.db.models.HandTypedReferenceData
import ed.unicoach.db.models.HandTypedReferenceData.REFRESH_LEAD
import ed.unicoach.db.models.TuitionExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.csv.CSVFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The tuition-exchange corpus (RFC 277): its refusals, and the real committed files through the real loader. */
class TuitionExchangeSeedLoaderTest : CollegeScorecardTestBase() {
  private val loader = TuitionExchangeSeedLoader(database)

  private val committedDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/tuition-exchanges") }
      .first { it.isDirectory }

  private val committedCsv = File(committedDir, "exchanges.csv")

  private fun rowsOf(file: File): List<Map<String, String>> =
    CSVFormat.DEFAULT
      .builder()
      .setHeader()
      .setSkipHeaderRecord(true)
      .get()
      .parse(file.bufferedReader())
      .use { parser -> parser.records.map { it.toMap() } }

  private val committedRows: List<Map<String, String>> = rowsOf(committedCsv)

  private fun seedCollege(
    unitId: Int,
    name: String,
  ): CollegeId = withSession { session -> CollegesDao.upsert(session, newCollegeFixture(unitId, name)).getOrThrow().id }

  private fun seedCommittedColleges(): Map<Int, CollegeId> =
    committedRows.associate { row ->
      val unitId = row.getValue("unit_id").toInt()
      unitId to seedCollege(unitId, row.getValue("college_name"))
    }

  private fun load(csv: File): TuitionExchangeSeedLoader.LoadResult = runBlocking { loader.load(csv) }

  private fun countRows(): Int = query("SELECT count(*) FROM college_tuition_exchanges") { rs -> rs.getInt(1) }.single()

  private val goodUrl = "https://example.edu/wue"
  private val unitId = 998877
  private val name = "Example State University"

  private fun row(
    unitId: Int = this.unitId,
    name: String = this.name,
    selection: String = "selective",
    percent: String = "150",
    url: String = goodUrl,
  ) = "$unitId,$name,wue,$percent,$selection,not_required,3.25,30,2027-02-03,$url,2026-11-08,2027-07-31"

  private fun writeCorpus(
    dir: File,
    rows: List<String> = listOf(row()),
    linkStatuses: List<String>? = listOf("$goodUrl,found,2026-11-08"),
  ): File {
    val csv = File(dir, "exchanges.csv")
    csv.writeText((listOf(TuitionExchangeSeedLoader.EXCHANGE_COLUMNS.joinToString(",")) + rows).joinToString("\n") + "\n")
    if (linkStatuses != null) {
      File(dir, "link-status.csv")
        .writeText((listOf(TuitionExchangeSeedLoader.LINK_STATUS_COLUMNS.joinToString(",")) + linkStatuses).joinToString("\n") + "\n")
    }
    return csv
  }

  private fun defectOf(csv: File): TuitionExchangeSeedLoader.Defect =
    assertFailsWith<TuitionExchangeSeedLoader.FormatException> { load(csv) }.defect

  @Test
  fun `the committed corpus loads with no refusal and a second load writes nothing`() {
    seedCommittedColleges()

    val first = load(committedCsv)
    assertEquals(committedRows.size, first.upserted, "every committed row is an insert")
    assertEquals(0, first.pruned)
    assertEquals(committedRows.size, countRows())

    val second = load(committedCsv)
    assertEquals(committedRows.size, second.unchanged)
    assertEquals(0, second.upserted + second.changed + second.pruned)
  }

  @Test
  fun `the committed rows read back with the terms they were typed with`() {
    val colleges = seedCommittedColleges()
    load(committedCsv)

    val stored = withSession { session -> CollegeTuitionExchangesDao.listByColleges(session, colleges.values).getOrThrow() }

    assertEquals(committedRows.size, stored.size)
    committedRows.forEach { row ->
      val college = colleges.getValue(row.getValue("unit_id").toInt())
      val read = stored.single { it.collegeId == college }
      assertEquals(row.getValue("source_url"), read.sourceUrl)
      assertEquals(row.getValue("selection"), read.selection.value)
      assertEquals(row.getValue("max_percent_of_resident_tuition").toInt(), read.maxPercentOfResidentTuition)
      assertEquals(LocalDate.parse(row.getValue("expires_on")), read.expiresOn)
    }
  }

  @Test
  fun `no committed row is within the shared refresh lead of expiring`() {
    val today = LocalDate.ofInstant(Instant.now(), HandTypedReferenceData.GUARD_ZONE)
    assertEquals(
      emptyList(),
      committedRows
        .filter { ChronoUnit.DAYS.between(today, LocalDate.parse(it.getValue("expires_on"))) < REFRESH_LEAD.days }
        .map { "[${it.getValue("college_name")}] expires_on=[${it.getValue("expires_on")}]" },
      "a tuition-exchange reading is about to go stale: re-read the school's page and edit [${committedCsv.path}]",
    )
  }

  @Test
  fun `every committed row expires on the cycle day following its deadline, or its reading`() {
    assertEquals(
      emptyList(),
      committedRows.filter { row ->
        val basis = LocalDate.parse(row.getValue("apply_by").ifEmpty { row.getValue("checked_on") })
        val sameYear = HandTypedReferenceData.CYCLE_EXPIRY.atYear(basis.year)
        val expected = if (sameYear.isAfter(basis)) sameYear else HandTypedReferenceData.CYCLE_EXPIRY.atYear(basis.year + 1)
        LocalDate.parse(row.getValue("expires_on")) != expected
      },
    )
  }

  @Test
  fun `PROVENANCE json agrees with the committed rows`() {
    val provenance = Json.parseToJsonElement(File(committedDir, "PROVENANCE.json").readText()).jsonObject
    val refreshInstruction = provenance.getValue("refresh_instruction").jsonPrimitive.content

    fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    val readings = provenance.getValue("rows").jsonArray.map { it.jsonObject }
    val readingsByKey = readings.associateBy { it.text("unit_id") to it.text("exchange") }
    assertEquals(readings.size, readingsByKey.size, "PROVENANCE.json repeats a row; $refreshInstruction")
    assertEquals(
      committedRows.map { it.getValue("unit_id") to it.getValue("exchange") }.toSet(),
      readingsByKey.keys,
      "PROVENANCE.json does not read the rows the CSV holds; $refreshInstruction",
    )

    val identityColumns = setOf("unit_id", "college_name", "exchange", "source_url", "checked_on", "expires_on")
    val termColumns = TuitionExchangeSeedLoader.EXCHANGE_COLUMNS - identityColumns
    committedRows.forEach { row ->
      val key = row.getValue("unit_id") to row.getValue("exchange")
      val reading = readingsByKey.getValue(key)
      assertEquals(row.getValue("college_name"), reading.text("college_name"), "$key college_name; $refreshInstruction")
      assertEquals(row.getValue("source_url"), reading.text("source_url"), "$key source_url; $refreshInstruction")
      assertEquals(row.getValue("checked_on"), reading.text("read_on"), "$key read_on; $refreshInstruction")
      assertEquals(
        termColumns.associateWith { row.getValue(it) }.filterValues { it.isNotEmpty() },
        reading.getValue("readings").jsonObject.mapValues { (_, value) -> value.jsonObject.text("value") },
        "$key readings; $refreshInstruction",
      )
    }
  }

  @Test
  fun `every url the committed corpus cites has an answer that says when we asked`() {
    val statuses = rowsOf(File(committedDir, "link-status.csv"))
    assertEquals(committedRows.map { it.getValue("source_url") }.sorted(), statuses.map { it.getValue("url") }.sorted())
    assertTrue(statuses.all { it.getValue("url_checked_on").isNotEmpty() }, "a status with no date: $statuses")
  }

  @Test
  fun `a unit id with no college is fatal and names the file and line`(
    @TempDir dir: File,
  ) {
    val defect = assertIs<TuitionExchangeSeedLoader.Defect.UnknownCollege>(defectOf(writeCorpus(dir)))
    assertEquals(File(dir, "exchanges.csv").path, defect.file)
    assertEquals(1L, defect.line)
    assertEquals(unitId, defect.ipedsUnitId)
    assertEquals(0, countRows())
  }

  @Test
  fun `a name that disagrees with the stored college is fatal and names both`(
    @TempDir dir: File,
  ) {
    seedCollege(unitId, name)
    val defect =
      assertIs<TuitionExchangeSeedLoader.Defect.CollegeNameDisagreement>(
        defectOf(writeCorpus(dir, rows = listOf(row(name = "Exemplar State University")))),
      )
    assertEquals("Exemplar State University", defect.typedName)
    assertEquals(name, defect.storedName)
    assertEquals(0, countRows())
  }

  @Test
  fun `a cited url with no committed answer is refused rather than defaulted`(
    @TempDir dir: File,
  ) {
    seedCollege(unitId, name)
    val defect =
      assertIs<TuitionExchangeSeedLoader.Defect.UncheckedUrl>(
        defectOf(writeCorpus(dir, rows = listOf(row(url = "https://example.edu/other")))),
      )
    assertEquals("https://example.edu/other", defect.url)
  }

  @Test
  fun `an absent sidecar is refused by name`(
    @TempDir dir: File,
  ) {
    assertIs<TuitionExchangeSeedLoader.Defect.MissingLinkStatusFile>(defectOf(writeCorpus(dir, linkStatuses = null)))
  }

  @Test
  fun `a repeated key names both lines`(
    @TempDir dir: File,
  ) {
    seedCollege(unitId, name)
    val defect = assertIs<TuitionExchangeSeedLoader.Defect.DuplicateKey>(defectOf(writeCorpus(dir, rows = listOf(row(), row()))))
    assertEquals(1L, defect.firstLine)
    assertEquals(2L, defect.line)
  }

  @Test
  fun `an unknown selection is a located cell refusal`(
    @TempDir dir: File,
  ) {
    seedCollege(unitId, name)
    val defect = assertIs<TuitionExchangeSeedLoader.Defect.Cell>(defectOf(writeCorpus(dir, rows = listOf(row(selection = "maybe")))))
    assertEquals("selection", defect.cell.column)
    assertEquals(ExchangeSelection.entries.map { it.value }, defect.cell.allowed)
  }

  @Test
  fun `a value outside the table's CHECK fails naming the file and line, and rolls the corpus back`(
    @TempDir dir: File,
  ) {
    seedCollege(unitId, name)
    val failure =
      assertFailsWith<TuitionExchangeSeedLoader.LoadException> { load(writeCorpus(dir, rows = listOf(row(percent = "99")))) }
    assertEquals(1L, failure.line)
    assertEquals(unitId, failure.ipedsUnitId)
    assertEquals(0, countRows())
  }

  @Test
  fun `a cap above the exchange's ceiling is refused by file and line, and the ceiling itself loads`(
    @TempDir dir: File,
  ) {
    seedCollege(unitId, name)
    val csv = writeCorpus(dir, rows = listOf(row(percent = "151")))

    val failure = assertFailsWith<TuitionExchangeSeedLoader.FormatException> { load(csv) }

    val defect = assertIs<TuitionExchangeSeedLoader.Defect.PercentAboveCeiling>(failure.defect)
    assertEquals(csv.path, defect.file)
    assertEquals(1L, defect.line)
    assertEquals(unitId, defect.ipedsUnitId)
    assertEquals(151, defect.percent)
    assertTrue(failure.message!!.contains("[${csv.path}] line [1]"), failure.message)
    assertEquals(0, countRows())

    load(writeCorpus(dir, rows = listOf(row(percent = "${TuitionExchange.WUE.ceilingPercentOfResidentTuition}"))))
    assertEquals(1, countRows())
  }

  @Test
  fun `a school the corpus no longer names is dropped, and an empty corpus is refused`(
    @TempDir dir: File,
  ) {
    seedCollege(unitId, name)
    val otherId = 998878
    val otherName = "Second State University"
    seedCollege(otherId, otherName)
    load(writeCorpus(dir, rows = listOf(row(), row(unitId = otherId, name = otherName))))
    assertEquals(2, countRows())

    assertEquals(1, load(writeCorpus(dir)).pruned)
    assertEquals(1, countRows())

    assertIs<TuitionExchangeSeedLoader.Defect.EmptyCorpus>(defectOf(writeCorpus(dir, rows = emptyList())))
    assertEquals(1, countRows())
  }
}
