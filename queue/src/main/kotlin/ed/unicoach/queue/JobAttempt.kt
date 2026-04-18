package ed.unicoach.queue

import java.time.Instant
import java.util.UUID

data class JobAttempt(
    val id: UUID,
    val jobId: UUID,
    val attemptNumber: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
    val status: AttemptStatus,
    val errorMessage: String?,
)
