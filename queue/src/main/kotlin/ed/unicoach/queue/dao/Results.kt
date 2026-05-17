package ed.unicoach.queue.dao

import ed.unicoach.queue.Job
import ed.unicoach.queue.JobAttempt
import ed.unicoach.queue.JobStatus

sealed interface JobInsertResult {
  data class Success(
    val job: Job,
  ) : JobInsertResult

  class DatabaseFailure(
    val error: Exception,
  ) : JobInsertResult
}

sealed interface JobFindResult {
  data class Success(
    val job: Job,
  ) : JobFindResult

  data class NotFound(
    val message: String,
  ) : JobFindResult

  class DatabaseFailure(
    val error: Exception,
  ) : JobFindResult
}

sealed interface JobUpdateResult {
  data class Success(
    val job: Job,
  ) : JobUpdateResult

  data class NotFound(
    val message: String,
  ) : JobUpdateResult

  data class InvalidState(
    val message: String,
  ) : JobUpdateResult

  class DatabaseFailure(
    val error: Exception,
  ) : JobUpdateResult
}

sealed interface JobResetResult {
  data class Success(
    val count: Int,
  ) : JobResetResult

  class DatabaseFailure(
    val error: Exception,
  ) : JobResetResult
}

sealed interface JobDeleteResult {
  data class Success(
    val count: Int,
  ) : JobDeleteResult

  class DatabaseFailure(
    val error: Exception,
  ) : JobDeleteResult
}

sealed interface JobCountResult {
  data class Success(
    val counts: Map<JobStatus, Int>,
  ) : JobCountResult

  class DatabaseFailure(
    val error: Exception,
  ) : JobCountResult
}

sealed interface AttemptInsertResult {
  data class Success(
    val attempt: JobAttempt,
  ) : AttemptInsertResult

  class DatabaseFailure(
    val error: Exception,
  ) : AttemptInsertResult
}

sealed interface AttemptCountResult {
  data class Success(
    val count: Int,
  ) : AttemptCountResult

  class DatabaseFailure(
    val error: Exception,
  ) : AttemptCountResult
}

sealed interface AttemptFindResult {
  data class Success(
    val attempts: List<JobAttempt>,
  ) : AttemptFindResult

  class DatabaseFailure(
    val error: Exception,
  ) : AttemptFindResult
}
