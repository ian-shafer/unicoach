# RFC 137: iOS College-List Screen

## Executive Summary

RFC 91 landed `college_list_entries` and its REST CRUD
(`/api/v1/students/me/college-list`), and RFC 133/134/135 built the "know your
real price" chain on top of the list — but no student-facing door to the list
exists. RFC 136 (in flight, same base) opens the conversational door via an
`update_college_list` chat tool and explicitly defers the iOS UI to a later
slice. This RFC is that slice: a native college-list screen — view the list, add
a college, change an entry's status, edit its reasons, remove it — reading and
writing the RFC 91 REST surface.

Two small server additions are required to make the screen honest, because the
RFC 91 wire contract was built for a client that already holds college ids:

1. **`PublicCollegeListEntry` gains a required `collegeName`** — the response
   today carries only `collegeId`, and a list of UUIDs is not a screen.
2. **A student-facing college search, `GET /api/v1/colleges?q=…`** — the add
   flow needs a name-to-id picker, and nothing student-facing can resolve a
   college by name today (`CollegesDao.search` filters on structured axes only;
   the chat tools resolve ids conversationally).

Everything else is iOS: a client, view models, three views, drawer navigation,
and snapshot scenes.

### Where it lives in navigation (RFC 117)

The app is chat-first: conversation is the root, everything secondary lives
behind the slide-over menu (DESIGN.md §7). The college list is exactly the kind
of surface the drawer exists for — a durable artifact the student consults and
curates occasionally, not a competing primary interface. So:

- **`SlideOverMenu` gains one entry, "My colleges"** (`graduationcap` glyph), in
  the pinned footer above "All conversations". It is a footer entry, not a
  recents-area entry: the drawer's top region is conversation switching; the
  footer is the stable index of non-chat surfaces (All conversations, Settings),
  and the college list joins that index.
- **`CollegeListView` is a pushed destination** with stock chrome, a title and a
  back button — chrome is a function of depth (§7), and this is depth 1, the
  same shape as All conversations and Settings. No tab bar, no root change.

Chat remains the primary write path for the list (RFC 136's tool); this screen
is the direct-manipulation counterpart — a student who already knows "I got into
Michigan" should not have to phrase it as a chat message to record it.

### Reconciliation with RFC 136 (parallel run)

Same domain, different surface; the semantics stay in `CollegeListService`,
which both runs treat as the single owner. RFC 136 declares the iOS UI a
non-goal, so there is **no iOS overlap**. The server overlap is one additive
seam: RFC 136 adds `CollegeListService.listActiveWithNames` (per-entry
`CollegesDao.findById`); this RFC adds a batch name read for REST enrichment.
Whichever lands second rebases and **reuses the survivor's name read rather than
keeping both** — the batch `CollegesDao.listNamesByIds` form is the one to
converge on. Both runs touch `Application.kt` wiring and `openapi.yaml`
additively; conflicts there are mechanical.

## Detailed Design

### Server: `collegeName` on `PublicCollegeListEntry`

`CollegeListService` gains one additive read (no existing outcome changes, to
keep the RFC 136 conflict surface minimal):

```kotlin
suspend fun listNames(ids: List<CollegeId>): Result<Map<CollegeId, String>>
```

implemented with the existing batch `CollegesDao.listByIds` projected to an
id→name map in one session (`listNamesByIds` returns bare names, which cannot
key a map). The `CollegeListRouteHandler` composes it after every success
outcome — one batch call for the collection `GET`, one single-id call after
create/get/update — and `PublicCollegeListEntry` gains
`val collegeName: String`, required in `openapi.yaml`. A college id that
resolves to no name is a broken FK invariant, not a reachable state
(`ON DELETE RESTRICT`, colleges are never deleted); the route treats a missing
name as a 500, never fabricates a placeholder.

`DELETE` returns 204 and needs no enrichment.

### Server: college search

New endpoint, authenticated session required, **no student profile required**
(searching a public catalog is not an operation on "your college list"; the
profile gate stays on the list routes):

| Method | Path                               | Success                     | Failure                                                                      |
| ------ | ---------------------------------- | --------------------------- | ---------------------------------------------------------------------------- |
| `GET`  | `/api/v1/colleges?q={q}&limit={n}` | `200 CollegeSearchResponse` | `400 VALIDATION_FAILED` (q missing/blank/too long, limit non-integer), `401` |

- `q`: required, trimmed, non-empty, ≤ 100 chars. The bound (`MAX_QUERY_LENGTH`)
  is owned by `CollegeSearchService`, which enforces the query boundary for
  every caller (blank → empty success, oversized → failure); the route's 400
  validation references the service's constant.
- `limit`: optional, default 20, clamped to `1..25` at the service boundary (the
  `CollegeQuery` convention).

```kotlin
// db: CollegesDao
fun searchByName(session: SqlSession, query: String, limit: Int): Result<List<CollegeSummary>>
// db model: CollegeSummary(id, name, city, state)
```

SQL: `WHERE name ILIKE '%' || ? || '%'` with the pattern's `%`/`_`/`\` escaped,
ordered by prefix match first, then `undergrad_enrollment DESC NULLS
LAST` (a
"Columbia" query should surface Columbia University before small same-named
colleges), then `name` for determinism.

Service: the **existing** `ed.unicoach.college.CollegeSearchService` (the
chat-tool thin service, which `rest-server` already depends on) gains a
`searchByName` method with the same `1..25` clamp — routes never reach past a
service into the DAO layer, and the chat tool and REST search share one
instance.

REST: `CollegeRoutes.kt` (`CollegeRouteHandler`), registered in `Application.kt`
beside the existing handlers, with `rejectUnsupportedMethods(HttpMethod.Get)`.
DTOs: `CollegeSearchResponse(colleges: List<PublicCollegeSummary>)`,
`PublicCollegeSummary(id: UUID, name: String, city: String, state: String)`.
`openapi.yaml` gains the path and schemas.

### iOS: models and client

New file `CollegeListModels.swift` (Models.swift stays auth/conversation
focused):

```swift
enum CollegeListStatus: String, Codable, CaseIterable { case considering, applying, admitted, rejected }
struct CollegeListEntry: Codable, Identifiable, Hashable { id, collegeId, collegeName, status, reasons, version, supportingObservations }
struct SupportingObservation: Codable, Hashable { id: Int64, quote: String, utteredAt: Date }
struct CollegeSummary: Codable, Identifiable, Hashable { id, name, city, state }
```

`CollegeListStatus` carries `displayName` ("Considering", "Applying",
"Admitted", "Rejected"). An unknown wire status fails decoding loudly — the enum
is closed on both ends (DB CHECK).

`CollegeListClient` / `CollegeListClientProtocol` (the `StudentClient` pattern,
`APIClient`-backed):

```swift
func listEntries() async throws -> [CollegeListEntry]                       // GET  …/college-list
func addEntry(collegeId: UUID) async throws -> CollegeListEntry             // POST …/college-list (status defaults server-side)
func updateEntry(id: UUID, version: Int, status: CollegeListStatus,
                 reasons: String?) async throws -> CollegeListEntry         // PATCH …/college-list/{id}
func removeEntry(id: UUID, version: Int) async throws                      // DELETE …/college-list/{id}?version=
func searchColleges(query: String) async throws -> [CollegeSummary]         // GET /api/v1/colleges?q=
```

iOS never sends `observationIds` — citations are conversational provenance (RFC
91/136); the screen displays them read-only and never writes them.

### iOS: view models

**`CollegeListViewModel`** (`@MainActor ObservableObject`, the
`ConversationListViewModel` shape): `state` of
`loading / loaded([CollegeListEntry]) / empty / failed(ErrorResponse)`, plus
`load()`, `refresh()` (no `.loading` blink, same rationale as RFC 117),
`remove(_:)`, and `update(_:status:reasons:)`. Error handling follows
`ConversationViewModel.handle`:

- `.studentProfileRequired` → the injected `onProfileRequired()` — same
  root-state contract as every other authenticated screen.
- `VERSION_CONFLICT` (and the create-path `CONFLICT`, "already on the list", and
  the mutation-path `NOT_FOUND` — the entry another device already removed) →
  **reload the list and surface the server's message** in the standard error
  alert. The list is single-owner-per-student; all three mean the chat tool or
  another device moved it, and the honest recovery is a fresh read.
- Anything else → the standard alert, entry state unchanged.

**`AddCollegeViewModel`**: `query` + debounced (~300 ms) `searchColleges`,
results list, `add(_:)` over the same client instance the list reads from
(passed through `CollegeListView`), returning an added/rejected/profile-required
outcome so the view pops on success and the list refreshes on return. A blank
query renders the prompt state, not a request.

### iOS: views

**`CollegeListView`** — pushed destination, stock chrome,
`.navigationTitle("My colleges")`, `.navigationBarTitleDisplayMode(.inline)`
(the `ConversationListView` precedent). States: loading spinner, empty state
(one `dsBody` line — "No colleges yet. Add one, or ask your coach." — plus the
add button), failed (`ErrorView`), loaded list. Rows are the drawer's bordered
`dsSurface` card shape: college name (`dsBody`, `dsTextPrimary`), a status pill
(capsule, `dsOverline` uppercase text, `dsFieldBorder` hairline — monochrome, no
new colour roles), and the first line of `reasons` as a `dsCaption` secondary
line when present. Swipe-to-remove with a `confirmationDialog` (the
`ConversationListView` delete precedent — removal discards status and reasons,
so it confirms). Toolbar `plus` pushes `AddCollegeView` (view-builder
`NavigationLink`, the existing in-list precedent); row tap pushes
`CollegeEntryDetailView`.

**`CollegeEntryDetailView`** — title = college name. `SegmentedSelector` over
the four statuses (the §5 component; a stock segmented picker is ruled out by
DESIGN.md §2/§3), a reasons editor (multiline, `LabeledField`-styled box,
2048-char cap mirrored client-side), a filled `PrimaryButtonStyle` **Save** (the
screen's one filled control) enabled only when dirty, and — when
`supportingObservations` is non-empty — a read-only "From your conversations"
section of quoted `dsCaption` cards. Save calls `update` and pops on success —
and on a stale-entry conflict/404 too, since the reloaded list underneath is the
fresh truth and retrying the stale version could only re-fail; only other
failures keep the screen up.

**`AddCollegeView`** — `LabeledField` search input (auto-focused), result rows
(name + "City, ST" caption), tap adds with default status `considering` and pops
to the list. "Already on the list" (409) surfaces the server message inline.
Empty results render "No colleges match." rather than a blank pane.

**Navigation wiring** — `SlideOverMenu` gains the `myColleges` footer row;
`AuthenticatedRootView.Destination` gains `.collegeList` and its
`destination(_:)` arm builds `CollegeListView` with the shared
`onProfileRequired`. `CollegeListClient` is constructed where the other clients
are and threaded through.

### Snapshot scenes (RFC 122/130)

Four new scenes in `SnapshotScenes.swift` (×2 schemes = 8 PNGs), seeded via a
new `MockCollegeListClient` before render (the catalogue's await-seed rule):

- `college-list-populated` — 3 entries across distinct statuses, one with a
  reasons line, hosted inside a `NavigationStack` so the stock title renders.
- `college-list-empty`
- `college-entry-detail` — a non-default status selected, reasons filled, two
  supporting observations.
- `add-college-results` — query "col" with 3 fixture results.

Fixture UUIDs and dates are pinned/deterministic (`SnapshotClock`); nothing in
these scenes uses a relative formatter, so all dates pin. Per RFC 130, if a
scene shows toolbar chrome that quantises, the tolerance mechanism already in
the harness covers it; no new machinery.

### Error handling / edge cases

- **Offline / infrastructure errors**: `ErrorView` with retry on first load; the
  standard alert for mutation failures.
- **Version conflict (or concurrent-removal 404) on remove/update**: reload +
  alert (above); the student re-applies their change against the fresh list. No
  client-side merge.
- **Profile deleted mid-session**: `onProfileRequired()` from any list/detail
  call, identical to chat.
- **Duplicate add**: server 409 message shown inline in `AddCollegeView`; the
  list is refreshed on return regardless.
- **NUL bytes in client text** (found by the pre-commit contract fuzzer during
  this RFC's land): PostgreSQL refuses `\u0000` in UTF-8 text with SQLSTATE
  `22021`, which used to surface as a 500 on any free-text field (a register
  name, `reasons`, `q`). `mapDatabaseError` now classifies `22021`/`22P05` as
  `UnstorableTextException`, a `PermanentError` answered as 400 — one shared
  seam, every endpoint.

## Files Modified

**Server — added**

- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CollegeRoutes.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CollegeSearchResponse.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/PublicCollegeSummary.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeSummary.kt`

**Server — modified**

- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — `searchByName`
- `college/src/main/kotlin/ed/unicoach/college/CollegeSearchService.kt` —
  `searchByName` (existing service extended, not a new class)
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListService.kt`
  — `listNames` (additive)
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/PublicCollegeListEntry…`
  (`CollegeListEntryResponse.kt`) — `collegeName`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CollegeListRoutes.kt` —
  enrichment
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — wiring
- `api-specs/openapi.yaml` — `collegeName`, `/api/v1/colleges`, schemas

**iOS — added** (each registered in `project.pbxproj`, or it silently never
compiles)

- `ios-app/UnicoachiOS/CollegeListModels.swift`
- `ios-app/UnicoachiOS/CollegeListClient.swift`
- `ios-app/UnicoachiOS/CollegeListViewModel.swift`
- `ios-app/UnicoachiOS/AddCollegeViewModel.swift`
- `ios-app/UnicoachiOS/CollegeListView.swift`
- `ios-app/UnicoachiOS/CollegeEntryDetailView.swift`
- `ios-app/UnicoachiOS/AddCollegeView.swift`
- `ios-app/UnicoachiOSTests/MockCollegeListClient.swift`
- `ios-app/UnicoachiOSTests/CollegeListClientTests.swift`
- `ios-app/UnicoachiOSTests/CollegeListViewModelTests.swift`
- `ios-app/UnicoachiOSTests/AddCollegeViewModelTests.swift`

**iOS — modified**

- `ios-app/UnicoachiOS/SlideOverMenu.swift` — "My colleges" footer row
- `ios-app/UnicoachiOS/AuthenticatedRootView.swift` — `.collegeList`
  destination + client threading
- `ios-app/UnicoachiOSTests/SnapshotScenes.swift` — four scenes
- `ios-app/DESIGN.md` — §7 menu contents; §8.2 if anything is left undesigned
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`

## Implementation Plan

1. Server: `CollegesDao.searchByName` + `CollegeSummary` + DAO test;
   `CollegeListService.listNames` + test.
2. Server: `CollegeSearchService.searchByName`, `CollegeRoutes`, DTOs,
   `collegeName` enrichment in `CollegeListRoutes`, `Application.kt` wiring,
   `openapi.yaml`; routing tests.
3. iOS: models + `CollegeListClient` + client tests against the mock-session
   harness.
4. iOS: view models + tests (`MockCollegeListClient`).
5. iOS: the three views, drawer row, destination wiring, `project.pbxproj`.
6. Snapshot scenes; run `bin/snapshot-ios`, eyeball all 8 new PNGs.
7. `DESIGN.md` updates.

Step 5 is the risk; 1–2 are conventional; 3–4 are mechanical.

## Tests

1. **Server** (`nix develop -c bin/test`): DAO test for `searchByName` (ILIKE
   escaping, prefix-first order, clamp), service test for `listNames`, routing
   tests for `/api/v1/colleges` (validation, auth, shape) and for `collegeName`
   presence on every college-list success response. Existing college-list
   routing tests updated for the new required field.
2. **iOS unit** (`bin/test-ios`, system Xcode, never inside the dev shell):
   client decode/encode round-trips including the `DELETE ?version=` query;
   view-model tests — load/empty/failed, remove-with-conflict reloads,
   `studentProfileRequired` escalates, add-duplicate surfaces inline, debounce
   coalesces queries. All existing tests stay green; a test that needs changing
   is a signal that behaviour moved — stop and say so.
3. **Snapshot gate**: `bin/snapshot-ios` with the four new scenes; compare
   against a pre-change corpus with `-b` and confirm only the new scenes
   appear/move. The reviewing step looks at the images (visual-gate), judging
   what the code cannot show: pill legibility in dark mode, the detail form at
   large Dynamic Type, no stock-blue tint or grey capsule leaking through.
4. **Navigation smoke, by hand, stated in the report**: menu → My colleges → add
   (search, pick) → restatus → remove → back to chat.
