package ed.unicoach.web.render

import ed.unicoach.college.CollegeSearchService
import ed.unicoach.db.models.CollegeSummary
import ed.unicoach.web.auth.COLLEGES_PATH
import ed.unicoach.web.auth.CsrfToken
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.ul

// Add a college (RFC 271): a name search whose every result can be added to
// the list. Copy is the iOS app's (`CollegeSearchView`), verbatim, except where
// marked new. iOS searches as you type; this page searches on submit (D-B), a
// GET form whose query is in the URL, so Back and reload keep it.

/** The search page. */
const val COLLEGE_SEARCH_PATH = "$COLLEGES_PATH/add"

/** The search's one control: its name in the GET form, and the hidden field an Add carries it back in. */
const val COLLEGE_QUERY_PARAM = "q"

/** The hidden field naming the college an Add posts. */
const val COLLEGE_ID_PARAM = "collegeId"

/** How many results the page shows; it asks the search for one more, and that one is the capped signal. */
const val SEARCH_SHOWN = 20

const val COLLEGE_NAME_LABEL = "College name"

/** No query yet, or a blank one. */
const val SEARCH_PROMPT = "Search by college name."

const val NO_COLLEGES_MATCH = "No colleges match."

/** The search form's submit (RFC 271, new: iOS searches as you type). */
const val SEARCH_LABEL = "Search"

/** A result's add button (RFC 271, new: iOS adds on a tap of the row). */
const val ADD_LABEL = "Add"

/** More matches than the page shows (RFC 271, new). */
const val CAPPED_NOTICE = "Showing the first $SEARCH_SHOWN matches. Type more of the name to narrow them down."

/** An add of a college already on the list: the service's REST wording, which iOS shows. */
const val ALREADY_ON_LIST_MESSAGE = "College is already on the list"

/** The class the page's section carries. */
const val COLLEGE_SEARCH_CLASS = "college-search"

/** The class the search form carries. */
const val COLLEGE_SEARCH_FORM_CLASS = "college-search-form"

/** The class the results list carries; each result is a card in it. */
const val COLLEGE_RESULTS_CLASS = "college-results"

/** What the page shows under its form. */
sealed interface SearchResults {
  /** No query, or a blank one — or one the search refused, which only a hand-made URL sends. */
  data object Prompt : SearchResults

  data object NoMatch : SearchResults

  /** The first [SEARCH_SHOWN] matches; [capped] when the search found more. */
  data class Matches(
    val shown: List<CollegeSummary>,
    val capped: Boolean,
  ) : SearchResults

  companion object {
    /** [found] — at most [SEARCH_SHOWN] + 1 matches — as the page shows them. */
    fun of(found: List<CollegeSummary>): SearchResults =
      if (found.isEmpty()) NoMatch else Matches(found.take(SEARCH_SHOWN), capped = found.size > SEARCH_SHOWN)
  }
}

/** The states the search page is rendered in, each owning its status and what it says above the results. */
enum class CollegeSearchState(
  val status: HttpStatusCode,
) {
  /** A GET: the prompt, or a search's results. */
  OPEN(HttpStatusCode.OK),

  /** Words the search refuses, or an add posting this page's forms did not send: the page, and nothing said. */
  UNREADABLE(HttpStatusCode.BadRequest),

  /** An add of a college already on the list. */
  ALREADY_ON_LIST(HttpStatusCode.Conflict),

  /** The add failed. */
  NOT_SAVED(HttpStatusCode.ServiceUnavailable),
}

/**
 * `/colleges/add`: the search form, what [state] says, then [results]. [query]
 * is the search's words as sent, `null` for none.
 */
suspend fun ApplicationCall.respondCollegeSearchPage(
  nav: ShellNav.SignedIn,
  state: CollegeSearchState,
  query: String?,
  results: SearchResults,
) {
  respondHtml(state.status) {
    siteLayout(ADD_A_COLLEGE, width = ShellWidth.WIDE, nav = nav) {
      section(COLLEGE_SEARCH_CLASS) {
        h1 { +ADD_A_COLLEGE }
        backToMyColleges()
        searchForm(query)
        when (state) {
          CollegeSearchState.OPEN, CollegeSearchState.UNREADABLE -> Unit
          CollegeSearchState.ALREADY_ON_LIST -> p(NOTICE_CLASS) { +ALREADY_ON_LIST_MESSAGE }
          CollegeSearchState.NOT_SAVED -> formError(NOT_SAVED_MESSAGE)
        }
        searchResults(results, query, nav.csrf)
      }
    }
  }
}

/** The GET form: one labelled box and its button. It changes nothing, so it carries no CSRF token. */
private fun FlowContent.searchForm(query: String?) {
  form(action = COLLEGE_SEARCH_PATH, method = FormMethod.get, classes = COLLEGE_SEARCH_FORM_CLASS) {
    div(FIELD_CLASS) {
      label {
        htmlFor = COLLEGE_QUERY_PARAM
        +COLLEGE_NAME_LABEL
      }
      input(type = InputType.search, name = COLLEGE_QUERY_PARAM) {
        id = COLLEGE_QUERY_PARAM
        maxLength = CollegeSearchService.MAX_QUERY_LENGTH.toString()
        autoFocus = true
        attributes["autocomplete"] = "off"
        if (query != null) value = query
      }
    }
    button(type = ButtonType.submit) { +SEARCH_LABEL }
  }
}

private fun FlowContent.searchResults(
  results: SearchResults,
  query: String?,
  csrf: CsrfToken,
) {
  when (results) {
    SearchResults.Prompt -> {
      p { +SEARCH_PROMPT }
    }

    SearchResults.NoMatch -> {
      p { +NO_COLLEGES_MATCH }
    }

    is SearchResults.Matches -> {
      ul(COLLEGE_RESULTS_CLASS) {
        for (college in results.shown) {
          li { resultCard(college, query, csrf) }
        }
      }
      if (results.capped) p(NOTICE_CLASS) { +CAPPED_NOTICE }
    }
  }
}

/**
 * One result: its name, "City, ST", and a one-button form that adds it. The
 * form carries the search's words back, so a refused add returns to the same
 * results; the button's accessible name is "Add <name>", as on iOS.
 */
private fun FlowContent.resultCard(
  college: CollegeSummary,
  query: String?,
  csrf: CsrfToken,
) {
  p { +college.name }
  p(FIELD_CAPTION_CLASS) { +"${college.city}, ${college.state}" }
  signedInForm(COLLEGES_PATH, csrf) {
    hiddenInput(name = COLLEGE_ID_PARAM) { value = college.id.value.toString() }
    if (query != null) hiddenInput(name = COLLEGE_QUERY_PARAM) { value = query }
    button(type = ButtonType.submit) {
      attributes["aria-label"] = "$ADD_LABEL ${college.name}"
      +ADD_LABEL
    }
  }
}
