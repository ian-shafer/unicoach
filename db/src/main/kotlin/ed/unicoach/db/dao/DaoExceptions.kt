package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.Id
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError

sealed class DaoException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

/** PostgreSQL SQLSTATE codes the write-path mappers dispatch on. */
private const val SQLSTATE_FOREIGN_KEY_VIOLATION = "23503"

/**
 * An ON DELETE/UPDATE **RESTRICT** foreign key raises its own state, not
 * `23503`: PostgreSQL answers `23001` (restrict_violation) for
 * `violates RESTRICT setting of foreign key constraint ...`. A mapper that
 * matches only `23503` therefore never sees a RESTRICT refusal and hands the
 * caller a bare [DatabaseException] — the defect this constant exists to stop
 * recurring. Do not "tidy" it away as a duplicate of the line above.
 */
private const val SQLSTATE_RESTRICT_VIOLATION = "23001"

/**
 * The two SQLSTATEs a blocked reference write can arrive as, published for the
 * DAOs that dispatch on both: `23503` foreign_key_violation and `23001`
 * restrict_violation, the state an ON DELETE RESTRICT foreign key actually
 * raises. Stated here, beside the constants, so the digits are written once and
 * a call site that copied them cannot drift from the reason they are two.
 */
internal val FOREIGN_KEY_SQLSTATES = setOf(SQLSTATE_FOREIGN_KEY_VIOLATION, SQLSTATE_RESTRICT_VIOLATION)

internal const val SQLSTATE_UNIQUE_VIOLATION = "23505"
private const val SQLSTATE_CHECK_VIOLATION = "23514"

/**
 * `raise_exception` — the state a bare PL/pgSQL `RAISE EXCEPTION` produces, and
 * so the state EVERY trigger guard in this schema raises. It says only "a
 * trigger refused this write"; WHICH rule refused it is the constraint name the
 * `RAISE` supplies with `USING CONSTRAINT = ...`, not the SQLSTATE.
 */
internal const val SQLSTATE_RAISE_EXCEPTION = "P0001"

/**
 * A write failure carrying PostgreSQL's own diagnostics: the violated
 * [constraint] name and the server DETAIL line, which names the offending value
 * (`Key (state)=(ZZ) is not present in table "us_states"`).
 *
 * It exists so a CONSUMER can ask for the diagnostics without knowing which
 * SQLSTATE produced them. `23505`/`23514` arrive as [ConstraintViolationException]
 * and `23503` as [NotFoundException]; both carry the same two fields, and an
 * ingest that matched only the first one logged `[constraint=null] [detail=null]`
 * for every row a foreign key rejected — which since migration 0067 is a routine
 * `colleges` outcome, not an exotic one.
 */
interface ConstraintDiagnostics {
  val constraint: String?
  val detail: String?
}

/**
 * A row that had to exist did not. On the write path (a `23503` foreign-key
 * violation) it also carries the PostgreSQL diagnostics its `23505`/`23514`
 * sibling [ConstraintViolationException] carries -- the violated [constraint]
 * name and the server [detail] line naming the offending key -- plus the
 * originating `SQLException` as the cause. Both default to null so read-path
 * construction sites are unchanged.
 */
class NotFoundException(
  message: String = "Record not found",
  cause: Throwable? = null,
  override val constraint: String? = null,
  override val detail: String? = null,
) : DaoException(message, cause),
  ConstraintDiagnostics,
  PermanentError

/**
 * A version-revert named a historical version that has no row. Carries the exact
 * lookup keys — the entity id and the requested version — plus the originating
 * [NotFoundException] as the cause, so the failure can be diagnosed straight from
 * the log without re-deriving what was queried.
 */
class TargetVersionMissingException(
  val entityId: Id,
  val targetVersion: Int,
  cause: Throwable? = null,
) : DaoException(
    "Target version missing: no version row for [${entityId.asString}] at version [$targetVersion]",
    cause,
  ),
  PermanentError

class DuplicateEmailException(
  message: String = "Duplicate email",
) : DaoException(message),
  PermanentError

class StudentAlreadyExistsException(
  message: String = "Student already exists for user",
) : DaoException(message),
  PermanentError

/**
 * A user already has a `supporters` row (RFC 221) — the
 * [StudentAlreadyExistsException] precedent for the other side of the family.
 *
 * Named, because `supporters_user_id_unique_idx` is TOTAL: a soft-deleted
 * supporter keeps its `user_id` forever, so "this user is already a supporter"
 * is a state a caller must be able to recognise WITHOUT string-matching an
 * index name out of a [ConstraintViolationException].
 */
class SupporterAlreadyExistsException(
  message: String = "Supporter already exists for user",
) : DaoException(message),
  PermanentError

/**
 * The three refusals of the `supporter_student_links` endpoint guards (RFC 221),
 * each arriving as its own type.
 *
 * All three are raised by a PL/pgSQL trigger as `P0001`, which is one SQLSTATE
 * for three different rules. The trigger therefore names a CONSTRAINT on every
 * `RAISE`, and these types are what those names map to — so a caller branches on
 * a type, never on a substring of an English sentence that a later migration is
 * free to reword.
 *
 * [PermanentError] for all three: the invite path never offers a student their
 * own account and never offers a closed one, so each is a programming error
 * rather than a race, and retrying the identical write cannot succeed.
 *
 * The server's own message is carried through as the message, because the guard
 * names BOTH endpoint ids in it — which endpoint is closed is useless without
 * knowing which link was being minted. It is NULLABLE for that reason only: a
 * refusal whose server message is absent still gets its type, falling back to
 * the constant sentence above, because the CONSTRAINT name alone identifies
 * which rule refused the write.
 */
class SelfSupporterLinkException(
  message: String? = null,
  cause: Throwable? = null,
  override val constraint: String? = null,
  override val detail: String? = null,
) : DaoException(message ?: "A student may not be their own supporter", cause),
  ConstraintDiagnostics,
  PermanentError

/** A link was minted to a supporter whose account is closed. See [SelfSupporterLinkException]. */
class ClosedSupporterLinkException(
  message: String? = null,
  cause: Throwable? = null,
  override val constraint: String? = null,
  override val detail: String? = null,
) : DaoException(message ?: "A link may not be created to a closed supporter", cause),
  ConstraintDiagnostics,
  PermanentError

/** A link was minted to a student whose account is closed. See [SelfSupporterLinkException]. */
class ClosedStudentLinkException(
  message: String? = null,
  cause: Throwable? = null,
  override val constraint: String? = null,
  override val detail: String? = null,
) : DaoException(message ?: "A link may not be created to a closed student", cause),
  ConstraintDiagnostics,
  PermanentError

/**
 * A write-path CHECK/unique violation. Carries the optional PostgreSQL
 * diagnostics — the violated [constraint] name and the server [detail] line — so
 * a caller can bucket the failure by constraint and surface the failing key
 * without parsing log text. Both are null when the cause is not a
 * `PSQLException` (the defaults keep existing construction sites unchanged).
 */
open class ConstraintViolationException(
  cause: Throwable,
  override val constraint: String? = null,
  override val detail: String? = null,
  message: String = "Database constraint violation",
) : DaoException(message, cause),
  ConstraintDiagnostics,
  PermanentError

/**
 * An ACTIVE link already joins this supporter and student (RFC 221/232) — the
 * `supporter_student_links_supporter_student_active_idx` partial unique index.
 *
 * The FOURTH refusal of that table, given the typed shape its three trigger
 * siblings have. A caller racing a concurrent accept used to recognise it by
 * string-matching a schema-owned INDEX NAME published out of `:db`, which made a
 * migration's rename a silent behaviour change in `:service`. A subclass of
 * [ConstraintViolationException] and not a sibling of it, because it IS one:
 * every caller that buckets by constraint keeps working, and the one caller that
 * cares names the type.
 */
class ActiveSupporterLinkException(
  cause: Throwable,
  constraint: String? = null,
  detail: String? = null,
) : ConstraintViolationException(cause, constraint, detail, "An active link already joins this supporter and student")

class DatabaseException(
  cause: Throwable,
) : DaoException("General database error", cause),
  PermanentError

/**
 * Client-supplied text PostgreSQL cannot store: SQLSTATE `22021`
 * (character_not_in_repertoire — e.g. a NUL byte inside a UTF-8 string) or
 * `22P05` (untranslatable_character). The bytes are the caller's data, not a
 * server fault, so StatusPages' generic [PermanentError] arm answers 400
 * rather than the 500 a [DatabaseException] would produce — found by the
 * pre-commit contract fuzzer sending `\u0000` in a register name (RFC 137).
 */
class UnstorableTextException(
  cause: Throwable,
) : DaoException("Text contains characters that cannot be stored", cause),
  PermanentError

/**
 * A persisted value failed reconstruction into its domain type. [location]
 * (optional, defaulted so existing construction sites are unchanged) names
 * where the corrupt value sits — column and row id — so the offending row can
 * be found straight from the log.
 */
class CorruptPersistedValueException(
  val value: String,
  val error: ValidationError,
  /**
   * WHERE the corrupt value sits — the column and the row's natural key — kept
   * as a property, not only rendered into the message, so a caller that carries
   * the failure out as data (`CanonicalMoneyReadDao.readRows`) can name the cell
   * without parsing this message back out of prose.
   */
  val location: String? = null,
) : DaoException("Persisted value [$value] failed reconstruction: $error" + (location?.let { " at $it" } ?: "")),
  PermanentError

/**
 * A persisted value no domain type reads, as the house's LOCATED exception --
 * declared once because every read raises it the same way, and the two copies
 * this replaced had already drifted apart in their own KDoc.
 *
 * [location] names the column and the row's natural key, so the offending cell
 * can be found from the log without re-deriving the query.
 */
internal fun corruptValue(
  raw: String,
  domain: String,
  location: String,
) = CorruptPersistedValueException(
  raw,
  ValidationError.InvalidFormat(expected = "a known [$domain] value"),
  location = location,
)

/**
 * A `money_profiles` status column holding a value its CHECK forbids -- today
 * only [AnswerStatus.UNDECIDED], which is the INTENDED-SUBJECT answer (RFC 205)
 * and belongs to no money field.
 *
 * Declared once because every money read states the same rule: the offending
 * value is row corruption, named with its column and row, and NEVER folded into
 * `declined`. "I don't know yet" is not "I would rather not say", and a read
 * that quietly turns one into the other states a refusal the family never made.
 * The expected set is [AnswerStatus.moneyCases] itself, so the sentence cannot
 * drift from the enum.
 *
 * It BUILDS the exception rather than throwing it. Most callers throw it at
 * once; [ed.unicoach.coaching.ResidencyOffer] logs it and degrades instead,
 * because a search must not fail on a corrupt profile -- same words, different
 * control flow.
 */
fun nonMoneyAnswerStatus(
  status: AnswerStatus,
  location: String,
) = CorruptPersistedValueException(
  status.value,
  ValidationError.InvalidFormat(expected = "one of " + AnswerStatus.moneyCases.joinToString { it.value }),
  location = location,
)

class LockAcquisitionFailureException(
  message: String = "Lock acquisition failure",
) : DaoException(message),
  TransientError

class ConcurrentModificationException(
  message: String = "Concurrent modification",
) : DaoException(message),
  TransientError

/**
 * A `colleges.control` code no [ed.unicoach.db.models.InstitutionControl] entry
 * names, found by the search-index rebuild BEFORE it writes (RFC 150 D61a).
 *
 * Named rather than left to the NOT NULL violation it used to cause: a
 * constraint name tells an operator which column broke, this tells them which
 * CODE broke it and how many colleges carry it. [PermanentError] — retrying the
 * same snapshot cannot map a code the enum does not have.
 */
class UnmappedControlCodeException(
  val counts: Map<String, Int>,
) : DaoException(
    "colleges.control carries ${counts.size} code(s) InstitutionControl does not name: " +
      counts.entries.joinToString(", ") { (code, n) -> "[$code] on $n row(s)" } +
      "; the search index cannot store a college with no control",
  ),
  PermanentError

/**
 * `college_search_index` has never been built (RFC 150) — the migration creates
 * it empty and only the ingest's `search-index` phase fills it.
 *
 * [TransientError], because it is: the next ingest fixes it without any code
 * change, so a caller should retry rather than dead-letter, and the REST layer
 * already answers a transient DAO failure with 503 plus this message. The
 * alternative — an empty list — is a zero that no caller can tell from a real
 * one, which is the defect this type exists to make impossible.
 */
class SearchIndexNotBuiltException :
  DaoException(
    "The college search index has not been built yet; run the ingest's `search-index` phase " +
      "(`bin/ingest-colleges`) — until then no college can be found by name",
  ),
  TransientError

/**
 * A dropped subject a student's answer still references, so the taxonomy reload
 * cannot delete it (RFC 205 D40).
 *
 * TYPED, not prose. The caller needs the slug and the dependant counts as data —
 * the [UnmappedControlCodeException] shape, a counts map plus a sentence the
 * exception builds itself — because a message is for a human and grepping one is
 * not an interface.
 *
 * NOT a [NotFoundException], which is what this used to be mapped to and is the
 * opposite of what happened: the row is very much there, and a student is
 * holding it. [PermanentError] — retrying the same reload cannot make the
 * student's answer go away; deprecating the slug is what resolves it.
 *
 * [slug] is null when the server's DETAIL line did not yield one; the sentence
 * then carries the whole classification, because a reader with no slug at all
 * has nowhere to start.
 *
 * A [slug] this run never classified has NO count, and the sentence says so
 * rather than printing `0` — a zero there would be a sentinel AND a false
 * statement, since zero dependants is exactly the state in which the delete
 * would have succeeded.
 */
class SubjectStillReferencedException(
  val slug: String?,
  val dependants: Map<String, Int>,
  cause: Throwable? = null,
  override val constraint: String? = null,
  override val detail: String? = null,
) : DaoException(
    if (slug == null) {
      "one of the dropped subjects is still referenced by a student's intended subjects; " +
        "dropped slugs and their dependants: " +
        dependants.entries.sortedBy { it.key }.joinToString(", ") { "[${it.key}] -> ${it.value}" }
    } else if (dependants[slug] == null) {
      "subject [$slug] is still referenced by a student's intended subjects and cannot be deleted; " +
        "this run did not classify that slug, so no dependant count for it was read " +
        "before the write; it must be deprecated instead"
    } else {
      "subject [$slug] is still referenced by ${dependants[slug]} intended-subject row(s) " +
        "and cannot be deleted; it must be deprecated instead"
    },
    cause,
  ),
  ConstraintDiagnostics,
  PermanentError

/**
 * The reference-table write-path SQLSTATE mapping: `23503` (a fact row
 * referencing an absent parent) and `23001` (a RESTRICT foreign key refusing a
 * delete) to [NotFoundException]; `23505`/`23514`
 * (unique/check, including a domain CHECK) to [ConstraintViolationException];
 * everything else through [mapDatabaseError].
 *
 * BOTH violation arms keep the same evidence: the driver `SQLException` as the
 * cause, and the server's violated-constraint name and DETAIL line as typed
 * fields. [missingReferenceMessage] is the caller's description of the write
 * that failed -- it should name the absent parent's id, the target table and
 * the key, not a constant sentence, because a `23503` here means something
 * genuinely surprising happened and a context-free message starts a table scan.
 */
fun mapReferenceWriteError(
  e: java.sql.SQLException,
  missingReferenceMessage: String,
): Exception {
  val serverError = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
  return when (e.sqlState) {
    // `23001` alongside `23503`: a RESTRICT foreign key refusing a delete is a
    // reference violation like any other, and it is the only state the
    // reference-data reload path can actually see.
    SQLSTATE_FOREIGN_KEY_VIOLATION, SQLSTATE_RESTRICT_VIOLATION -> {
      NotFoundException(
        message = missingReferenceMessage,
        cause = e,
        constraint = serverError?.constraint,
        detail = serverError?.detail,
      )
    }

    "23505", "23514" -> {
      ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
    }

    else -> {
      mapDatabaseError(e)
    }
  }
}

/**
 * The `colleges` / `college_search_index` foreign keys that point at a CODEBOOK
 * table rather than at `colleges` (migration 0067), each mapped to the table it
 * points AT. Named so [mapCollegeWriteError] can tell "this college_id does not
 * exist" from "this code is not in the published vocabulary", and so the message
 * can say WHICH vocabulary instead of listing every one it might have been.
 *
 * These are schema-owned names restated in Kotlin, which is a copy: a rename in
 * a migration without a change here silently reverts the message to "Referenced
 * college not found". `CollegesDaoTest` provokes all three from the real
 * database for exactly that reason.
 */
private val CODEBOOK_FOREIGN_KEYS =
  mapOf(
    "colleges_state_codebook_fkey" to "us_states",
    "colleges_locale_codebook_fkey" to "nces_locales",
    "college_search_index_state_fkey" to "us_states",
  )

/**
 * The write-path SQLSTATE mapping both college DAOs share: `23503` (FK -- a row
 * referencing an absent college) to [NotFoundException]; `23505`/`23514`
 * (unique/check) to [ConstraintViolationException] carrying the violated
 * constraint name and the server DETAIL line, so a loader can bucket a skip by
 * constraint. Everything else routes through [mapDatabaseError], which
 * classifies transient SQLSTATEs.
 *
 * Declared once here rather than per-DAO: [CollegesDao] and [CollegeIpedsDao]
 * write the same reference tables under the same constraints, and a new
 * SQLSTATE mapping must not have to be remembered twice.
 */
internal fun mapCollegeWriteError(e: java.sql.SQLException): Exception {
  // Read ONCE, above the `when`: both violation arms keep the same evidence --
  // the driver SQLException as the cause plus the server's constraint name and
  // DETAIL line -- exactly as [mapReferenceWriteError] does. A `23503` that
  // arrived with none of it left an ingest skip reading
  // [constraint=null] [detail=null], which names neither the offending
  // college_id nor the violated FK.
  val serverError = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
  return when (e.sqlState) {
    "23503" -> {
      // Two different absences arrive as one SQLSTATE, and saying "referenced
      // college not found" about both would be a lie half the time. A row
      // hanging off `colleges` can miss its COLLEGE; since migration 0067 a
      // `colleges` (or `college_search_index`) row can also miss its CODEBOOK
      // row, because `state` and `locale` reference `us_states` / `nces_locales`.
      // The constraint name is what tells them apart, and the remedy differs:
      // one is a dangling college_id, the other is an unloaded codebook.
      val codebookTable = CODEBOOK_FOREIGN_KEYS[serverError?.constraint]
      val message =
        if (codebookTable != null) {
          "Referenced codebook row not found in [$codebookTable] — the value names no published code, or " +
            "that codebook table was never loaded [constraint=${serverError?.constraint}] " +
            "[detail=${serverError?.detail}]"
        } else {
          "Referenced college not found [constraint=${serverError?.constraint}] [detail=${serverError?.detail}]"
        }
      NotFoundException(
        message = message,
        cause = e,
        constraint = serverError?.constraint,
        detail = serverError?.detail,
      )
    }

    "23505", "23514" -> {
      ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
    }

    else -> {
      mapDatabaseError(e)
    }
  }
}

/**
 * The per-table write-path SQLSTATE mapping every child-table DAO repeats:
 * `23503` dispatched to a caller-named message by the violated FK's name (a
 * plain [NotFoundException] for an unregistered one), `23505`/`23514` to
 * [ConstraintViolationException], everything else through [mapDatabaseError].
 * Declared once here so a new SQLSTATE mapping does not have to be remembered
 * per DAO.
 *
 * Both violation arms keep the same evidence the other shared mappers carry:
 * the driver `SQLException` as the cause plus the server's violated-constraint
 * name and DETAIL line as typed fields.
 */
internal fun mapChildWriteError(
  e: java.sql.SQLException,
  foreignKeyMessages: Map<String, String>,
): Exception {
  val serverError = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
  return when (e.sqlState) {
    SQLSTATE_FOREIGN_KEY_VIOLATION -> {
      val named = foreignKeyMessages[serverError?.constraint]
      if (named != null) {
        NotFoundException(message = named, cause = e, constraint = serverError?.constraint, detail = serverError?.detail)
      } else {
        NotFoundException(cause = e, constraint = serverError?.constraint, detail = serverError?.detail)
      }
    }

    SQLSTATE_UNIQUE_VIOLATION, SQLSTATE_CHECK_VIOLATION -> {
      ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
    }

    else -> {
      mapDatabaseError(e)
    }
  }
}

fun mapDatabaseError(e: Exception): Exception {
  if (e is DaoException) return e
  val sqlState = (e as? java.sql.SQLException)?.sqlState
  return when {
    sqlState != null && isTransientSqlState(sqlState) -> TransientDatabaseException(e)
    sqlState == "22021" || sqlState == "22P05" -> UnstorableTextException(e)
    else -> DatabaseException(e)
  }
}

class TransientDatabaseException(
  cause: Throwable,
) : DaoException("Transient database error", cause),
  TransientError

private fun isTransientSqlState(sqlState: String): Boolean =
  sqlState.startsWith("08") ||
    // connection exceptions
    sqlState == "40001" ||
    // serialization failure
    sqlState == "40P01" ||
    // deadlock detected
    sqlState.startsWith("53") ||
    // insufficient resources
    sqlState.startsWith("57P") // operator intervention
