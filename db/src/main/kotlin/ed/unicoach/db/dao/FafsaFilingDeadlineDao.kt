package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.db.models.AidDeadlineKind
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.FafsaFilingDeadline
import ed.unicoach.db.models.FafsaFilingDeadlineId
import ed.unicoach.db.models.NewFafsaFilingDeadline
import ed.unicoach.db.models.PublishedDeadline
import java.sql.ResultSet
import java.util.UUID

/**
 * Read layer over the `fafsa_filing_deadlines` reference table (RFC 215): the
 * federal and state FAFSA dates for one award year, seeded by migration from the
 * committed CSV the `fafsa/02` generator writes.
 *
 * [AdmissionTestStateProgrammeDao] is the shape this follows, because it is the
 * same shape one table along -- a small migration-seeded reference table, a
 * stateless `object`, one [SqlSession] per call, the transaction boundary owned
 * by the caller, and an [upsert] that exists for fixtures and for the
 * generator's own test rather than for any request path.
 *
 * EXPIRY IS NOT DECIDED HERE. `fafsa/03`'s `FafsaDeadlineService` owns the
 * three-arm refusal past `expires_on`; this layer returns rows and their dates,
 * so a caller that wants today's answer says so.
 */
object FafsaFilingDeadlineDao {
  private const val TABLE = "fafsa_filing_deadlines"

  /**
   * Every column [mapRow] reads: the deadline row itself plus its two citation
   * urls (RFC 213) -- the source an INNER join, because `source_document_id` is
   * NOT NULL and a row that cites nothing cannot exist, and the archive a LEFT
   * join, because most rows needed no archived address.
   *
   * ONE HOME, because a read that projected a different set of columns, or
   * joined a different pair of documents, would not be readable by the one
   * mapper. Each read below adds only what actually differs: its filter and its
   * order.
   */
  private val SELECT_WITH_CITATIONS =
    """
    SELECT d.*, s.url AS source_url, a.url AS archive_url
    FROM $TABLE d
    JOIN external_documents s ON s.id = d.source_document_id
    LEFT JOIN external_documents a ON a.id = d.archive_document_id
    """.trimIndent()

  /**
   * Every row of ONE cycle, federal rows first.
   *
   * THE YEAR IS THE ARGUMENT AND NOT AN OPTION, because the award year is the
   * row's identity: state deadlines recur annually, so an unfiltered read
   * returns last cycle's 1 March beside this cycle's and nothing downstream can
   * tell them apart. There is deliberately no `listAll` FOR ANY RENDERER:
   * every door reads only what `FafsaDeadlineService` returns. That service is
   * the ONE reader that may see all cycles, because it is the one that DECIDES
   * the live set (D23); its whole-table read is [listAll].
   *
   * IT COSTS ONE STATEMENT, whatever the size of the corpus -- about sixty rows
   * per cycle, read whole. Nothing here runs a statement per row.
   *
   * THE TWO JOINS ARE THE CITATION (RFC 213), exactly as on the sibling table;
   * they and the projection live in [SELECT_WITH_CITATIONS], which argues them.
   *
   * `state ASC NULLS FIRST` is the federal-rows-lead order the public page
   * renders, and NULLS FIRST is explicit because PostgreSQL's ascending default
   * is NULLS LAST -- the one place in this file where the default is the wrong
   * answer.
   */
  fun listByAwardYear(
    session: SqlSession,
    awardYear: AcademicYear,
  ): Result<List<FafsaFilingDeadline>> =
    session
      .queryList(
        """
        |$SELECT_WITH_CITATIONS
        |WHERE d.award_year = ?
        |ORDER BY d.state ASC NULLS FIRST, d.program, d.event
        """.trimMargin(),
        bind = { stmt -> stmt.setInt(1, awardYear.firstCalendarYear) },
        map = ::mapRow,
      )

  /**
   * Every row, every cycle -- the read `FafsaDeadlineService` uses to decide the
   * live set (D23), and deliberately the ONLY reader that may see all cycles.
   *
   * The newest-cycle-per-jurisdiction decision is a computation over the whole
   * table, so the honest cost is one statement reading it whole. The table is
   * append-only and reference-sized -- tens of rows per cycle, and no number
   * written here, because an append-only corpus makes any count in a comment
   * wrong at the next refresh. The alternative -- one statement
   * per jurisdiction to find its newest year, then another per winner -- is one
   * statement per jurisdiction for a reference table, the exact shape every
   * other read here refuses.
   *
   * `state ASC NULLS FIRST, award_year DESC, program, event` is the DAO order
   * the service hands the page and the coach: federal rows lead, and within a
   * jurisdiction the newest cycle's rows come first. Nothing downstream may
   * re-sort a served row; the service already returned them in this order.
   */
  fun listAll(session: SqlSession): Result<List<FafsaFilingDeadline>> =
    session.queryList(
      """
      |$SELECT_WITH_CITATIONS
      |ORDER BY d.state ASC NULLS FIRST, d.award_year DESC, d.program, d.event
      """.trimMargin(),
      bind = {},
      map = ::mapRow,
    )

  /**
   * One row, on the natural key `(award_year, state, program, event)` -- for the
   * test fixtures, for this DAO's own suite and for the `fafsa/02` generator's
   * test, and for nothing else. Production rows arrive by migration, so this
   * path is never on a request.
   *
   * It UPSERTS because a corrected re-read of the SAME cycle's same event is a
   * correction of that row rather than a second contradictory one -- the key the
   * generated seed writes against. The index is `NULLS NOT DISTINCT`, so two
   * FEDERAL rows (`state IS NULL`) for one event collide here too, which is the
   * whole reason it is an index and not a plain unique constraint.
   *
   * IT WRITES TWO TABLES AND EXPECTS A TRANSACTION. The citation documents are
   * written first, so the caller's transaction is what makes the pair atomic: a
   * refused deadline row takes its documents with it. A caller in autocommit
   * gets two committed documents and no deadline -- untidy, not wrong, since a
   * document row is a url and its link state whoever cites it.
   */
  fun upsert(
    session: SqlSession,
    row: NewFafsaFilingDeadline,
  ): Result<FafsaFilingDeadline> =
    // THE CITATION IS RESOLVED FIRST, through the ONE home for that policy:
    // [ExternalDocumentsDao.citationIds] cites and never sweeps, so this path
    // cannot overwrite a known link status with "we never asked" (RFC 182). It
    // is shared with the state-programme corpus rather than copied from it --
    // the same rule RFC 213 states for a vocabulary, applied to code.
    //
    // `checked_on` IS THE EVIDENCE IT STATES (RFC 220): the day a person opened
    // these pages and wrote this row from what they served. A citation with no
    // evidence at all can no longer be stored, and this path has that one fact.
    ExternalDocumentsDao
      .citationIds(session, row.sourceUrl, row.archiveUrl, row.checkedOn)
      .mapCatching { (sourceId, archiveId) ->
        session
          .mutateReturning(
            """
            INSERT INTO $TABLE (
              award_year, state, program, event, deadline_kind, deadline_date,
              deadline_as_published, publisher, source_document_id,
              archive_document_id, checked_on, expires_on
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (award_year, state, program, event) DO UPDATE SET
              deadline_kind = EXCLUDED.deadline_kind,
              deadline_date = EXCLUDED.deadline_date,
              deadline_as_published = EXCLUDED.deadline_as_published,
              publisher = EXCLUDED.publisher,
              source_document_id = EXCLUDED.source_document_id,
              archive_document_id = EXCLUDED.archive_document_id,
              checked_on = EXCLUDED.checked_on,
              expires_on = EXCLUDED.expires_on
            RETURNING *,
              (SELECT e.url FROM external_documents e WHERE e.id = $TABLE.source_document_id) AS source_url,
              (SELECT e.url FROM external_documents e WHERE e.id = $TABLE.archive_document_id) AS archive_url
            """.trimIndent(),
            bind = { stmt ->
              // A cursor, never twelve hand-typed ordinals
              // ([AdmissionTestStateProgrammeDao]'s idiom): three slots are
              // nullable, so a one-slot renumber slip writes a row that passes
              // every CHECK and says something else.
              var idx = 1
              stmt.setInt(idx++, row.awardYear.firstCalendarYear)
              stmt.setStringOrNull(idx++, row.jurisdiction.uspsCode)
              stmt.setString(idx++, row.program)
              stmt.setString(idx++, row.event.value)
              stmt.setString(idx++, row.deadline.kind.value)
              // The day comes off the ONE arm that has one: PublishedDeadline
              // exposes no interface-level nullable date, precisely so this
              // line cannot be written against a flat (kind, date) pair.
              stmt.setObject(idx++, (row.deadline as? PublishedDeadline.OnDate)?.date)
              stmt.setString(idx++, row.deadline.deadlineAsPublished)
              stmt.setString(idx++, row.publisher)
              stmt.setObject(idx++, sourceId.value)
              stmt.setObject(idx++, archiveId?.value)
              stmt.setObject(idx++, row.checkedOn)
              stmt.setObject(idx, row.expiresOn)
            },
            map = ::mapRow,
            // The state column still has no foreign key (the DDL says why and
            // which slice adds it), so a bad state is a CHECK violation like any
            // other and the mapper's constraint name is what locates it.
            // The prose serves the FOREIGN-KEY arm, which is the one arm this
            // path can still fail on late: both ids came from `citationIds`
            // two statements ago, so a 23503 here means a document row was
            // deleted under us. A CHECK or the natural-key index raises its own
            // SQLSTATE, and the constraint NAME plus PostgreSQL's "Failing row
            // contains" are what locate those.
            mapError = { e ->
              mapReferenceWriteError(
                e,
                "[$TABLE] row for [${row.jurisdiction.uspsCode ?: "federal"}] " +
                  "program [${row.program}] event [${row.event.value}] in [${row.awardYear.label}] could not be written",
              )
            },
          ).getOrThrow()
      }

  private fun mapRow(rs: ResultSet): FafsaFilingDeadline {
    val state = rs.getString("state")
    val awardYear = AcademicYear(rs.getInt("award_year"))
    val program = rs.getString("program")
    val location = "$TABLE (award_year [${awardYear.label}] state [${state ?: "federal"}] program [$program])"
    val eventRaw = rs.getString("event")
    return FafsaFilingDeadline(
      id = FafsaFilingDeadlineId(UUID.fromString(rs.getString("id"))),
      awardYear = awardYear,
      // NULL IS FEDERAL, and this is the one place that knows it. A stored code
      // that is not one of the 59 published USPS codes is row corruption and is
      // LOCATED here rather than resolved: the schema's regex admits 676
      // strings and there is no foreign key to `us_states` yet (the DDL says
      // which slice adds it), so this mapper is the read side's membership
      // check.
      jurisdiction =
        state?.let {
          DeadlineJurisdiction.State.of(it)
            ?: throw corruptValue(it, "UsStateCodes", "$location.state")
        } ?: DeadlineJurisdiction.Federal,
      program = program,
      event =
        AidDeadlineEvent.fromValue(eventRaw)
          ?: throw corruptValue(eventRaw, "AidDeadlineEvent", "$location.event"),
      deadline = mapDeadline(rs, location),
      publisher = rs.getString("publisher"),
      sourceUrl = rs.getString("source_url"),
      archiveUrl = rs.getString("archive_url"),
      checkedOn = rs.getDate("checked_on").toLocalDate(),
      expiresOn = rs.getDate("expires_on").toLocalDate(),
    )
  }

  /**
   * The published deadline, read from `deadline_kind`, `deadline_date` and
   * `deadline_as_published` TOGETHER because [PublishedDeadline] is one value.
   *
   * The schema's value-iff-kind CHECK already refuses a `date` row with no date
   * and a `wording` or `none` row that has one, so a row carrying the wrong half
   * is corruption and is surfaced with the same LOCATED refusal a bad code gets
   * -- never silently resolved to whichever half is present, because both
   * resolutions tell a family something no publisher said.
   */
  private fun mapDeadline(
    rs: ResultSet,
    location: String,
  ): PublishedDeadline {
    val kindRaw = rs.getString("deadline_kind")
    val kind =
      AidDeadlineKind.fromValue(kindRaw)
        ?: throw corruptValue(kindRaw, "AidDeadlineKind", "$location.deadline_kind")
    val asPublished = rs.getString("deadline_as_published")
    val date = rs.getDate("deadline_date")?.toLocalDate()

    // THE `when` IS OVER THE ENUM AND NOT OVER BOOLEANS, so a fourth
    // AidDeadlineKind is a COMPILE error here. Branching on
    // `kind == DATE && date != null` with an `else` covers the same six
    // combinations today and silently routes a legally stored new kind into the
    // corruption arm -- a runtime refusal for a value the schema accepts, which
    // is the one failure this mapper must not invent.
    fun refuse(): Nothing =
      throw corruptValue(
        raw = "deadline_kind=[$kindRaw] deadline_date=[$date] deadline_as_published=[$asPublished]",
        domain = "PublishedDeadline",
        location = location,
      )

    return when (kind) {
      AidDeadlineKind.DATE -> date?.let { PublishedDeadline.OnDate(it, asPublished) } ?: refuse()
      AidDeadlineKind.WORDING -> if (date == null) PublishedDeadline.InWords(asPublished) else refuse()
      AidDeadlineKind.NONE -> if (date == null) PublishedDeadline.NonePublished(asPublished) else refuse()
    }
  }
}
