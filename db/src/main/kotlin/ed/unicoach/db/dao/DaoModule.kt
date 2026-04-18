package ed.unicoach.db.dao

import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserVersion

sealed interface FindResult {
  data class Success(
    val user: User,
  ) : FindResult

  data object NotFound : FindResult

  data object LockAcquisitionFailure : FindResult

  data class DatabaseFailure(
    val error: ed.unicoach.error.AppError,
  ) : FindResult
}

sealed interface CreateResult {
  data class Success(
    val user: User,
  ) : CreateResult

  data object DuplicateEmail : CreateResult

  data class ConstraintViolation(
    val error: ed.unicoach.error.AppError,
  ) : CreateResult

  data class DatabaseFailure(
    val error: ed.unicoach.error.AppError,
  ) : CreateResult
}

sealed interface UpdateResult {
  data class Success(
    val user: User,
  ) : UpdateResult

  data object NotFound : UpdateResult

  data object DuplicateEmail : UpdateResult

  data object ConcurrentModification : UpdateResult

  data object TargetVersionMissing : UpdateResult

  data class ConstraintViolation(
    val error: ed.unicoach.error.AppError,
  ) : UpdateResult

  data class DatabaseFailure(
    val error: ed.unicoach.error.AppError,
  ) : UpdateResult
}

sealed interface DeleteResult {
  data class Success(
    val user: User,
  ) : DeleteResult

  data object NotFound : DeleteResult

  data object ConcurrentModification : DeleteResult

  data class DatabaseFailure(
    val error: ed.unicoach.error.AppError,
  ) : DeleteResult
}

sealed interface FindVersionResult {
  data class Success(
    val version: UserVersion,
  ) : FindVersionResult

  data object NotFound : FindVersionResult

  data class DatabaseFailure(
    val error: ed.unicoach.error.AppError,
  ) : FindVersionResult
}
