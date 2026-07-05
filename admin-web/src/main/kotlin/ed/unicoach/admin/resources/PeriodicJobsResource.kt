package ed.unicoach.admin.resources

import ed.unicoach.admin.engine.AdminEdge
import ed.unicoach.admin.engine.AdminField
import ed.unicoach.admin.engine.AdminKind
import ed.unicoach.admin.engine.AdminResource
import ed.unicoach.admin.engine.CustomAction
import ed.unicoach.admin.engine.FieldType
import ed.unicoach.admin.render.respondDaoError
import ed.unicoach.cron.PeriodicJob
import ed.unicoach.cron.PeriodicJobName
import ed.unicoach.cron.dao.PeriodicFindResult
import ed.unicoach.cron.dao.PeriodicJobsDao
import ed.unicoach.cron.dao.PeriodicListResult
import ed.unicoach.cron.dao.SetEnabledResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.models.SoftDeleteScope
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

/**
 * The `periodic_jobs` schedule table (RFC 97), surfaced read-only with a single
 * mutable column, `enabled`, flipped by a per-row action rather than the edit
 * form. All row fields are read-only (`create`/`update`/`delete`/`undelete` are
 * null), so the engine registers no create/edit/delete routes. The natural key is
 * the `name`, so the id type is [PeriodicJobName]: `parseId` delegates to
 * [PeriodicJobName.parse], which allowlists a lowercase slug
 * ([PeriodicJobName.PATTERN]) — so a parsed name can never carry `CR`/`LF`/`/`/
 * `?`/`#` into the redirect `Location` header — and `idToPath` returns the name.
 *
 * `enabled` is toggled by two [CustomAction]s — `Enable` and `Disable` — exactly
 * one of which is active per row state (the other renders disabled with its reason
 * as tooltip). Two buttons rather than one because [CustomAction.label] is a
 * static string, not a function of the row.
 */
class PeriodicJobsResource(
  private val periodicJobsDao: PeriodicJobsDao,
) : AdminResource<PeriodicJob, PeriodicJobName> {
  private val logger = LoggerFactory.getLogger(PeriodicJobsResource::class.java)

  override val slug = "periodic-job"
  override val title = "Periodic Job"
  override val kind = AdminKind.NON_ENTITY
  override val topLevel = true

  override val fields =
    listOf(
      AdminField("name", "Name", FieldType.TEXT, editable = false, sensitive = false, refSlug = "periodic-job"),
      AdminField("jobType", "Job Type", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("schedule", "Schedule", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("timezone", "Timezone", FieldType.TEXT, editable = false, sensitive = false),
      AdminField("nextRunAt", "Next Run", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("lastRunAt", "Last Run", FieldType.TIMESTAMP, editable = false, sensitive = false),
      AdminField("enabled", "Enabled", FieldType.BOOL, editable = false, sensitive = false),
      AdminField("payload", "Payload", FieldType.JSON, editable = false, sensitive = false, inList = false),
      AdminField("createdAt", "Created", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
      AdminField("updatedAt", "Updated", FieldType.TIMESTAMP, editable = false, sensitive = false, inList = false),
    )

  override val edges = emptyList<AdminEdge>()

  override fun rowId(row: PeriodicJob): PeriodicJobName = row.name

  /**
   * Allowlists the path segment via [PeriodicJobName.parse] (a lowercase slug,
   * bounded by the DB's 128-char name limit). Rejecting anything outside the
   * allowlist keeps `CR`/`LF`/`/`/`?`/`#` out of the redirect `Location` header.
   */
  override fun parseId(raw: String): PeriodicJobName? = PeriodicJobName.parse(raw)

  override fun idToPath(id: PeriodicJobName): String = id.value

  override fun isDeleted(row: PeriodicJob): Boolean = false

  override fun cells(row: PeriodicJob): Map<String, String> =
    mapOf(
      "name" to row.name.value,
      "jobType" to row.jobType.value,
      "schedule" to row.schedule,
      "timezone" to row.timezone.id,
      "nextRunAt" to row.nextRunAt.toString(),
      "lastRunAt" to (row.lastRunAt?.toString() ?: ""),
      "enabled" to row.enabled.toString(),
      "payload" to row.payload.toString(),
      "createdAt" to row.createdAt.toString(),
      "updatedAt" to row.updatedAt.toString(),
    )

  /**
   * Lists the healthy rows. `list()` degrades per-row: a corrupt row (unknown
   * `job_type` / malformed `payload`) never folds the whole table into a 500 —
   * the healthy rows still render. Each corrupt row is logged by name + field +
   * value so it is surfaced (not silently dropped) and the operator can find and
   * quarantine it (disable works on a corrupt row, see [setEnabledRoute]).
   *
   * The shared admin list render is keyed on [PeriodicJob], which cannot represent
   * a corrupt row (its `jobType` is a non-null [ed.unicoach.queue.JobType] enum,
   * and a corrupt row is corrupt precisely because no enum value matches). Rather
   * than fabricate a lossy placeholder row or widen the shared render engine
   * (out of scope), corrupt rows are surfaced through the log here and the detail
   * path's [PeriodicFindResult.Corrupt] handling in [get].
   */
  override suspend fun list(
    db: Database,
    limit: Int,
    offset: Int,
    scope: SoftDeleteScope,
  ): Result<List<PeriodicJob>> =
    db.withConnection { session ->
      when (val result = periodicJobsDao.list(session)) {
        is PeriodicListResult.Success -> {
          result.corruptRows.forEach { corrupt ->
            logger.warn(
              "Periodic job [{}] omitted from the admin list: corrupt [{}] value [{}]",
              corrupt.name.value,
              corrupt.field,
              corrupt.value,
              corrupt.cause,
            )
          }
          Result.success(result.jobs)
        }

        is PeriodicListResult.DatabaseFailure -> {
          Result.failure(result.error)
        }
      }
    }

  override suspend fun get(
    db: Database,
    id: PeriodicJobName,
    includeDeleted: Boolean,
  ): Result<PeriodicJob> =
    db.withConnection { session ->
      when (val result = periodicJobsDao.findByName(session, id)) {
        is PeriodicFindResult.Success -> {
          Result.success(result.job)
        }

        is PeriodicFindResult.NotFound -> {
          // The shared respondDaoError NotFound branch renders a generic 404 and
          // does not read the exception message, so surface the row name here (the
          // local, in-scope site that owns it) rather than widening that chokepoint.
          logger.warn(notFoundMessage(result.name))
          Result.failure(NotFoundException(notFoundMessage(result.name)))
        }

        // A corrupt row cannot be rendered as a PeriodicJob detail (no enum value
        // matches its job_type / its payload will not parse), and the shared detail
        // render is not widened here (out of scope). Log the row name + reason and
        // fail as a not-found detail rather than a bare 500, so the operator still
        // learns which row is corrupt and why, and can disable it by name.
        is PeriodicFindResult.Corrupt -> {
          logger.warn("Periodic job [{}] detail is corrupt: [{}]", result.name.value, result.cause.message, result.cause)
          Result.failure(NotFoundException(corruptMessage(result.name, result.cause.field)))
        }

        is PeriodicFindResult.DatabaseFailure -> {
          Result.failure(result.error)
        }
      }
    }

  override val create: (suspend (Database, Map<String, String>) -> Result<PeriodicJobName>)? = null
  override val update: (suspend (Database, PeriodicJobName, Map<String, String>) -> Result<Unit>)? = null
  override val delete: (suspend (Database, PeriodicJobName) -> Result<Unit>)? = null
  override val undelete: (suspend (Database, PeriodicJobName) -> Result<Unit>)? = null

  override val customActions =
    listOf(
      CustomAction<PeriodicJob>("Enable", "enable") { row ->
        if (row.enabled) "Already enabled." else null
      },
      CustomAction<PeriodicJob>("Disable", "disable") { row ->
        if (!row.enabled) "Already disabled." else null
      },
    )

  override fun registerExtraRoutes(
    scope: Route,
    db: Database,
  ) {
    scope.post("/$slug/{id}/enable") { setEnabledRoute(db, enabled = true) }
    scope.post("/$slug/{id}/disable") { setEnabledRoute(db, enabled = false) }
  }

  /**
   * Shared enable/disable handler: parses the name (malformed → redirect to the
   * list, per the extra-route convention), flips `enabled`, and dispatches the
   * result — `Success` → detail redirect, `NotFound` → 404, `DatabaseFailure` →
   * `respondDaoError`. Idempotent: setting an already-`enabled` row re-Successes.
   */
  private suspend fun io.ktor.server.routing.RoutingContext.setEnabledRoute(
    db: Database,
    enabled: Boolean,
  ) {
    val name = parseId(call.parameters["id"].orEmpty()) ?: return call.respondRedirect("/$slug")
    val result = db.withConnection { session -> periodicJobsDao.setEnabled(session, name, enabled) }
    when (result) {
      // setEnabled confirms from the was_updated flag alone (never mapRow), so a
      // Success here covers a corrupt row too — the operator's quarantine remedy.
      is SetEnabledResult.Success -> {
        call.respondRedirect("/$slug/${name.value}")
      }

      is SetEnabledResult.NotFound -> {
        // The shared respondDaoError NotFound branch renders a generic 404 and does
        // not read the exception message, so surface the row name here (the local,
        // in-scope site that owns it) rather than widening that shared chokepoint.
        logger.warn(notFoundMessage(result.name))
        call.respondDaoError(NotFoundException(notFoundMessage(result.name)))
      }

      is SetEnabledResult.DatabaseFailure -> {
        call.respondDaoError(result.error)
      }
    }
  }

  /** Formats the operator-facing not-found sentence at the admin edge (the DAO carries only the id). */
  private fun notFoundMessage(name: PeriodicJobName): String = "Periodic job [${name.value}] not found."

  /** Formats the operator-facing corrupt-detail sentence (which row, which field is corrupt). */
  private fun corruptMessage(
    name: PeriodicJobName,
    field: String,
  ): String = "Periodic job [${name.value}] has a corrupt [$field] value and cannot be displayed; disable it to quarantine it."
}
