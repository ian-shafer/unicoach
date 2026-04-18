package ed.unicoach.queue

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

data class Job(
    val id: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val jobType: JobType,
    val payload: JsonObject,
    val status: JobStatus,
    val scheduledAt: Instant,
    val lockedUntil: Instant?,
    val maxAttempts: Int?,
)
