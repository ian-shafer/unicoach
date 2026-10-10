package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.ApplyDeadline
import ed.unicoach.db.models.AwardGuarantee
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FirstChoiceRule
import ed.unicoach.db.models.NationalMeritAward
import ed.unicoach.db.models.NationalMeritAwardId
import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.NationalMeritClaim
import ed.unicoach.db.models.NewNationalMeritAward
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * A prune that would have emptied the whole table
 * ([NationalMeritAwardsDao.deleteExcept] with no surviving row).
 *
 * A NAMED TYPE AND NOT A MESSAGE, the [EmptyAwardPruneException] precedent one
 * corpus along: the ingest loader must tell this refusal from any other write
 * failure to report its own located defect, and it should not have to
 * re-derive the reason from its own local state to do it.
 */
class EmptyNationalMeritPruneException :
  RuntimeException(
    "[college_national_merit_awards] refuses a prune with no surviving row: an empty corpus is a defect to be " +
      "named, not a delete-all",
  )

/**
 * Read and write layer over `college_national_merit_awards` (RFC 243, slice
 * `test/08/the-psat-is-money`): what a named school pays a National Merit
 * Finalist, Semifinalist or Commended student.
 *
 * [AutomaticAwardsDao] is the shape this follows, part for part, because it is
 * the same ROUTE one corpus along -- a hand-typed corpus keyed by
 * `colleges(id)`, which the import run fills, so it loads with
 * `bin/ingest-colleges` and not from a migration (RFC 239 D-C). A stateless
 * `object`, one [SqlSession] per call, the transaction boundary owned by the
 * caller, and a write path that exists for the INGEST LOADER and the fixtures
 * rather than for any request path.
 *
 * WHAT IS *NOT* SHARED IS THE SHAPE OF THE QUALIFICATION, and that is why this
 * is a second table rather than a widening: a standing is not a score. There is
 * no band table here, so every read is ONE statement.
 *
 * EXPIRY IS NOT DECIDED HERE, and neither is the live set. The service owns the
 * five-arm answer and the `expires_on` refusal; this layer returns rows and
 * their dates, so a service can tell "we read this school and our reading is
 * stale" from "we never read this school" -- two answers that send a family to
 * two different places.
 *
 * NEITHER IS THE SIZE OF A READ. The chat tool's college list is capped at
 * `StudentScopedChatTool.MAX_COLLEGE_IDS` (50) at the door the model speaks
 * through, which is where a cap on a request belongs; a second cap here would
 * be a request rule stated in the layer with no request.
 */
object NationalMeritAwardsDao {
  private const val TABLE = "college_national_merit_awards"

  /**
   * Every column [mapRow] reads: the award row plus its two citation urls
   * (RFC 213/228) -- the source an INNER join, because `source_document_id` is
   * NOT NULL and a row that cites nothing cannot exist, and the archive a LEFT
   * join, because a mirror is optional.
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
   * Every award row for [collegeIds] -- the tool's read, for the schools on the
   * student's list.
   *
   * ONE STATEMENT, whatever the number of colleges. Nothing here runs a
   * statement per college.
   *
   * IT DOES NOT FILTER BY ENTRY YEAR, for the reason [AutomaticAwardsDao]
   * states: a school's National Merit page is re-keyed per cycle, and deciding
   * WHICH cycle a student is applying for is a clock question the service owns;
   * a DAO that filtered here would answer it twice, in the layer with no clock.
   *
   * IT DOES NOT FILTER BY RESIDENCY EITHER, and Auburn is why that matters: the
   * service scopes the answer from the money profile, and a DAO that dropped
   * the rows for other residencies could not tell a non-resident "this award
   * exists and is not yours" apart from "this school publishes nothing".
   *
   * An empty [collegeIds] short-circuits without a query, because `IN ()` is
   * not valid SQL and a query for nothing has nothing to answer.
   */
  fun listByColleges(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): Result<List<NationalMeritAward>> {
    val ids = IdList(collegeIds.map { it.value })
    if (ids.isEmpty) return Result.success(emptyList())
    return session.queryList(
      """
      |$SELECT_WITH_CITATIONS
      |WHERE w.college_id IN (${ids.placeholders})
      |ORDER BY w.college_id, w.entry_year DESC, w.residency_basis, w.qualifying_standing
      """.trimMargin(),
      bind = { stmt -> ids.bindFrom(stmt, 1) },
      map = ::mapRow,
    )
  }

  /**
   * One row on its natural key `(college_id, entry_year, residency_basis,
   * qualifying_standing)` -- the ingest loader's write, and the fixtures' and
   * this DAO's suite's. Production rows arrive with `bin/ingest-colleges`, so
   * this path is never on a request.
   *
   * THE KEY IS `NULLS NOT DISTINCT` (the DDL declares it so), which is what
   * makes the one `none_published` row per school, entry year and residency --
   * whose standing is NULL -- collide with itself rather than landing twice.
   *
   * It UPSERTS because a corrected re-read of the same school's same cycle is a
   * correction of that row rather than a second contradictory one, and it
   * reports its [UpsertOutcome] so a re-ingest of an unchanged corpus writes
   * nothing, does not advance `updated_at`, and says so in the run summary --
   * the [AutomaticAwardsDao.upsertAward] shape, through the same
   * `IS DISTINCT FROM` guard.
   *
   * THE CITATION IS RESOLVED FIRST, through the ONE home for that policy:
   * [ExternalDocumentsDao.citationIds] cites and never sweeps, so this path
   * cannot overwrite a swept link status with "we never asked" (RFC 182).
   */
  fun upsert(
    session: SqlSession,
    row: NewNationalMeritAward,
  ): Result<WrittenRow<NationalMeritAwardId>> =
    ExternalDocumentsDao
      .citationIds(session, row.sourceUrl, row.archiveUrl, row.checkedOn)
      .mapCatching { (sourceId, archiveId) ->
        session
          .mutateReturning(
            """
            WITH up AS (
              INSERT INTO $TABLE (
                college_id, entry_year, residency_basis, qualifying_standing,
                award_kind, award_name, award_per_year_usd, award_total_usd, award_years,
                award_as_published, absence_as_published,
                first_choice_required, first_choice_deadline, first_choice_as_published,
                apply_deadline, apply_deadline_as_published,
                publisher, source_document_id, archive_document_id, checked_on, expires_on
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (college_id, entry_year, residency_basis, qualifying_standing) DO UPDATE SET
                award_kind = EXCLUDED.award_kind,
                award_name = EXCLUDED.award_name,
                award_per_year_usd = EXCLUDED.award_per_year_usd,
                award_total_usd = EXCLUDED.award_total_usd,
                award_years = EXCLUDED.award_years,
                award_as_published = EXCLUDED.award_as_published,
                absence_as_published = EXCLUDED.absence_as_published,
                first_choice_required = EXCLUDED.first_choice_required,
                first_choice_deadline = EXCLUDED.first_choice_deadline,
                first_choice_as_published = EXCLUDED.first_choice_as_published,
                apply_deadline = EXCLUDED.apply_deadline,
                apply_deadline_as_published = EXCLUDED.apply_deadline_as_published,
                publisher = EXCLUDED.publisher,
                source_document_id = EXCLUDED.source_document_id,
                archive_document_id = EXCLUDED.archive_document_id,
                checked_on = EXCLUDED.checked_on,
                expires_on = EXCLUDED.expires_on
              WHERE ($TABLE.award_kind, $TABLE.award_name, $TABLE.award_per_year_usd,
                     $TABLE.award_total_usd, $TABLE.award_years, $TABLE.award_as_published,
                     $TABLE.absence_as_published, $TABLE.first_choice_required,
                     $TABLE.first_choice_deadline, $TABLE.first_choice_as_published,
                     $TABLE.apply_deadline, $TABLE.apply_deadline_as_published,
                     $TABLE.publisher, $TABLE.source_document_id,
                     $TABLE.archive_document_id, $TABLE.checked_on, $TABLE.expires_on)
                IS DISTINCT FROM
                    (EXCLUDED.award_kind, EXCLUDED.award_name, EXCLUDED.award_per_year_usd,
                     EXCLUDED.award_total_usd, EXCLUDED.award_years, EXCLUDED.award_as_published,
                     EXCLUDED.absence_as_published, EXCLUDED.first_choice_required,
                     EXCLUDED.first_choice_deadline, EXCLUDED.first_choice_as_published,
                     EXCLUDED.apply_deadline, EXCLUDED.apply_deadline_as_published,
                     EXCLUDED.publisher, EXCLUDED.source_document_id,
                     EXCLUDED.archive_document_id, EXCLUDED.checked_on, EXCLUDED.expires_on)
              RETURNING id, (xmax = 0) AS inserted
            )
            SELECT id, inserted FROM up
            UNION ALL
            SELECT w.id, NULL::boolean
            FROM $TABLE w
            WHERE w.college_id = ? AND w.entry_year = ? AND w.residency_basis = ?
              AND w.qualifying_standing IS NOT DISTINCT FROM ?
              AND NOT EXISTS (SELECT 1 FROM up)
            """.trimIndent(),
            bind = { stmt ->
              // A cursor, never twenty-five hand-typed ordinals
              // ([AutomaticAwardsDao]'s idiom): most slots are nullable, so a
              // one-slot renumber slip writes a row that passes every CHECK and
              // says something else -- here, another school's money.
              var idx = 1
              bindNaturalKey(stmt, idx, row)
              idx += NATURAL_KEY_SLOTS
              stmt.setString(idx++, row.awardKind.value)
              stmt.setStringOrNull(idx++, row.awardName)
              stmt.setIntOrNull(idx++, row.awardPerYearUsd)
              stmt.setIntOrNull(idx++, row.awardTotalUsd)
              stmt.setIntOrNull(idx++, row.awardYears)
              stmt.setStringOrNull(idx++, row.awardAsPublished)
              stmt.setStringOrNull(idx++, row.absenceAsPublished)
              stmt.setBooleanOrNull(idx++, row.firstChoiceRequired)
              stmt.setDateOrNull(idx++, row.firstChoiceDeadline)
              stmt.setStringOrNull(idx++, row.firstChoiceAsPublished)
              stmt.setDateOrNull(idx++, row.applyDeadline)
              stmt.setStringOrNull(idx++, row.applyDeadlineAsPublished)
              stmt.setString(idx++, row.publisher)
              stmt.setObject(idx++, sourceId.value)
              stmt.setObject(idx++, archiveId?.value)
              stmt.setObject(idx++, row.checkedOn)
              stmt.setObject(idx++, row.expiresOn)
              // The natural key again, for the arm that reads back the row the
              // IS DISTINCT FROM guard declined to write.
              bindNaturalKey(stmt, idx, row)
            },
            map = { rs -> WrittenRow(NationalMeritAwardId(UUID.fromString(rs.getString("id"))), rs.upsertOutcome()) },
            // The document ids came from `citationIds` one statement ago, so a
            // 23503 here means either a document row was deleted under us or --
            // the one this corpus actually risks -- `college_id` names no
            // college, which is the mis-keyed row the loader exists to refuse.
            mapError = { e -> mapReferenceWriteError(e, location(row)) },
          ).getOrThrow()
      }

  /**
   * Removes every row this table holds EXCEPT [keep] -- the re-ingest prune, so
   * a school dropped from the committed corpus stops being answered for.
   *
   * A PRUNE WITH NOTHING KEPT IS REFUSED, with a typed
   * [EmptyNationalMeritPruneException], so the loader reads WHICH refusal this
   * is rather than re-deriving it from its own state or matching the sentence.
   * An empty corpus is a defect to be named, not a delete-all.
   */
  fun deleteExcept(
    session: SqlSession,
    keep: Collection<NationalMeritAwardId>,
  ): Result<Int> {
    val kept = IdList(keep.map { it.value })
    if (kept.isEmpty) return Result.failure(EmptyNationalMeritPruneException())
    return session.execute(
      "DELETE FROM $TABLE WHERE id NOT IN (${kept.placeholders})",
      bind = { stmt -> kept.bindFrom(stmt, 1) },
    )
  }

  /** How many slots [bindNaturalKey] fills, so the cursor advances past them without counting `setObject` calls. */
  private const val NATURAL_KEY_SLOTS = 4

  /**
   * The natural key, bound from ONE place because the statement binds it TWICE
   * -- once to insert and once for the arm that reads back a suppressed write.
   * Two hand-written copies is two chances for the halves to name different
   * rows, and the second copy is the one no test would notice.
   */
  private fun bindNaturalKey(
    stmt: PreparedStatement,
    firstIndex: Int,
    row: NewNationalMeritAward,
  ) {
    var idx = firstIndex
    stmt.setObject(idx++, row.collegeId.value)
    stmt.setInt(idx++, row.entryYear.firstCalendarYear)
    stmt.setString(idx++, row.residencyBasis.value)
    stmt.setStringOrNull(idx, row.qualifyingStanding?.value)
  }

  private fun location(row: NewNationalMeritAward): String =
    "[$TABLE] row for college [${row.collegeId.value}] entry year [${row.entryYear.label}] " +
      "residency [${row.residencyBasis.value}] standing [${row.qualifyingStanding?.value}] could not be written"

  private fun mapRow(rs: ResultSet): NationalMeritAward {
    val id = NationalMeritAwardId(UUID.fromString(rs.getString("id")))
    val entryYear = AcademicYear(rs.getInt("entry_year"))
    val location = "$TABLE (row [${id.value}] entry year [${entryYear.label}])"
    val residencyRaw = rs.getString("residency_basis")
    return NationalMeritAward(
      id = id,
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      entryYear = entryYear,
      residencyBasis =
        ResidencyBasis.fromValue(residencyRaw)
          ?: throw corruptValue(residencyRaw, "ResidencyBasis", "$location.residency_basis"),
      claim = claimOf(rs, location),
      publisher = rs.getString("publisher"),
      sourceUrl = rs.getString("source_url"),
      archiveUrl = rs.getString("archive_url"),
      checkedOn = rs.getDate("checked_on").toLocalDate(),
      expiresOn = rs.getDate("expires_on").toLocalDate(),
    )
  }

  /**
   * WHICH claim this row states, read from `award_kind` and the cells that kind
   * owns.
   *
   * THE CROSSED ROWS ARE LOCATED REFUSALS HERE, because [NationalMeritClaim] has
   * arms: a paying row with no standing or no words, and an absence row with no
   * words, are states the schema refuses
   * (`..._standing_iff_award_check`, `..._claim_has_words_check`,
   * `..._absence_has_words_check`) and the read model can no longer express --
   * so a row that reached this mapper carrying one is corruption, and it is
   * named with its own id and column rather than resolved to whichever half is
   * present.
   *
   * The cells an absence row may not carry are not re-checked here: the arm
   * simply does not read them, which is what `..._none_published_is_empty_check`
   * guarantees on the way in.
   */
  private fun claimOf(
    rs: ResultSet,
    location: String,
  ): NationalMeritClaim {
    val kindRaw = rs.getString("award_kind")
    val kind =
      NationalMeritAwardKind.fromValue(kindRaw)
        ?: throw corruptValue(kindRaw, "NationalMeritAwardKind", "$location.award_kind")
    if (kind == NationalMeritAwardKind.NONE_PUBLISHED) {
      return NationalMeritClaim.NonePublished(
        asPublished =
          rs.getString("absence_as_published")
            ?: throw corruptValue(
              raw = "award_kind=[$kindRaw] with no absence_as_published",
              domain = "NationalMeritClaim.NonePublished",
              location = location,
            ),
      )
    }
    val standingRaw =
      rs.getString("qualifying_standing")
        ?: throw corruptValue(
          raw = "award_kind=[$kindRaw] with no qualifying_standing",
          domain = "NationalMeritClaim.Paying",
          location = location,
        )
    val applyDeadline = rs.getDate("apply_deadline")?.toLocalDate()
    val applyDeadlineWords = rs.getString("apply_deadline_as_published")
    return NationalMeritClaim.Paying(
      guarantee =
        when (kind) {
          NationalMeritAwardKind.AUTOMATIC -> AwardGuarantee.AUTOMATIC

          NationalMeritAwardKind.COMPETITIVE -> AwardGuarantee.COMPETITIVE

          // Answered above, and named rather than left to an `else` so a fourth
          // code cannot compile until this mapper says which arm it is.
          NationalMeritAwardKind.NONE_PUBLISHED -> throw IllegalStateException("$location: unreachable")
        },
      standing =
        QualifyingStanding.fromValue(standingRaw)
          ?: throw corruptValue(standingRaw, "QualifyingStanding", "$location.qualifying_standing"),
      asPublished =
        rs.getString("award_as_published")
          ?: throw corruptValue(
            raw = "award_kind=[$kindRaw] with no award_as_published",
            domain = "NationalMeritClaim.Paying",
            location = location,
          ),
      awardName = rs.getString("award_name"),
      perYearUsd = rs.getIntOrNull("award_per_year_usd"),
      totalUsd = rs.getIntOrNull("award_total_usd"),
      years = rs.getIntOrNull("award_years"),
      firstChoice = firstChoiceOf(rs, location),
      applyDeadline =
        applyDeadline?.let {
          ApplyDeadline(
            on = it,
            asPublished =
              applyDeadlineWords
                ?: throw corruptValue(
                  raw = "apply_deadline=[$it] with no apply_deadline_as_published",
                  domain = "ApplyDeadline",
                  location = location,
                ),
          )
        },
    )
  }

  /**
   * The school's first-choice rule, read from the three columns TOGETHER
   * because [FirstChoiceRule] is one value -- the [AutomaticAwardsDao] mapper's
   * idiom for its own cut-off trio.
   *
   * The schema's trio CHECK already refuses a flag with no words and words with
   * no flag, so a row carrying one half is corruption and is surfaced with a
   * LOCATED refusal -- never silently resolved to whichever half is present,
   * because both resolutions state a commitment the school did not ask for.
   */
  private fun firstChoiceOf(
    rs: ResultSet,
    location: String,
  ): FirstChoiceRule? {
    val required = rs.getBoolean("first_choice_required").takeUnless { rs.wasNull() }
    val deadline = rs.getDate("first_choice_deadline")?.toLocalDate()
    val asPublished = rs.getString("first_choice_as_published")
    if (required == null && asPublished == null) {
      if (deadline == null) return null
      throw corruptValue(
        raw = "first_choice_deadline=[$deadline] with no rule",
        domain = "FirstChoiceRule",
        location = location,
      )
    }
    if (required == null || asPublished == null) {
      throw corruptValue(
        raw = "first_choice_required=[$required] first_choice_as_published=[$asPublished]",
        domain = "FirstChoiceRule",
        location = location,
      )
    }
    if (!required) {
      if (deadline != null) {
        throw corruptValue(
          raw = "first_choice_required=[false] with first_choice_deadline=[$deadline]",
          domain = "FirstChoiceRule",
          location = location,
        )
      }
      return FirstChoiceRule.NotRequired(asPublished = asPublished)
    }
    return FirstChoiceRule.Required(deadline = deadline, asPublished = asPublished)
  }
}
