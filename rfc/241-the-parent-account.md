# RFC 241 — The parent account

**Slice:** `family/03/the-parent-account` (brief 0014, _parents in the room_).
**Status:** proposed. **Base:** `main@e0b612cb`.

A parent-shaped account that works: an account becomes parent-shaped by an
explicit choice or by holding a link, it lands on a home surface that is not the
student onboarding form, and it carries a working-student selection — prominent
with several students, quiet with one.

## Context

`family/01/the-link` (RFC 221) landed `supporters` and
`supporter_student_links`. `family/02/invite-and-accept` (RFC 232) landed the
invite, the web accept page, and — as a side effect of accepting — the only
production write of a `supporters` row
(`service/src/main/kotlin/ed/unicoach/family/SupporterInviteService.kt:191-197`,
called at `:219` and `:392`).

Three facts from the code decide this design.

1. **A parent who never touches an invite has a `users` row and nothing else.**
   Registration is already role-agnostic — `AuthService.register` writes no
   `students` row — and `users` carries no role column. Today "student-shaped"
   means "has a `students` row", so a new parent is indistinguishable from a
   student who has not finished onboarding.

2. **Nothing tells a client it is a supporter.** `GET /api/v1/auth/me` returns
   `PublicUser{id,email,name,emailVerified}` and never reads `students` or
   `supporters`. No endpoint lists a supporter's students; no endpoint returns
   supporter identity. The whole supporter REST surface is two POSTs
   (`rest-server/.../routes/SupporterInviteRoutes.kt:30,37,39`). Both DAO reads
   this slice needs already exist with zero production callers
   (`SupporterStudentLinksDao.listForSupporter:170-173`,
   `SupportersDao.findByUserId:97-107`).

3. **The "no student ⇒ student onboarding form" wiring is iOS-only.**
   `public-web` resolves a `students` row nowhere and already has a student-free
   signed-in home (`GET /account`). On iOS, `AppViewModel.resolveProfileState`
   (`ios-app/UnicoachiOS/AppViewModel.swift:230-262`, the `.onboarding`
   assignment at `:239`) routes any profile-less user into the student form, and
   nine `onProfileRequired()` call sites plus the launch and re-entry paths lead
   to the same screen.

So the slice is one new read, one new write, and an iOS client that stops
assuming student-ness.

## What this slice does NOT do

Boundaries taken from the spec, so review can check them:

- **No link-authorised read of a student's data** — that is
  `family/05/the-subject-seam`. Nothing here resolves a student other than the
  caller's own, and no client sends a student id to a student-scoped route.
- **No parent coach, no parent conversations, no tool registry change** —
  `family/06`.
- **No unlink, and no student-facing list of supporters** — `family/04`.
- **No billing, meter or paywall change** — `family/03.5`, whose product
  decision (D10) is not yet taken.
- **No parent deletion path** — `family/09`. `DELETE /api/v1/students/me` still
  404s for a parent; that gap is restated in the report, not closed here.
- **No report-page claim CTA and no pending-link state** — `family/10` / D19.

Brief 0014 D21 already settled the question this slice's spec text left open:
parent-ness IS the `supporters` row, not a column on `users`, not client state.
That is not re-litigated.

## Detailed Design

### 1. A purpose-built roster read (`:db`)

A new DAO read on `SupporterStudentLinksDao`:

```kotlin
fun listActiveLinkedStudents(
  session: SqlSession,
  supporterId: SupporterId,
): Result<List<LinkedStudent>>
```

returning `LinkedStudent(linkId, studentId, relationship, studentName)` — one
SQL statement joining `supporter_student_links` → `students` → `users`, filtered
to `deleted_at IS NULL` on the link, on the student AND on the student's `users`
row, ordered by the link's `created_at`. All three, in the statement that admits
the rows: account closure soft-deletes the `students` row and the `users` row in
one transaction today (`StudentService.deleteStudentAndAccount`), so the user
predicate is redundant — and it is what keeps this read correct when `family/09`
adds a deletion path of its own, instead of leaving the boundary in an argument
about another module's transaction.

Two reasons it is its own read rather than `listForSupporter` plus N lookups. It
is one query where the composed form is N+1 (`StudentsDao` has no batch read),
and — the load-bearing reason — **its projection cannot leak.** The only student
column it can return is the display name, which comes from `users.name`;
`students` carries no name (`db/.../models/Student.kt:6-24`). Returning
`Student` here would hand a supporter the date of birth and the expected
graduation date, which decides `family/05`'s read policy silently. The named
projection makes that a compile error instead of a judgement call.

The index already exists: `supporter_student_links_supporter_student_active_idx`
is partial on `(supporter_id, student_id) WHERE deleted_at IS NULL` and leads on
`supporter_id` (`db/schema/supporters-and-student-links.sql:87-94`).

**No new table, no new column, no migration.** Nothing in this slice changes the
schema.

### 2. `SupporterService` (`:service`)

A new service in the existing `ed.unicoach.family` package, with three
operations:

- `getOrCreate(session, userId): Supporter` — the session-scoped get-or-create,
  so a caller already inside a transaction (the invite accept) mints the row as
  part of it. This is the one writer of a `supporters` row above `:db`.
- `ensure(userId): Result<EnsureSupporterOutcome>` — the same mint on its own
  connection, reporting whether THIS call wrote the row, because
  `POST /api/v1/supporters` answers `201` then `200`.
- `homeFor(userId): Result<SupporterHome?>` — `null` when the caller holds no
  `supporters` row; otherwise the supporter plus `listActiveLinkedStudents`.

The private get-or-create in `SupporterInviteService` moves here — that service
keeps no wrapper of its own and calls this one at both of its use sites — so
there is exactly one writer of a `supporters` row after this change, as there is
exactly one before it.

`ensure` is idempotent under CONCURRENCY too, not only in sequence.
`supporters_user_id_unique_idx` is TOTAL, so the loser of two simultaneous POSTs
takes a constraint violation; it re-reads in a NEW transaction (a Postgres
transaction that has taken a violation can run nothing else) and answers `200`
with the winner's row. A caller whose supporter row was SOFT-DELETED reaches the
same refusal and finds no live row: there is no re-opening path, so the outcome
is the ADT's third variant, `Closed`, rather than a home they do not have. It is
a value and not a thrown failure — a closed row is a predictable state of the
account, and the index's own "already exists" exception would have reached the
client as an untyped `400` about a row that does not exist. Re-opening a closed
supporter is `family/09`.

The SESSION-scoped mint cannot recover for itself: the caller's transaction is
poisoned by the violation, so classification belongs to whoever owns that
transaction. `SupporterInviteService.accept` therefore answers it beside the
raced-link arm it mirrors — one retry against the row that won (its own
transaction rolled back, so the invite is still live), and a SECOND refusal is
the CLOSED case, which takes the same uniform refusal a dead token gets. Left
unclassified, the DAO's "already exists" reached an invite recipient as an
untyped `400` about a link that was never made.

### 3. Two routes (`:rest-server`)

A new `SupporterRoutes.kt`, both routes behind the existing auth and
email-verification gates:

- **`POST /api/v1/supporters`** — makes the caller's account parent-shaped.
  Idempotent: `201 Created` the first time, `200 OK` when the row already
  exists, the same body either way. It takes no request body: there is nothing
  to ask. It does not refuse a caller who also holds a `students` row — a
  `supporters` row is a home surface, not a claim about what someone is not, and
  the self-link guard in the database already prevents the only dangerous case.
  The client never offers the choice to a student. Its one refusal is
  `409 supporter_closed`, for a caller whose supporter row was closed.
- **`GET /api/v1/supporters/me`** — `200` with
  `{supporter:{id}, students:[{linkId, studentId, name, relationship}]}`, or
  `404 supporter_not_found`. The 404 mirrors `GET /api/v1/students/me`'s
  `404 student_not_found` (`StudentRoutes.kt:127`) — the same "you do not have
  one of these" fact gets the same shape of answer on the parallel route.

`CallerResolution` gains no member. It is student-shaped by design and its 409
`student_profile_required` is exactly what a student-scoped route should keep
saying; a supporter is resolved by the two routes that are about supporters. It
is SPLIT, though: the identity half — `resolveUser` and its 401 — becomes
`CallerAuthentication`, which `CallerResolution` extends and which the supporter
door delegates alone. Every existing handler keeps the interface and the
constructor it has; what changes is that the one door that must never send
`student_profile_required` no longer has it to send.

Both paths are added to `api-specs/openapi.yaml`, where every other REST path
except RFC 232's two invite paths is documented.

### 4. iOS: three states instead of two

`resolveProfileState` currently answers "student, or onboarding". It gains a
third answer, resolved in this order:

1. `GET /students/me` succeeds → today's student app, unchanged.
2. else `GET /supporters/me` succeeds → `.supporterHome(user, home)`.
3. else → `.roleChoice(user)`.

`UserAuthState` gains the two cases; its hand-written `==` (`:21-38`) is
extended with them. The nine `onProfileRequired()` call sites and the two
`AppViewModel` entry points are NOT edited one by one: they already funnel
through profile re-resolution, and that one function now knows about supporters,
so a 409 reaching a supporter's client sends them home rather than into the
student form.

**`RoleChoiceView`** — the neutral screen an account with neither row sees: two
equal paths, "I'm the student" → the existing onboarding form, "I'm supporting a
student" → `POST /supporters` → the supporter home. Signup itself is unchanged:
the question is not asked at registration, it is asked once, in the app, after
the account exists.

Both paths are REVERSIBLE while they are still unanswered. `.onboarding` now has
exactly one producer — the "I'm the student" tap — so the form carries an
optional back control ("I'm not the student") wired to
`AppViewModel.onRoleChoiceRequested`, `nil` for every other caller. Neither tap
has written a row at that point: the `students` row is written by the form and
the `supporters` row by the `POST`. Without it a parent who mistaps is on a form
asking for THEIR date of birth with no exit but logging out — and once that form
is submitted the supporter home is unreachable, because the student read comes
first.

**`SupporterHomeView`** — the parent's home:

- with **no links**: what linking adds, in plain words, and the invite action;
- with **one link**: a quiet line naming the student ("Supporting Ava");
- with **two or more**: a visible switcher control in the header, naming the
  current subject, switching in one tap from an in-view disclosure (two taps
  total) — `OptionCard`, the design system's own pick-one-of-N row, so this list
  signals "current" the way every other choice in the app does;
- always: the invite action, college search — which works today with no student
  row (`CollegeRoutes.kt:43`) — and settings.

The home RE-READS `GET /api/v1/supporters/me` when it appears, and again on
every return to the foreground, handing the fresh roster to the state machine.
The read runs under the screen that is already on it — the first render comes
from the resolve, so a home that has data never shows a spinner — and a failure
leaves that home standing, because nobody asked for the read. Without it the
result of this screen's OWN headline action is invisible: the invite is shared
through the system share sheet, so the parent leaves the app to send it, and a
student who accepts while the parent sits here would not be named until the
process was relaunched.

The refresh, and the `409` re-resolution beside it, both RE-CHECK the state
after their round trip and before they write it. `@MainActor` rules out a data
race, not an interleaving: the refresh runs from the screen's own `.task` and
from the scene-phase hook, and `Settings -> Log out` is two taps away — so a
logout completing mid-read is ordinary, and a writer that assumed the state it
read before the await would put a signed-out parent back on a home whose session
cookie is already deleted. `logout()` also cancels the detached re-resolution
task, which no view teardown cancels for it.

The switcher control and the college-search screen are their own files
(`SupporterSubjectSwitcher.swift`, `CollegeSearchView.swift`); the home composes
them. The search is ONE view at both of its readings — a student adding to their
own list, a supporter looking a college up — differing only in whether a result
row has a destination for a tap.

**`SupporterSubjectStore`** — the working-student selection, held client-side in
`UserDefaults` behind a protocol and keyed by user id, on the `AppleNameStore`
precedent (`ios-app/UnicoachiOS/AppleNameStore.swift:8-32`). It is client-side
because brief 0014 D14 forbids server-side "current student" state, and because
no server route takes a subject until `family/05`. An unreadable or stale
selection (a link that is gone) falls back to the first student in the roster.

**Honest scope of the switcher.** Until `family/05` lands, no screen shows a
linked student's data, so the switcher selects and labels — it does not yet
change what any other screen reads. It ships now because it is the surface the
roster makes possible and because the selection it persists is what `family/05`
and `family/06` will consume.

**Invite copy** gains a supporter-voiced variant. Today's share message is
student-voiced ("Join me on unicoach to support my college journey"), and the
same string is what a parent would send their child. The client now knows which
role it is in, so it picks the right sentence.

**One pre-existing defect is fixed:** `SnapshotScenes.swift:563-569` builds
`SettingsView` with a five-argument initialiser that RFC 232 replaced
(`SettingsView.swift:39-53` requires `supporterInviteClient:`). The iOS test
target therefore does not compile on this base, so no iOS test or snapshot can
run until it is repaired. Two lines.

### Reachability — the door

The iOS app. A parent who has never been invited: register → verify email → "I'm
supporting a student" → a home that names what linking adds and offers the
invite. A parent who accepted an invite (RFC 232): they already hold a
`supporters` row, so signing in on iOS lands them on the same home with their
student already in the roster — the case that, before this slice, put them in
the student onboarding form.

### Value before ask (standing D12)

The parent gets the product before linking: college search and the vocabularies
work with no student row, the home says what linking would add, the invite is
offered and never required, and nothing is gated on completing it. The role
choice is one screen with two paths and no wrong answer; a parent who picks the
student path can still be linked by accepting an invite.

## Files Modified

**Database** — none. No migration, no `db/schema/ORDER` line.

- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterStudentLinksDao.kt` — add
  `listActiveLinkedStudents`.
- `db/src/main/kotlin/ed/unicoach/db/models/` — add `LinkedStudent`.
- `service/src/main/kotlin/ed/unicoach/family/SupporterService.kt` — new.
- `service/src/main/kotlin/ed/unicoach/family/SupporterInviteService.kt` —
  delegate the get-or-create to the new service.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routes/SupporterRoutes.kt` —
  new.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/` — request/response
  models for the two routes.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`,
  `.../Application.kt` — wire the routes and the service.
- `api-specs/openapi.yaml` — document `POST /api/v1/supporters` and
  `GET /api/v1/supporters/me`.
- `rest-server/src/test/kotlin/ed/unicoach/rest/models/OpenApiSpec.kt`,
  `OpenApiStudentTest.kt` — the model-field reflection the supporter guard needs
  moves into `OpenApiSpec`, so the two guards that read a model that way share
  one copy of it instead of the guard retyping the roster's field list.
- `ios-app/UnicoachiOS/AppViewModel.swift` — three-state resolution.
- `ios-app/UnicoachiOS/Models.swift`, `StudentClient.swift` (or a new
  `SupporterClient.swift`) — the two calls and their types.
- `ios-app/UnicoachiOS/RoleChoiceView.swift`, `SupporterHomeView.swift`,
  `SupporterSubjectSwitcher.swift`, `SupporterSubjectStore.swift` — new.
- `ios-app/UnicoachiOS/AddCollegeView.swift` → `CollegeSearchView.swift` — one
  search screen for both readings, with the tap as its parameter; the supporter
  home pushes it browse-only.
- `ios-app/UnicoachiOS/APIClient.swift` — a plural-status `decode`, so the
  idempotent `POST /api/v1/supporters` states its two successes as data instead
  of computing one.
- `ios-app/UnicoachiOS/OnboardingView.swift` — the optional back control.
- `ios-app/UnicoachiOS/UnicoachiOSApp.swift`, `AuthenticatedRootView.swift` —
  route the new scenes; stop firing student-scoped calls for a supporter.
- `ios-app/UnicoachiOS/SettingsView.swift` — supporter-voiced invite copy,
  carried by ONE `SettingsView.Caller` value (`.student(rail:)` or
  `.supporter`), so "a supporter with a subscription rail" does not compile.
- `ios-app/UnicoachiOSTests/SnapshotScenes.swift` — repair the initialiser, add
  the new scenes.

## Implementation Plan

1. `:db` — `LinkedStudent` + `listActiveLinkedStudents`, with its DAO test.
2. `:service` — `SupporterService`; move the get-or-create into it and repoint
   `SupporterInviteService`; service tests.
3. `:rest-server` — the two routes, their models, wiring, `openapi.yaml`, and
   `SupporterRoutingTest`.
4. Suite (`nix develop -c bin/test`) — the server half is complete and green
   before any Swift is written.
5. iOS — repair `SnapshotScenes`, then the client calls, the state machine, the
   three views, the selection store, the copy.
6. `bin/test-ios` and `bin/snapshot-ios -b`, both outside the Nix shell, with
   before/after PNGs captured as run artifacts for the visual gate.

## Tests

**`:db` — `SupporterStudentLinksDaoTest`**

- a supporter with no links reads an empty list;
- links are returned with the student's `users.name` and the link's
  `relationship`;
- a soft-deleted link is excluded;
- a link whose student is soft-deleted is excluded;
- a link whose student's `users` row is soft-deleted — the `students` row still
  live — is excluded too, which is the predicate `family/09` will need;
- another supporter's links are never returned;
- ordering is by the link's creation, oldest first.

**`:service` — `SupporterServiceTest`**

- `ensure` creates once and returns the same row on a second call;
- `homeFor` returns `null` for a user with no supporters row;
- `homeFor` returns the roster for a linked supporter;
- an invite accepted through `SupporterInviteService` still produces exactly one
  `supporters` row after the delegation change;
- `ensure` answers `EnsureSupporterOutcome.Closed` for a user whose `supporters`
  row was closed: the one-per-user index is total, so the row cannot be
  re-opened and the caller is not handed a home they do not hold.

**`:rest-server` — `SupporterRoutingTest`** (embedded server, the
`SupporterInviteRoutingTest` shape)

- `401` unauthenticated on both routes;
- `403 email_not_verified` for an unverified caller;
- `POST` returns `201` then `200`, and exactly one row exists;
- `GET` answers `404 supporter_not_found` before, `200` after;
- `POST` answers `409 supporter_closed` for a caller whose supporter row was
  closed, and the home read still answers `404`;
- the roster lists only active links, and never another supporter's;
- **the response body contains no `dateOfBirth` and no graduation date** — the
  `family/05` boundary, asserted rather than assumed;
- a caller who also holds a `students` row is not refused.

**iOS**

- the snapshot target compiles again;
- scenes for the role choice, the empty home, the one-student home, and the
  three-student home with the switcher open;
- a unit test that the selection store round-trips per user id and falls back
  when the stored student is no longer linked;
- `AppViewModelTests` — the roster refresh replaces the roster, leaves the home
  standing on a failure or an absent row, and is a no-op in every other state; a
  logout completing while a refresh — or a `409` re-resolution — is in flight
  leaves the user signed out; "I'm not the student" returns to the question
  without writing a row; and concurrent `409`s resolve the profile ONCE;
- `SupporterClientTests` — `POST /api/v1/supporters` succeeds on `201` AND on
  `200` and throws the server's own error on anything else (a
  `409
  supporter_closed` included), and a relationship word this build has
  never heard of still yields a usable home: it decodes as
  `SupporterRelationship.unrecognised(<the raw word>)`, which is logged, rather
  than costing the parent their whole screen. Its own case rather than the
  catch-all `supporter`, because `supporter` is a word the server can genuinely
  send: folding the two together would make "the server widened the vocabulary"
  indistinguishable from a real catch-all, and this type is also ENCODED. The
  words are owned by the DB CHECK and nothing derives this client's copy, so
  `family/04` can publish a fifth one before this app is rebuilt.

**Not tested here:** anything that reads a linked student's data, because
nothing does.

## Rollback

Revert. The change adds no table, no column, no migration and no config key, so
there is no state to unwind: the two new routes stop existing and the iOS client
returns to its two-state resolution. `supporters` rows created by a parent-first
signup are inert without the routes — exactly like the rows RFC 232 already
creates on accept.
