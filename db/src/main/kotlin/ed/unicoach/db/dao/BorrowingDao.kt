package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeBorrowing
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.MeasureUnit
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.gapStatusOf
import java.sql.ResultSet
import java.util.UUID

/**
 * The Common Data Set borrowing read (RFC 175): what the students who
 * GRADUATED from a school in one named year borrowed, by loan type.
 *
 * A sibling of [AidPolicyDao] rather than an extension of it, because the two
 * answer different questions of the same filing and the aid-policy read is
 * narrowed to ONE aid scope -- the need-based denominator its two averages
 * share. Borrowing has five denominators, one per loan type, and folding five
 * scopes into that query would have made its single `aid_scope` narrowing
 * meaningless for both halves.
 *
 * The narrowing here is the pin: a borrowing average is admitted only at the
 * scope [LoanType] says is its DENOMINATOR, so a row written with the wrong
 * scope is a corrupt stored value and says so, rather than being served as an
 * average over a population it was not computed over.
 *
 * Batch by construction, like every other chat read: one query for the whole
 * answer, never one per college.
 */
object BorrowingDao {
  /**
   * Each college's borrowing block from its own newest CDS cycle. A college we
   * hold NO filing for is absent from the answer; a college whose filing
   * reports no borrowing comes back with an EMPTY block, because those are two
   * different silences and only the caller that can tell them apart can say
   * each in its own words.
   *
   * The cycle is resolved PER COLLEGE -- the newest CDS DOCUMENT this college
   * has canonical facts under, which is the rule [AidPolicyDao] resolves and
   * for the same reason: the seed keeps the newest document that reports the
   * group, so one school's answer is 2024-25 and its neighbour's 2025-26.
   *
   * Every join here follows `source_document_id` (schema 0087, RFC 170 D13).
   * No join in this read is a (college, source, year) triple: a fact whose
   * `vintage` disagreed with its document's `academic_year` was DROPPED by
   * such a join, and the caller then said "we hold no Common Data Set filing
   * for this school" about a filing we do hold.
   */
  fun listLatest(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): Result<List<CollegeBorrowing>> {
    val ids = collegeIds.distinct()
    if (ids.isEmpty()) return Result.success(emptyList())
    val sql =
      """
      WITH ids AS (
        SELECT unnest($TEXT_ARRAY_PARAM)::uuid AS college_id
      ),
      cds AS (SELECT ?::text AS source),
      -- The FILING, resolved before its facts and resolved from the DOCUMENT
      -- table itself (schema 0087, RFC 170 D13). A college whose newest cycle
      -- carries no borrowing row at all must still come back from this read:
      -- "we hold no filing for this school" and "the filing reports no
      -- borrowing" are two different sentences, and a read that returned
      -- nothing for both would make the caller say the first about a school
      -- whose filing we do hold.
      --
      -- The cycle is the newest CDS document this college has canonical facts
      -- UNDER -- the same rule [AidPolicyDao] resolves, so one answer cites one
      -- year of one filing in both sections, but asked through the reference
      -- rather than through a (college, source, year) triple. Asked as a
      -- triple, a document whose `academic_year` disagreed with its facts'
      -- `vintage` dropped every fact on the inner join and the coach said "we
      -- hold no Common Data Set for this school" about a filing we hold. The
      -- EXISTS arms are what keeps a document this college has NO canonical
      -- fact under -- a merit-aid-only filing of a later year, say -- from
      -- being served as its newest aid cycle and emptying the block.
      filings AS (
        SELECT DISTINCT ON (d.college_id)
               d.id, d.college_id, d.academic_year AS year, d.source_url, d.archive_url
        FROM source_documents d
        JOIN ids i ON i.college_id = d.college_id
        CROSS JOIN cds
        WHERE d.source = cds.source
          AND (
            EXISTS (SELECT 1 FROM cohort_money_stats s WHERE s.source_document_id = d.id)
            OR EXISTS (SELECT 1 FROM cohort_population_counts c WHERE c.source_document_id = d.id)
            OR EXISTS (SELECT 1 FROM aid_form_requirements a WHERE a.source_document_id = d.id)
          )
        ORDER BY d.college_id, d.academic_year DESC
      ),
      facts AS (
        -- The average, WITH the aid scope it was stored under. The scope is
        -- projected rather than narrowed to one literal because each measure
        -- has its OWN denominator, and the decoder checks the pair: narrowing
        -- on the measure alone would serve a mis-scoped row as though it were
        -- an average over this loan type's borrowers.
        --
        -- FOLLOWED from the filing by `source_document_id`: a fact is
        -- attributed to the document it was read out of, so no borrowing cell
        -- can be lost to a year column that disagrees with its document's.
        -- The REST of the row's address travels with it as one `basis`
        -- string, and is refused rather than narrowed away: a borrowing
        -- average written at an income band or at the in-state rate is a
        -- corrupt stored value, and a query that simply did not match it would
        -- make the caller say "this filing reports no borrowing" about a filing
        -- that has the row.
        SELECT f.college_id, '$STAT_KIND' AS kind, s.measure AS name, s.aid_scope AS scope, s.value AS number,
               s.status AS status,
               s.residency_scope || '/' || coalesce(s.income_band, '$NO_INCOME_BAND') AS basis
        FROM filings f
        JOIN cohort_money_stats s
          ON s.source_document_id = f.id
         AND s.measure = ANY ($TEXT_ARRAY_PARAM)
        -- Every borrowing average is reported over the graduating class; a row
        -- about anyone else is not this figure.
         AND s.population = ?
        UNION ALL
        SELECT f.college_id, '$COUNT_KIND', c.population, NULL::text, c.headcount, c.status,
               c.residency_basis || '/' || c.arrangement
        FROM filings f
        JOIN cohort_population_counts c
          ON c.source_document_id = f.id
         AND c.population = ANY ($TEXT_ARRAY_PARAM)
      )
      SELECT f.college_id, f.year, f.source_url, f.archive_url, x.kind, x.name, x.scope, x.number, x.status, x.basis
      FROM filings f
      LEFT JOIN facts x ON x.college_id = f.college_id
      ORDER BY f.college_id, x.kind, x.name
      """.trimIndent()
    return session
      .queryList(
        sql,
        bind = { stmt ->
          jsonbArrayBinder(ids.map { it.value.toString() })(stmt, 1)
          stmt.setString(2, MoneySource.COMMON_DATA_SET.value)
          jsonbArrayBinder(LoanType.DEBT_AVERAGES.map { it.value })(stmt, 3)
          stmt.setString(4, CohortPopulation.GRADUATING_CLASS.value)
          jsonbArrayBinder(LoanType.COUNTED_POPULATIONS.map { it.value })(stmt, 5)
        },
        map = ::mapFact,
        // The decoders throw a located CorruptPersistedValueException, and
        // `map` runs AFTER queryList's own try/catch: without mapCatching a
        // stored slug no enum reads would leave the Result channel entirely
        // and lose its PermanentError marker.
      ).mapCatching { facts -> facts.groupBy { it.collegeId }.map { (_, rows) -> mapCollegeBorrowing(rows) } }
  }

  // The two kinds the union emits, named once and interpolated into the SQL
  // that produces them, so the query and the decoder cannot disagree.
  private const val STAT_KIND = "stat"
  private const val COUNT_KIND = "count"

  /** The income band a borrowing row has none of, spelled so the basis string has no null half. */
  private const val NO_INCOME_BAND = "none"

  /**
   * The ONE remaining address a CDS borrowing AVERAGE is written at
   * (`CdsSeedLoader.statOf`): the whole cohort, at no income band.
   *
   * Checked rather than narrowed on, for the reason the aid-scope pin is
   * checked: `singleByKey` refuses a SECOND row, so a lone row written at
   * `in_state_rate_paying` or under an income band would otherwise be served
   * as this filing's overall figure with nothing said about it.
   */
  private val STAT_BASIS = "${CohortResidencyScope.ALL.value}/$NO_INCOME_BAND"

  /** The ONE remaining address a CDS borrowing HEADCOUNT is written at (`CdsSeedLoader.countOf`). */
  private val COUNT_BASIS = "${ResidencyBasis.NOT_APPLICABLE.value}/${FigureArrangement.NOT_APPLICABLE.value}"

  /**
   * WHICH row a refusal is about -- an ADDRESS, as the values that identify the
   * row rather than as prose about them. It carries none of the row's contents,
   * which is why it is not called a row: it says where to look.
   *
   * Rendered at the throw site -- the only place a located [corruptValue] needs
   * words -- so no helper here takes a sentence where it means a college, a
   * cycle and a cell. The CELL is part of it because this read is a UNION: a
   * bare college-and-year leaves an operator scanning two tables' columns for
   * the row that broke.
   */
  private data class RowAddress(
    val collegeId: CollegeId,
    val academicYear: AcademicYear,
    val kind: String? = null,
    val name: String? = null,
  ) {
    override fun toString(): String =
      "college_id=[${collegeId.value}] year=[${academicYear.firstCalendarYear}]" +
        (kind?.let { " kind=[$it]" } ?: "") +
        (name?.let { " name=[$it]" } ?: "")
  }

  /**
   * What one read row IS. An average names a loan type through its MEASURE, a
   * headcount names one through its POPULATION, and the graduating class names
   * no loan type at all -- which is why it is its own case and not a sixth
   * borrower cohort.
   */
  private sealed interface BorrowingFact {
    /**
     * The stored status of this cell, or null for the LEFT JOIN's unmatched
     * side (which is a filing, not a cell).
     *
     * The STATUS, not a `notReadByUs` boolean: `not_collected_by_us` is our own
     * gap (D7), `suppressed_by_publisher` is the publisher withholding the
     * cell, `not_applicable` is the source saying the question does not apply,
     * and `not_reported_by_institution` is the school's own silence. Flattened
     * into one boolean, the middle two were spoken as the school's silence.
     */
    val status: FigureStatus?

    data class Average(
      val loanType: LoanType,
      val amountUsd: Int?,
      override val status: FigureStatus?,
    ) : BorrowingFact

    data class Borrowers(
      val loanType: LoanType,
      val headcount: Int?,
      override val status: FigureStatus?,
    ) : BorrowingFact

    data class GraduatingClass(
      val headcount: Int?,
      override val status: FigureStatus?,
    ) : BorrowingFact

    /** A filing with no borrowing row under it at all -- the second silence. */
    data object NoBlock : BorrowingFact {
      override val status: FigureStatus? = null
    }
  }

  /**
   * One read row as a cited fact. A ROUTER: which kind of row this is, and
   * nothing else. What each kind IS is [mapAverageFact] and [mapCountFact], on the
   * [AidPolicyDao.mapFact] shape.
   */
  private fun mapFact(rs: ResultSet): CitedFact<BorrowingFact> {
    val name = rs.getString("name")
    val kind = rs.getString("kind")
    val number = rs.getBigDecimal("number")?.toDouble()
    val collegeId = CollegeId(UUID.fromString(rs.getString("college_id")))
    val academicYear = AcademicYear(rs.getInt("year"))
    // WHICH row, not just which column: this is a batch read over a whole
    // college list, so a stored value no enum reads is unfindable without the
    // college, the cycle and the cell -- all four already in this ResultSet.
    val address = RowAddress(collegeId, academicYear, kind, name)
    // WHOSE silence a cell is, read from the status rather than guessed from
    // the missing value. DECODED on every fact row, value-bearing or not: a
    // slug no enum reads is a schema/enum drift whether or not a number sits
    // beside it, and decoding it only when the value was missing left the
    // status unaudited on exactly the rows a family is shown.
    // The LEFT JOIN's unmatched side carries no status at all; that row is the
    // filing itself, not a cell, so it is nobody's silence.
    val status = rs.getString("status")?.let { statusOf(it, kind, address) }
    return CitedFact(
      collegeId = collegeId,
      academicYear = academicYear,
      sourceUrl = rs.getString("source_url"),
      archiveUrl = rs.getString("archive_url"),
      fact =
        when (kind) {
          // The LEFT JOIN's unmatched side: this filing exists and reports no
          // borrowing. It is a row on purpose -- the caller needs the filing
          // to say the second silence rather than the first.
          null -> BorrowingFact.NoBlock

          STAT_KIND -> mapAverageFact(name, rs.getString("scope"), rs.getString("basis"), number, status, address)

          COUNT_KIND -> mapCountFact(name, rs.getString("basis"), number, status, address)

          // Unreachable, and routed through the same located exception as
          // every other corrupt-value read: a bare IllegalStateException would
          // leave the Result channel and lose its PermanentError marker.
          else -> throw corruptValue(kind, "one of the kinds this read selects", "the borrowing read ($address)")
        },
    )
  }

  /** One stored average, at the loan type whose measure AND scope the row agrees with. */
  private fun mapAverageFact(
    name: String,
    scope: String?,
    basis: String?,
    number: Double?,
    status: FigureStatus?,
    address: RowAddress,
  ): BorrowingFact {
    assertBasis(basis, STAT_BASIS, "cohort_money_stats", address)
    val loanType = loanTypeOfMeasure(name, scope, address)
    return BorrowingFact.Average(loanType, wholeDollars(loanType.debtAverage, number, address), status)
  }

  /**
   * One stored headcount: this loan type's borrowers, or the graduating class
   * they are all counted against -- which names no loan type and so is its own
   * fact, never a sixth borrower cohort.
   *
   * The class is NAMED, never reached by elimination. Chosen by fall-through, a
   * counted population this read did not anticipate became the DENOMINATOR of
   * every borrowing share a family is shown, and no refusal would have fired.
   */
  private fun mapCountFact(
    name: String,
    basis: String?,
    number: Double?,
    status: FigureStatus?,
    address: RowAddress,
  ): BorrowingFact {
    assertBasis(basis, COUNT_BASIS, "cohort_population_counts", address)
    val population = decodeCohortPopulation(name, address.toString())
    val loanType = LoanType.ofBorrowers(population)
    return when {
      loanType != null -> {
        BorrowingFact.Borrowers(loanType, number?.toInt(), status)
      }

      population == CohortPopulation.GRADUATING_CLASS -> {
        BorrowingFact.GraduatingClass(number?.toInt(), status)
      }

      else -> {
        throw corruptValue(
          population.value,
          "the graduating class [${CohortPopulation.GRADUATING_CLASS.value}] or one loan type's borrowers",
          "cohort_population_counts.population ($address)",
        )
      }
    }
  }

  /**
   * The rest of a row's natural key, refused when it is not the address this
   * fact is written at.
   *
   * REFUSED rather than narrowed away in SQL: a mis-axed row that simply did
   * not match would make the caller say "this filing reports no borrowing"
   * about a filing that has the row.
   */
  private fun assertBasis(
    basis: String?,
    expected: String,
    table: String,
    address: RowAddress,
  ) {
    if (basis != expected) {
      throw corruptValue(
        "basis=[$basis]",
        "a Common Data Set borrowing row at basis [$expected]",
        "$table ($address)",
      )
    }
  }

  /**
   * The loan type an average belongs to, refusing a row whose stored aid scope
   * is not that loan type's own DENOMINATOR.
   *
   * This is the fourth guard against the defect RFC 148, 162 and 170 each hit,
   * and the first one that fires at READ time: a `private_loan_debt_average`
   * stored at `all` is an average over a population it was never computed over,
   * and serving it would tell a family that everyone who graduated owed
   * $43,000 in private loans.
   *
   * The stored scope is DECODED before it is judged, like every other stored
   * string in this file: compared as raw text, a scope no enum reads was
   * reported as a mis-scoped row, which sends a reader after the wrong defect.
   */
  private fun loanTypeOfMeasure(
    name: String,
    scope: String?,
    address: RowAddress,
  ): LoanType {
    val measure =
      MoneyMeasure.fromValue(name) ?: throw corruptValue(name, "MoneyMeasure", "cohort_money_stats.measure ($address)")
    val loanType =
      LoanType.of(measure)
        ?: throw corruptValue(name, "a borrowing measure", "cohort_money_stats.measure ($address)")
    val storedScope =
      CohortAidScope.fromValue(scope.orEmpty())
        ?: throw corruptValue(scope.orEmpty(), "CohortAidScope", "cohort_money_stats.aid_scope ($address)")
    if (storedScope != loanType.aidScope) {
      throw corruptValue(
        "measure=[$name] aid_scope=[${storedScope.value}]",
        "a borrowing average stored at its own loan type's scope [${loanType.aidScope.value}]",
        "cohort_money_stats ($address)",
      )
    }
    return loanType
  }

  /**
   * A stored dollar figure as whole dollars, ROUNDED, unit-checked and BOUNDED:
   * `toInt()` truncates -- 18400.9 became 18400 -- and the same column carries
   * 0-1 shares for other measures, so reading a dollar figure out of it is
   * exactly where a share could be spoken as money.
   *
   * The sign and the magnitude are audited beside the unit. `cohort_money_stats.value`
   * is an unbounded `NUMERIC` with no range CHECK, and `Math.round` answers a
   * `Long` whose `toInt()` DROPS the high bits, so a mis-extracted cell was
   * served to a family as a negative average debt with a citation beside it. A
   * value with no whole-dollar form is REFUSED, never clamped -- the rule
   * `CollegeFigures.toWholeDollars` already states one module away.
   *
   * NOT shared with [AidPolicyDao.wholeDollars], and the difference is
   * deliberate. There the measure is a compile-time CONSTANT the call site
   * names, so a wrong unit is a code fault and `require` is the right channel.
   * Here the measure came out of `cohort_money_stats.measure`, so a wrong unit
   * is a stored value -- and a stored value is refused as the located
   * [corruptValue], which keeps its PermanentError marker inside the Result
   * channel and names the row that holds it.
   */
  private fun wholeDollars(
    measure: MoneyMeasure,
    amount: Double?,
    address: RowAddress,
  ): Int? {
    if (measure.unit != MeasureUnit.USD_PER_YEAR) {
      throw corruptValue(
        "measure=[${measure.value}] unit=[${measure.unit}] value=[$amount]",
        "a borrowing average stored in [${MeasureUnit.USD_PER_YEAR}]",
        "cohort_money_stats ($address)",
      )
    }
    if (amount == null) return null
    val rounded = if (amount.isFinite()) Math.round(amount) else null
    if (rounded == null || rounded < 0L || rounded > Int.MAX_VALUE.toLong()) {
      throw corruptValue(
        "value=[$amount] measure=[${measure.value}]",
        "whole dollars a graduate can owe ([0] to [${Int.MAX_VALUE}])",
        "cohort_money_stats.value ($address)",
      )
    }
    return rounded.toInt()
  }

  /** One college's facts as the read model: the constructor call, and named steps under it. */
  private fun mapCollegeBorrowing(facts: List<CitedFact<BorrowingFact>>): CollegeBorrowing {
    val first = facts.first()
    val address = RowAddress(first.collegeId, first.academicYear)
    val read = "the borrowing read ($address)"
    return CollegeBorrowing(
      collegeId = first.collegeId,
      academicYear = first.academicYear,
      sourceUrl = first.sourceUrl,
      archiveUrl = first.archiveUrl,
      graduatingClass = graduatingClassOf(facts, address),
      // The bare figures, not ready-made pairs: [CollegeBorrowing] stitches
      // each count against the ONE class size above, so this read cannot hand
      // five loan types five different graduating classes -- and the school's
      // own contradiction (a count above that class) is decided there, once,
      // as an OUTCOME rather than raised as an error that would deny this
      // whole batch of colleges its price answer.
      averageDebtUsdByLoanType = averageDebtUsdByLoanType(facts, read),
      borrowersByLoanType = borrowersByLoanType(facts, read),
      // WHICH cells are value-less and WHOSE silence each one is, not merely
      // THAT one exists: the caller says our gap, the publisher's withholding
      // and the school's own silence in three different sentences, and only a
      // reader that can tell them apart can say each in its own words.
      gapStatusByLoanType = gapStatusByLoanType(facts),
      // At most ONE H.401 row survives -- [graduatingClassOf] refuses a second,
      // and it is evaluated above -- so this fold answers that one row's status
      // or null. It is a fold and not `first()` so the per-cell rule in
      // [gapStatusOf] still decides if that refusal is ever relaxed.
      graduatingClassGapStatus =
        facts
          .mapNotNull { (it.fact as? BorrowingFact.GraduatingClass)?.takeIf { fact -> fact.headcount == null }?.status }
          .reduceOrNull(::gapStatusOf),
    )
  }

  /** The ONE class size this filing reports, refusing a second: every share divides by it. */
  private fun graduatingClassOf(
    facts: List<CitedFact<BorrowingFact>>,
    address: RowAddress,
  ): Int? {
    val classSizes = facts.mapNotNull { it.fact as? BorrowingFact.GraduatingClass }
    if (classSizes.size > 1) {
      throw corruptValue(
        "count=[${classSizes.size}] " + classSizes.joinToString(prefix = "headcounts=") { "[${it.headcount}]" },
        "one graduating-class count per filing",
        "cohort_population_counts.population=[${CohortPopulation.GRADUATING_CLASS.value}] ($address)",
      )
    }
    return classSizes.singleOrNull()?.headcount
  }

  /** The average this filing reports per loan type; a value-less cell yields no entry. */
  private fun averageDebtUsdByLoanType(
    facts: List<CitedFact<BorrowingFact>>,
    read: String,
  ): Map<LoanType, Int> =
    singleByKey(facts.mapNotNull { it.fact as? BorrowingFact.Average }, { it.loanType }, "loan type", read)
      .mapNotNull { (type, average) -> average.amountUsd?.let { type to it } }
      .toMap()

  /** The borrower headcount this filing reports per loan type, on the same rule. */
  private fun borrowersByLoanType(
    facts: List<CitedFact<BorrowingFact>>,
    read: String,
  ): Map<LoanType, Int> =
    singleByKey(facts.mapNotNull { it.fact as? BorrowingFact.Borrowers }, { it.loanType }, "loan type", read)
      .mapNotNull { (type, count) -> count.headcount?.let { type to it } }
      .toMap()

  /**
   * WHOSE silence each value-less cell of this filing is, per loan type.
   *
   * A VALUE-LESS cell only: a `reported` or `imputed_by_publisher` row carries
   * its figure and is no silence at all. A loan type has two cells and one
   * entry, so [gapStatusOf] decides which of two disagreeing silences is said.
   */
  private fun gapStatusByLoanType(facts: List<CitedFact<BorrowingFact>>): Map<LoanType, FigureStatus> {
    val gaps =
      facts.mapNotNull { cited ->
        when (val fact = cited.fact) {
          is BorrowingFact.Average -> if (fact.amountUsd == null) fact.status?.let { fact.loanType to it } else null
          is BorrowingFact.Borrowers -> if (fact.headcount == null) fact.status?.let { fact.loanType to it } else null
          is BorrowingFact.GraduatingClass, BorrowingFact.NoBlock -> null
        }
      }
    return gaps
      .groupBy({ it.first }, { it.second })
      .mapValues { (_, statuses) -> statuses.reduce(::gapStatusOf) }
  }

  /**
   * The stored status of one read row, refused when no enum reads it -- the
   * [decodeCohortPopulation] rule. It decides WHOSE silence a value-less row is, so
   * an unreadable status must not fall through to "the school reports nothing".
   *
   * The COLUMN is named from the row's own kind: this read is a UNION over
   * `cohort_money_stats` and `cohort_population_counts`, and a refusal that
   * named neither left an operator scanning both.
   */
  private fun statusOf(
    status: String,
    kind: String?,
    address: RowAddress,
  ): FigureStatus {
    val column =
      when (kind) {
        STAT_KIND -> "cohort_money_stats.status"
        COUNT_KIND -> "cohort_population_counts.status"
        else -> "the borrowing read"
      }
    return FigureStatus.fromValue(status) ?: throw corruptValue(status, "FigureStatus", "$column ($address)")
  }
}
