package ed.unicoach.db.dao

import ed.unicoach.error.ExceptionWrapper
import java.sql.SQLException

sealed interface DaoResult<out T> {

  data class Success<T>(val value: T) : DaoResult<T>

  sealed interface TransientError : DaoResult<Nothing> {
    data object ConcurrentModification : TransientError
    data object LockAcquisitionFailure : TransientError
    data class DatabaseError(val error: ExceptionWrapper) : TransientError
  }

  sealed interface PermanentError : DaoResult<Nothing> {
    data class NotFound(val message: String = "") : PermanentError
    data object DuplicateEmail : PermanentError
    data object TargetVersionMissing : PermanentError
    data class ConstraintViolation(val error: ExceptionWrapper) : PermanentError
    data class DatabaseError(val error: ExceptionWrapper) : PermanentError
  }
}

/**
 * Classifies an exception as a transient or permanent database error based on
 * SQLSTATE codes. Non-SQLException defaults to permanent (application bug).
 */
fun classifyDatabaseError(e: Exception): DaoResult<Nothing> {
  val wrapper = ExceptionWrapper.from(e)
  val sqlState = (e as? SQLException)?.sqlState
  return if (sqlState != null && isTransientSqlState(sqlState)) {
    DaoResult.TransientError.DatabaseError(wrapper)
  } else {
    DaoResult.PermanentError.DatabaseError(wrapper)
  }
}

private fun isTransientSqlState(sqlState: String): Boolean =
  sqlState.startsWith("08")       // connection exceptions
    || sqlState == "40001"        // serialization failure
    || sqlState == "40P01"        // deadlock detected
    || sqlState.startsWith("53")  // insufficient resources
    || sqlState.startsWith("57P") // operator intervention
