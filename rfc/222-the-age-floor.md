# RFC 222 — The age floor: a 13+ date of birth at student creation

Slice `family/08/the-age-floor` of brief 0014 (parents in the room). Decision D5
of that brief, resized by its correction X6: no date of birth is stored anywhere
today, so this is a net-new column, migration, API field, iOS field and written
policy. It depends on no other slice in the brief and blocks none.

## Why

unicoach is a general-audience service, so the Children's Online Privacy
Protection Act (COPPA) reaches it only through **actual knowledge** that it is
collecting personal information from a child under 13. A neutral date-of-birth
screen at student signup that refuses under-13 keeps the product wholly outside
COPPA's verifiable-parental-consent machinery — parental consent methods,
parental review and deletion rights, and the amended Rule's retention limits
(effective 2025-06-23). The alternative is not "do nothing"; it is building that
machinery for a college-planning product whose youngest sensible user is 13 or
14.

The parent channel this brief opens is also the thing that will _generate_
actual knowledge — a parent writes "my 12-year-old" into a chat — so the written
escalation path lands in the same slice as the gate, not later.

Research: `product/0014-parents-in-the-room/research/consent-privacy-safety.md`
(primary sources: FTC COPPA FAQ, Federal Register 2025-05904, App Store Review
Guideline 5.1.4).

## Detailed Design

### 1. The floor is enforced in Kotlin, at student creation

`POST /api/v1/students` is the only way a `students` row is created **by a
user** (`StudentRoutes.kt:56-91`) — see section 4a for the operator tool, which
is the one other writer — and it runs _after_ every authentication path —
email/password register, Google, and Apple. So one check at student creation
covers every signup path, including the two identity-provider routes that cannot
carry a birthdate in their token (`POST /api/v1/auth/google`,
`POST /api/v1/auth/apple`). **No auth route changes.** That is the whole reason
the gate sits at student creation rather than at registration.

**Neither identity provider can supply the date, so asking is the only path.**
Sign in with Apple's identity token carries user identity, full name, verified
email and a real-user signal — no birthdate, and no age claim of any kind
([Receiving a User's Identity Token](https://developer.apple.com/documentation/signinwithapple/receiving-a-users-identity-token)).
Google's OpenID Connect identity token carries the profile claims (`name`,
`given_name`, `family_name`, `picture`, `locale`) and never `birthdate`
([Sign in with Google, OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect));
a birthday is reachable only through a separate People API call under the
restricted `user.birthday.read` scope, which needs its own consent screen and
app verification, and returns nothing at all when the user has not set a
birthday or has set it to private — which is the common case. A gate that works
for most users and silently has no answer for the rest is not a gate, and buying
a restricted Google scope to half-fill one field is a bad trade. So the form
asks, on every path.

It cannot be a database `CHECK`. An age test needs today's date, and
`CURRENT_DATE` is not `IMMUTABLE`, so Postgres refuses it in a constraint.
Separately, `StudentsDao` maps SQLSTATE 23514 to `ConstraintViolationException`,
which `StudentService.kt:45` turns into a failed `Result` and the route renders
as **500** — a check constraint could therefore only ever produce a server
error, never an honest refusal.

The rule, evaluated in `StudentService` against an injected `java.time.Clock`
(house precedent: `SynthesisService.kt:83`, `FederalAidPolicyService.kt:150`):

    accepted  iff  dateOfBirth <= LocalDate.now(clock) - 13 years

A student whose thirteenth birthday is **today** is accepted. UTC is the clock's
zone; at worst a signup within one day of the boundary is judged by a date up to
a few hours away from the student's local one, which is immaterial to a
compliance floor and is not worth a timezone field on the request.

### 2. The refusal is its own outcome, not a generic validation failure

`CreateStudentResult` (`CreateStudentResult.kt:6-16`) is a sealed interface, so
a new arm is compiler-enforced at the one `when` in `StudentRoutes.kt:78-90`. It
gains `UnderMinimumAge`, rendered as:

    HTTP 403
    {"code": "age_requirement_not_met",
     "message": "unicoach is for students who are 13 or older. Ask a parent to
                 get in touch if you think this is wrong."}

`ErrorCode.AGE_REQUIREMENT_NOT_MET` joins `ErrorCode.kt:35-38` (whose KDoc is
the single source of truth the existing `ErrorCodeTest` guards).

It is **403, not 400**, because the request was well-formed and was refused on
its merits. A malformed or missing `dateOfBirth`, and a date that is in the
future or implies an age over 120, stay **400 `validation_error`** with a
`fieldError` on `dateOfBirth`, exactly like the graduation date today
(`StudentService.kt:23-25,32-35`).

**The refusal stores nothing.** No `students` row is written, and the rejected
birthdate is not persisted or logged — writing a under-13 child's personal
information into the database is the exact thing the gate exists to avoid.

Two behaviours review added here, both of which the first draft got wrong:

- **The accepted shape is the published one, checked before parsing.**
  `LocalDate.parse` accepts the ISO expanded-year form, so `+02026-07-10` would
  have parsed to a date the OpenAPI pattern rejects. The canonical
  `^\d{4}-\d{2}-\d{2}$` regex is tested first, on the `PartialDate.parse`
  precedent, so what the server accepts is exactly what it publishes.
- **Both fields are judged, then answered.** A request with a bad graduation
  date AND a bad birthdate reports both `FieldError`s at once, because
  `ValidationFailure` carries a list and `RegistrationValidator` is the house
  contract for it. A 400 outranks the 403: a malformed date is not judged for
  age at all.

### 3. The request field

`CreateStudentRequest` (`models/CreateStudentRequest.kt:3-5`) gains
`dateOfBirth: String?`, an ISO full date `YYYY-MM-DD`.

It is declared **nullable in Kotlin and required by the API contract**. The
serializer runs with `FAIL_ON_UNKNOWN_PROPERTIES` and would reject a missing
non-nullable field as a deserialization failure; a nullable field with an
explicit check produces the documented `400 validation_error` naming
`dateOfBirth` instead. An older installed iOS build that omits the field
therefore gets a clean, readable refusal rather than a serializer error, and a
student on that build must update the app to create a profile. That is the
correct outcome for a compliance gate: a signup that cannot state an age does
not get an account.

This is a **full** date, not the variable-precision `PartialDate` used for the
expected graduation date. "Year only" cannot decide the floor in the year the
student turns 13.

`UpdateStudentRequest` is **not** touched. The birthdate is asked once, at
creation, and this slice ships no way for a student to edit it.

### 4. The column

`date_of_birth DATE NOT NULL` on both `students` and `students_versions`, with
existing rows backfilled to `DATE '2009-07-10'`.

**Ian's call at the approval gate, 2026-09-15, verbatim:**
_"`Option B — make it
mandatory for all rows.` We do not have real users, so
this is okay. Make the default DoB July 10, 2009."_ The design offered a
nullable column on the ground that a birthdate is underivable and no migration
in this tree invents a personal fact; that argument is answered by the premise,
not overruled by it — there are no real students yet, so the backfilled value is
not a false fact about a real person, and `NOT NULL` means no code path anywhere
has to carry an "unknown age" case. The sentinel is a pre-launch artefact with a
finite lifetime, and it is recorded here because it will look like real data
later.

The backfill follows `create-partial-date-storage.sql:51-110` exactly, and for
its stated reason: `students` carries the versioning, `updated_at` and history
triggers, so a plain `UPDATE` would bump every student's version and write a
history row for a change no family made.

    ALTER TABLE students          ADD COLUMN date_of_birth DATE NULL;
    ALTER TABLE students_versions ADD COLUMN date_of_birth DATE NULL;

    ALTER TABLE students DISABLE TRIGGER USER;
    UPDATE students          SET date_of_birth = DATE '2009-07-10';
    ALTER TABLE students ENABLE TRIGGER USER;
    UPDATE students_versions SET date_of_birth = DATE '2009-07-10';

    ALTER TABLE students          ALTER COLUMN date_of_birth SET NOT NULL;
    ALTER TABLE students_versions ALTER COLUMN date_of_birth SET NOT NULL;

So `Student.dateOfBirth` is a non-null `LocalDate` and
`PublicStudent.dateOfBirth` a non-null string: no reader ever branches on a
missing age.

Four things a new `students` column must do, all shown by that precedent:

1. `ALTER TABLE students` — the column.
2. `ALTER TABLE students_versions` identically. `StudentsDao.mapStudent` reads
   `SELECT *` from **both** tables (`StudentsDao.kt:57-66,275-283`).
3. **No `CHECK`** on either table, for the `IMMUTABLE` reason above. A sanity
   bound (`date_of_birth > DATE '1900-01-01'`) is tempting and is still a
   `CHECK` whose violation renders as 500; the Kotlin validation owns it.
4. `CREATE OR REPLACE FUNCTION log_student_version()` **restated in full**, with
   the new column in its explicit column list
   (`0005.create-students.sql:56-74`). A plpgsql body is TEXT and is not
   dependency-tracked, so an un-restated function fails the next write
   with 42703.

Migration file `db/schema/add-students-date-of-birth.sql` (RFC 180: kebab slug,
no number), with one line appended to `db/schema/ORDER` by `ship-order` at land.

`PublicStudent` gains `dateOfBirth: String`; `admin-web`'s read surface shows it
unchanged in shape.

### 4a. The operator tool writes the column too, and carries no floor

A `NOT NULL` column has a consequence the first draft of this RFC missed, and
review caught: `admin-web` can create a `students` row from the user page
(`UsersResource.kt`, the create form rendered by `StudentsResource`), and that
path does not go through `StudentService`. It has to supply the column or it
cannot compile, so the create form asks the operator for the date, and
`StudentsResource` grows one `createFields` list holding the same field that the
read surface shows and the EDIT form does not (`StudentEdit` cannot carry it —
the birthdate is written once, at creation).

It is still bounded: the operator's date must be a real date, not in the future,
and not older than the maximum age the service already defines — one constant,
shared, not a second number. **But no age floor runs there, deliberately.** The
floor exists to keep unicoach out of COPPA's consent regime by refusing under-13
SIGNUPS; an operator behind admin authentication creating a row by hand is not a
signup, and a floor there would only stop the one person who may legitimately
need to fix a record. The actual-knowledge runbook in section 6 is what governs
an operator who learns a real student is under 13, and it says delete, not "type
a different date".

The honest cost of this: the product's guarantee is "no under-13 account can be
created through the app", not "no under-13 row can exist". Both sentences are
true; only the first one is a promise to a family.

### 5. The door: the iOS onboarding form

Onboarding is one screen (`OnboardingView.swift:36-77`). A date-of-birth section
is added **above** the graduation-date section, in the same style — three
`DSPickerRow` menus (year, month, day), with the same clamping the graduation
picker already does (`OnboardingViewModel.swift:33-41,208-259`).

Two things make it a _neutral_ age screen, which is what the FTC's guidance
turns on:

- **It starts empty.** No pre-selected year. A wheel that opens on an adult
  birth year is an invitation, and the form must not nudge the answer it wants.
  Submitting with no date selected shows an inline field error via
  `FieldErrorText` — the control `LabeledField(error:)` itself renders
  (`Components.swift:484`), and the one this section can use, because
  `LabeledField` wraps a single text binding and this field is three pickers —
  and does not call the server.
- **The refusal copy is the server's, shown verbatim** in the existing
  `FormErrorBanner` (`OnboardingView.swift:58-60`), the same way the money copy
  is quoted verbatim today (`OnboardingView.swift:234-253`, RFC 142). No second
  home for the sentence, and the user stays on the form rather than being
  bounced to a dead end.

Year range: `currentYear - 100 ... currentYear`. `StepIndicator` stays at three
steps — this is a field in step one, not a new step.

### 6. The escalation path, written down

`policy/under-13-actual-knowledge.md` — a new top-level directory holding one
file, linked from this RFC and from nothing else yet. It is a **human runbook**,
not code; this slice ships no automation for it.

    SIGNAL      A credible indication that an account holder is under 13 —
                a parent writing "my 12-year-old", a support email, a
                self-report in chat.
    1 SUSPEND   Suspend the account on the signal, not after an investigation.
                Suspension is reversible; continued collection from a child is
                not.
    2 CONFIRM   Ask the account holder (and, if a parent link exists, the
                parent) to confirm the date of birth, with a stated deadline.
    3 RESOLVE   Confirmed 13+: restore the account. Confirmed under 13, or
                unanswered by the deadline: delete the account and its data on
                the existing deletion path, and tell the parent it was deleted.

**Every step names a mechanism that exists today**, which is the difference
between a runbook and a wish. Suspend and restore are the admin tool's soft
delete and `undelete` on the `users` row — a soft-deleted user is refused at
authentication as `Disabled`, and nothing new is built here to support this. The
confirmed date is recorded in the support thread, NOT in the row: the stored
`date_of_birth` is written once at creation and has no edit screen, so the
runbook says the account is deleted and made again if the stored date itself
must change. Review caught the first draft promising a "record the confirmed
date" step with no surface behind it.

The file names who owns each step (today: Ian) and states that the signal is
what starts the clock, because "actual knowledge" is a knowledge standard — the
day the operator learns is the day the obligation attaches.

### What this slice does not do

- No age gate on the **parent** side. COPPA's concern is the child; a parent
  account is an adult account and brief 0014's parent slices are not yet built.
- No re-ask, no lockout on repeated attempts, no cookie that remembers a
  refusal. A determined 12-year-old can retype the year; a neutral screen is
  what the standard asks for, and the actual-knowledge path is what catches the
  rest.
- No backfill campaign for existing students.

## Files Modified

**Schema**

- `db/schema/add-students-date-of-birth.sql` (new) — two `ALTER TABLE`s and a
  full restatement of `log_student_version()`.
- `db/schema/ORDER` — one appended line (written by `ship-order` at land).

**db**

- `db/src/main/kotlin/ed/unicoach/db/models/Student.kt` —
  `dateOfBirth: LocalDate` (non-null).
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt` — insert binder,
  `mapStudent` (`rs.getDate("date_of_birth").toLocalDate()`, precedent
  `AdmissionTestCalendarDao.kt:33-61`).
- `db/src/testFixtures/kotlin/ed/unicoach/db/dao/StudentRowFixture.kt` — a
  `dateOfBirth` parameter defaulting to a fixed 13+ date (RFC 209: the one way a
  test writes a students row). The default is a literal, never `now() - 13y`: a
  fixture that drifts with the wall clock makes the boundary tests lie.

**service**

- `service/src/main/kotlin/ed/unicoach/student/StudentService.kt` — injected
  `Clock`, `MINIMUM_AGE_YEARS = 13`, `DATE_OF_BIRTH_FIELD`, parse + validate +
  floor, ahead of the insert.
- `service/src/main/kotlin/ed/unicoach/student/CreateStudentResult.kt` —
  `UnderMinimumAge` arm.

**rest-server**

- `.../rest/models/CreateStudentRequest.kt` — `dateOfBirth: String?`.
- `.../rest/models/StudentResponse.kt` (it declares `PublicStudent`) —
  `dateOfBirth: String` (non-null).
- `.../rest/models/ErrorCode.kt` — `AGE_REQUIREMENT_NOT_MET` + KDoc line.
- `.../rest/routing/StudentRoutes.kt` — pass the field; render the new arm as
  403.

**api-specs**

- `api-specs/openapi.yaml` — `CreateStudentRequest` (new required property,
  pattern `^[0-9]{4}-[0-9]{2}-[0-9]{2}$`), `PublicStudent`, and a `403` response
  on `POST /api/v1/students`.

**iOS**

- `ios-app/UnicoachiOS/Models.swift` — `CreateStudentRequest`, `PublicStudent`,
  `ServerErrorCode.ageRequirementNotMet`.
- `ios-app/UnicoachiOS/OnboardingViewModel.swift` — DOB state, clamping, ISO
  emission, unselected-field error.
- `ios-app/UnicoachiOS/OnboardingView.swift` — the date-of-birth section.
- `ios-app/UnicoachiOSTests/*` + `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`
  for any new test file (a test file absent from the pbxproj silently never
  compiles).

**admin-web** (section 4a — forced by the `NOT NULL` column, not a feature)

- `.../admin/resources/StudentsResource.kt` — the read field, and a
  `createFields` list that asks for the date on the create form only.
- `.../admin/resources/UsersResource.kt` — the create route parses it.

**policy**

- `policy/under-13-actual-knowledge.md` (new).

**Forced by the mandatory column, everywhere else**: any test or harness that
writes a `students` row in raw SQL (`bin/db-convos-tests`, `SubjectLoaderTest`,
the admin test support), and any Swift `switch` that is exhaustive over
`ServerErrorCode` and therefore gains the new case.

## Implementation Plan

1. Migration + `db` model, DAO and fixture; `StudentsDaoTest` round-trip.
2. `StudentService`: clock, parse, validate, floor; `CreateStudentResult` arm.
3. `rest-server`: request/response models, error code, route rendering.
4. `api-specs/openapi.yaml` and a new `OpenApiStudentTest` matching house style
   (`OpenApiMoneyProfileTest` is the template; there is no student one today).
5. iOS: models, view model, view, tests, pbxproj.
6. `policy/under-13-actual-knowledge.md`.
7. Full suite, then the iOS suite and a snapshot refresh for the onboarding
   scene (`SnapshotScenes.swift:782-804`).

## Tests

**db** — `StudentsDaoTest`: a row written with a birthdate reads it back; an
update bumps `version` and the new column appears in `students_versions` (proves
the restated trigger function).

**service** — `StudentServiceTest`, with a fixed `Clock`:

- thirteenth birthday **today** → accepted (the boundary, pinned explicitly);
- thirteenth birthday **tomorrow** → `UnderMinimumAge`;
- one day past the thirteenth birthday → accepted;
- 29 February birthdate against a non-leap "today" → accepted at 1 March, which
  is what `LocalDate.minusYears` yields;
- malformed, empty, future-dated, and age-over-120 → `ValidationFailure` naming
  `dateOfBirth`;
- a refused creation writes **no** `students` row (asserted by a count).

**rest-server** — `StudentRoutingTest` (real server, real cookie):

- under-13 → **403** with body containing `age_requirement_not_met`;
- 13+ → 201, and `GET /api/v1/students/me` echoes the date;
- field omitted entirely (the old-client case) → 400 `validation_error` with a
  `fieldError` on `dateOfBirth`, **not** a 415 or a 500;
- unauthenticated → still 401 (unchanged).

**openapi** — `OpenApiStudentTest`: the spec's `CreateStudentRequest` required
set and pattern match the Kotlin model; the 403 response is declared.

**iOS** — `OnboardingViewModelTests`: no date selected → inline field error and
**no** network call; a selected date emits canonical `YYYY-MM-DD`; a 403
`age_requirement_not_met` sets `errorResponse` and does **not** call
`onComplete()`. `StudentClientTests`: the new field on the wire. One snapshot
scene for the empty-DOB state.

**Not tested** — the escalation runbook. It is a human procedure with no code.
