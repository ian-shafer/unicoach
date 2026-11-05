package ed.unicoach.web.colleges

import ed.unicoach.auth.AuthService
import ed.unicoach.auth.SessionMetadataService
import ed.unicoach.coaching.collegelist.AddToListResult
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.StudentId
import ed.unicoach.student.AccountRoleService
import ed.unicoach.web.UnreadableControl
import ed.unicoach.web.UnreadableMode
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.auth.COLLEGES_PATH
import ed.unicoach.web.auth.WebCaller
import ed.unicoach.web.auth.getStudent
import ed.unicoach.web.auth.postStudent
import ed.unicoach.web.common.http.canonicalUuidOrNull
import ed.unicoach.web.common.http.respondSeeOther
import ed.unicoach.web.formField
import ed.unicoach.web.formParametersOrNull
import ed.unicoach.web.oneValue
import ed.unicoach.web.render.COLLEGE_ID_PARAM
import ed.unicoach.web.render.COLLEGE_QUERY_PARAM
import ed.unicoach.web.render.COLLEGE_SEARCH_PATH
import ed.unicoach.web.render.CollegeListArrival
import ed.unicoach.web.render.CollegeSearchState
import ed.unicoach.web.render.ENTRY_ID_PARAM
import ed.unicoach.web.render.ListReceipt
import ed.unicoach.web.render.MOVED_QUERY_PARAM
import ed.unicoach.web.render.MOVED_QUERY_VALUE
import ed.unicoach.web.render.SEARCH_SHOWN
import ed.unicoach.web.render.SearchResults
import ed.unicoach.web.render.collegeListPath
import ed.unicoach.web.render.respondCollegeListPage
import ed.unicoach.web.render.respondCollegeSearchPage
import ed.unicoach.web.render.signedInNav
import ed.unicoach.web.unreadablePosting
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import org.slf4j.LoggerFactory

/** My colleges' own log: a write it could not make is otherwise only a sentence on a page. */
internal val collegesLog = LoggerFactory.getLogger("ed.unicoach.web.Colleges")

/** What My colleges reads and writes through (RFC 271), held together so the routes take one value. */
internal class CollegeListServices(
  val accountRoles: AccountRoleService,
  val collegeList: CollegeListService,
  val collegeSearch: CollegeSearchService,
)

/** One entry's edit page, as a route path. */
private const val ENTRY_ROUTE = "$COLLEGES_PATH/{$ENTRY_ID_PARAM}"

/**
 * My colleges (RFC 271): the student's college list — browse, search, add,
 * edit, remove — every call in-process (brief 0013 D13).
 *
 * | Method + path                     | Does                                                     |
 * | --------------------------------- | -------------------------------------------------------- |
 * | `GET /colleges`                   | the list, or the empty state                             |
 * | `POST /colleges`                  | add one `collegeId`; 303 to the list with its receipt    |
 * | `GET /colleges/add`               | the search page; `?q=` runs a search                     |
 * | `GET /colleges/{entryId}`         | the edit page, with a drafted edit if this entry has one |
 * | `POST /colleges/{entryId}`        | save `version`, `status`, `reasons`; 303 to the list     |
 * | `GET /colleges/{entryId}/remove`  | "Remove this college?" with the entry's current version  |
 * | `POST /colleges/{entryId}/remove` | remove at `version`; 303 to the list                     |
 *
 * Every one is a student page ([getStudent]/[postStudent]): a supporter has
 * no list, so is sent to their account, as Your details sends one.
 */
internal fun Route.collegeListRoutes(
  services: CollegeListServices,
  sessionMetadata: SessionMetadataService,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  getStudent(COLLEGES_PATH, authService, sessionConfig, services.accountRoles) { caller, studentId ->
    val entries = services.collegeList.listActiveWithNames(studentId).getOrThrow()
    call.respondCollegeListPage(caller.signedInNav, entries, call.request.queryParameters.arrival())
  }

  postStudent(COLLEGES_PATH, authService, sessionConfig, services.accountRoles) { caller, studentId ->
    call.add(services, caller, studentId)
  }

  getStudent(COLLEGE_SEARCH_PATH, authService, sessionConfig, services.accountRoles) { caller, _ ->
    call.search(services, caller)
  }

  getStudent(ENTRY_ROUTE, authService, sessionConfig, services.accountRoles) { caller, studentId ->
    call.showEntry(services, caller, studentId)
  }

  postStudent(ENTRY_ROUTE, authService, sessionConfig, services.accountRoles) { caller, studentId ->
    call.saveEntry(services, sessionMetadata, caller, studentId)
  }

  getStudent("$ENTRY_ROUTE/remove", authService, sessionConfig, services.accountRoles) { caller, studentId ->
    call.showRemove(services, caller, studentId)
  }

  postStudent("$ENTRY_ROUTE/remove", authService, sessionConfig, services.accountRoles) { caller, studentId ->
    call.removeEntry(services, sessionMetadata, caller, studentId)
  }
}

/** The path's entry id, iff it is a canonical UUID. */
internal fun ApplicationCall.pathEntryId(): CollegeListEntryId? =
  parameters[ENTRY_ID_PARAM]?.let(::canonicalUuidOrNull)?.let(::CollegeListEntryId)

/**
 * What the redirect that led to the list asked it to say. Each value is read
 * as a KEY from a closed set: an id that is not a canonical UUID — or that is,
 * but names no row on the list — and any `moved` but `1`, render nothing.
 */
private fun Parameters.arrival(): CollegeListArrival =
  CollegeListArrival(
    receipts =
      ListReceipt.entries
        .mapNotNull { receipt ->
          formField(receipt.key)
            ?.let(::canonicalUuidOrNull)
            ?.let { id -> CollegeListEntryId(id) to receipt }
        }.toMap(),
    moved = formField(MOVED_QUERY_PARAM) == MOVED_QUERY_VALUE,
  )

// --- search and add ------------------------------------------------------------------

/** One run of the name search, as the page answers it. */
private sealed interface SearchRun {
  data class Ran(
    val results: SearchResults,
  ) : SearchRun

  /** The search refused the query, for [why] — which only a hand-made URL or posting sends. */
  data class Refused(
    val why: SearchRefusal,
  ) : SearchRun
}

/** Why a search was refused: the query's shape, never its words, which are a family's. */
private sealed interface SearchRefusal {
  /** `q` was sent [sent] times; the page's form sends it once. */
  data class Repeated(
    val sent: Int,
  ) : SearchRefusal

  /** The query is [actualLength] characters, longer than the [maxLength] the search reads. */
  data class TooLong(
    val actualLength: Int,
    val maxLength: Int,
  ) : SearchRefusal
}

/** What [this] run shows under the form: a refused query shows the prompt. */
private val SearchRun.results: SearchResults
  get() =
    when (this) {
      is SearchRun.Ran -> results
      is SearchRun.Refused -> SearchResults.Prompt
    }

/** The search page's state after [this] run, with no add refused: open, or unreadable for a refused query. */
private val SearchRun.state: CollegeSearchState
  get() =
    when (this) {
      is SearchRun.Ran -> CollegeSearchState.OPEN
      is SearchRun.Refused -> CollegeSearchState.UNREADABLE
    }

/**
 * Runs the one query in [sent] — each `q` the request carried — through the
 * name search, asking for one more than the page shows so a full page can say
 * there were more. No query, or a blank one, is the prompt and runs nothing; a
 * repeated `q`, or one longer than the search reads, is refused. A failed
 * search is a fault, answered by the branded 503.
 */
private suspend fun CollegeSearchService.run(sent: List<String>): SearchRun {
  if (sent.size > 1) return refused(SearchRefusal.Repeated(sent.size))
  val query = sent.singleOrNull()
  if (query.isNullOrBlank()) return SearchRun.Ran(SearchResults.Prompt)
  return searchByName(query, SEARCH_SHOWN + 1).fold(
    onSuccess = { found -> SearchRun.Ran(SearchResults.of(found)) },
    onFailure = { cause ->
      val tooLong = rejectedInput(cause) ?: throw cause
      refused(SearchRefusal.TooLong(tooLong.actualLength, tooLong.maxLength))
    },
  )
}

/** A refused run, logged here once as its unreadable sibling postings are: the refusal's shape, NEVER the query. */
private fun refused(why: SearchRefusal): SearchRun.Refused {
  when (why) {
    is SearchRefusal.Repeated -> collegesLog.info("college search refused: q sent [{}] times", why.sent)
    is SearchRefusal.TooLong -> collegesLog.info("college search refused: query length [{}] over max [{}]", why.actualLength, why.maxLength)
  }
  return SearchRun.Refused(why)
}

/** `GET /colleges/add`: the page, running `q` when there is exactly one. A repeated `q` is a hand-made URL, refused. */
private suspend fun ApplicationCall.search(
  services: CollegeListServices,
  caller: WebCaller,
) {
  val sent = request.queryParameters.getAll(COLLEGE_QUERY_PARAM).orEmpty()
  val run = services.collegeSearch.run(sent)
  respondCollegeSearchPage(caller.signedInNav, run.state, sent.singleOrNull(), run.results)
}

/**
 * `POST /colleges`: adds the posted college at the service's first status,
 * with no reasons, as iOS adds one. A refusal re-runs the search the add form
 * carried back, so the family lands on the results they added from.
 */
private suspend fun ApplicationCall.add(
  services: CollegeListServices,
  caller: WebCaller,
  studentId: StudentId,
) {
  val form = formParametersOrNull()
  val query = form?.formField(COLLEGE_QUERY_PARAM)
  val collegeId =
    when (val posting = form?.addPosting()) {
      null, is AddPosting.Unreadable -> {
        collegesLog.unreadablePosting("college add", posting?.at)
        return respondAddRefused(services, caller, CollegeSearchState.UNREADABLE, query)
      }

      is AddPosting.Add -> {
        posting.collegeId
      }
    }
  services.collegeList
    .addToList(studentId, collegeId, CollegeListEntryStatus.CONSIDERING, reasons = null, livingPlan = null, observationIds = emptyList())
    .fold(
      onSuccess = { outcome ->
        when (outcome) {
          is AddToListResult.Success -> {
            respondSeeOther(collegeListPath(ListReceipt.ADDED, outcome.entry.id))
          }

          AddToListResult.AlreadyOnList -> {
            respondAddRefused(services, caller, CollegeSearchState.ALREADY_ON_LIST, query)
          }

          AddToListResult.CollegeNotFound -> {
            collegesLog.info("college add for student [{}] named no college [{}]", studentId.value, collegeId.value)
            respondAddRefused(services, caller, CollegeSearchState.UNREADABLE, query)
          }

          // The add posts no reasons and no observations, so neither check can refuse it.
          AddToListResult.InvalidReasons, is AddToListResult.ObservationNotFound -> {
            error("college add for student [${studentId.value}] posted no reasons or observations, yet was refused as [$outcome]")
          }
        }
      },
      onFailure = { cause ->
        collegesLog.warn("college [{}] not added for student [{}]", collegeId.value, studentId.value, cause)
        respondAddRefused(services, caller, CollegeSearchState.NOT_SAVED, query)
      },
    )
}

/** The search page in [state] after a refused add, the carried-back [query] run again. */
private suspend fun ApplicationCall.respondAddRefused(
  services: CollegeListServices,
  caller: WebCaller,
  state: CollegeSearchState,
  query: String?,
) {
  respondCollegeSearchPage(caller.signedInNav, state, query, services.collegeSearch.run(listOfNotNull(query)).results)
}

/** An add form's posting, read as the one college it adds or as not this page's. */
private sealed interface AddPosting {
  data class Add(
    val collegeId: CollegeId,
  ) : AddPosting

  data class Unreadable(
    val at: UnreadableControl,
  ) : AddPosting
}

/** The add grammar: exactly one `collegeId`, a canonical UUID. The carried-back `q` is read beside it, not by it. */
private fun Parameters.addPosting(): AddPosting {
  val raw = oneValue(COLLEGE_ID_PARAM) { return AddPosting.Unreadable(it) }
  val id = canonicalUuidOrNull(raw) ?: return AddPosting.Unreadable(UnreadableControl(COLLEGE_ID_PARAM, UnreadableMode.MALFORMED))
  return AddPosting.Add(CollegeId(id))
}
