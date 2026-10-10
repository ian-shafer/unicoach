package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardBand
import ed.unicoach.db.models.AutomaticAwardBandId
import ed.unicoach.db.models.AutomaticAwardId
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.Id
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.NewAutomaticAward
import ed.unicoach.db.models.NewAutomaticAwardBand
import ed.unicoach.db.models.PartialDateColumns
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * A row this DAO wrote, and whether writing it changed anything: the id, so a
 * caller can hang a child row off it, and the [UpsertOutcome], so a re-ingest of
 * an unchanged corpus can say so instead of reporting a write it did not make.
 *
 * A named pair and not a `Pair`, because `Pair<AutomaticAwardId, UpsertOutcome>`
 * reads identically to its own reverse at the call site.
 */
data class WrittenRow<ID : Id>(
  val id: ID,
  val outcome: UpsertOutcome,
)

/**
 * What writing one published cell did. A sealed outcome and not a failed
 * [Result]: "this school publishes no automatic award, so it has no cell" is a
 * state the corpus is allowed to be in and a caller must ROUTE on, while a
 * missing award row is a referential fault of the caller's own transaction and
 * stays an error.
 *
 * The two used to arrive as one [NotFoundException] whose message named both,
 * which a caller could only string-match -- so the ingest loader re-implemented
 * the rule ahead of the door that enforces it rather than reading its answer.
 */
sealed interface BandWriteOutcome {
  /** The cell landed (or was deliberately left alone), under the id and with the disposition [WrittenRow] carries. */
  data class Written(
    val row: WrittenRow<AutomaticAwardBandId>,
  ) : BandWriteOutcome

  /** The award exists and is [AutomaticAwardKind.NONE_PUBLISHED]: it has no grid to publish a cell of. */
  data class NoGridPublished(
    val awardId: AutomaticAwardId,
  ) : BandWriteOutcome
}

/**
 * A prune that would have emptied the whole table
 * ([AutomaticAwardsDao.deleteAwardsExcept] with no surviving award).
 *
 * A NAMED TYPE AND NOT A MESSAGE. The ingest loader must tell this refusal from
 * any other write failure to report its own located `EmptyAwardCorpus` defect,
 * and it should not have to re-derive the reason from its own local state to do
 * it -- which is what it did, and which would have reported this defect for the
 * next reason this door learns to refuse.
 */
class EmptyAwardPruneException :
  RuntimeException(
    "[college_automatic_awards] refuses a prune with no surviving award: an empty corpus is a defect to be " +
      "named, not a delete-all",
  )

/**
 * A composed grid was asked to write a cell under an award that publishes none
 * ([AutomaticAwardsDao.upsert]).
 *
 * The OUTCOME and the CELL ride as typed properties, and the sentence is one
 * rendering of them: a caller that must tell this from a constraint fault
 * switches on the type, never on the message.
 */
class NoGridForComposedBandException(
  val outcome: BandWriteOutcome.NoGridPublished,
  val band: NewAutomaticAwardBand,
) : RuntimeException(
    "[college_automatic_award_bands] row [${band.awardName}] was given for award " +
      "[${outcome.awardId.value}], which is [${AutomaticAwardKind.NONE_PUBLISHED.value}] and has no grid to " +
      "publish: a composed grid cannot drop a cell",
  )

/**
 * The zero-row signal of [AutomaticAwardsDao.upsertBand]'s guarded statement,
 * and never a caller's failure: it is recovered one line later into either
 * [BandWriteOutcome.NoGridPublished] or a located [NotFoundException]. Private,
 * so no caller can come to depend on it in place of the outcome.
 */
private class NoBandRowWritten : RuntimeException("no band row was written")

/**
 * Read and write layer over the `college_automatic_awards` /
 * `college_automatic_award_bands` pair (RFC 239): what a named school publishes
 * about its automatic merit grid, and the cells in it.
 *
 * [FafsaFilingDeadlineDao] is the shape this follows, because it is the same
 * shape one corpus along -- a small hand-typed reference corpus, a stateless
 * `object`, one [SqlSession] per call, the transaction boundary owned by the
 * caller, and a write path that exists for the INGEST LOADER and the fixtures
 * rather than for any request path. The one difference is where the rows come
 * from: this corpus is keyed by `colleges(id)` and `colleges` is filled by the
 * import run, so it loads with `bin/ingest-colleges` and not from a migration
 * (D-C).
 *
 * EXPIRY IS NOT DECIDED HERE, and neither is the live set.
 * `AutomaticAwardService` owns the five-arm answer and the `expires_on`
 * refusal; this layer returns rows and their dates, exactly as
 * [AdmissionTestCalendarDao] hands the whole calendar to `TestTimelineService`.
 * There is deliberately no `today` parameter and no `WHERE expires_on > ?`: a
 * service that could not see a stale row could not tell a family "we read this
 * school and our reading is stale" apart from "we never read this school", and
 * those two send a family to two different places.
 */

object AutomaticAwardsDao {
  private const val TABLE = "college_automatic_awards"
  private const val BANDS_TABLE = "college_automatic_award_bands"

  /**
   * The order a school prints its own grid in, and the order "the next band up"
   * reads in; see [listBandsOf] for why `test` leads NULLS FIRST.
   *
   * A NAMED DAO CONSTANT, because [queryListWhereIn] splices this fragment
   * unquoted: at the call site it then reads as an identifier list this file
   * wrote, which is the one thing that distinguishes it from caller data.
   */
  private const val BAND_SERVED_ORDER = "award_id, award_name, test ASC NULLS FIRST, score_low, gpa_low"

  /**
   * Every column [mapAward] reads: the award row plus its two citation urls
   * (RFC 213/228) -- the source an INNER join, because `source_document_id` is
   * NOT NULL and a row that cites nothing cannot exist, and the archive a LEFT
   * join, because no row in the curated corpus needed an archived address.
   *
   * ONE HOME, because a read that projected a different set of columns, or
   * joined a different pair of documents, would not be readable by the one
   * mapper.
   */
  private val SELECT_WITH_CITATIONS =
    """
    SELECT w.*, s.url AS source_url, a.url AS archive_url
    FROM $TABLE w
    JOIN external_documents s ON s.id = w.source_document_id
    LEFT JOIN external_documents a ON a.id = w.archive_document_id
    """.trimIndent()

  /**
   * Every award row for [collegeIds], each carrying its own bands.
   *
   * TWO STATEMENTS AND NOT ONE, whatever the number of colleges: one for the
   * awards and one for their bands, joined in Kotlin. A single SQL join would
   * multiply every award row by its band count -- `db/seed/automatic-awards/`
   * commits an order of magnitude more cells than awards -- and then the mapper
   * would have to de-duplicate an award it had already built. Nothing here runs
   * a statement per college or per award.
   *
   * IT DOES NOT FILTER BY ENTRY YEAR either. A school's grid is re-keyed per
   * cycle, and deciding WHICH cycle a student is applying for is a clock
   * question the service owns; a DAO that filtered here would answer it twice,
   * in the layer with no clock.
   *
   * An empty [collegeIds] short-circuits without a query, because `IN ()` is not
   * valid SQL and a query for nothing has nothing to answer.
   */
  fun listByColleges(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): Result<List<AutomaticAward>> {
    val ids = IdList(collegeIds.map { it.value })
    if (ids.isEmpty) return Result.success(emptyList())
    return session
      .queryList(
        """
        |$SELECT_WITH_CITATIONS
        |WHERE w.college_id IN (${ids.placeholders})
        |ORDER BY w.college_id, w.entry_year DESC, w.residency_basis
        """.trimMargin(),
        bind = { stmt -> ids.bindFrom(stmt, 1) },
        map = ::mapAward,
      ).mapCatching { rows ->
        val bands = listBandsOf(session, rows.map { it.id }).getOrThrow().groupBy { it.awardId }
        rows.map { row -> row.build(bands[row.id].orEmpty()) }
      }
  }

  /**
   * The bands of [awardIds], newest-school-first order irrelevant: they are
   * ordered so a renderer never re-sorts a served row -- by test, then by the
   * bottom of the score band, then by the GPA floor, which is the order a school
   * prints its own grid in and the order "the next band up" reads in.
   *
   * NULLS FIRST on `test` is explicit and is the published NO-SCORE row -- the
   * tier a test-optional applicant lands in -- so it leads the grid rather than
   * trailing it, which is where PostgreSQL's ascending default would put it.
   */
  private fun listBandsOf(
    session: SqlSession,
    awardIds: Collection<AutomaticAwardId>,
  ): Result<List<AutomaticAwardBand>> =
    session.queryListWhereIn(
      table = BANDS_TABLE,
      columns = "*",
      keyColumn = "award_id",
      keys = awardIds.map { it.value },
      bindKey = { stmt, index, id -> stmt.setObject(index, id) },
      map = ::mapBand,
      orderBy = BAND_SERVED_ORDER,
    )

  /**
   * One award row on its natural key `(college_id, entry_year,
   * residency_basis)`, and the id it landed under -- the ingest loader's first
   * write, and the fixtures' and this DAO's suite's. Production rows arrive with
   * `bin/ingest-colleges`, so this path is never on a request.
   *
   * IT UPSERTS because a corrected re-read of the same school's same cycle is a
   * correction of that row rather than a second contradictory one, and it
   * reports its [UpsertOutcome] so a re-ingest of an unchanged corpus writes
   * nothing, does not advance `updated_at`, and says so in the run summary --
   * the [CollegeIpedsDao] shape, through the same `IS DISTINCT FROM` guard.
   *
   * THE ID COMES BACK WHATEVER THE OUTCOME, which is why this is not
   * [upsertDetectingChange]: the bands hang off the award, so a loader that
   * learned only "unchanged" would have nothing to hang them on. The suppressed
   * write is recovered by the second arm of the union, which reads the row the
   * guard declined to touch.
   *
   * IT WRITES TWO TABLES AND EXPECTS A TRANSACTION. The citation documents are
   * resolved first, through the ONE home for that policy:
   * [ExternalDocumentsDao.citationIds] cites and never sweeps, so this path
   * cannot overwrite a swept link status with "we never asked" (RFC 182). The
   * caller's transaction is what makes the pair atomic.
   *
   * THE DOCUMENT WRITE IS IDEMPOTENT AND MAY BE REDUNDANT, and it stays here
   * deliberately. `AutomaticAwardSeedLoader` upserts every url in the corpus's
   * sidecar before this is reached, so on the ingest path this call only ever
   * takes its conflict arm; the FIXTURES hold no sidecar, and a door that took
   * resolved ids would make every one of them cite a page by hand. So a reader
   * meeting the second write is not surprised by it: it is a cite, not a sweep,
   * and citing a page twice says nothing new about it.
   */
  fun upsertAward(
    session: SqlSession,
    input: NewAutomaticAward,
  ): Result<WrittenRow<AutomaticAwardId>> =
    ExternalDocumentsDao
      .citationIds(session, input.sourceUrl, input.archiveUrl, input.checkedOn)
      .mapCatching { (sourceId, archiveId) ->
        session
          .mutateReturning(
            """
            WITH up AS (
              INSERT INTO $TABLE (
                college_id, entry_year, residency_basis, award_kind, score_requirement,
                score_requirement_as_published, superscore_rule, superscore_as_published,
                score_deadline, score_deadline_as_published,
                last_sitting_through, last_sitting_precision, last_sitting_as_published,
                publisher, source_document_id, archive_document_id, checked_on, expires_on
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (college_id, entry_year, residency_basis) DO UPDATE SET
                award_kind = EXCLUDED.award_kind,
                score_requirement = EXCLUDED.score_requirement,
                score_requirement_as_published = EXCLUDED.score_requirement_as_published,
                superscore_rule = EXCLUDED.superscore_rule,
                superscore_as_published = EXCLUDED.superscore_as_published,
                score_deadline = EXCLUDED.score_deadline,
                score_deadline_as_published = EXCLUDED.score_deadline_as_published,
                last_sitting_through = EXCLUDED.last_sitting_through,
                last_sitting_precision = EXCLUDED.last_sitting_precision,
                last_sitting_as_published = EXCLUDED.last_sitting_as_published,
                publisher = EXCLUDED.publisher,
                source_document_id = EXCLUDED.source_document_id,
                archive_document_id = EXCLUDED.archive_document_id,
                checked_on = EXCLUDED.checked_on,
                expires_on = EXCLUDED.expires_on
              WHERE ($TABLE.award_kind, $TABLE.score_requirement,
                     $TABLE.score_requirement_as_published, $TABLE.superscore_rule,
                     $TABLE.superscore_as_published, $TABLE.score_deadline,
                     $TABLE.score_deadline_as_published, $TABLE.last_sitting_through,
                     $TABLE.last_sitting_precision, $TABLE.last_sitting_as_published,
                     $TABLE.publisher, $TABLE.source_document_id,
                     $TABLE.archive_document_id, $TABLE.checked_on, $TABLE.expires_on)
                IS DISTINCT FROM
                    (EXCLUDED.award_kind, EXCLUDED.score_requirement,
                     EXCLUDED.score_requirement_as_published, EXCLUDED.superscore_rule,
                     EXCLUDED.superscore_as_published, EXCLUDED.score_deadline,
                     EXCLUDED.score_deadline_as_published, EXCLUDED.last_sitting_through,
                     EXCLUDED.last_sitting_precision, EXCLUDED.last_sitting_as_published,
                     EXCLUDED.publisher, EXCLUDED.source_document_id,
                     EXCLUDED.archive_document_id, EXCLUDED.checked_on, EXCLUDED.expires_on)
              RETURNING id, (xmax = 0) AS inserted
            )
            SELECT id, inserted FROM up
            UNION ALL
            SELECT w.id, NULL::boolean
            FROM $TABLE w
            WHERE w.college_id = ? AND w.entry_year = ? AND w.residency_basis = ?
              AND NOT EXISTS (SELECT 1 FROM up)
            """.trimIndent(),
            bind = { stmt ->
              // A cursor, never twenty-one hand-typed ordinals
              // ([FafsaFilingDeadlineDao]'s idiom): seven slots are nullable, so
              // a one-slot renumber slip writes a row that passes every CHECK
              // and says something else -- here, another school's rule.
              var idx = 1
              stmt.setObject(idx++, input.collegeId.value)
              stmt.setInt(idx++, input.entryYear.firstCalendarYear)
              stmt.setString(idx++, input.residencyBasis.value)
              stmt.setString(idx++, input.awardKind.value)
              stmt.setString(idx++, input.scoreRequirement.value)
              stmt.setStringOrNull(idx++, input.scoreRequirementAsPublished)
              stmt.setString(idx++, input.superscoreRule.value)
              stmt.setStringOrNull(idx++, input.superscoreAsPublished)
              stmt.setObject(idx++, input.scoreDeadline)
              stmt.setStringOrNull(idx++, input.scoreDeadlineAsPublished)
              // The period comes off the ONE arm that has one, through the one
              // codec: LastSittingConsidered exposes no interface-level nullable
              // date, precisely so this cannot be written against a flat
              // (date, precision, words) clump.
              val through = (input.lastSittingConsidered as? LastSittingConsidered.Through)?.through
              stmt.setObject(idx++, through?.let { PartialDateColumns.periodStart(it) })
              stmt.setStringOrNull(idx++, through?.let { PartialDateColumns.precision(it).value })
              stmt.setStringOrNull(idx++, input.lastSittingConsidered?.asPublished)
              stmt.setString(idx++, input.publisher)
              stmt.setObject(idx++, sourceId.value)
              stmt.setObject(idx++, archiveId?.value)
              stmt.setObject(idx++, input.checkedOn)
              stmt.setObject(idx++, input.expiresOn)
              // The natural key again, for the arm that reads back the row the
              // IS DISTINCT FROM guard declined to write.
              stmt.setObject(idx++, input.collegeId.value)
              stmt.setInt(idx++, input.entryYear.firstCalendarYear)
              stmt.setString(idx, input.residencyBasis.value)
            },
            map = { rs -> WrittenRow(AutomaticAwardId(UUID.fromString(rs.getString("id"))), rs.upsertOutcome()) },
            // Both document ids came from `citationIds` one statement ago, so a
            // 23503 here means either a document row was deleted under us or --
            // the one this corpus actually risks -- `college_id` names no
            // college, which is the mis-keyed row the loader exists to refuse.
            mapError = { e -> mapReferenceWriteError(e, awardLocation(input)) },
          ).getOrThrow()
      }

  /**
   * One published cell on its natural key `(award_id, award_name, test,
   * score_low, gpa_low)` -- the index is `NULLS NOT DISTINCT`, so the published
   * NO-SCORE row (`test IS NULL`) collides with itself rather than landing
   * twice, and so does a band with no GPA axis.
   *
   * A BAND UNDER A `none_published` AWARD IS REFUSED HERE, at the only door that
   * writes one. PostgreSQL cannot express that as a cross-table CHECK without a
   * trigger, and a trigger on a corpus written only by this path buys less than
   * the refusal at the door (D28) -- so the insert selects FROM the award and
   * the predicate that admits it is part of the statement, not a check the
   * caller might forget. [listByColleges] makes the matching read-side
   * assertion.
   *
   * THAT REFUSAL IS AN OUTCOME AND NOT A FAILURE.
   * [BandWriteOutcome.NoGridPublished] is a state this corpus is allowed to be
   * in -- the loader enumerates it as a defect of the CSV -- so a caller routes
   * on the arm. An award that DOES NOT EXIST stays a [NotFoundException]: that
   * is a reference the caller's own transaction should have written first, and
   * it is a different answer with a different remedy. The two used to be one
   * exception whose message named both, which no caller could branch on.
   *
   * The second read that tells them apart runs ONLY on the zero-row path, so
   * the ordinary write is the one statement it always was.
   */
  fun upsertBand(
    session: SqlSession,
    awardId: AutomaticAwardId,
    input: NewAutomaticAwardBand,
  ): Result<BandWriteOutcome> =
    session
      .mutateReturning(
        """
        WITH up AS (
          INSERT INTO $BANDS_TABLE (
            award_id, award_name, test, score_low, score_high, gpa_low, gpa_high,
            award_per_year_usd, award_as_published
          )
          SELECT ?::uuid, ?::text, ?::admission_test, ?::integer, ?::integer,
                 ?::numeric, ?::numeric, ?::integer, ?::text
          FROM $TABLE w
          WHERE w.id = ?::uuid AND w.award_kind <> ?::text
          ON CONFLICT (award_id, award_name, test, score_low, gpa_low) DO UPDATE SET
            score_high = EXCLUDED.score_high,
            gpa_high = EXCLUDED.gpa_high,
            award_per_year_usd = EXCLUDED.award_per_year_usd,
            award_as_published = EXCLUDED.award_as_published
          WHERE ($BANDS_TABLE.score_high, $BANDS_TABLE.gpa_high,
                 $BANDS_TABLE.award_per_year_usd, $BANDS_TABLE.award_as_published)
            IS DISTINCT FROM
                (EXCLUDED.score_high, EXCLUDED.gpa_high,
                 EXCLUDED.award_per_year_usd, EXCLUDED.award_as_published)
          RETURNING id, (xmax = 0) AS inserted
        )
        SELECT id, inserted FROM up
        UNION ALL
        SELECT b.id, NULL::boolean
        FROM $BANDS_TABLE b
        WHERE b.award_id = ?::uuid AND b.award_name = ?::text
          AND b.test IS NOT DISTINCT FROM ?::admission_test
          AND b.score_low IS NOT DISTINCT FROM ?::integer
          AND b.gpa_low IS NOT DISTINCT FROM ?::numeric
          AND NOT EXISTS (SELECT 1 FROM up)
        """.trimIndent(),
        bind = { stmt ->
          var idx = 1
          stmt.setObject(idx++, awardId.value)
          stmt.setString(idx++, input.awardName)
          stmt.setStringOrNull(idx++, input.test?.value)
          stmt.setIntOrNull(idx++, input.scoreLow)
          stmt.setIntOrNull(idx++, input.scoreHigh)
          stmt.setObject(idx++, input.gpaLow)
          stmt.setObject(idx++, input.gpaHigh)
          stmt.setIntOrNull(idx++, input.awardPerYearUsd)
          stmt.setStringOrNull(idx++, input.awardAsPublished)
          stmt.setObject(idx++, awardId.value)
          // BOUND AND NOT INTERPOLATED, though it is a compile-time constant:
          // every other value in this file is bound, and one literal is what a
          // later edit copies when it interpolates a FIELD instead.
          stmt.setString(idx++, AutomaticAwardKind.NONE_PUBLISHED.value)
          // The natural key again, for the arm that reads back the row the
          // IS DISTINCT FROM guard declined to write.
          stmt.setObject(idx++, awardId.value)
          stmt.setString(idx++, input.awardName)
          stmt.setStringOrNull(idx++, input.test?.value)
          stmt.setIntOrNull(idx++, input.scoreLow)
          stmt.setObject(idx, input.gpaLow)
        },
        map = { rs -> WrittenRow(AutomaticAwardBandId(UUID.fromString(rs.getString("id"))), rs.upsertOutcome()) },
        mapError = { e ->
          mapReferenceWriteError(
            e,
            "[$BANDS_TABLE] row [${input.awardName}] under award [${awardId.value}] could not be written",
          )
        },
        // ZERO ROWS IS NOT "UNCHANGED" HERE: both arms of the union are empty only
        // when the guarded SELECT found no writable award, so the award either
        // does not exist or publishes no automatic award at all. Which of the two
        // it is is the caller's next decision, so it is answered below rather than
        // fused into one sentence -- a silent skip is how a school ends up quoted
        // for a grid it does not publish.
        onNoRow = { NoBandRowWritten() },
      ).fold(
        onSuccess = { row -> Result.success(BandWriteOutcome.Written(row)) },
        onFailure = { cause ->
          if (cause !is NoBandRowWritten) Result.failure(cause) else classifyNoBandRow(session, awardId, input)
        },
      )

  /**
   * What writing NO cell meant: an award that publishes no grid, an award that
   * is not there, or an award that changed under us between the write and this
   * read.
   *
   * One level down from [upsertBand], which writes the cell: "write it" and
   * "decide what writing nothing meant" are two questions, and the second one
   * used to be answered inside the first one's expression.
   */
  private fun classifyNoBandRow(
    session: SqlSession,
    awardId: AutomaticAwardId,
    input: NewAutomaticAwardBand,
  ): Result<BandWriteOutcome> =
    awardKindOf(session, awardId).mapCatching { kind ->
      when (kind) {
        // A REFERENTIAL FAULT AND NOT A DOMAIN STATE: nothing in this
        // corpus can hang a cell off an award that is not there, and the
        // caller's own transaction is where the award was supposed to be
        // written.
        null -> {
          throw NotFoundException(
            "[$BANDS_TABLE] row [${input.awardName}] could not be written: " +
              "award [${awardId.value}] does not exist",
          )
        }

        AutomaticAwardKind.NONE_PUBLISHED -> {
          BandWriteOutcome.NoGridPublished(awardId)
        }

        // EVERY MEMBER NAMED, and no `else`: the guarded SELECT's own predicate
        // (`award_kind <> 'none_published'`) and this recovery must not come to
        // disagree, and an `else` here would let a fourth kind -- another
        // "publishes no grid" variant is the obvious one -- compile silently
        // and be reported to the loader as a concurrent modification that never
        // happened.
        //
        // The guarded SELECT admitted no award and the award is writable:
        // that is not a state this statement has. It is reachable only if
        // the row changed under us between the two reads, and absorbing it
        // would report a cell that was never written.
        AutomaticAwardKind.SCORE_GRID, AutomaticAwardKind.GPA_ONLY_GRID -> {
          throw ConcurrentModificationException(
            "[$BANDS_TABLE] row [${input.awardName}] wrote no cell under award [${awardId.value}], " +
              "which now reads [${kind.value}] and would have admitted one: the award row changed " +
              "between the write and this read",
          )
        }
      }
    }

  /**
   * The kind of one award row, for the ONE caller that must tell a refusal from
   * an absence -- [upsertBand]'s zero-row path, and nothing else. Null is "no
   * such row".
   */
  private fun awardKindOf(
    session: SqlSession,
    awardId: AutomaticAwardId,
  ): Result<AutomaticAwardKind?> =
    session
      .queryList(
        "SELECT award_kind FROM $TABLE WHERE id = ?",
        bind = { stmt -> stmt.setObject(1, awardId.value) },
        map = { rs ->
          val raw = rs.getString("award_kind")
          AutomaticAwardKind.fromValue(raw)
            ?: throw corruptValue(raw, "AutomaticAwardKind", "$TABLE.award_kind (row [${awardId.value}])")
        },
      ).map { it.firstOrNull() }

  /**
   * Drops every band of [awardId] that is not in [keep], and answers how many it
   * dropped.
   *
   * WHY A PRUNE AND NOT JUST AN UPSERT, and it is a correctness requirement
   * rather than tidiness: a natural-key upsert is idempotent for an UNCHANGED
   * corpus but not for a CORRECTED one. A re-read that DROPS a band leaves the
   * old cell behind, and the coach then quotes a school paying money it stopped
   * paying -- the same class of defect as a mis-keyed unit id, and in the same
   * direction. With the prune the loader is a true replace-in-place per award,
   * inside one transaction, with the natural key still catching a cell
   * duplicated within the CSV.
   *
   * AN EMPTY [keep] DROPS EVERY BAND, deliberately: that is a grid emptied by a
   * re-read. It is not how a `none_published` school is loaded -- [upsertBand]
   * refuses a band under one before this is ever reached.
   */
  fun deleteBandsExcept(
    session: SqlSession,
    awardId: AutomaticAwardId,
    keep: Collection<AutomaticAwardBandId>,
  ): Result<Int> {
    val kept = IdList(keep.map { it.value })
    if (kept.isEmpty) {
      return session.execute(
        "DELETE FROM $BANDS_TABLE WHERE award_id = ?",
        bind = { stmt -> stmt.setObject(1, awardId.value) },
      )
    }
    return session.execute(
      "DELETE FROM $BANDS_TABLE WHERE award_id = ? AND id NOT IN (${kept.placeholders})",
      bind = { stmt ->
        stmt.setObject(1, awardId.value)
        // The award is bound first, so the list starts at 2 -- and that offset is
        // [IdList]'s to know, not this statement's.
        kept.bindFrom(stmt, 2)
      },
    )
  }

  /**
   * Drops every award row not in [keep], and answers how many it dropped.
   *
   * THE CORPUS IS THE WHOLE TRUTH, which is [deleteBandsExcept]'s argument one
   * table up: a school dropped from `awards.csv` is a school we no longer
   * publish a grid for, and a mis-keyed unit id CORRECTED leaves the OLD school
   * priced from a row nobody will look at again -- served as current money until
   * `expires_on`. Pruning the cells and not the rows above them fixes the
   * smaller half of that defect and leaves the larger.
   *
   * The bands go with the award through the bands table's `ON DELETE CASCADE`
   * (`create-college-automatic-awards.sql`), so this is one statement and not
   * two, and there is no window in which a band outlives its award.
   *
   * AN EMPTY [keep] IS REFUSED rather than obeyed -- the opposite of
   * [deleteBandsExcept], deliberately. "This re-read kept no award at all" is a
   * corpus the caller should already have refused by name (an empty file, a
   * header-only file, a read that threw before its first row); obeying it would
   * empty the table for every school at once. An emptied GRID is a school that
   * stopped publishing cells; an emptied TABLE is never a thing a corpus says.
   */
  fun deleteAwardsExcept(
    session: SqlSession,
    keep: Collection<AutomaticAwardId>,
  ): Result<Int> {
    val kept = IdList(keep.map { it.value })
    // A TYPED REFUSAL, so the loader reads WHICH refusal this is rather than
    // re-deriving it from its own state or matching the sentence.
    if (kept.isEmpty) return Result.failure(EmptyAwardPruneException())
    return session.execute(
      "DELETE FROM $TABLE WHERE id NOT IN (${kept.placeholders})",
      bind = { stmt -> kept.bindFrom(stmt, 1) },
    )
  }

  /**
   * One award and the COMPLETE set of cells under it, written as one
   * replace-in-place -- the fixtures' door and this DAO's suite's, and the
   * shape a caller wants when it holds the whole grid in memory.
   *
   * It is composed from [upsertAward], [upsertBand] and [deleteBandsExcept] and
   * adds no write of its own, so there is exactly one SQL statement per table in
   * this file and a second write path cannot drift from the first.
   *
   * A CELL REFUSED IS THE WHOLE CALL REFUSED. Where [upsertBand] answers
   * [BandWriteOutcome.NoGridPublished] -- the caller handed cells for a school
   * recorded as publishing no automatic award -- this FAILS rather than
   * returning the award with that cell quietly missing: it was asked to write
   * ONE grid, and a partial grid read back as complete is a school priced from
   * some of its bands. A caller that means "this school publishes nothing"
   * passes no bands, which is the `none_published` row and writes no cell at
   * all.
   */
  fun upsert(
    session: SqlSession,
    row: NewAutomaticAward,
    bands: List<NewAutomaticAwardBand>,
  ): Result<AutomaticAward> =
    upsertAward(session, row).mapCatching { written ->
      val kept =
        bands.map { band ->
          when (val outcome = upsertBand(session, written.id, band).getOrThrow()) {
            is BandWriteOutcome.Written -> {
              outcome.row.id
            }

            is BandWriteOutcome.NoGridPublished -> {
              throw NoGridForComposedBandException(outcome, band)
            }
          }
        }
      deleteBandsExcept(session, written.id, kept).getOrThrow()
      readAward(session, written.id).getOrThrow()
    }

  /** One award by id, with its bands -- the read-back [upsert] answers with. */
  private fun readAward(
    session: SqlSession,
    id: AutomaticAwardId,
  ): Result<AutomaticAward> =
    session
      .queryList(
        "$SELECT_WITH_CITATIONS\nWHERE w.id = ?",
        bind = { stmt -> stmt.setObject(1, id.value) },
        map = ::mapAward,
      ).mapCatching { rows ->
        val pending = rows.firstOrNull() ?: throw NotFoundException("[$TABLE] row [${id.value}] was written and could not be read back")
        pending.build(listBandsOf(session, listOf(id)).getOrThrow())
      }

  private fun awardLocation(input: NewAutomaticAward): String =
    "[$TABLE] row for college [${input.collegeId.value}] entry year [${input.entryYear.label}] " +
      "residency [${input.residencyBasis.value}] could not be written"

  /**
   * One award row read but not yet assembled: its [id], which the band read
   * keys on, and the [build] that still needs those cells.
   *
   * A HALF-BUILT AWARD IS NOT A VALUE THIS DAO MAKES. `bands = emptyList()` is
   * a DECLARED legal answer -- it is exactly what a `none_published` row
   * carries -- so an award built with it as a "not read yet" placeholder is
   * indistinguishable from the verified absence until a second call happens to
   * replace it. A reader that forgot that second call served a published grid
   * as a school with no cells.
   */
  private data class PendingAward(
    val id: AutomaticAwardId,
    val build: (List<AutomaticAwardBand>) -> AutomaticAward,
  )

  private fun mapAward(rs: ResultSet): PendingAward {
    val id = AutomaticAwardId(UUID.fromString(rs.getString("id")))
    val entryYear = AcademicYear(rs.getInt("entry_year"))
    val location = "$TABLE (row [${id.value}] entry year [${entryYear.label}])"
    val kindRaw = rs.getString("award_kind")
    val residencyRaw = rs.getString("residency_basis")
    val requirementRaw = rs.getString("score_requirement")
    val superscoreRaw = rs.getString("superscore_rule")
    val collegeId = CollegeId(UUID.fromString(rs.getString("college_id")))
    val residencyBasis =
      ResidencyBasis.fromValue(residencyRaw)
        ?: throw corruptValue(residencyRaw, "ResidencyBasis", "$location.residency_basis")
    val awardKind =
      AutomaticAwardKind.fromValue(kindRaw)
        ?: throw corruptValue(kindRaw, "AutomaticAwardKind", "$location.award_kind")
    val scoreRequirement =
      ScoreRequirement.fromValue(requirementRaw)
        ?: throw corruptValue(requirementRaw, "ScoreRequirement", "$location.score_requirement")
    val superscoreRule =
      ScholarshipSuperscoreRule.fromValue(superscoreRaw)
        ?: throw corruptValue(superscoreRaw, "ScholarshipSuperscoreRule", "$location.superscore_rule")
    val scoreRequirementAsPublished = rs.getString("score_requirement_as_published")
    val superscoreAsPublished = rs.getString("superscore_as_published")
    val scoreDeadline = rs.getDate("score_deadline")?.toLocalDate()
    val scoreDeadlineAsPublished = rs.getString("score_deadline_as_published")
    val lastSittingConsidered = mapLastSitting(rs, location)
    val publisher = rs.getString("publisher")
    val sourceUrl = rs.getString("source_url")
    val archiveUrl = rs.getString("archive_url")
    val checkedOn = rs.getDate("checked_on").toLocalDate()
    val expiresOn = rs.getDate("expires_on").toLocalDate()
    return PendingAward(id) { bands ->
      assertBandsMatchKind(id, awardKind, bands)
      AutomaticAward(
        id = id,
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
        bands = bands,
      )
    }
  }

  /**
   * The school's cut-off, read from the three columns TOGETHER because
   * [LastSittingConsidered] is one value.
   *
   * The schema's pair CHECK already refuses a period with no precision and a
   * period with no words, so a row carrying the wrong halves is corruption and
   * is surfaced with a LOCATED refusal -- never silently resolved to whichever
   * half is present, because both resolutions tell a family something no school
   * said. A period whose stored date is not the start of its own period is the
   * same refusal, through [PartialDateColumns.decode].
   */
  private fun mapLastSitting(
    rs: ResultSet,
    location: String,
  ): LastSittingConsidered? {
    val periodStart = rs.getDate("last_sitting_through")?.toLocalDate()
    val precision = rs.getString("last_sitting_precision")
    val asPublished = rs.getString("last_sitting_as_published")

    // The REASON rides, and the two corruptions do not share one sentence: "the
    // three columns disagree" and "the stored date is not its period's start"
    // are different rows to fix, and a `refuse()` taking no argument rendered
    // them identically -- over the codec's own answer, which it dropped.
    fun refuse(error: ValidationError): Nothing =
      throw CorruptPersistedValueException(
        value = "last_sitting_through=[$periodStart] last_sitting_precision=[$precision] last_sitting_as_published=[$asPublished]",
        error = error,
        location = location,
      )

    if (periodStart == null && precision == null) {
      return asPublished?.let { LastSittingConsidered.InWords(it) }
    }
    if (periodStart == null || precision == null || asPublished == null) {
      refuse(
        ValidationError.InvalidFormat(
          expected = "all three of last_sitting_through, last_sitting_precision and last_sitting_as_published, or none",
        ),
      )
    }
    return when (val decoded = PartialDateColumns.decode(periodStart, precision)) {
      is ValidationResult.Valid -> LastSittingConsidered.Through(decoded.value, asPublished)

      // The CODEC'S stated reason: it is the one thing here that knows which of
      // its rules the row broke.
      is ValidationResult.Invalid -> refuse(decoded.error)
    }
  }

  private fun mapBand(rs: ResultSet): AutomaticAwardBand {
    val id = AutomaticAwardBandId(UUID.fromString(rs.getString("id")))
    return AutomaticAwardBand(
      id = id,
      awardId = AutomaticAwardId(UUID.fromString(rs.getString("award_id"))),
      awardName = rs.getString("award_name"),
      score = mapScoreAxis(rs, id),
      gpaLow = rs.getBigDecimal("gpa_low"),
      gpaHigh = rs.getBigDecimal("gpa_high"),
      awardPerYearUsd = rs.getIntOrNull("award_per_year_usd"),
      awardAsPublished = rs.getString("award_as_published"),
    )
  }

  /**
   * The `(test, score_low, score_high)` triple as the one value it encodes
   * ([BandScoreAxis]), refused HERE and located, like [mapLastSitting] beside
   * it -- the triple never escapes a reader that knows all three.
   *
   * Every refusal below is a row `college_automatic_award_bands_*_check` should
   * already have made impossible, which is the point: this is the read side of
   * a guarantee the database states, and a mapper that trusted the CHECK would
   * turn a corrupt row into an unlocated `IllegalArgumentException` out of a
   * data-class constructor. A migration applied by hand, a generated seed, or a
   * constraint dropped in a future migration all arrive here.
   */
  private fun mapScoreAxis(
    rs: ResultSet,
    id: AutomaticAwardBandId,
  ): BandScoreAxis {
    val testRaw = rs.getString("test")
    val low = rs.getIntOrNull("score_low")
    val high = rs.getIntOrNull("score_high")
    val location = "$BANDS_TABLE (row [${id.value}])"

    fun refuse(constraint: String): Nothing =
      throw corruptValue(
        raw = "test=[$testRaw] score_low=[$low] score_high=[$high] ($constraint)",
        domain = "BandScoreAxis",
        location = location,
      )

    // The published NO-SCORE row: no test, no range. A MEMBER, so it is decided
    // here rather than left for a caller to infer from three nulls.
    if (testRaw == null && low == null && high == null) return BandScoreAxis.NoScore
    if (testRaw == null || low == null || high == null) refuse("college_automatic_award_bands_score_iff_test_check")
    val test =
      AdmissionTest.fromValue(testRaw)
        ?: throw corruptValue(testRaw, "AdmissionTest", "$BANDS_TABLE.test (row [${id.value}])")
    // Checked before the constructor rather than by it, so a stored row that is
    // out of order names its own id instead of failing inside `Range.init`.
    if (low > high) refuse("college_automatic_award_bands_score_ordered_check")
    return BandScoreAxis.Range(test = test, low = low, high = high)
  }

  /**
   * The read-side half of "a `none_published` row has no bands". The loader
   * refuses to WRITE such a pair; this refuses to SERVE one, because a grid that
   * exists under a school we recorded as publishing nothing is a contradiction a
   * renderer would resolve by showing one side of it.
   *
   * Asked inside [PendingAward.build], which is the only place an
   * [AutomaticAward] is now made: the pair cannot be assembled unchecked.
   */
  private fun assertBandsMatchKind(
    id: AutomaticAwardId,
    awardKind: AutomaticAwardKind,
    bands: List<AutomaticAwardBand>,
  ) {
    if (awardKind == AutomaticAwardKind.NONE_PUBLISHED && bands.isNotEmpty()) {
      throw corruptValue(
        raw = "award_kind=[${AutomaticAwardKind.NONE_PUBLISHED.value}] with [${bands.size}] bands",
        domain = "AutomaticAward",
        location = "$TABLE (row [${id.value}])",
      )
    }
  }
}
