package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.Share
import ed.unicoach.db.models.AidForm
import ed.unicoach.db.models.AidFormApplicantGroup
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.AssuredFigure
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CollegeAidPolicy
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FullyMetNeedCounts
import ed.unicoach.db.models.MeasureUnit
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import java.sql.ResultSet
import java.util.UUID

/**
 * The Common Data Set aid-policy read (RFC 170): what a school asks a family to
 * FILE, and how it treats need, for the newest CDS cycle it reported.
 *
 * There is no `aid_policy` table to read -- that was RFC 158 D9's guess, and
 * RFC 170 D2 replaced it with the tables the domain already puts these facts
 * in. So this DAO reads three: the two need statistics from
 * `cohort_money_stats`, the two headcounts from `cohort_population_counts`,
 * and the required forms from `aid_form_requirements`, all bounded to
 * [MoneySource.COMMON_DATA_SET] rows so no IPEDS or Scorecard figure can drift
 * into an answer cited to a school's own filing.
 *
 * Every fact carries the id of the document it came from (D13), so the
 * citation is a REFERENCE this read follows rather than an edge it re-derives
 * from a (college, year, source) triple -- a join on a triple that misses drops
 * the fact from the answer, where a reference cannot.
 *
 * Batch by construction, like every other chat read: one query for the whole
 * answer, never one per college.
 */
object AidPolicyDao {
  /**
   * Each college's aid policy from its own newest CDS cycle; colleges with no
   * CDS aid facts are simply absent, which the caller reports as "this school
   * does not report it" rather than as zeroes.
   *
   * The cycle is resolved PER COLLEGE (`max(year)`), never written down by a
   * caller: the seed keeps the newest document that reports the group, so one
   * school's answer is 2024-25 while its neighbour's is 2025-26, and a
   * hardcoded year would silently drop half the corpus.
   */
  fun listLatest(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): Result<List<CollegeAidPolicy>> {
    val ids = collegeIds.distinct()
    if (ids.isEmpty()) return Result.success(emptyList())
    // The ids are bound ONCE, through the house array binder, and every arm
    // selects from that one CTE: the alternative (a placeholder list emitted
    // per UNION arm, with the bind loop counting arms) couples the parameter
    // count to a string forty lines away, and a fourth arm mis-binds silently.
    val sql =
      """
      WITH ids AS (
        SELECT unnest($TEXT_ARRAY_PARAM)::uuid AS college_id
      ),
      -- The publisher and the aid scope, BOUND once and joined, rather than
      -- quoted into six SQL literals: no other DAO in this package interpolates
      -- a value, and six copies of one slug is six chances to edit five.
      cds AS (SELECT ?::text AS source, ?::text AS aid_scope),
      cds_years AS (
        SELECT s.college_id, s.vintage AS year FROM cohort_money_stats s
        JOIN ids i ON i.college_id = s.college_id
        CROSS JOIN cds
        WHERE s.source = cds.source AND s.vintage IS NOT NULL
        UNION ALL
        SELECT c.college_id, c.vintage FROM cohort_population_counts c
        JOIN ids i ON i.college_id = c.college_id
        CROSS JOIN cds
        WHERE c.source = cds.source
        UNION ALL
        SELECT a.college_id, a.academic_year FROM aid_form_requirements a
        JOIN ids i ON i.college_id = a.college_id
        CROSS JOIN cds
        WHERE a.source = cds.source
      ),
      latest AS (SELECT college_id, max(year) AS year FROM cds_years GROUP BY college_id),
      facts AS (
        SELECT l.college_id, l.year, '$STAT_KIND' AS kind, s.measure AS name, s.value AS number,
               NULL::boolean AS is_required, s.source, s.source_variable, s.source_document_id
        FROM latest l
        CROSS JOIN cds
        JOIN cohort_money_stats s
          ON s.college_id = l.college_id AND s.vintage = l.year AND s.source = cds.source
        -- The measure alone does not identify the figure: the same measure over
        -- another cohort is a different number, and this read speaks for one.
        -- Narrowed to the cohort the CDS fill writes, so a row about anyone
        -- else cannot be picked up and spoken as the freshman figure.
         AND s.population = ANY ($TEXT_ARRAY_PARAM)
         AND s.aid_scope = cds.aid_scope
        UNION ALL
        SELECT l.college_id, l.year, '$COUNT_KIND', c.population, c.headcount, NULL::boolean,
               c.source, c.source_variable, c.source_document_id
        FROM latest l
        CROSS JOIN cds
        JOIN cohort_population_counts c
          ON c.college_id = l.college_id AND c.vintage = l.year AND c.source = cds.source
         AND c.population = ANY ($TEXT_ARRAY_PARAM)
        UNION ALL
        -- Every form row of THIS applicant group, requirement or not: whether
        -- a row states a requirement is [CdsAidFact.Form.required]'s question,
        -- and a row that states our own gap (D7) still proves the filing
        -- exists -- which is a different silence from having no filing, and
        -- the reader must be able to tell them apart.
        SELECT l.college_id, l.year, '$FORM_KIND', a.aid_form, NULL, a.is_required, a.source,
               a.source_variable, a.source_document_id
        FROM latest l
        CROSS JOIN cds
        JOIN aid_form_requirements a
          ON a.college_id = l.college_id AND a.academic_year = l.year AND a.source = cds.source
         AND a.applicant_group = ?
      )
      -- `(source, source_variable)` is the WHOLE key an assurance tier is a
      -- function of (RFC 179): who published the row, and the CDS field id it
      -- was published under (H.209, H.211, H.801, ...). BOTH halves are selected
      -- on all three arms and decoded from the row -- the read binds the CDS
      -- slug as a filter sixty lines up, but a filter is not a value, and a
      -- decoder that asserted the publisher as a literal would be re-typing at
      -- the mapper what the row already says. The columns are NOT NULL on all
      -- three tables, so a NULL on any arm would be a lie about the row.
      SELECT f.college_id, f.year, f.kind, f.name, f.number, f.is_required, f.source, f.source_variable,
             ${SourceDocumentJoin.CITATION_COLUMNS}
      ${SourceDocumentJoin.withDocumentById("facts")}
      ORDER BY f.college_id, f.kind, f.name
      """.trimIndent()
    return session
      .queryList(
        sql,
        bind = { stmt ->
          jsonbArrayBinder(ids.map { it.value.toString() })(stmt, 1)
          stmt.setString(2, MoneySource.COMMON_DATA_SET.value)
          stmt.setString(3, CohortAidScope.NEED_BASED_AID_RECEIVING.value)
          // 4 and 5 are the SAME shape -- two text[] population lists -- and
          // the only thing that tells them apart is the order of the UNION arms
          // sixty lines up: 4 narrows the money-stat arm, 5 the headcount arm.
          // Swapping them binds cleanly and silently serves each measure over
          // the other's cohort, so the arm order and these two indexes are one
          // fact written in two places and have to be edited together.
          jsonbArrayBinder(STAT_POPULATIONS.map { it.value })(stmt, 4)
          jsonbArrayBinder(COUNT_POPULATIONS.map { it.value })(stmt, 5)
          stmt.setString(6, AidFormApplicantGroup.DOMESTIC_FIRST_YEAR.value)
        },
        map = ::mapFact,
        // The decoders throw a located CorruptPersistedValueException, and
        // `map` runs AFTER queryList's own try/catch: without mapCatching a
        // stored slug no enum reads would leave the Result channel entirely
        // and lose its PermanentError marker.
      ).mapCatching { facts -> facts.groupBy { it.collegeId }.map { (_, rows) -> mapCollegeAidPolicy(rows) } }
  }

  /** The populations the CDS fill writes each kind against; the read admits no others. */
  private val STAT_POPULATIONS = listOf(CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_NEED_BASED_GRANT)
  private val COUNT_POPULATIONS =
    listOf(
      CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_ANY_AID,
      CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_NEED_FULLY_MET,
    )

  // The three kinds the union emits, named once and interpolated into the SQL
  // that produces them, so the query and the decoder cannot disagree.
  private const val STAT_KIND = "stat"
  private const val COUNT_KIND = "count"
  private const val FORM_KIND = "form"

  /**
   * What one read row IS, in the read model's own terms -- the read-side twin
   * of `CdsSeedLoader.AidPolicyFact`. A statistic names a measure, a headcount
   * names a population, and a required form carries no number at all, so a form
   * with a value, or a measure read against the population vocabulary, cannot
   * be built past [mapFact].
   */
  private sealed interface CdsAidFact {
    data class Stat(
      val measure: MoneyMeasure,
      val figure: Double?,
      /**
       * What KIND of number this is (RFC 179), resolved from the row's own
       * `(source, source_variable)` pair.
       *
       * Resolved HERE rather than in `:service` for the reason the one resolver
       * exists at all: this DAO is in `:db` and cannot see the service layer, so
       * a service-side resolver would have forced a SECOND mapping for the
       * Common Data Set -- the special case this seam must not have. The source
       * is this read's own bound constant, so the pair is complete on the spot.
       */
      val assurance: AssuranceTier,
    ) : CdsAidFact

    data class Count(
      val population: CohortPopulation,
      val headcount: Int?,
    ) : CdsAidFact

    /**
     * [required] is null exactly when the row bears no value -- our own gap
     * (D7), which is not the school requiring the form and not the school
     * saying it does not.
     */
    data class Form(
      val form: AidForm,
      val required: Boolean?,
    ) : CdsAidFact
  }

  private fun mapFact(rs: ResultSet): CitedFact<CdsAidFact> {
    val name = rs.getString("name")
    val number = rs.getBigDecimal("number")?.toDouble()
    // WHICH row, not just which column: this is a batch read over a whole
    // college list, so a stored value no enum reads is unfindable without the
    // college and the cycle -- both already in this ResultSet.
    val row = "college_id=[${rs.getString("college_id")}] year=[${rs.getInt("year")}]"
    return CitedFact(
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      academicYear = AcademicYear(rs.getInt("year")),
      sourceUrl = rs.getString("source_url"),
      archiveUrl = rs.getString("archive_url"),
      fact =
        when (val kind = rs.getString("kind")) {
          STAT_KIND -> {
            CdsAidFact.Stat(
              measure = decodeMeasure(name, row),
              figure = number,
              // BOTH halves of the tier key off the ROW. The publisher used to
              // be asserted here as a literal while only the variable was read,
              // 150 lines from the SQL that binds the filter it was copied
              // from: a pair half-read and half-typed is a pair that can
              // disagree with the row it claims to describe.
              assurance =
                AssuranceTier.of(
                  decodeSource(rs.getString("source"), row),
                  rs.getString("source_variable"),
                  "the aid-policy read ($row)",
                ),
            )
          }

          COUNT_KIND -> {
            CdsAidFact.Count(decodeCohortPopulation(name, row), number?.toInt())
          }

          FORM_KIND -> {
            CdsAidFact.Form(decodeForm(name, row), rs.getBoolean("is_required").takeUnless { rs.wasNull() })
          }

          // Unreachable, and routed through the same located exception as
          // every other corruptValueException read: a bare IllegalStateException would leave
          // the Result channel and lose its PermanentError marker.
          else -> {
            throw corruptValue(kind, "one of the kinds this read selects", "the aid-policy read ($row)")
          }
        },
    )
  }

  /** One college's facts as the read model. */
  private fun mapCollegeAidPolicy(rows: List<CitedFact<CdsAidFact>>): CollegeAidPolicy {
    val first = rows.first()
    val read = "the aid-policy read (a college's newest CDS cycle)"
    val statRows = singleByKey(rows.mapNotNull { it.fact as? CdsAidFact.Stat }, { it.measure }, "measure", read)
    val counts =
      singleByKey(rows.mapNotNull { it.fact as? CdsAidFact.Count }, { it.population }, "population", read)
        .mapValues { (_, count) -> count.headcount }
    val forms = rows.mapNotNull { it.fact as? CdsAidFact.Form }
    return CollegeAidPolicy(
      collegeId = first.collegeId,
      academicYear = first.academicYear,
      sourceUrl = first.sourceUrl,
      archiveUrl = first.archiveUrl,
      // Each figure leaves this read WITH its own row's tier (RFC 179). The
      // tier is a fact about the row a figure came from, so it travels inside
      // the figure rather than in a side-map beside it: a policy-wide tier
      // would be a claim about rows this read did not look at, and a side-map
      // could simply be missing the key.
      averageNeedMet = assuredShare(statRows, MoneyMeasure.AVG_NEED_MET_SHARE),
      averageNeedBasedGrantUsd = assuredWholeDollars(statRows, MoneyMeasure.AVG_NEED_BASED_GRANT),
      fullyMetNeed = fullyMetNeedCounts(counts),
      // The tri-state, carried rather than collapsed (D7): a REQUIREMENT the
      // source states, our own gap, and -- by absence from both -- a form the
      // filing does not list. Dropping the middle one made our gap read as the
      // school's silence.
      requiredForms = forms.filter { it.required == true }.map { it.form },
      notCollectedForms = forms.filter { it.required == null }.map { it.form },
    )
  }

  /**
   * A stored 0-1 share as the [Share] it is spoken as, unit-checked.
   *
   * The unit and the conversion legal for it are stated TOGETHER, once: they
   * used to be two independent arguments at the call site, so a share's unit
   * could be paired with money's rounding and a stored `0.68` spoken as `$1`.
   * No call site chooses either half now.
   */
  private fun assuredShare(
    rows: Map<MoneyMeasure, CdsAidFact.Stat>,
    measure: MoneyMeasure,
  ): AssuredFigure<Share>? = assured(rows, measure, MeasureUnit.SHARE) { Share.ofRatio(it) }

  /** A stored dollar figure as whole dollars, ROUNDED -- the money half of the same one decision. */
  private fun assuredWholeDollars(
    rows: Map<MoneyMeasure, CdsAidFact.Stat>,
    measure: MoneyMeasure,
  ): AssuredFigure<Int>? = assured(rows, measure, MeasureUnit.USD_PER_YEAR) { Math.round(it).toInt() }

  /**
   * One statistic as the read model carries it: the converted figure ENVELOPED
   * with its own row's assurance tier, or null where the read holds no such row
   * or the row bears no value.
   *
   * The tier comes from the SAME row the number does and leaves this function
   * inside the same value, so a figure can never be built without one -- the
   * side-map this replaced could be missing the key while the figure was served
   * (RFC 179).
   *
   * PRIVATE to the two per-unit readers above, never called directly: [unit]
   * and [convert] are two halves of ONE decision, and a call site free to
   * choose them separately can pair a share's unit with money's rounding.
   *
   * [unit] is asserted rather than assumed: `cohort_money_stats.value` is one
   * NUMERIC column carrying whole dollars and 0-1 shares, and `toInt()`
   * truncates -- 18400.9 became 18400 -- so reading the wrong measure out of it
   * is exactly where a share could be spoken as money.
   *
   * `require`, deliberately, and NOT the located [corruptValue] its
   * [BorrowingDao] twin raises: the measure here is a compile-time CONSTANT
   * this file names, so a wrong unit is a code fault and not a stored value.
   * The borrowing read decodes its measure FROM the row, so there the same
   * failure is a corrupt persisted value and is refused as one. The two guards
   * ask different questions and stay apart on purpose.
   */
  private fun <T : Any> assured(
    rows: Map<MoneyMeasure, CdsAidFact.Stat>,
    measure: MoneyMeasure,
    unit: MeasureUnit,
    convert: (Double) -> T,
  ): AssuredFigure<T>? {
    require(measure.unit == unit) {
      "[${measure.value}] is [${measure.unit}], not [$unit]; it may not be read as one"
    }
    val stat = rows[measure] ?: return null
    val figure = stat.figure ?: return null
    return AssuredFigure(convert(figure), stat.assurance)
  }

  /**
   * Lines d and h as one value, or null: half a pair never leaves this read, so
   * no caller can meet a fully-met headcount with no denominator.
   *
   * A fully-met count LARGER than the aided count it is reported against is
   * refused here rather than rendered: the pair would produce a share above
   * 100%, spoken with both numbers, and no source can mean that.
   */
  private fun fullyMetNeedCounts(counts: Map<CohortPopulation, Int?>): FullyMetNeedCounts? {
    val awardedAnyAid = counts[CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_ANY_AID] ?: return null
    val fullyMet = counts[CohortPopulation.FIRST_TIME_FULL_TIME_FRESHMEN_NEED_FULLY_MET] ?: return null
    if (fullyMet > awardedAnyAid) {
      throw corruptValue(
        "need_fully_met=[$fullyMet] awarded_any_aid=[$awardedAnyAid]",
        "a fully-met count no larger than the aided count it is reported against",
        "cohort_population_counts (CDS H2 lines h and d)",
      )
    }
    return FullyMetNeedCounts(freshmenAwardedAnyAid = awardedAnyAid, freshmenNeedFullyMet = fullyMet)
  }

  /*
   * A stored slug no enum reads is a schema/enum drift, not a row to skip. It
   * is raised as the house's located [corruptValue] exception (the
   * CdsAdmissionsDao precedent) so it stays inside the Result channel with its
   * PermanentError marker and names the column that holds it.
   */
  private fun decodeMeasure(
    name: String,
    row: String,
  ): MoneyMeasure = MoneyMeasure.fromValue(name) ?: throw corruptValue(name, "MoneyMeasure", "cohort_money_stats.measure ($row)")

  /** The publisher this row was actually stored under -- read, never assumed (RFC 179). */
  private fun decodeSource(
    name: String,
    row: String,
  ): MoneySource = MoneySource.fromValue(name) ?: throw corruptValue(name, "MoneySource", "the aid-policy read ($row)")

  private fun decodeForm(
    name: String,
    row: String,
  ): AidForm = AidForm.fromValue(name) ?: throw corruptValue(name, "AidForm", "aid_form_requirements.aid_form ($row)")
}
