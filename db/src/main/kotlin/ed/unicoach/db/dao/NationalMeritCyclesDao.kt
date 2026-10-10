package ed.unicoach.db.dao

import ed.unicoach.db.models.AlternateEntry
import ed.unicoach.db.models.CommendedCutoffBasis
import ed.unicoach.db.models.CyclePublication
import ed.unicoach.db.models.CycleWindow
import ed.unicoach.db.models.NationalMeritCycle
import ed.unicoach.db.models.NationalMeritFunnel
import ed.unicoach.db.models.NewNationalMeritCycle
import ed.unicoach.db.models.NmscOwnAward
import ed.unicoach.db.models.SelectionIndexScale
import ed.unicoach.db.models.SemifinalistCutoffBasis
import java.sql.ResultSet

/**
 * Read layer over the `national_merit_cycles` reference table (RFC 243, slice
 * `test/08/the-psat-is-money`): the National Merit competition's OWN dated
 * facts, one row per competition year, seeded by migration from the committed
 * corpus under `db/seed/national-merit/`.
 *
 * [AdmissionTestStateProgrammeDao] is the shape this follows, because it is the
 * same ROUTE one corpus along -- a small migration-seeded reference table, a
 * stateless `object`, one [SqlSession] per call, the transaction boundary owned
 * by the caller, and an [upsert] that exists for the test fixtures rather than
 * for any production write. Route A matters here more than it looks: `bin/test`
 * does `db-reset` and never ingests, so these rows are present in every
 * database by construction and "what this test is and when it is" can never
 * depend on an import having run.
 *
 * EXPIRY AND LIVENESS ARE NOT DECIDED HERE, exactly as [AutomaticAwardsDao]
 * states for its own corpus: this layer returns rows and their dates, and the
 * service that wants today's answer says so. There is deliberately no `today`
 * parameter and no `WHERE expires_on > ?` -- a service that could not see a
 * stale row could not tell a family "we read this and our reading is stale"
 * apart from "we never read it", and those two send a family to two different
 * places.
 *
 * THERE IS NO CUTOFF TO READ. The table stores HOW each bar is set and NMSC's
 * own sentence saying the qualifying score is not published in advance; no
 * column here holds a number a family could act on as her bar.
 */
object NationalMeritCyclesDao {
  private const val TABLE = "national_merit_cycles"

  /**
   * Every column [mapRow] reads: the cycle row plus FOUR citation urls -- the
   * sponsor's page and mirror, and the administrator's page and mirror
   * (RFC 213/228).
   *
   * FOUR JOINS AND NOT TWO, because this row has two publishers and no page has
   * all of it (see [NationalMeritCycle]). Each source is an INNER join, because
   * both `*_document_id` columns are NOT NULL and a row that cites nothing
   * cannot exist; each archive is a LEFT join, because a mirror is optional.
   *
   * ONE HOME, because a read that projected a different set of columns, or
   * joined a different set of documents, would not be readable by the one
   * mapper.
   */
  private val SELECT_WITH_CITATIONS =
    """
    SELECT c.*,
           s.url AS source_url, a.url AS archive_url,
           ads.url AS administrator_source_url, ada.url AS administrator_archive_url
    FROM $TABLE c
    JOIN external_documents s ON s.id = c.source_document_id
    LEFT JOIN external_documents a ON a.id = c.archive_document_id
    JOIN external_documents ads ON ads.id = c.administrator_document_id
    LEFT JOIN external_documents ada ON ada.id = c.administrator_archive_document_id
    """.trimIndent()

  /**
   * The cycle for one competition year, or null where we hold none.
   *
   * THE ARGUMENT IS THE STUDENT'S OWN GRADUATION YEAR. NMSC names the 2028
   * competition for the class of 2028, which is exactly
   * `students.expected_high_school_graduation_year`, so a caller passes her
   * stored year and infers nothing.
   *
   * NULL IS AN ANSWER AND NOT A FAULT: "we hold no dates for your year" is what
   * the tool's `not_curated` arm says, and it names no date. A corpus that
   * covers the cycles their publishers have described cannot cover a year
   * nobody has described yet.
   */
  fun findByCompetitionYear(
    session: SqlSession,
    competitionYear: Int,
  ): Result<NationalMeritCycle?> =
    session
      .queryOne(
        "$SELECT_WITH_CITATIONS\nWHERE c.competition_year = ?",
        bind = { stmt -> stmt.setInt(1, competitionYear) },
        map = ::mapRow,
      ).orNullOnNotFound()

  /**
   * Every cycle we hold, newest competition first.
   *
   * ONE STATEMENT OVER THE WHOLE TABLE, which is a handful of rows and has no
   * index by design (the DDL says why). The committed-corpus guard reads it,
   * and so does the base-rate answer: NMSC publishes a funnel only for the
   * competition in progress, so a caller that needs figures walks this list to
   * the most recent cycle that publishes one and NAMES that year when it speaks
   * them.
   */
  fun list(session: SqlSession): Result<List<NationalMeritCycle>> =
    session.queryList(
      "$SELECT_WITH_CITATIONS\nORDER BY c.competition_year DESC",
      bind = {},
      map = ::mapRow,
    )

  /**
   * One cycle row on its natural key, the competition year -- for the test
   * fixtures and for this DAO's own suite, and for nothing else. Production
   * rows arrive by migration (Route A), so this path is never on a request.
   *
   * It UPSERTS because a corrected re-read of the same competition year is a
   * correction of that row rather than a second contradictory one -- the same
   * natural key the generated seed writes against.
   *
   * THE CITATIONS ARE RESOLVED FIRST, both of them, through the ONE home for
   * that policy: [ExternalDocumentsDao.citationIds] cites and never sweeps, so
   * this path cannot overwrite a swept link status with "we never asked"
   * (RFC 182). TWO CALLS AND NOT ONE, because the two publishers are two
   * citations; `checked_on` is the evidence both state (RFC 220) -- it is the
   * day a person opened these pages and wrote this row from what they served.
   */
  fun upsert(
    session: SqlSession,
    row: NewNationalMeritCycle,
  ): Result<NationalMeritCycle> =
    ExternalDocumentsDao
      .citationIds(session, row.sponsor.sourceUrl, row.sponsor.archiveUrl, row.checkedOn)
      .mapCatching { (sourceId, archiveId) ->
        val (administratorId, administratorArchiveId) =
          ExternalDocumentsDao
            .citationIds(session, row.administrator.sourceUrl, row.administrator.archiveUrl, row.checkedOn)
            .getOrThrow()
        val dated = row.window as? CycleWindow.Dated
        session
          .mutateReturning(
            """
            INSERT INTO $TABLE (
              competition_year, qualifying_grade,
              window_opens_on, window_closes_on, window_as_published,
              alternate_entry_deadline, alternate_entry_contact, alternate_entry_as_published,
              selection_index_low, selection_index_high,
              semifinalist_cutoff_basis, commended_cutoff_basis, cutoff_as_published,
              score_visibility_as_published,
              test_takers, met_entry_requirements, high_scorers_screened, commended,
              semifinalists, finalists, winners, funnel_as_published,
              nmsc_award_usd, nmsc_award_as_published, sponsor_cap_as_published,
              publisher, source_document_id, archive_document_id,
              administrator_publisher, administrator_document_id, administrator_archive_document_id,
              checked_on, expires_on
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (competition_year) DO UPDATE SET
              qualifying_grade = EXCLUDED.qualifying_grade,
              window_opens_on = EXCLUDED.window_opens_on,
              window_closes_on = EXCLUDED.window_closes_on,
              window_as_published = EXCLUDED.window_as_published,
              alternate_entry_deadline = EXCLUDED.alternate_entry_deadline,
              alternate_entry_contact = EXCLUDED.alternate_entry_contact,
              alternate_entry_as_published = EXCLUDED.alternate_entry_as_published,
              selection_index_low = EXCLUDED.selection_index_low,
              selection_index_high = EXCLUDED.selection_index_high,
              semifinalist_cutoff_basis = EXCLUDED.semifinalist_cutoff_basis,
              commended_cutoff_basis = EXCLUDED.commended_cutoff_basis,
              cutoff_as_published = EXCLUDED.cutoff_as_published,
              score_visibility_as_published = EXCLUDED.score_visibility_as_published,
              test_takers = EXCLUDED.test_takers,
              met_entry_requirements = EXCLUDED.met_entry_requirements,
              high_scorers_screened = EXCLUDED.high_scorers_screened,
              commended = EXCLUDED.commended,
              semifinalists = EXCLUDED.semifinalists,
              finalists = EXCLUDED.finalists,
              winners = EXCLUDED.winners,
              funnel_as_published = EXCLUDED.funnel_as_published,
              nmsc_award_usd = EXCLUDED.nmsc_award_usd,
              nmsc_award_as_published = EXCLUDED.nmsc_award_as_published,
              sponsor_cap_as_published = EXCLUDED.sponsor_cap_as_published,
              publisher = EXCLUDED.publisher,
              source_document_id = EXCLUDED.source_document_id,
              archive_document_id = EXCLUDED.archive_document_id,
              administrator_publisher = EXCLUDED.administrator_publisher,
              administrator_document_id = EXCLUDED.administrator_document_id,
              administrator_archive_document_id = EXCLUDED.administrator_archive_document_id,
              checked_on = EXCLUDED.checked_on,
              expires_on = EXCLUDED.expires_on
            RETURNING *,
              (SELECT e.url FROM external_documents e WHERE e.id = $TABLE.source_document_id) AS source_url,
              (SELECT e.url FROM external_documents e WHERE e.id = $TABLE.archive_document_id) AS archive_url,
              (SELECT e.url FROM external_documents e WHERE e.id = $TABLE.administrator_document_id)
                AS administrator_source_url,
              (SELECT e.url FROM external_documents e WHERE e.id = $TABLE.administrator_archive_document_id)
                AS administrator_archive_url
            """.trimIndent(),
            bind = { stmt ->
              // A cursor, never thirty-three hand-typed ordinals
              // ([AdmissionTestStateProgrammeDao]'s idiom): most slots are
              // nullable, so a one-slot renumber slip writes a row that passes
              // every CHECK and says something else -- here, another cycle's
              // deadline.
              var idx = 1
              stmt.setInt(idx++, row.competitionYear)
              stmt.setInt(idx++, row.qualifyingGrade)
              // The dates come off the ONE arm that has them: CycleWindow
              // exposes no interface-level nullable date, precisely so this
              // cannot be written against a flat (opens, closes, words) clump.
              stmt.setDateOrNull(idx++, dated?.opensOn)
              stmt.setDateOrNull(idx++, dated?.closesOn)
              stmt.setString(idx++, row.window.asPublished)
              stmt.setDateOrNull(idx++, row.alternateEntry?.deadline)
              stmt.setStringOrNull(idx++, row.alternateEntry?.contact)
              stmt.setStringOrNull(idx++, row.alternateEntry?.asPublished)
              stmt.setInt(idx++, row.selectionIndex.low)
              stmt.setInt(idx++, row.selectionIndex.high)
              stmt.setString(idx++, row.semifinalistCutoff.value)
              stmt.setString(idx++, row.commendedCutoff.value)
              stmt.setString(idx++, row.cutoffAsPublished)
              stmt.setString(idx++, row.scoreVisibilityAsPublished)
              stmt.setIntOrNull(idx++, row.funnel.testTakers)
              stmt.setIntOrNull(idx++, row.funnel.metEntryRequirements)
              stmt.setIntOrNull(idx++, row.funnel.highScorersScreened)
              stmt.setIntOrNull(idx++, row.funnel.commended)
              stmt.setIntOrNull(idx++, row.funnel.semifinalists)
              stmt.setIntOrNull(idx++, row.funnel.finalists)
              stmt.setIntOrNull(idx++, row.funnel.winners)
              stmt.setString(idx++, row.funnel.asPublished)
              stmt.setIntOrNull(idx++, row.nmscAward?.amountUsd)
              stmt.setStringOrNull(idx++, row.nmscAward?.asPublished)
              stmt.setStringOrNull(idx++, row.sponsorCapAsPublished)
              stmt.setString(idx++, row.sponsor.publisher)
              stmt.setObject(idx++, sourceId.value)
              stmt.setObject(idx++, archiveId?.value)
              stmt.setString(idx++, row.administrator.publisher)
              stmt.setObject(idx++, administratorId.value)
              stmt.setObject(idx++, administratorArchiveId?.value)
              stmt.setObject(idx++, row.checkedOn)
              stmt.setObject(idx, row.expiresOn)
            },
            map = ::mapRow,
            // All four document ids came from `citationIds` one statement ago,
            // so a 23503 here means a document row was deleted under us; every
            // other refusal on this table is a CHECK, and the constraint name
            // the mapper carries is what locates it.
            mapError = { e ->
              mapReferenceWriteError(e, "[$TABLE] row for competition year [${row.competitionYear}] could not be written")
            },
          ).getOrThrow()
      }

  private fun mapRow(rs: ResultSet): NationalMeritCycle {
    val competitionYear = rs.getInt("competition_year")
    val location = "$TABLE (competition_year [$competitionYear])"
    val semifinalistRaw = rs.getString("semifinalist_cutoff_basis")
    val commendedRaw = rs.getString("commended_cutoff_basis")
    return NationalMeritCycle(
      competitionYear = competitionYear,
      qualifyingGrade = rs.getInt("qualifying_grade"),
      window = windowOf(rs, location),
      alternateEntry = alternateEntryOf(rs, location),
      selectionIndex = SelectionIndexScale(rs.getInt("selection_index_low"), rs.getInt("selection_index_high")),
      semifinalistCutoff =
        SemifinalistCutoffBasis.fromValue(semifinalistRaw)
          ?: throw corruptValue(semifinalistRaw, "SemifinalistCutoffBasis", "$location.semifinalist_cutoff_basis"),
      commendedCutoff =
        CommendedCutoffBasis.fromValue(commendedRaw)
          ?: throw corruptValue(commendedRaw, "CommendedCutoffBasis", "$location.commended_cutoff_basis"),
      cutoffAsPublished = rs.getString("cutoff_as_published"),
      scoreVisibilityAsPublished = rs.getString("score_visibility_as_published"),
      funnel =
        NationalMeritFunnel(
          testTakers = rs.getIntOrNull("test_takers"),
          metEntryRequirements = rs.getIntOrNull("met_entry_requirements"),
          highScorersScreened = rs.getIntOrNull("high_scorers_screened"),
          commended = rs.getIntOrNull("commended"),
          semifinalists = rs.getIntOrNull("semifinalists"),
          finalists = rs.getIntOrNull("finalists"),
          winners = rs.getIntOrNull("winners"),
          asPublished = rs.getString("funnel_as_published"),
        ),
      nmscAward = nmscAwardOf(rs, location),
      sponsorCapAsPublished = rs.getString("sponsor_cap_as_published"),
      sponsor =
        CyclePublication(
          publisher = rs.getString("publisher"),
          sourceUrl = rs.getString("source_url"),
          archiveUrl = rs.getString("archive_url"),
        ),
      administrator =
        CyclePublication(
          publisher = rs.getString("administrator_publisher"),
          sourceUrl = rs.getString("administrator_source_url"),
          archiveUrl = rs.getString("administrator_archive_url"),
        ),
      checkedOn = rs.getDate("checked_on").toLocalDate(),
      expiresOn = rs.getDate("expires_on").toLocalDate(),
    )
  }

  /**
   * The window, read from the three columns TOGETHER because [CycleWindow] is
   * one value.
   *
   * The schema's pair CHECK already refuses one date without the other, so a
   * row carrying half a window is corruption and is surfaced with a LOCATED
   * refusal -- never silently resolved to whichever half is present, because
   * both resolutions tell a family a day no publisher named.
   */
  private fun windowOf(
    rs: ResultSet,
    location: String,
  ): CycleWindow {
    val opensOn = rs.getDate("window_opens_on")?.toLocalDate()
    val closesOn = rs.getDate("window_closes_on")?.toLocalDate()
    val asPublished = rs.getString("window_as_published")
    return when {
      opensOn == null && closesOn == null -> {
        CycleWindow.Described(asPublished)
      }

      opensOn != null && closesOn != null -> {
        CycleWindow.Dated(opensOn, closesOn, asPublished)
      }

      else -> {
        throw corruptValue(
          raw = "window_opens_on=[$opensOn] window_closes_on=[$closesOn]",
          domain = "CycleWindow",
          location = location,
        )
      }
    }
  }

  /**
   * The alternate entry route, read from the three columns together for
   * [windowOf]'s reason: the schema's trio CHECK refuses a deadline or an
   * address with no publisher's words, so those rows are corruption here.
   *
   * THE WORDS ARE WHAT MAKES THE VALUE EXIST, and the date does not: NMSC
   * describes the route for a cycle it has not yet dated, and a reader that
   * keyed on the deadline would hide a live route behind a missing day.
   */
  private fun alternateEntryOf(
    rs: ResultSet,
    location: String,
  ): AlternateEntry? {
    val deadline = rs.getDate("alternate_entry_deadline")?.toLocalDate()
    val contact = rs.getString("alternate_entry_contact")
    val asPublished = rs.getString("alternate_entry_as_published")
    if (asPublished == null) {
      if (deadline == null && contact == null) return null
      throw corruptValue(
        raw = "alternate_entry_deadline=[$deadline] alternate_entry_contact=[$contact]",
        domain = "AlternateEntry",
        location = location,
      )
    }
    return AlternateEntry(deadline = deadline, contact = contact, asPublished = asPublished)
  }

  /**
   * NMSC's own award, read from the two columns TOGETHER for [windowOf]'s
   * reason: `national_merit_cycles_nmsc_award_pair_check` admits a figure only
   * beside the sentence it was read from, so a priced award with no words is
   * corruption and is LOCATED rather than resolved to the half that is there.
   *
   * THE READ IT REPLACED THREW THE DOLLAR FIGURE AWAY. `nmsc_award_usd` was
   * read only inside a `?.let` on the words, so such a row answered `null` with
   * no refusal and no log -- while the three sibling readers in this file refuse
   * that exact shape with a located error, and the schema calls it corruption. A
   * discarded dollar figure with no log is the worst failure this corpus can
   * have.
   */
  private fun nmscAwardOf(
    rs: ResultSet,
    location: String,
  ): NmscOwnAward? {
    val amountUsd = rs.getIntOrNull("nmsc_award_usd")
    val asPublished = rs.getString("nmsc_award_as_published")
    if (asPublished == null) {
      if (amountUsd == null) return null
      throw corruptValue(
        raw = "nmsc_award_usd=[$amountUsd] with no nmsc_award_as_published",
        domain = "NmscOwnAward",
        location = location,
      )
    }
    return NmscOwnAward(amountUsd = amountUsd, asPublished = asPublished)
  }
}
