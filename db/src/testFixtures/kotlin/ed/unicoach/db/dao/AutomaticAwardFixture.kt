package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.NewAutomaticAward
import ed.unicoach.db.models.NewAutomaticAwardBand
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `college_automatic_awards` rows (RFC 239) for suites that write their own, AND
 * the reader for the committed corpus on disk.
 *
 * IT DOES BOTH, unlike [AdmissionTestStateProgrammeFixture], for the reason
 * [AdmissionTestCalendarFixture] does both: this corpus's committed CSVs are
 * read by a reader that lives here, because `:db`'s test source set is where
 * `commons-csv` is already on the classpath and a hand-rolled `split(",")` reads
 * a CRLF line ending into the last cell of a file edited by hand.
 *
 * THE COMMITTED ROWS ARE NOT KEYED BY [CollegeId] AND CANNOT BE. `awards.csv`
 * names the school by IPEDS `unit_id` plus the name AS TYPED, because a college
 * id is assigned by the import run and a hand-typed file cannot know one. The
 * ingest loader resolves the pair against `colleges` and REFUSES a unit id it
 * cannot find and a name that disagrees -- a fuzzy match of these 48 names
 * returned 8 WRONG schools, and a mis-keyed row here is another school's money
 * under this school's name. [CommittedAward.toNew] is where a resolved id is
 * attached, and it is the only way to get a writable row out of this reader.
 *
 * `colleges` IS A PRECONDITION for every write here: `college_id` foreign-keys
 * it, so a caller creates its colleges first.
 */
object AutomaticAwardFixture {
  /**
   * One award row, with the defaults a suite does not care about already
   * filled: Alabama's shape -- a score grid for Fall 2027 that does not vary by
   * residency, requires a submitted score, reads one sitting, and was read on
   * [DEFAULT_CHECKED_ON].
   *
   * Every field is overridable because every field is somebody's subject: a
   * suite about the superscore rule overrides [superscoreRule] alone and stays
   * readable, which is the whole reason this is a builder and not sixteen
   * positional arguments at each call site.
   */
  fun award(
    collegeId: CollegeId,
    entryYear: AcademicYear = AcademicYear(DEFAULT_ENTRY_YEAR),
    residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
    awardKind: AutomaticAwardKind = AutomaticAwardKind.SCORE_GRID,
    scoreRequirement: ScoreRequirement = ScoreRequirement.REQUIRED,
    scoreRequirementAsPublished: String? = "A test score is required for scholarship consideration.",
    superscoreRule: ScholarshipSuperscoreRule = ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY,
    superscoreAsPublished: String? = "Scholarship awards are based on a single test administration.",
    scoreDeadline: LocalDate? = DEFAULT_SCORE_DEADLINE,
    scoreDeadlineAsPublished: String? = "Official test scores must be received by December 4.",
    lastSittingConsidered: LastSittingConsidered? = null,
    publisher: String = "Example State University",
    sourceUrl: String = DEFAULT_SOURCE_URL,
    archiveUrl: String? = null,
    checkedOn: LocalDate = DEFAULT_CHECKED_ON,
    expiresOn: LocalDate = DEFAULT_EXPIRES_ON,
  ): NewAutomaticAward =
    NewAutomaticAward(
      collegeId = collegeId,
      entryYear = entryYear,
      residencyBasis = residencyBasis,
      awardKind = awardKind,
      scoreRequirement = scoreRequirement,
      scoreRequirementAsPublished = scoreRequirementAsPublished,
      superscoreRule = superscoreRule,
      superscoreAsPublished = superscoreAsPublished,
      scoreDeadline = scoreDeadline,
      scoreDeadlineAsPublished = scoreDeadlineAsPublished,
      lastSittingConsidered = lastSittingConsidered,
      publisher = publisher,
      sourceUrl = sourceUrl,
      archiveUrl = archiveUrl,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /** One published cell. [test] null with no score range is the published NO-SCORE row. */
  fun band(
    awardName: String = DEFAULT_AWARD_NAME,
    test: AdmissionTest? = AdmissionTest.SAT,
    scoreLow: Int? = 1200,
    scoreHigh: Int? = 1250,
    gpaLow: BigDecimal? = null,
    gpaHigh: BigDecimal? = null,
    awardPerYearUsd: Int? = 6000,
    awardAsPublished: String? = null,
  ): NewAutomaticAwardBand =
    NewAutomaticAwardBand(
      awardName = awardName,
      test = test,
      scoreLow = scoreLow,
      scoreHigh = scoreHigh,
      gpaLow = gpaLow,
      gpaHigh = gpaHigh,
      awardPerYearUsd = awardPerYearUsd,
      awardAsPublished = awardAsPublished,
    )

  /** Empties the corpus. NAMED and called by the caller, never hidden inside a `seed` verb ([AdmissionTestCalendarFixture]'s rule). */
  fun deleteAll(session: SqlSession) {
    // The bands go with their award through ON DELETE CASCADE, so one statement
    // is the whole corpus and a second would only be able to disagree with it.
    session.prepareStatement("DELETE FROM college_automatic_awards").use { it.executeUpdate() }
  }

  /** Writes one award and its bands through the DAO's own upsert, so a fixture cannot write a row the read path could not read. */
  fun seed(
    session: SqlSession,
    row: NewAutomaticAward,
    bands: List<NewAutomaticAwardBand> = emptyList(),
  ) = AutomaticAwardsDao.upsert(session, row, bands).getOrThrow()

  /** The entry year [award] describes by default: Fall 2027 entry, the cycle the committed corpus was read for. */
  const val DEFAULT_ENTRY_YEAR = 2027

  /** The award name [band] carries by default. */
  const val DEFAULT_AWARD_NAME = "Presidential Scholarship"

  /** The page a fixture row cites when the case is not about the citation. */
  const val DEFAULT_SOURCE_URL = "https://example-state.edu/admissions/scholarships"

  /** The day these defaults were "read" -- inside the Fall-2027 cycle, so a row built on them is legal. */
  val DEFAULT_CHECKED_ON: LocalDate = LocalDate.of(2026, 9, 24)

  /** The worked example's receipt deadline, and inside the cycle window the schema requires. */
  val DEFAULT_SCORE_DEADLINE: LocalDate = LocalDate.of(2026, 12, 4)

  /** July 31 of the entry year (D-G), which is after [DEFAULT_CHECKED_ON] as the schema requires. */
  val DEFAULT_EXPIRES_ON: LocalDate = LocalDate.of(2027, 7, 31)

  /** The committed seed directory, found by walking up from the test's working directory -- [CodebookReferenceFixture]'s rule. */
  val COMMITTED_DIR: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/automatic-awards") }
      .firstOrNull { it.isDirectory }
      ?: error("db/seed/automatic-awards was not found above [${File(".").absolutePath}]")

  /** One row per college x entry year x residency basis: what the school publishes. */
  val AWARDS_CSV: File = File(COMMITTED_DIR, "awards.csv")

  /** One row per published cell. */
  val BANDS_CSV: File = File(COMMITTED_DIR, "bands.csv")

  /** What `bin/check-link` answered per url (RFC 220), committed exactly as the other hand-typed corpora commit theirs. */
  val LINK_STATUS_FILE: File = File(COMMITTED_DIR, "link-status.csv")

  /** The corpus record: the reading day, the counts, the entry year and `expires_on`. */
  val PROVENANCE_FILE: File = File(COMMITTED_DIR, "PROVENANCE.json")

  /**
   * The header [committedAwards] requires, exactly and in order (the
   * `CdsSeedLoader` rule): a renamed or reordered column is a broken seed, not a
   * column to look up by chance, and asserting it before the first row means a
   * rename cannot make a read silently find nothing.
   */
  val AWARDS_HEADER: List<String> =
    listOf(
      "unit_id",
      "college_name",
      "entry_year",
      "residency_basis",
      "award_kind",
      "score_requirement",
      "superscore_rule",
      "score_deadline",
      "score_deadline_as_published",
      "score_requirement_as_published",
      "superscore_as_published",
      "last_sitting_through",
      "last_sitting_precision",
      "last_sitting_as_published",
      "publisher",
      "source_url",
      "archive_url",
      "checked_on",
      "expires_on",
    )

  /** The header [committedBands] requires; see [AWARDS_HEADER]. */
  val BANDS_HEADER: List<String> =
    listOf(
      "unit_id",
      "entry_year",
      "residency_basis",
      "award_name",
      "test",
      "score_low",
      "score_high",
      "gpa_low",
      "gpa_high",
      "award_per_year_usd",
      "award_as_published",
    )

  /** The header [committedLinkStatuses] requires -- the three columns every hand-typed corpus commits, and `external_documents`' own names. */
  val LINK_STATUS_HEADER: List<String> = listOf("url", "url_status", "url_checked_on")

  /**
   * The natural key a band is filed under: the school, the cycle and whose grid
   * it is. A `data class` and not a `Triple`, because a Triple of two strings
   * and an enum reads identically whichever order it is built in.
   */
  data class AwardKey(
    val unitId: String,
    val entryYear: AcademicYear,
    val residencyBasis: ResidencyBasis,
  )

  /**
   * One committed award row: the school as TYPED, and everything the schema
   * stores. It is deliberately NOT a [NewAutomaticAward]: that type carries a
   * [CollegeId], which nothing on disk knows.
   */
  data class CommittedAward(
    val key: AwardKey,
    val collegeName: String,
    val awardKind: AutomaticAwardKind,
    val scoreRequirement: ScoreRequirement,
    val scoreRequirementAsPublished: String?,
    val superscoreRule: ScholarshipSuperscoreRule,
    val superscoreAsPublished: String?,
    val scoreDeadline: LocalDate?,
    val scoreDeadlineAsPublished: String?,
    val lastSittingConsidered: LastSittingConsidered?,
    val publisher: String,
    val sourceUrl: String,
    val archiveUrl: String?,
    val checkedOn: LocalDate,
    val expiresOn: LocalDate,
  ) {
    /** The writable row, once a caller has RESOLVED [AwardKey.unitId] to a college it also name-checked. */
    fun toNew(collegeId: CollegeId): NewAutomaticAward =
      NewAutomaticAward(
        collegeId = collegeId,
        entryYear = key.entryYear,
        residencyBasis = key.residencyBasis,
        awardKind = awardKind,
        scoreRequirement = scoreRequirement,
        scoreRequirementAsPublished = scoreRequirementAsPublished,
        superscoreRule = superscoreRule,
        superscoreAsPublished = superscoreAsPublished,
        scoreDeadline = scoreDeadline,
        scoreDeadlineAsPublished = scoreDeadlineAsPublished,
        lastSittingConsidered = lastSittingConsidered,
        publisher = publisher,
        sourceUrl = sourceUrl,
        archiveUrl = archiveUrl,
        checkedOn = checkedOn,
        expiresOn = expiresOn,
      )
  }

  /** One committed band row, under the award its [key] names. */
  data class CommittedBand(
    val key: AwardKey,
    val band: NewAutomaticAwardBand,
  )

  /** The committed award rows, parsed with a REAL CSV reader; every refusal names the FILE, the ROW and the COLUMN. */
  fun committedAwards(): List<CommittedAward> = parse(AWARDS_CSV, AWARDS_HEADER, ::awardOf)

  /** The committed band rows; see [committedAwards]. */
  fun committedBands(): List<CommittedBand> = parse(BANDS_CSV, BANDS_HEADER, ::bandOf)

  /**
   * The committed link sweep, `url -> (status, the day we asked)` (RFC 220).
   *
   * Every url the corpus cites has an answer here, and EVERY URL HERE IS LOADED
   * whether or not an award row cites it: the ACT composite-rule page is cited
   * by the service's arithmetic and by no award row, and a sweep that dropped it
   * would take the citation the ACT combination is refused without.
   */
  fun committedLinkStatuses(): Map<String, Pair<String, LocalDate?>> =
    parse(LINK_STATUS_FILE, LINK_STATUS_HEADER) { record ->
      record.get("url") to
        Pair(
          record.get("url_status"),
          record.get("url_checked_on").ifEmpty { null }?.let(LocalDate::parse),
        )
    }.toMap()

  private fun <T> parse(
    file: File,
    header: List<String>,
    rowOf: (CSVRecord) -> T,
  ): List<T> =
    CSVFormat.DEFAULT
      .builder()
      .setHeader()
      .setSkipHeaderRecord(true)
      .get()
      .parse(file.bufferedReader())
      .use { parser ->
        require(parser.headerNames == header) {
          "[${file.path}] header is [${parser.headerNames}], and this reader requires exactly [$header] in that order"
        }
        parser.records.map(rowOf)
      }

  private fun cells(
    record: CSVRecord,
    file: File,
    header: List<String>,
  ): Cells {
    require(record.isConsistent) {
      "row [${record.recordNumber}] of [${file.path}] has [${record.size()}] cells, not [${header.size}]: [$record]"
    }
    return Cells(record, file)
  }

  /** The per-cell accessors, so every refusal in this file names the same three things in the same order. */
  private class Cells(
    val record: CSVRecord,
    val file: File,
  ) {
    fun required(column: String): String =
      record.get(column).ifEmpty {
        error("row [${record.recordNumber}] of [${file.path}] has an empty [$column], which is required")
      }

    fun optional(column: String): String? = record.get(column).ifEmpty { null }

    fun date(column: String): LocalDate =
      try {
        LocalDate.parse(required(column))
      } catch (e: DateTimeParseException) {
        throw IllegalStateException(
          "row [${record.recordNumber}] of [${file.path}] has [$column] = [${record.get(column)}], which is not a yyyy-mm-dd date",
          e,
        )
      }

    fun optionalDate(column: String): LocalDate? = optional(column)?.let { date(column) }

    fun int(column: String): Int? =
      optional(column)?.let {
        it.toIntOrNull() ?: error("row [${record.recordNumber}] of [${file.path}] has [$column] = [$it], which is not a whole number")
      }

    fun decimal(column: String): BigDecimal? =
      optional(column)?.let {
        it.toBigDecimalOrNull() ?: error("row [${record.recordNumber}] of [${file.path}] has [$column] = [$it], which is not a number")
      }

    fun <T : Any> coded(
      column: String,
      domain: String,
      fromValue: (String) -> T?,
    ): T {
      val raw = required(column)
      return fromValue(raw)
        ?: error("row [${record.recordNumber}] of [${file.path}] has [$column] = [$raw], which is not a known [$domain]")
    }

    fun key(): AwardKey =
      AwardKey(
        unitId = required("unit_id"),
        entryYear = AcademicYear(required("entry_year").toInt()),
        residencyBasis = coded("residency_basis", "ResidencyBasis", ResidencyBasis::fromValue),
      )
  }

  private fun awardOf(record: CSVRecord): CommittedAward {
    val c = cells(record, AWARDS_CSV, AWARDS_HEADER)
    // The last-sitting trio is read as ONE value, because
    // [LastSittingConsidered] is one: a period with no words, or a precision
    // with no period, is a broken row here rather than a state the writer
    // discovers at a CHECK.
    val lastSittingWords = c.optional("last_sitting_as_published")
    val lastSittingThrough = c.optional("last_sitting_through")
    val lastSittingPrecision = c.optional("last_sitting_precision")
    val lastSitting =
      when {
        lastSittingThrough == null && lastSittingPrecision == null -> {
          lastSittingWords?.let { LastSittingConsidered.InWords(it) }
        }

        lastSittingThrough == null || lastSittingPrecision == null || lastSittingWords == null -> {
          error(
            "row [${record.recordNumber}] of [${AWARDS_CSV.path}] has half a last-sitting cut-off: " +
              "through [$lastSittingThrough] precision [$lastSittingPrecision] words [$lastSittingWords]",
          )
        }

        else -> {
          LastSittingConsidered.Through(
            partialDateOf(record, lastSittingThrough, lastSittingPrecision),
            lastSittingWords,
          )
        }
      }
    return CommittedAward(
      key = c.key(),
      collegeName = c.required("college_name"),
      awardKind = c.coded("award_kind", "AutomaticAwardKind", AutomaticAwardKind::fromValue),
      scoreRequirement = c.coded("score_requirement", "ScoreRequirement", ScoreRequirement::fromValue),
      scoreRequirementAsPublished = c.optional("score_requirement_as_published"),
      superscoreRule = c.coded("superscore_rule", "ScholarshipSuperscoreRule", ScholarshipSuperscoreRule::fromValue),
      superscoreAsPublished = c.optional("superscore_as_published"),
      scoreDeadline = c.optionalDate("score_deadline"),
      scoreDeadlineAsPublished = c.optional("score_deadline_as_published"),
      lastSittingConsidered = lastSitting,
      publisher = c.required("publisher"),
      sourceUrl = c.required("source_url"),
      archiveUrl = c.optional("archive_url"),
      checkedOn = c.date("checked_on"),
      expiresOn = c.date("expires_on"),
    )
  }

  private fun bandOf(record: CSVRecord): CommittedBand {
    val c = cells(record, BANDS_CSV, BANDS_HEADER)
    val test = c.optional("test")?.let { AdmissionTest.fromValue(it) ?: error("row [${record.recordNumber}] names test [$it]") }
    return CommittedBand(
      key = c.key(),
      band =
        NewAutomaticAwardBand(
          awardName = c.required("award_name"),
          test = test,
          scoreLow = c.int("score_low"),
          scoreHigh = c.int("score_high"),
          gpaLow = c.decimal("gpa_low"),
          gpaHigh = c.decimal("gpa_high"),
          awardPerYearUsd = c.int("award_per_year_usd"),
          awardAsPublished = c.optional("award_as_published"),
        ),
    )
  }

  /**
   * The stored `(period start, precision)` pair as the [PartialDate] it encodes,
   * refused through the one codec rather than rebuilt here -- the pair never
   * escapes a reader that knows both halves.
   */
  private fun partialDateOf(
    record: CSVRecord,
    through: String,
    precision: String,
  ): PartialDate {
    val date =
      try {
        LocalDate.parse(through)
      } catch (e: DateTimeParseException) {
        throw IllegalStateException(
          "row [${record.recordNumber}] of [${AWARDS_CSV.path}] has last_sitting_through [$through], which is not a yyyy-mm-dd date",
          e,
        )
      }
    return when (
      val decoded =
        ed.unicoach.db.models.PartialDateColumns
          .decode(date, precision)
    ) {
      is ed.unicoach.common.models.ValidationResult.Valid -> {
        decoded.value
      }

      is ed.unicoach.common.models.ValidationResult.Invalid -> {
        error(
          "row [${record.recordNumber}] of [${AWARDS_CSV.path}] has last_sitting_through [$through] at precision " +
            "[$precision], which is not the start of its own period",
        )
      }
    }
  }
}
