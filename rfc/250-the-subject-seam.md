# RFC 250 — The subject seam

**Slice:** `family/05/the-subject-seam` (brief 0014, _parents in the room_).
**Status:** proposed. **Base:** `main@a81472d9`. **Lane:** A (rfc) — a new
authorisation seam and a new read surface.

Today no code path can name a student other than the caller's own (correction
X5). This slice builds that seam once: a request names the student it is about,
the server checks a live link on every call, and what comes back is a read. It
covers the two surfaces a parent is allowed — the student's college list and the
derived cost picture — and nothing else.

**Nothing new is user-visible here.** `family/06/the-parent-coach` is the door:
its read-only tools read through this seam. This RFC says so, as the slice
requires.

## Context

Two read-only research passes over `main@a81472d9`, with `path:line` citations,
are in `.scratch/ship/rfc-250/research/` (`routes.md`, `data.md`). The facts
that decide the design:

1. **`resolveStudent(user)` is the only way a caller becomes a student**
   (`rest-server/.../routing/CallerResolution.kt:71`). It takes no subject.
   Every student route is `/students/me/...`, and no client sends a student id.
2. **No query checks a link the way authorisation needs.**
   `SupporterStudentLinksDao.findActive` checks only the link's `deleted_at`
   (`:123-140`). Closing a supporter, or a student, leaves its links standing
   (`SupportersDao.kt:137-143`; `StudentService.kt:242`). The roster read checks
   link + student + student's user; `listActiveSupportersOf` checks link +
   supporter + supporter's user. Nothing checks all five.
3. **There is no REST read of the cost picture.** `CollegeCostService` is
   read-only and reaches people through two doors only: the coach tool
   `college_cost_profile` and the token-authorised Family Cost Report
   (`ServiceCostReportSource.kt:71-81`). That report is also the precedent for
   "authorise and read in ONE session": it resolves the share, then calls
   `CollegeCostService.readInSession` on the same session.
4. **A college-list entry carries conversation content.**
   `PublicCollegeListEntry.supportingObservations` are verbatim quotes from the
   student's coach conversations (`CollegeListRoutes.kt:324-327`), and `reasons`
   is "the student's own words for why this school", usually written by the
   coach from chat (`CollegeListChatTool.kt:76-80`).
5. **There are no parent-supplied money figures anywhere** in the schema. Every
   money input the cost picture reads (income band, residency, living plan) is
   the student's, and each one shows up inside the derived output. The existing
   parent-facing surface (the Family Cost Report) states the band in dollars by
   design (`CostReportPage.kt:85-88`).
6. **What a student wants to study is withheld by default** from people they
   share with (RFC 248, permission `intended_subjects`). The cost picture
   carries program returns keyed by those subjects
   (`CollegeCostService.kt:1105`).
7. **The parent client already holds the student id** for each linked student,
   from `GET /api/v1/supporters/me` (`SupporterResponses.kt:38-44`).

## What this slice does NOT do

- **No schema change.** No table, no column, no migration.
- **No coach conversation is subject-addressable**, now or later (D4a). No
  conversation route takes a subject.
- **No write takes a subject.** No route and no service method lets anyone but
  the student write the student's data (D3).
- **No `/students/me/...` route changes.** They are not re-routed through the
  seam and not turned into aliases; a student's own behaviour is byte-for-byte
  what it was.
- **No client change.** No iOS or web screen calls the new route yet.
- **No cost-picture REST route** (gate decision 3).

## Detailed Design

### 1. The live-link check (`:db`)

One new read on `SupporterStudentLinksDao`, the single answer to "may this user
read this student":

```kotlin
fun findLiveForUser(session: SqlSession, userId: UserId, studentId: StudentId): Result<SupporterStudentLinkId?>
```

```sql
SELECT l.id
FROM supporter_student_links l
JOIN supporters sup ON sup.id = l.supporter_id
JOIN users su       ON su.id  = sup.user_id
JOIN students s     ON s.id   = l.student_id
JOIN users stu      ON stu.id = s.user_id
WHERE sup.user_id = ?
  AND l.student_id = ?
  AND l.deleted_at IS NULL
  AND sup.deleted_at IS NULL
  AND su.deleted_at IS NULL
  AND s.deleted_at IS NULL
  AND stu.deleted_at IS NULL
```

All five rows must be live. The partial active-pair unique index guarantees at
most one row. A student can never pass this check for their own id: a self-link
is refused by the database (RFC 221), so the seam is for linked adults only, and
a student keeps using `/me`.

### 2. The seam (`:service`)

New `ed.unicoach.family.SubjectReadService` — the ONLY service that accepts a
student id from someone who is not that student. It has two methods and both are
reads:

```kotlin
suspend fun collegeList(viewer: UserId, studentId: StudentId): Result<SubjectRead<List<SharedCollegeListEntry>>>
suspend fun costPicture(viewer: UserId, studentId: StudentId): Result<SubjectRead<SharedCostPicture>>

sealed interface SubjectRead<out T> {
  data class Found<T>(val value: T) : SubjectRead<T>
  data object NotFound : SubjectRead<Nothing>
}

sealed interface SharedCostPicture {
  data class Full(val profile: CollegeCostProfile) : SharedCostPicture
  class SubjectsWithheld(studentsOwn: CollegeCostProfile) : SharedCostPicture // profile has programReturn dropped
}
```

Each call opens ONE `withConnection`, runs `findLiveForUser`, and on a hit reads
on the same session. That follows the Family Cost Report precedent: what was
authorised is what is read. The check is never cached, so a severed link answers
`NotFound` on the very next call (D14). `NotFound` is one value for every
refusal: no such student, never linked, severed, closed adult, closed student.
Existence is never confirmed (the `CoachingService` precedent,
`CoachingService.kt:66-69`).

- **College list.** A session-taking read
  `CollegeListService.listShared(session, studentId)` returns the active entries
  with their college names as
  `SharedCollegeListEntry(collegeId,
  collegeName, status, livingPlan, updatedAt)`.
  It does not return `reasons`, `supportingObservations`, the entry id or the
  version (gate decision 1). The read does not load observations at all, so they
  cannot leak by mistake.
- **Cost picture.** `CollegeCostService.readInSession(session, studentId)`,
  unchanged, which is the same computation the Family Cost Report shows a parent
  (gate decision 2). The program return reaches the adult only when
  `SharingPermissionService.isGranted(session, studentId, INTENDED_SUBJECTS)` is
  true on the same session (fact 6). The answer says which: `Full` carries the
  student's own profile; `SubjectsWithheld` carries it with `programReturn`
  dropped on construction. A `null` `programReturn` already means "the
  intended-subjects answer is not `answered`", so the withheld case is a type
  and not a second meaning of that null — `family/06`'s coach must not tell a
  parent "your student has not said" when the truth is "has not shared". The
  student's raw money-profile record (`GET /students/me/money-profile`) is not
  readable through the seam.

**View only is enforced in the service layer, not by leaving out UI.** Every
write service method takes a `StudentId`, and the REST layer gets that only from
`resolveStudent(user)`, the caller's OWN student. A parent account has no
student row, so it never gets one. `SubjectReadService` is the one place a
non-owner's student id enters the service layer. It has no write method, and it
hands back projections, never the id.

**The FAFSA partition (D4c).** Derived outputs are visible to both parties: the
cost picture. Raw inputs stay with their owner: the parent cannot read the
student's money profile, and there are no parent-supplied figures yet, so there
is nothing to keep from the student. When `family/07` adds the parent's own
inputs, they get their own owner keys and are not reachable through this seam.

### 3. REST (`:rest-server`)

One new route:

    GET /api/v1/students/{studentId}/college-list

- `{studentId}` is a PATH parameter, read with the existing `pathId` helper
  (path only, canonical UUID only), so a `?studentId=` query cannot redirect it.
  A path parameter is the house style for REST resources. A header would be
  invisible in logs and in the OpenAPI spec.
- Ktor matches the constant segment `me` before a parameter, so
  `/students/me/college-list` keeps going to its existing handler.
- New handler `SubjectRoutes` uses `CallerAuthentication` only (RFC 241's
  split). It cannot answer `student_profile_required`, which would tell a parent
  something about their own account that does not matter here.
- Answers: `401` with no session. `403 email_not_verified` for an unverified
  caller (the app-wide email-verification gate, before the handler runs).
  `400 validation_failed` for a malformed id (as on RFC 249's DELETEs; a
  malformed id names no student, so it reveals nothing). `404` with ONE fixed
  body, `ErrorCode.STUDENT_NOT_FOUND` "No such student", for every `NotFound`,
  byte-identical across the five refusal causes.
  `200 { "entries": [ PublicSharedCollegeListEntry... ] }` otherwise — the wire
  form of `SharedCollegeListEntry`, the same five fields.
- `api-specs/openapi.yaml` documents the path and its response schema.

The cost picture gets no route (gate decision 3). Its only consumer is
`family/06`'s in-process tool registry, which calls `SubjectReadService`
directly with the subject injected by the dispatcher (D15). A JSON wire shape
for the whole `CollegeCostProfile` would be a large new public contract with no
client.

### Reachability — the door

Nothing new is user-visible in this slice. The door is `family/06`: a parent in
their own chat asks about their student's list, and that coach's tools read
through `SubjectReadService`. The REST route is there for a parent screen to
use, and nothing calls it in this slice.

### Value before ask (standing D12)

Not applicable. No flow and no ask are added.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterStudentLinksDao.kt` —
  `findLiveForUser`.
- `service/src/main/kotlin/ed/unicoach/family/SubjectReadService.kt` — new.
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListService.kt`
  — `listShared(session, studentId)` and `SharedCollegeListEntry`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/SubjectRoutes.kt` — new.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/` — the response model.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`, `Application.kt` —
  wiring. `CollegeCostService` is hoisted to one instance shared by the cost
  chat tool and the seam.
- `api-specs/openapi.yaml` — the new path.
- Tests listed below.

## Implementation Plan

1. `:db` — `findLiveForUser` and its DAO test, one case per predicate.
2. `:service` — `listShared`, `SubjectReadService`, and service tests.
3. `:rest-server` — `SubjectRoutes`, wiring, OpenAPI, route tests, and the
   route-enumeration test.
4. `nix develop -c bin/test` (full suite, no module, no `-f`).

## Tests

- **DAO** (`SupporterStudentLinksDaoTest`): a live link answers its id. Each of
  the five predicates, turned off alone, answers `null`: severed link, closed
  supporter row, closed supporter user, closed student row, closed student user.
  A link for a different student answers `null`.
- **Service** (`SubjectReadServiceTest`): a linked adult reads the list and the
  cost picture. After `unlink`, the very next call answers `NotFound`, which
  proves nothing is cached. The picture is `Full` only when `intended_subjects`
  is granted, and `SubjectsWithheld` with no program return otherwise. The
  shared list has no reasons and no observations, even when the entry has both;
  the observations half is in `CollegeListServiceTest`, which already has the
  conversation fixture a cited quote needs.
- **Route** (`SubjectRoutingTest`): linked → 200 with the projection. Never
  linked, severed link, closed supporter row, closed student row, closed student
  user, and an id that was never a student → 404, and the six bodies are
  byte-identical. A closed supporter USER cannot reach the seam over HTTP:
  closing the account ends its sessions, so it is a 401, asserted as such; the
  DAO test holds that cause. Malformed id → 400. No session → 401. A
  `?studentId=` query does not override the path. A student's own
  `GET /students/me/college-list` response is unchanged (the existing
  `CollegeListRoutingTest` stays green unedited).
- **Route enumeration** (`SubjectSeamRouteSweepTest`): walks the REGISTERED
  route tree at runtime (`plugin(RoutingRoot)`, method nodes with handlers),
  following the `CspConformanceTest` precedent. This reads what the server
  serves, not source text.
  - Every method route with a `{studentId}` segment is a `GET`. A write route
    that takes a subject fails the test.
  - Every `{studentId}` route answers 200 for a linked adult and the identical
    404 for an unlinked one and for a severed one.
  - Every NON-GET route is called by a linked parent account (no student row),
    with the linked student's id wherever the path has an id and an empty JSON
    body. Each route must either refuse the CALLER (401, 403, 404 or 409 — a 400
    from the empty body does not count, because it is answered before anyone
    asks who is calling), or be in the named set of routes that write the
    caller's OWN account (auth, `POST /students`, `PATCH /students/me`, the
    session-less Apple notification webhook, `POST /supporters`, invites and
    accept, subscriptions, the supporter-side disconnect). Closing the caller's
    own account (`DELETE /auth/me`, RFC 252) is named but never called: it ends
    the adult's links by design, and its own test covers it. Every parameterless
    read of the student's own data under `/students/me`, taken from the served
    route tree, is read back afterwards and must be unchanged. A new write route
    in no named group fails the test, so it forces a decision.
- **OpenAPI** (`OpenApiSubjectCollegeListTest`): the documented response matches
  the served one, following the existing `OpenApi*Test` files.

## Rollback

Revert. No schema change, no configuration key, and no client depends on the
route.
