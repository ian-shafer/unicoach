package ed.unicoach.web.render

import ed.unicoach.coaching.collegelist.EntryWithCollegeName
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.web.auth.COLLEGES_PATH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.FlowContent
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

// My colleges (RFC 271): the student's college list — name, status and the
// first line of their reasons — with the way to add, edit and remove one. Copy
// is the iOS app's (`CollegeListView`), verbatim, except where marked new.

/** The page's name: its heading, its title, and the header link that opens it. */
const val MY_COLLEGES_TITLE = "My colleges"

/** The list's way to the search page, and the search page's heading. */
const val ADD_A_COLLEGE = "Add a college"

/** The list with nothing on it. */
const val NO_COLLEGES_YET = "No colleges yet. Add one, or ask your coach."

// The table's column headings (RFC 271 §4). The status and reasons headings
// are the edit page's own labels, so a column cannot call a value something
// the form that sets it does not.
const val COLLEGE_COLUMN = "College"

/** The link on every row, and the edit page's, that opens the remove confirmation. */
const val REMOVE_COLLEGE_LABEL = "Remove"

/** The receipt beside a college a redirect just added (RFC 271, new). */
const val ADDED_RECEIPT = "Added"

/** A save or remove that met a list changed elsewhere (RFC 271, new). */
const val MOVED_NOTICE =
  "Your list changed somewhere else, so your last change wasn't made. This is your list as it is now."

/** The search, edit and remove pages' way back (RFC 271, new). */
const val BACK_TO_MY_COLLEGES = "Back to $MY_COLLEGES_TITLE"

/** The class the page's section carries. */
const val COLLEGE_LIST_CLASS = "college-list"

/** The class the list's table carries, on top of [DATA_TABLE_CLASS]: what it tunes of the shared table. */
const val COLLEGE_LIST_TABLE_CLASS = "college-list-table"

/** The class the "Remove" cell carries: the narrow column on a laptop. */
const val COLLEGE_REMOVE_CELL_CLASS = "college-remove"

/** The class the status cell carries: one line in the grid, so a narrow column never splits a status word. */
const val COLLEGE_STATUS_CELL_CLASS = "college-status-cell"

/** The class an "Added"/"Saved" receipt carries. */
const val COLLEGE_RECEIPT_CLASS = "college-receipt"

/** The class the "Add a college" link carries: a link styled as the site's primary button. */
const val COLLEGE_ADD_CLASS = "college-add"

/** The class the "Back to My colleges" link's paragraph carries. */
const val COLLEGE_BACK_CLASS = "college-back"

/** The query key that asks the list for the moved notice, and the one value that does. */
const val MOVED_QUERY_PARAM = "moved"

const val MOVED_QUERY_VALUE = "1"

/** The path segment that names one entry: `/colleges/{entryId}`. */
const val ENTRY_ID_PARAM = "entryId"

/** One entry's edit page. */
fun collegeEntryPath(entryId: CollegeListEntryId): String = "$COLLEGES_PATH/${entryId.asString}"

/** One entry's remove confirmation. */
fun collegeRemovePath(entryId: CollegeListEntryId): String = "${collegeEntryPath(entryId)}/remove"

/** The list, landed on [entryId]'s row with [receipt] beside its name. */
fun collegeListPath(
  receipt: ListReceipt,
  entryId: CollegeListEntryId,
): String = "$COLLEGES_PATH?${receipt.key}=${entryId.asString}#${entryId.asString}"

/** The list with the moved notice. */
const val MOVED_LIST_PATH = "$COLLEGES_PATH?$MOVED_QUERY_PARAM=$MOVED_QUERY_VALUE"

/**
 * A status's words (RFC 271): web copy at the edge (brief 0013 D13), the iOS
 * app's `CollegeListStatus.displayName`. Exhaustive, so a new status is a
 * compile error here rather than a row with no words.
 */
val CollegeListEntryStatus.label: String
  get() =
    when (this) {
      CollegeListEntryStatus.CONSIDERING -> "Considering"
      CollegeListEntryStatus.APPLYING -> "Applying"
      CollegeListEntryStatus.ADMITTED -> "Admitted"
      CollegeListEntryStatus.REJECTED -> "Rejected"
    }

/**
 * The receipts a post-redirect-get can ask the list for. [key] is the query key
 * that names the entry, and [label] the word beside its name: a KEY from this
 * closed set, as a [WebNotice] is, and an entry id that is not on the rendered
 * list matches no row, so no text from a URL reaches the page.
 */
enum class ListReceipt(
  val key: String,
  val label: String,
) {
  ADDED("added", ADDED_RECEIPT),
  SAVED(SAVED_QUERY_PARAM, SAVED_RECEIPT),
}

/** What the redirect that led here asked the list to say: a receipt per entry id, and the moved notice. */
data class CollegeListArrival(
  val receipts: Map<CollegeListEntryId, ListReceipt>,
  val moved: Boolean,
)

/**
 * `/colleges`: the active list in the order the student added each school, as
 * a [DATA_TABLE_CLASS] table — a grid on a laptop, one card per college on a
 * phone — or the empty state.
 */
suspend fun ApplicationCall.respondCollegeListPage(
  nav: ShellNav.SignedIn,
  entries: List<EntryWithCollegeName>,
  arrival: CollegeListArrival,
) {
  respondHtml(HttpStatusCode.OK) {
    siteLayout(MY_COLLEGES_TITLE, width = ShellWidth.WIDE, nav = nav) {
      section(COLLEGE_LIST_CLASS) {
        h1 { +MY_COLLEGES_TITLE }
        if (arrival.moved) p(NOTICE_CLASS) { +MOVED_NOTICE }
        if (entries.isEmpty()) {
          p { +NO_COLLEGES_YET }
          addCollegeLink()
        } else {
          addCollegeLink()
          collegeTable(entries, arrival.receipts)
        }
      }
    }
  }
}

/** "Add a college": a link to the search page, styled as the site's primary button. */
private fun FlowContent.addCollegeLink() {
  p { a(href = COLLEGE_SEARCH_PATH, classes = COLLEGE_ADD_CLASS) { +ADD_A_COLLEGE } }
}

/** The list's table: one row per entry, each row's `id` the entry's, so `#<entryId>` lands on it. */
private fun FlowContent.collegeTable(
  entries: List<EntryWithCollegeName>,
  receipts: Map<CollegeListEntryId, ListReceipt>,
) {
  table("$DATA_TABLE_CLASS $COLLEGE_LIST_TABLE_CLASS") {
    thead {
      tr {
        th { +COLLEGE_COLUMN }
        th { +STATUS_LABEL }
        th { +REASONS_LABEL }
        // The remove column has no heading: its one link says what it does.
        th {}
      }
    }
    tbody {
      for ((entry, collegeName) in entries) {
        tr {
          id = entry.id.asString
          collegeRow(entry.id, collegeName, entry.status, entry.reasons, receipts[entry.id])
        }
      }
    }
  }
}

/** One row's four cells; each data cell re-labels itself with its column on a phone. */
private fun TR.collegeRow(
  entryId: CollegeListEntryId,
  collegeName: String,
  status: CollegeListEntryStatus,
  reasons: String?,
  receipt: ListReceipt?,
) {
  labelledCell(COLLEGE_COLUMN) {
    a(href = collegeEntryPath(entryId)) { +collegeName }
    if (receipt != null) span(COLLEGE_RECEIPT_CLASS) { +receipt.label }
  }
  labelledCell(STATUS_LABEL, COLLEGE_STATUS_CELL_CLASS) { +status.label }
  labelledCell(REASONS_LABEL) { firstReasonsLine(reasons)?.let { +it } }
  td(COLLEGE_REMOVE_CELL_CLASS) { a(href = collegeRemovePath(entryId)) { +REMOVE_COLLEGE_LABEL } }
}

/**
 * The first line of [reasons], trimmed, or `null` when there are none — what
 * the iOS row shows (`CollegeListView.firstReasonsLine`).
 */
internal fun firstReasonsLine(reasons: String?): String? =
  reasons
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.lineSequence()
    ?.first()
    ?.trim()

/** "Back to My colleges", under a page's heading. */
internal fun FlowContent.backToMyColleges() {
  p(COLLEGE_BACK_CLASS) { a(href = COLLEGES_PATH) { +BACK_TO_MY_COLLEGES } }
}
