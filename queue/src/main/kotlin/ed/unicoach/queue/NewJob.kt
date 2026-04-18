package ed.unicoach.queue

import kotlinx.serialization.json.JsonObject

data class NewJob(
    val jobType: JobType,
    val payload: JsonObject,
    val maxAttempts: Int?,
    val delay: kotlin.time.Duration?,
)
