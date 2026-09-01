package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.Id
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError

sealed class DaoException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

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
 * A write-path CHECK/unique violation. Carries the optional PostgreSQL
 * diagnostics — the violated [constraint] name and the server [detail] line — so
 * a caller can bucket the failure by constraint and surface the failing key
 * without parsing log text. Both are null when the cause is not a
 * `PSQLException` (the defaults keep existing construction sites unchanged).
 */
class ConstraintViolationException(
  cause: Throwable,
  override val constraint: String? = null,
  override val detail: String? = null,
) : DaoException("Database constraint violation", cause),
  ConstraintDiagnostics,
  PermanentError

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
  location: String? = null,
) : DaoException("Persisted value failed reconstruction: $error" + (location?.let { " at $it" } ?: "")),
  PermanentError

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
 * The reference-table write-path SQLSTATE mapping: `23503` (a fact row
 * referencing an absent parent) to [NotFoundException]; `23505`/`23514`
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
    "23503" -> {
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
