package ed.unicoach.queue.dao

import ed.unicoach.error.ExceptionWrapper
import ed.unicoach.queue.Job
import ed.unicoach.queue.JobAttempt
import ed.unicoach.queue.JobStatus

sealed interface JobInsertResult {
    data class Success(val job: Job) : JobInsertResult
    data class DatabaseFailure(val error: ExceptionWrapper) : JobInsertResult
}

sealed interface JobFindResult {
    data class Success(val job: Job) : JobFindResult
    data class NotFound(val message: String) : JobFindResult
    data class DatabaseFailure(val error: ExceptionWrapper) : JobFindResult
}

sealed interface JobUpdateResult {
    data class Success(val job: Job) : JobUpdateResult
    data class NotFound(val message: String) : JobUpdateResult
    data class InvalidState(val message: String) : JobUpdateResult
    data class DatabaseFailure(val error: ExceptionWrapper) : JobUpdateResult
}

sealed interface JobResetResult {
    data class Success(val count: Int) : JobResetResult
    data class DatabaseFailure(val error: ExceptionWrapper) : JobResetResult
}

sealed interface JobDeleteResult {
    data class Success(val count: Int) : JobDeleteResult
    data class DatabaseFailure(val error: ExceptionWrapper) : JobDeleteResult
}

sealed interface JobCountResult {
    data class Success(val counts: Map<JobStatus, Int>) : JobCountResult
    data class DatabaseFailure(val error: ExceptionWrapper) : JobCountResult
}

sealed interface AttemptInsertResult {
    data class Success(val attempt: JobAttempt) : AttemptInsertResult
    data class DatabaseFailure(val error: ExceptionWrapper) : AttemptInsertResult
}

sealed interface AttemptCountResult {
    data class Success(val count: Int) : AttemptCountResult
    data class DatabaseFailure(val error: ExceptionWrapper) : AttemptCountResult
}

sealed interface AttemptFindResult {
    data class Success(val attempts: List<JobAttempt>) : AttemptFindResult
    data class DatabaseFailure(val error: ExceptionWrapper) : AttemptFindResult
}
