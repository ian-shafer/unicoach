package ed.unicoach.queue

import kotlinx.serialization.json.JsonObject

interface JobHandler {
  val jobType: JobType
  val config: JobTypeConfig

  suspend fun execute(payload: JsonObject): JobResult
}
