package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.LocalDate
import java.util.UUID

/**
 * Surface id for a [FafsaFilingDeadline] row. Beside the model rather than in
 * its own file because `fafsa_filing_deadlines` is seeded reference data with no
 * id-keyed read path -- the [AdmissionTestStateProgrammeId] precedent, which is
 * the same shape one table along.
 */
@JvmInline
value class FafsaFilingDeadlineId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * The deadline a publisher published, as ONE value: a day, a rule in words, or
 * a stated absence -- each carrying the publisher's own sentence.
 *
 * The schema says the same thing in SQL
 * (`fafsa_filing_deadlines_date_iff_kind_check`:
 * `(deadline_date IS NOT NULL) = (deadline_kind = 'date')`). Modelling the pair
 * as an [AidDeadlineKind] beside a nullable `LocalDate` would let the Kotlin
 * side hold a state the database rejects -- a `wording` row carrying a date
 * somebody guessed, or a `date` row carrying none that reaches the public page
 * as a blank cell. Here neither typechecks. This is the [SchoolDayTestChoice] /
 * `ScoreRelease` precedent one table along.
 *
 * [deadlineAsPublished] rides on EVERY arm, including the one that already has
 * the day, and the name is the repo's landed one for this idea
 * (`closingAsPublished` beside `closingResolved` in
 * `AdmissionTestTimelineChatTool`). Two reasons, both load bearing: a `DATE`
 * cannot hold "11:59 p.m. Pacific", which is the thing the state actually
 * enforces; and a reader shown only our parse would attribute OUR reading to
 * the publisher.
 */
sealed interface PublishedDeadline {
  /**
   * The publisher's own words for this deadline, on every arm -- "March 2,
   * 2027, 11:59 p.m. Pacific", "as soon as possible after October 1", "no state
   * deadline is published". Never empty; the schema refuses an empty string,
   * because a value-shaped absence rendered onto a page is a blank pretending
   * to be a fact.
   */
  val deadlineAsPublished: String

  /**
   * The stored kind, so a reader never re-derives it from the arm, and so the
   * DAO binds `deadline_kind` off the value rather than off a `when`.
   *
   * THERE IS DELIBERATELY NO `date` BESIDE IT. An interface-level nullable day
   * would hand every caller back exactly the flat (kind, nullable date) pair
   * this type exists to abolish, and it would read `when (d.kind)` with
   * `d.date!!` instead of an exhaustive `when (d)`. The day is reachable only
   * on the arm that has one.
   */
  val kind: AidDeadlineKind

  /** The publisher names a day, and this is it. */
  data class OnDate(
    val date: LocalDate,
    override val deadlineAsPublished: String,
  ) : PublishedDeadline {
    override val kind: AidDeadlineKind get() = AidDeadlineKind.DATE
  }

  /**
   * The publisher names a RULE and no day. The words are the fact; nothing here
   * or above may turn them into a date.
   */
  data class InWords(
    override val deadlineAsPublished: String,
  ) : PublishedDeadline {
    override val kind: AidDeadlineKind get() = AidDeadlineKind.WORDING
  }

  /**
   * The page was read and publishes no deadline for this program. A VERIFIED
   * absence -- the row exists, with its citation and its `checked_on`, so a
   * family can tell it from the state nobody checked (D9).
   */
  data class NonePublished(
    override val deadlineAsPublished: String,
  ) : PublishedDeadline {
    override val kind: AidDeadlineKind get() = AidDeadlineKind.NONE
  }
}

/**
 * WHOSE deadline this is: the federal government's, or one state's.
 *
 * The column is a nullable `state` whose NULL MEANS federal, and the natural-key
 * index is `NULLS NOT DISTINCT` so two federal rows collide rather than both
 * landing. A `scope` column beside it would be a stored derivation of
 * `state IS NULL` (CLAUDE.md), so the distinction is drawn HERE instead: with
 * this type, "federal" is a named value and not a null every caller has to
 * remember the meaning of.
 */
sealed interface DeadlineJurisdiction {
  /** The stored `state` cell: the USPS code, or null for [Federal]. */
  val uspsCode: String?

  /** The federal government -- `state IS NULL`. */
  data object Federal : DeadlineJurisdiction {
    override val uspsCode: String? get() = null
  }

  /**
   * One US jurisdiction, by USPS code. Membership in the 59 published codes is
   * `UsStateCodes.parse`'s job, in the `fafsa/02` generator and the seed test;
   * the schema's `^[A-Z]{2}$` CHECK is the coarse backstop, and there is no
   * foreign key to `us_states` yet for the reason the DDL states.
   */
  @ConsistentCopyVisibility
  data class State internal constructor(
    override val uspsCode: String,
  ) : DeadlineJurisdiction {
    init {
      // THE BACKSTOP BEHIND [of]. The constructor is internal and
      // `@ConsistentCopyVisibility` makes `copy` internal with it, so nothing
      // outside `:db` can build one -- but `:db` itself can, and 'ZZ' passes
      // the schema's `^[A-Z]{2}$` as easily as 'CA' does. The membership rule
      // is UsStateCodes'; this is the PriceRuler precedent, one require at the
      // one place the value is made.
      require(uspsCode in UsStateCodes.ALL) {
        "[$uspsCode] is not one of the 59 published USPS codes; a deadline row for it would name no jurisdiction"
      }
    }

    companion object {
      /**
       * The jurisdiction for [raw], or null when it is not one of the 59
       * published USPS codes.
       *
       * THE TYPE'S OTHER HALF. `Federal` is a named value here precisely so no
       * caller has to remember that a null `state` cell means the federal
       * government -- and then `State("XX")` would have compiled, passed the
       * schema's `^[A-Z]{2}$` backstop and landed a row for a jurisdiction that
       * does not exist, while `State("ca")` would have compiled and died on a
       * CHECK at write time. Membership is [UsStateCodes.parse]'s rule and this
       * is where this corpus asks it; the constructor is `internal` so the only
       * unchecked entry is [ed.unicoach.db.dao.FafsaFilingDeadlineDao]'s
       * mapper, which refuses a stored code it cannot parse with a LOCATED
       * failure rather than a null.
       */
      fun of(raw: String): State? = UsStateCodes.parse(raw)?.let { State(it) }
    }
  }
}

/**
 * One published FAFSA-cycle date, from the `fafsa_filing_deadlines` reference
 * table (RFC 215, brief 0012 D4): when the form opens, and the deadlines the
 * federal government and each state publish for the aid a FAFSA unlocks.
 *
 * ONE STORE FOR THE PAGE AND THE COACH. The public state-by-state table
 * (`fafsa/04`) and the coach (`fafsa/05`) read these same rows, because two
 * copies of a date eventually disagree and a family sees only one of them.
 *
 * THE AWARD YEAR IS THE ROW'S IDENTITY, NOT ITS DECORATION. State deadlines
 * recur annually, so last cycle's row is not merely old -- it is a different
 * date that LOOKS like this cycle's. Every read filters on [awardYear] first,
 * and [FafsaFilingCycle.currentFilingYear] is the clock that names the cycle
 * families are filing now. A passed deadline INSIDE the current cycle is still
 * a fact worth showing ("Texas closed on 15 January").
 *
 * Hand-typed from each publisher's page (brief 0010 D19, the RFC 197
 * precedent), which is why every row carries its OWN citation
 * ([sourceUrl]/[archiveUrl], [checkedOn]) and its OWN [expiresOn] rather than
 * the table carrying one. Expiry is NOT decided here: `fafsa/03`'s service owns
 * the refusal, and this layer returns rows and their dates.
 *
 * NO `FigureStatus` and no assurance tier, for RFC 197's reason: that vocabulary
 * belongs to the canonical MONEY tables, whose rows record a publisher's
 * suppression and imputation flags. A deadline is published or it is not, and
 * [PublishedDeadline] is how it says which.
 */
data class FafsaFilingDeadline(
  override val id: FafsaFilingDeadlineId,
  /**
   * The cycle this date belongs to, under the `academic_year` domain (0087
   * D14): 2027 IS the 2027-28 FAFSA, and the "2027-28" label is
   * [AcademicYear]'s and is never stored.
   */
  val awardYear: AcademicYear,
  /** Federal, or the state whose agency published it -- one value, never a null to remember. */
  val jurisdiction: DeadlineJurisdiction,
  /**
   * The program as ITS OWN publisher names it -- "Cal Grant", "TEXAS Grant",
   * "Federal student aid". A source-defined name, stored raw (CLAUDE.md), and
   * part of the natural key because a state with three grant programs has three
   * deadlines.
   */
  val program: String,
  /** Which of the cycle's dates this is. The publisher's own naming of it is in [deadline]. */
  val event: AidDeadlineEvent,
  /** What the publisher published: a day, a rule, or a stated absence -- with their words either way. */
  val deadline: PublishedDeadline,
  /**
   * Who published it, as a family would recognise them -- "California Student
   * Aid Commission". The link text the public table renders, and a fact about
   * this ROW, which is exactly why `external_documents` carries no publisher of
   * its own (RFC 213).
   */
  val publisher: String,
  /** The agency's own page, read through the `external_documents` join. A row cites something or it does not exist. */
  val sourceUrl: String,
  /** The archived address the row was read through, where the agency's page opens for no client we have. Null where none was needed. */
  val archiveUrl: String?,
  /** The day a human read [sourceUrl]. A day in the world, so a `LocalDate` and not an instant. */
  val checkedOn: LocalDate,
  /**
   * The day this reading stops being believed (D7). A fact about the READING and
   * not a promise about the world.
   *
   * NOTHING HERE ENFORCES IT YET. Past this day it is `fafsa/03`'s
   * `FafsaDeadlineService` that refuses to name a date and points at the
   * publisher; and once `fafsa/02` commits rows, its seed test fails the build
   * when a committed row of the cycle now being filed is within
   * [HandTypedReferenceData.REFRESH_LEAD] of this. This slice ships the column
   * and no rows, so it ships neither rule.
   */
  val expiresOn: LocalDate,
) : Identifiable<FafsaFilingDeadlineId>

/**
 * A row to write. Separate from [FafsaFilingDeadline] because the id is the
 * database's (`uuidv7()`), exactly as every other `New*` model in this package
 * works.
 *
 * It carries the two urls as STRINGS and not as [ExternalDocumentId]s: a caller
 * typing a deadline knows the page it read, and the DAO resolves both through
 * [ed.unicoach.db.dao.ExternalDocumentsDao.idOf] -- never its upsert, which
 * would overwrite a swept link status with "we never asked" (RFC 182).
 */
data class NewFafsaFilingDeadline(
  val awardYear: AcademicYear,
  val jurisdiction: DeadlineJurisdiction,
  val program: String,
  val event: AidDeadlineEvent,
  val deadline: PublishedDeadline,
  val publisher: String,
  val sourceUrl: String,
  val archiveUrl: String?,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
) {
  init {
    // A FORM THAT OPENS DOES OPEN, refused here as well as by
    // `fafsa_filing_deadlines_opening_is_dated_check`.
    //
    // The kind/date pairing above is a COMPILE error, and this invariant is
    // argued the same way -- "a row that said it would render as 'the FAFSA
    // does not open'" -- so leaving it only to the database would enforce the
    // weaker half of one argument in a weaker place. It cannot be a type: the
    // event and the deadline are independent axes and three of the four events
    // accept all three kinds. So it is the next strongest thing, a refusal at
    // construction, naming the constraint it mirrors.
    require(!(event == AidDeadlineEvent.FORM_OPENS && deadline.kind == AidDeadlineKind.NONE)) {
      "a form_opens row cannot have kind [${AidDeadlineKind.NONE.value}] " +
        "(fafsa_filing_deadlines_opening_is_dated_check): an opening date a publisher " +
        "does not set would render as [the FAFSA does not open]"
    }
  }
}
