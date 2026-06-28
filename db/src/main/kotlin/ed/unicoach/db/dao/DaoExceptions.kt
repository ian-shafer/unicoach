package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.Id
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError

sealed class DaoException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

class NotFoundException(
  message: String = "Record not found",
) : DaoException(message),
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
  val constraint: String? = null,
  val detail: String? = null,
) : DaoException("Database constraint violation", cause),
  PermanentError

class DatabaseException(
  cause: Throwable,
) : DaoException("General database error", cause),
  PermanentError

class CorruptPersistedValueException(
  val value: String,
  val error: ValidationError,
) : DaoException("Persisted value failed reconstruction: $error"),
  PermanentError

class LockAcquisitionFailureException(
  message: String = "Lock acquisition failure",
) : DaoException(message),
  TransientError

class ConcurrentModificationException(
  message: String = "Concurrent modification",
) : DaoException(message),
  TransientError

fun mapDatabaseError(e: Exception): Exception {
  if (e is DaoException) return e
  val sqlState = (e as? java.sql.SQLException)?.sqlState
  return if (sqlState != null && isTransientSqlState(sqlState)) {
    TransientDatabaseException(e)
  } else {
    DatabaseException(e)
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
