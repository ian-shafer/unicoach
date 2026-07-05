package ed.unicoach.cron

import ed.unicoach.queue.JobType
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

/**
 * A full `periodic_jobs` row (RFC 97): the schedule the `:cron` process claims
 * from and enqueues onto the queue. A mutable operational record, not a domain
 * entity — its natural key is [name], not a synthetic id.
 *
 * [jobType] is mapped through [JobType.fromValue] at DAO read time (as
 * `jobs.job_type` is); [timezone] via [ZoneId.of]. [schedule] is the source of
 * truth; [nextRunAt] is a value derived from it and recomputed whenever the row
 * fires (or whenever [schedule] changes).
 */
data class PeriodicJob(
  val name: PeriodicJobName,
  val jobType: JobType,
  val payload: JsonObject,
  val schedule: String,
  val timezone: ZoneId,
  val nextRunAt: Instant,
  val lastRunAt: Instant?,
  val enabled: Boolean,
  val createdAt: Instant,
  val updatedAt: Instant,
)
