# RFC 271 — The college list on the web

**Slice:** `web/08/college-list` (brief 0013, _Unicoach on the web_).
**Status:** approved (Ian, 2026-11-04: "Approve all"). **Base:**
`main@42e75798`. **Lane:** rfc.

## Executive Summary

A signed-in student gets "My colleges" at `/colleges`, reached from the
signed-in header. It lists the colleges on their list — name, status and the
first line of their reasons — as a table on a laptop and one card per college on
a phone. From it they can search for a college and add it, open one to change
its status and reasons, and remove one after confirming.

Every call is in-process (brief 0013 D13): `CollegeListService` for the list,
`CollegeSearchService` for search. The edit form carries the entry's `version`
and resends `status`. When the list moved under them — another device, or the
coach in chat — the change is not made, the family lands on the list as it now
is, and a notice says why. What they typed is kept in the session (RFC 265).

The pages copy the iOS app's screens (`CollegeListView`, `CollegeSearchView`,
`CollegeEntryDetailView`) and their words, verbatim except where marked **new**.

## Detailed Design

### 1. What the code says, where it differs from the slice

- **Search is in `:college`, which `public-web` does not depend on.**
  `CollegeSearchService.searchByName(query, limit)` is what the REST search
  calls; `:service` depends on `:college` with `implementation`, so it does not
  reach `public-web`. `public-web` gains `implementation(project(":college"))`
  (it already has the test fixtures).
- **Nothing says results were capped.** `searchByName` returns a bare list. The
  page asks for one more than it shows (`SEARCH_SHOWN + 1` = 21, inside the
  service's 1..25 clamp) and shows at most `SEARCH_SHOWN` = 20; a 21st result is
  the signal. There is no paging, and the page offers none.
- **Names come with the list.** `listActiveWithNames` returns each active entry
  with its college name, oldest first — the order iOS shows. The edit page reads
  `getForStudent` (the entry and its supporting observations) and one name
  through `listNames`.
- **iOS removes by swipe**, which a desktop does not have. The web puts a
  "Remove" link on every row and on the edit page; both open a confirmation
  page.
- **iOS recovers a 409 by reloading the list and showing the server's message**
  ("College list entry was modified concurrently") in an alert. The web reloads
  the same way and says it in words a family reads (section 5).
- **The route's own checks** that the service does not make are made here too:
  the status parse, the path id, the `version` as an integer. Living plan is not
  on any iOS screen and is not offered; every update passes
  `LivingPlanUpdate.Keep`. Reasons are passed as typed, except that a blank or
  whitespace-only box clears them, as the iOS app does
  (`CollegeEntryDetailView.swift:138-141`).

### 2. Routes

All are `ONBOARDED`: `mapPathToAccess` gains `/colleges` and `/colleges/…`
beside `/account` and `/chat`. Every handler then asks the caller's
`AccountRole`: a supporter has no list and is sent to `/account`, as Your
details sends one (RFC 270).

| Method + path                     | Does                                                     |
| --------------------------------- | -------------------------------------------------------- |
| `GET /colleges`                   | the list, or the empty state                             |
| `POST /colleges`                  | add one `collegeId`; 303 to the list with its receipt    |
| `GET /colleges/add`               | the search page; `?q=` runs a search                     |
| `GET /colleges/{entryId}`         | the edit page, with a drafted edit if this entry has one |
| `POST /colleges/{entryId}`        | save `version`, `status`, `reasons`; 303 to the list     |
| `GET /colleges/{entryId}/remove`  | "Remove this college?" with the entry's current version  |
| `POST /colleges/{entryId}/remove` | remove at `version`; 303 to the list                     |

The search is a GET form: its query is in the URL, so Back and reload keep it,
and it is not a session draft (a POST form's rail, RFC 265). The add and remove
forms carry nothing the family typed, so they have no draft either.

**Path id.** Read through `CollegeListEntryId` from a canonical UUID. Anything
else is the site's 404 page, as is a GET of an entry that is not this student's.
A readable save or remove of such an entry gets the service's `NotFound`, which
it also answers for an entry removed meanwhile, so it is answered as the list
having moved; nothing is written.

**Add (`POST /colleges`).** Exactly one `collegeId`, a UUID.

| Outcome                               | Answer                                                     |
| ------------------------------------- | ---------------------------------------------------------- |
| `Success`                             | 303 `/colleges?added=<entryId>#<entryId>`                  |
| `AlreadyOnList`                       | 409, the search page re-run for the posted `q`, the notice |
|                                       | "College is already on the list"                           |
| `CollegeNotFound`, unreadable posting | 400, the search page as posted, nothing said, logged       |
| failure                               | logged; 503, the search page with "That didn't save. Try   |
|                                       | again."                                                    |

The add form posts the search's `q` back in a hidden field so a refused add
returns to the same results. Status is the service's default, `considering`; no
reasons, living plan or observations, as iOS sends none.

**Save (`POST /colleges/{entryId}`).** Exactly one `version` (an integer),
exactly one `status` (one of the four `CollegeListEntryStatus` values), exactly
one `reasons` (any string; blank or whitespace-only → `null`).

| Outcome                       | Answer                                                   |
| ----------------------------- | -------------------------------------------------------- |
| `Success`                     | draft cleared; 303 `/colleges?saved=<entryId>#<entryId>` |
| `VersionConflict`, `NotFound` | typing kept in the draft (D-A); 303 `/colleges?moved=1`  |
| `InvalidReasons`              | 400, the edit page as posted, "Reasons must be non-empty |
|                               | and at most 2048 characters", typing kept in the draft   |
| failure                       | logged; 503, the edit page as posted, "That didn't save. |
|                               | Try again.", typing kept in the draft                    |
| unreadable posting            | 400, the edit page as stored, nothing written, no draft  |

`ObservationNotFound` cannot occur (no observations are posted); the exhaustive
`when` names it and treats it as a fault.

**Remove (`POST /colleges/{entryId}/remove`).** Exactly one `version`.

| Outcome                       | Answer                                               |
| ----------------------------- | ---------------------------------------------------- |
| `Success`                     | that entry's draft, if any, cleared; 303 `/colleges` |
| `VersionConflict`, `NotFound` | 303 `/colleges?moved=1`                              |
| failure                       | logged; 503, the confirmation page with "That didn't |
|                               | save. Try again."                                    |
| unreadable posting            | 400, the confirmation page as it stands              |

**Query keys** (`added`, `saved`, `moved`) are read through closed types: an
entry id that is not on the rendered list, or any `moved` value but `1`, renders
nothing — no text from a URL reaches the page (the `WebNotice` rule).

### 3. The draft (D11 — schema diff presented at approval)

```proto
message FormDrafts {
  ChangeEmailDraft change_email = 1;
  StudentSignupDraft student_signup = 2;
  MoneyProfileDraft money_profile = 3;
  CollegeEntryDraft college_entry = 4;
}

// The college list's edit form (public-web, /colleges/{id}, RFC 271): what the
// family typed into one entry's form and did not save. entry_id names that
// entry; the draft is offered only on its page, and a failed save of another
// entry replaces it.
message CollegeEntryDraft {
  string entry_id = 1;
  string status = 2;
  string reasons = 3;
}
```

Strings as posted (RFC 265). `entry_id` is our own hidden value but is kept as
the UUID's string, the form RFC 265 allows. The version is NOT drafted: a
drafted edit is always saved against the version read when the page is next
opened, so the family re-saves knowingly over whatever changed (D-A). One
`FormSlot` (`COLLEGE_ENTRY_DRAFT`) beside the page code.

On `GET /colleges/{entryId}` with a draft for that entry, the status and reasons
start on the drafted values, and the form says "Not saved yet." above its Save
button. A successful save or remove of that entry clears it.

### 4. The pages

Server-rendered under the signed-in header, `ShellWidth.WIDE`.

**The list (`/colleges`).** Heading "My colleges"; an "Add a college" button (a
link styled as the site's primary button). The entries are a `DATA_TABLE_CLASS`
table — the Family Cost Report's mechanism, a grid on a laptop and one card per
row below 640px, each cell relabelled by `data-label`:

| Column                | Cell                                                    |
| --------------------- | ------------------------------------------------------- |
| College               | the name, a link to `/colleges/{id}`                    |
| Status                | the status label                                        |
| Why it's on your list | the first line of the reasons, trimmed; empty when none |
| (no heading)          | "Remove", a link to `/colleges/{id}/remove`             |

Each row's `id` is the entry id, so `#<entryId>` lands on it; an `added` or
`saved` row carries the receipt beside its name. Above the table, `?moved=1`
shows the moved notice. The empty state replaces the table: "No colleges yet.
Add one, or ask your coach." with the "Add a college" button.

**Search (`/colleges/add`).** Heading "Add a college". A GET form: the input
`q`, labelled "College name", `maxlength=100`, autofocused, and a "Search"
button. Under it, one of:

| State                   | Shows                                                       |
| ----------------------- | ----------------------------------------------------------- |
| no `q`, or a blank one  | "Search by college name."                                   |
| results                 | a list of cards: name, then "City, ST", and an "Add" button |
| results, more than 20   | the first 20, then the capped notice                        |
| none                    | "No colleges match."                                        |
| `q` over 100 characters | 400, "Search by college name." (only a hand-made URL)       |

Each "Add" is its own one-button form (hidden `collegeId` and `q`); its
accessible name is "Add <college name>", as on iOS. A link "Back to My colleges"
sits under the heading.

**Edit (`/colleges/{entryId}`).** Heading: the college name. Then:

1. "Status" — a fieldset of four radio buttons, in enum order, the stored (or
   drafted) one checked. On a phone they wrap, each on the 44px tap floor.
2. "Why it's on your list" — a `textarea`, placeholder "Add your reasons",
   `maxlength=2048`, the stored (or drafted) reasons.
3. "From your conversations" — only when the entry has supporting observations:
   each quote in curly quotes, read-only.
4. "Save" (the submit), and "Remove" (a link to the confirmation page).

Hidden: `version` (as read for this render). A link "Back to My colleges".

**Remove (`/colleges/{entryId}/remove`).** Heading "Remove this college?", the
college's name, "Its status and reasons are discarded.", a "Remove" button
(hidden `version`) and a "Cancel" link back to the edit page.

**Copy.** iOS's, verbatim, except **new**:

| Element                    | Copy                                                                                                         |
| -------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Header link, list heading  | My colleges                                                                                                  |
| Status labels              | Considering / Applying / Admitted / Rejected                                                                 |
| Empty state                | No colleges yet. Add one, or ask your coach.                                                                 |
| Add button, search heading | Add a college                                                                                                |
| Search label, prompt, none | College name / Search by college name. / No colleges match.                                                  |
| Duplicate add              | College is already on the list                                                                               |
| Edit labels                | Status / Why it's on your list / Add your reasons / From your conversations / Save                           |
| Remove                     | Remove / Remove this college? / Its status and reasons are discarded. / Cancel                               |
| Over-long reasons          | Reasons must be non-empty and at most 2048 characters                                                        |
| Search button              | Search — **new** (iOS searches as you type)                                                                  |
| Result row action          | Add — **new** (iOS taps the row)                                                                             |
| Capped notice              | Showing the first 20 matches. Type more of the name to narrow them down. — **new**                           |
| Moved notice               | Your list changed somewhere else, so your last change wasn't made. This is your list as it is now. — **new** |
| Receipts                   | Added / Saved — "Added" **new**; "Saved" is RFC 270's                                                        |
| Drafted edit               | Not saved yet. — RFC 270's                                                                                   |
| Write fault                | That didn't save. Try again. — RFC 270's                                                                     |
| Back link                  | Back to My colleges — **new**                                                                                |

The status labels are an exhaustive `when` over `CollegeListEntryStatus` in
`public-web` — web copy at the edge (D13), a compile error if the enum grows.

**The door.** `ShellNav.SignedIn` gains "My colleges" between "Chat" and "Your
details", label and path from the page's own constants. It is shown to
supporters too, as "Your details" is (RFC 270 D-D).

**CSS.** The list's own class tunes the shared table only where it differs (the
remove column narrow on a laptop; the reasons cell wraps). The search cards and
the status radios get one small block each; no fixed widths.

## Files Modified

- `db/src/main/proto/unicoach/session/v1/session_metadata.proto` —
  `CollegeEntryDraft`.
- `public-web/build.gradle.kts` — `implementation(project(":college"))`.
- `public-web/src/main/kotlin/ed/unicoach/web/auth/WebAuthGate.kt` —
  `COLLEGES_PATH` and its access line.
- `public-web/src/main/kotlin/ed/unicoach/web/colleges/CollegeListRoutes.kt`
  (new) — the seven routes, the posting reads, the draft.
- `public-web/src/main/kotlin/ed/unicoach/web/render/CollegeListPage.kt`,
  `CollegeSearchPage.kt`, `CollegeEntryPage.kt` (new) — the pages, their
  constants, `COLLEGE_ENTRY_DRAFT`.
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt` — the header
  link.
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`, `Application.kt` —
  construct `CollegeListService` and `CollegeSearchService`, register the
  routes.
- `public-web/src/main/resources/static/site.css` — the list, search and status
  blocks.
- Tests under `public-web/src/test/kotlin/ed/unicoach/web/` (new
  `CollegeListRoutingTest.kt`, `CollegeSearchRoutingTest.kt`; `ShellNavTest.kt`
  and `GatedPathSpellingTest.kt` for the link and the gate).

## Implementation Plan

1. Add `CollegeEntryDraft` to the proto; regenerate; add the `FormSlot`.
2. Add the `:college` dependency; construct and pass both services.
3. Add `COLLEGES_PATH` and its `ONBOARDED` line; the header link.
4. The list page and `GET /colleges`, with the receipts and moved notice.
5. The search page, `GET /colleges/add` and `POST /colleges`.
6. The edit page, `GET`/`POST /colleges/{entryId}`, and the draft.
7. The confirmation page and `GET`/`POST /colleges/{entryId}/remove`.
8. CSS; tests (below); `nix develop -c bin/test` unscoped.
9. The D19 device matrix on the three pages, in a real browser.

## Tests

All against the real database through the public-web test application, asserting
rendered HTML, redirects and stored rows, never source text. Search tests seed
colleges and rebuild the name index, as the REST search test does.

1. **Door.** A student's signed-in header carries "My colleges" → `/colleges`.
2. **Empty list** renders the empty-state sentence and "Add a college", no
   table.
3. **First-session test.** Search a seeded college → its card with "City, ST"
   and an Add form; add it → 303 to `/colleges?added=<id>#<id>`; the row exists
   with status `considering`; the list shows the name, "Considering" and the
   "Added" receipt.
4. **List** — two entries in creation order, the reasons cell is the first line
   only, every cell carries its `data-label`, the table carries
   `DATA_TABLE_CLASS`.
5. **Capped.** 21+ matches → 20 cards and the capped notice; exactly 20 → no
   notice; no paging control either way.
6. **Search states** — no `q`, blank `q`, no match, a 101-character `q` (400).
7. **Duplicate add** → 409, the notice, the same results; one row stored.
8. **Save** — status and reasons → 303 with "Saved"; stored row has both and a
   bumped version; blank and whitespace-only reasons clear the stored reasons;
   living plan untouched.
9. **Stale version** → no write; 303 to `/colleges?moved=1`; the list shows the
   moved notice; the draft holds the typed status and reasons; reopening the
   entry shows them with "Not saved yet." and the CURRENT version; saving then
   succeeds and clears the draft.
10. **Removed elsewhere** — save or remove of an entry deleted meanwhile → the
    moved notice.
11. **Over-long reasons** (2,049 characters, bypassing `maxlength`) → 400 with
    the message; nothing written; draft kept.
12. **Write fault** (a service whose write fails) → 503 with "That didn't save.
    Try again."; draft kept.
13. **Remove** — the confirmation page carries the copy and version; POST → 303
    `/colleges`, the row soft-deleted, the entry's draft cleared; a stale
    version → moved notice, row kept.
14. **Refused postings** — missing or doubled `version`/`status`/`reasons`, a
    non-integer version, an unknown status, a non-UUID `collegeId`, a body that
    is not a form → 400, nothing written, no draft.
15. **Ownership** — a non-UUID id → 404 on GET and POST; another student's entry
    id → 404 on GET, and on a readable save or remove the moved notice (the
    service answers `NotFound` for both "not yours" and "removed meanwhile");
    the other student's row is never written.
16. **Supporter** → every route redirects to `/account`; a verified user with no
    role → `/get-started`; a POST without the CSRF token is refused.
17. **Query keys** — an unknown `saved`/`added` id or a `moved` other than `1`
    renders nothing.
18. **Supporting observations** render as quotes on the edit page, and the
    section is absent when there are none.

## Decisions

- **D-A — on a moved list, the typing is kept.** A stale save is refused, the
  family is sent to the list with the moved notice, and what they typed stays in
  the session; opening that college again shows it as "Not saved yet." over the
  current version, so saving is a deliberate second act. iOS discards it.
  Alternative: discard it, exactly as iOS does.
- **D-B — search runs on submit, not as you type.** A server-rendered GET form;
  Back and reload keep the query. iOS searches 300ms after each keystroke.
  Alternative: a TypeScript enhancement that fetches results as you type (a JSON
  endpoint and a client renderer).
- **D-C — new copy**, marked **new** in section 4.
- **D-D — `/colleges`, not `/account/colleges`.** It is a list the student works
  with, not an account setting; the cost is one line in the access map.
- **D-E — confirmation is a page, not a script dialog.** It works the same in
  every browser and is tested by the routing test. Alternative: a `<dialog>`
  opened by script.
- **D-F — results do not mark colleges already on the list**, as iOS does not; a
  duplicate add is answered by the notice.
