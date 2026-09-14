# DISCOVER — Forms and data surfaces the web must re-create

Brief 0013 "Unicoach on the web". Ground truth read from the ROOT checkout
`/Users/ian/Work/unicoach` at the time of writing (read-only). Every claim below
carries a `path:line` citation. Anything I could not verify is marked
**UNVERIFIED**.

Scope: the data surfaces an SSR form layer has to re-create — money profile,
vocabularies, college list, college search, student, and the error model.

---

## 0. Read this first — six corrections

These are the places where the brief, the iOS app, or ordinary expectation would
lead a slice author to write instructions against an API that does not exist.

1. **`public-web` cannot import `ErrorCode`, `ErrorResponse`, `SessionConfig` or
   `resolveCaller`.** They live in `:rest-server`
   (`rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt:13`,
   `.../models/ErrorResponse.kt:5`,
   `rest-server/src/main/kotlin/ed/unicoach/rest/auth/SessionConfig.kt:6`,
   `rest-server/src/main/kotlin/ed/unicoach/rest/auth/CallerResolution.kt:40`),
   and `public-web/build.gradle.kts:11-18` depends only on `:common`,
   `:web-common`, `:db`, `:auth`, `:service`. `FieldError` IS reachable — it is
   in `:common` (`common/src/main/kotlin/ed/unicoach/error/FieldError.kt:3`).
   `AuthService` IS reachable — despite its `ed.unicoach.auth` package it is
   compiled in `:service`
   (`service/src/main/kotlin/ed/unicoach/auth/AuthService.kt:39`); the `:auth`
   MODULE holds only `EmailVerifier`/`DbEmailVerifier`/`VerifyEmailResult`
   (`auth/src/main/kotlin/ed/unicoach/auth/`). So session-cookie resolution for
   the web is **new code or a moved type**, not a reuse.

2. **The money profile has FOUR tri-state fields; REST exposes THREE.**
   `MoneyProfile` carries `income / residency / livingPlan / dependency`
   (`db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile.kt:24-31`) and
   `MoneyProfileUpdate` accepts all four
   (`service/src/main/kotlin/ed/unicoach/coaching/moneyprofile/MoneyProfileService.kt:36-52`).
   But `UpdateMoneyProfileRequest` has no `dependency*` keys
   (`rest-server/.../models/UpdateMoneyProfileRequest.kt:9-19`) and
   `PublicMoneyProfile` does not return it
   (`rest-server/.../models/MoneyProfileResponse.kt:13-23`). A web money form
   that wants dependency must call `MoneyProfileService` directly — which, given
   decision "server-side calls to the service layer", it can.

3. **There is NO `living_plans` vocabulary.** `VocabularyService.registry`
   registers exactly two suppliers: `income_bands` and `residency_states`
   (`service/src/main/kotlin/ed/unicoach/vocabulary/VocabularyService.kt:107-111`,
   names at `:142-143`). A living-plan or dependency picker gets no labels from
   `GET /api/v1/vocabularies`; the labels exist only as Kotlin enum constants
   (`LivingArrangement.label`,
   `db/src/main/kotlin/ed/unicoach/db/models/LivingArrangement.kt:44-46`;
   `DependencyStatus.label`, `.../DependencyStatus.kt:25-26`). SSR can read
   those enums directly (`:db` is a dependency), so this is cheap for the web —
   but do not write "fetch the living-plan vocabulary".

4. **The money profile PUT has NO optimistic concurrency.** It is an
   apply-or-keep upsert with no client version (`MoneyProfileService.kt:78-82`
   states this explicitly; the route body carries no `version`,
   `UpdateMoneyProfileRequest.kt:9-19`). The response returns `version`
   (`rest-server/.../models/MoneyProfileResponse.kt:20`) but nothing consumes
   it. Only **college list** and **student** use OCC. Do not design a
   money-profile form around a version token.

5. **`sessions.metadata` is JSONB, updatable at the DB level, but there is NO
   DAO write path after INSERT and it is capped at 2048 bytes.** Column and
   CHECK: `db/schema/0002.create-sessions.sql:16` and `:23`
   (`pg_column_size(metadata) <= 2048`). `SessionsDao` reads metadata
   (`db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt:39,50`) and writes it
   only in `create` (`:159,:171`); there is no `updateMetadata`. The
   `prevent_immutable_updates` trigger guards only `id`, `created_at`,
   `row_created_at` (`db/schema/0000.shared-functions.sql:41-55`), so metadata
   IS updatable — the missing piece is Kotlin, not SQL. **2 KiB is the real
   budget for a proto3-JSON form draft**, and proto3 canonical JSON is verbose
   (lowerCamelCase keys, no field-number compression).

6. **A REST call from the browser would hit two gates the web must not
   inherit.** `X-Unicoach-Client-Key` is required on every non-allowlisted path
   when keys are configured
   (`rest-server/src/main/kotlin/ed/unicoach/rest/plugins/ClientKeyGate.kt:14-34`),
   and an authenticated-but-unverified caller gets `403 email_not_verified` on
   everything outside `/healthz` and `/api/v1/auth/**`
   (`.../plugins/EmailVerificationGate.kt:38-50`). Since the gate-1 decision is
   server-side service-layer calls, neither gate applies — but it also means the
   web does **not** get the email-verification gate for free. Re-creating that
   refusal is web work.

---

## 1. Money profile

### 1.1 Storage

`money_profiles` — one active row per student, soft-deleted, versioned.

| Column               | Type                                 | Constraint                                                                                                                |
| -------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------- |
| `income_band`        | TEXT NULL                            | `IN ('under_30k','30k_to_48k','48k_to_75k','75k_to_110k','over_110k')` — `db/schema/0046.create-money-profiles.sql:41-43` |
| `income_band_status` | TEXT NOT NULL DEFAULT `'unanswered'` | `IN ('unanswered','answered','declined')` — `0046:44-45`                                                                  |
| `residency_state`    | TEXT NULL                            | `~ '^[A-Z]{2}$'` — `0046:46-47`                                                                                           |
| `residency_status`   | TEXT NOT NULL DEFAULT `'unanswered'` | `0046:48-49`                                                                                                              |
| `living_plan`        | TEXT NULL                            | `IN ('on_campus','off_campus','with_family')` — `db/schema/0070.add-money-profile-living-plan.sql:21-25`                  |
| `living_plan_status` | TEXT NOT NULL DEFAULT `'unanswered'` | `0070:22,26-27`                                                                                                           |
| `dependency`         | TEXT NULL                            | `db/schema/0078.add-money-profile-dependency-status.sql:29,31`                                                            |
| `dependency_status`  | TEXT NOT NULL DEFAULT `'unanswered'` | `0078:30`                                                                                                                 |

Every field has a **value-iff-answered CHECK**: the value is non-null exactly
when its status is `answered` (`0046:52-56`, `0070:30-31`). A unique partial
index enforces one active row per student (`0046:59-60`).

Kotlin row: `db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile.kt:21-40`.

### 1.2 Enums (exact wire values)

- `AnswerStatus` — `unanswered` | `answered` | `declined`
  (`db/src/main/kotlin/ed/unicoach/db/models/AnswerStatus.kt:14-16`).
- `IncomeBand` — value / `bracket` label pairs, in display order
  (`db/.../IncomeBand.kt:24-36`): `under_30k` "$0 to
  $30,000"; `30k_to_48k` "$30,001 to $48,000";
  `48k_to_75k` "$48,001 to $75,000"; `75k_to_110k` "$75,001 to $110,000";
  `over_110k` "$110,000 or more". Parser: `fromValue` (`IncomeBand.kt:80`).
- `LivingArrangement` — `on_campus` "living on campus", `off_campus` "renting
  off campus", `with_family` "living at home"
  (`db/.../LivingArrangement.kt:44-46`). NOTE: the labels are phrased to sit
  inside a coach sentence, not as picker captions. A web picker that renders
  them verbatim will read oddly ("renting off campus" as a radio label).
- `DependencyStatus` — `dependent` "dependent for federal aid", `independent`
  "independent for federal aid" (`db/.../DependencyStatus.kt:25-26`).
- Residency codes — **59 members**, not 51: the 50 states, DC, and AS FM GU MH
  MP PR PW VI
  (`db/src/main/kotlin/ed/unicoach/db/models/UsStateCodes.kt:22-27`).
  Normalisation is trim + uppercase + membership (`UsStateCodes.kt:33`),
  re-exported as `MoneyProfileService.USPS_STATE_CODES` / `parseResidencyState`
  (`MoneyProfileService.kt:152,162`).

### 1.3 REST surface

Route base `/api/v1/students/me/money-profile`, `GET` + `PUT` only
(`rest-server/.../routing/MoneyProfileRoutes.kt:47-51`). Both require an
authenticated user AND a student profile (`:55-56`, `:70-71`).

**GET** — `200 { "profile": PublicMoneyProfile }`
(`MoneyProfileRoutes.kt:58-61`), or **`404 not_found` "No money profile yet"**
before the first write (`:63-65`). `PublicMoneyProfile` keys:
`incomeBandStatus, incomeBand, residencyStatus, residencyState,
livingPlanStatus, livingPlan, version, createdAt, updatedAt`
(`rest-server/.../models/MoneyProfileResponse.kt:13-23`). All statuses are raw
strings; there is **no label** on the wire — the client joins to the vocabulary
itself.

**PUT** body — `UpdateMoneyProfileRequest`
(`rest-server/.../models/UpdateMoneyProfileRequest.kt:9-19`), nine optional
keys:

```
incomeBand?: string    incomeBandDeclined: bool=false    incomeBandClear: bool=false
residencyState?: string residencyDeclined: bool=false     residencyClear: bool=false
livingPlan?: string     livingPlanDeclined: bool=false    livingPlanClear: bool=false
```

Per field, **at most one of value/declined/clear**; an omitted field is
untouched (`:3-8`). Violation →
`FieldError(<fieldName>, "At most one of ...
may be set")`
(`MoneyProfileRoutes.kt:138,162,186`). An unknown enum value →
`FieldError("incomeBand", "Unknown income band value: $raw")` (`:143`),
`FieldError("residencyState", "Must be a two-letter US state postal code, got:
$raw")`
(`:167`), `FieldError("livingPlan", "Unknown living plan value: [$raw]")`
(`:191`). **Every invalid field reports at once** in one 400 — the route pools
all three parsers' errors (`:105-120`). That is exactly the shape an SSR form
needs for per-field error rendering.

Outcomes: `200` with the new profile (`:81-83`); `404 not_found` "Owning student
not found" if the student row vanished mid-write (`:85-89`);
`400 validation_failed` with `fieldErrors` (`:77`). No conflict case.

### 1.4 What the iOS "Your details" screen actually writes

`ios-app/UnicoachiOS/YourDetailsViewModel.swift:8-10` — **two fields only**,
`income` and `residency`. The living plan is deliberately left to chat. Field
titles are the app's own, not the server's: "Household income" and "State or
territory of residence" (`YourDetailsViewModel.swift:18-22`) — the second worded
that way because the served list includes Guam. The screen treats `404` as
"nothing answered yet", not an error (`YourDetailsViewModel.swift:80-88`). It
ships a narrower Swift fallback vocabulary (51 jurisdictions vs the server's 59)
and SAYS so when it falls back (`YourDetailsViewModel.swift:152-160`). Saves are
per-field, and a failed save keeps the user's selection on screen while `state`
still holds the old stored answer (`YourDetailsViewModel.swift:181-186`).

---

## 2. Vocabularies endpoint

`GET /api/v1/vocabularies`, GET only
(`rest-server/.../routing/VocabularyRoutes.kt:42-46`). **Authenticated session
required, but NO student-profile gate** (`:48-51`, reasoning at `:24-29`) — the
picker screen is exactly the screen with no profile yet.

Response (`rest-server/.../models/VocabulariesResponse.kt:13-37`):

```jsonc
{
  "version": "<16 hex chars>",
  "vocabularies": {
    "income_bands":     { "entries": [ { "value": "...", "label": "..." }, ... ] },
    "residency_states": { "entries": [ { "value": "AL", "label": "Alabama",
                                         "jurisdictionKind": "state" }, ... ] }
  }
}
```

- The map preserves registration order; each vocabulary's entries are in
  **display order — the client sorts nothing**
  (`VocabulariesResponse.kt:10-11`).
- Entry extras are **flattened** beside `value`/`label` via `@JsonAnyGetter`
  (`VocabulariesResponse.kt:36`, rationale `:23-32`). The only extra served
  today is `jurisdictionKind` (`VocabularyService.kt:146,244`).
- `version` is a short SHA-256 content hash over exactly the served bytes
  (`VocabularyService.kt:257,288-296`). It is **deliberately not an ETag** and
  no cache header is set (`:248-256`). An SSR layer that wants caching must add
  that itself.
- `income_bands` comes from the `IncomeBand` enum with no DB read
  (`VocabularyService.kt:183-187`). `residency_states` is `USPS_STATE_CODES` ∩
  `us_states` rows, in `us_states.name` order (`VocabularyService.kt:211-246`).
  A code with no `us_states` row is a **500**, not a dropped entry (`:215`,
  `:59-64`).

**How iOS uses it**: `DetailOption<Value>` pairs the typed wire value with the
server's label (`YourDetailsViewModel.swift:145-150`), and `VocabularySource`
records `served` vs `fallback` so a degraded picker is visible rather than
silent (`YourDetailsViewModel.swift:145-160`). For SSR the analogue is:
`<option value="{entry.value}">{entry.label}</option>` in the served order, no
sorting, no client-side labelling.

---

## 3. College list

### 3.1 Model and status enum

`CollegeListEntry` — `status`, `reasons`, per-college `livingPlan` override, OCC
`version`, soft delete
(`db/src/main/kotlin/ed/unicoach/db/models/CollegeListEntry.kt:11-36`).
`livingPlan == null` means "no override, use the family's usual plan"
(`:17-27`).

`CollegeListEntryStatus` — `considering` | `applying` | `admitted` | `rejected`
(`db/.../CollegeListEntryStatus.kt:7-10`). `fromValue` at `:14`.

`reasons` constraints: `length <= 2048` and `trim(reasons) <> ''`
(`db/schema/0024.create-college-list.sql:42-43`), surfaced as `InvalidReasons`
by matching constraint name
(`service/.../collegelist/CollegeListService.kt:293-295`).

### 3.2 Routes

Base `/api/v1/students/me/college-list`
(`rest-server/.../routing/CollegeListRoutes.kt:50-62`). All handlers require
user + student profile.

| Verb   | Path                   | Request                         | Success                                                                                            |
| ------ | ---------------------- | ------------------------------- | -------------------------------------------------------------------------------------------------- |
| POST   | ``                     | `CreateCollegeListEntryRequest` | `201 { entry }` (`:160-163`)                                                                       |
| GET    | ``                     | —                               | `200 { entries: [...] }` (`:189-192`), wrapper key `entries` (`models/CollegeListResponse.kt:3-5`) |
| GET    | `/{entryId}`           | —                               | `200 { entry }` (`:201-204`)                                                                       |
| PATCH  | `/{entryId}`           | `UpdateCollegeListEntryRequest` | `200 { entry }` (`:241-244`)                                                                       |
| DELETE | `/{entryId}?version=N` | query `version`                 | `204` (`:276-278`)                                                                                 |

`CreateCollegeListEntryRequest`
(`models/CreateCollegeListEntryRequest.kt:5-11`): `collegeId: UUID` (required),
`status: String = "considering"`, `reasons: String? = null`,
`livingPlan: String? = null`, `observationIds: List<Long> = []`. **No `version`
on create.**

`UpdateCollegeListEntryRequest`
(`models/UpdateCollegeListEntryRequest.kt:3-22`): `version: Int` (**required**),
`status: String` (**required — a PATCH that omits it cannot be expressed; the
field is non-nullable**), `reasons: String?`, `livingPlan: String? = null`,
`livingPlanClear: Boolean = false`, `addObservationIds: List<Long> = []`.

Three living-plan states off two keys: value SETS, `livingPlanClear` CLEARS,
neither key KEEPS (`UpdateCollegeListEntryRequest.kt:7-17`,
`CollegeListRoutes.kt:117-126`). Both together → `400`
`FieldError("livingPlan", "At most one of livingPlan, livingPlanClear may be
set")`
(`:121-123`). An explicit JSON `null` is indistinguishable from an omission and
therefore means KEEP (`UpdateCollegeListEntryRequest.kt:10-12`) — **an SSR form
that always submits every field must send neither key when it means "keep"**.

`PublicCollegeListEntry` (`models/CollegeListEntryResponse.kt:10-22`):
`id, collegeId, collegeName, status, reasons, livingPlan, version, createdAt,
updatedAt, supportingObservations[{id, quote, utteredAt}]`.

### 3.3 The real OCC mechanism

There is **no ETag and no `If-Match`**. The version is an integer in the body
(PATCH) or the query string (DELETE).

- PATCH: `expectedVersion = request.version` (`CollegeListRoutes.kt:233`);
  mismatch → `UpdateEntryResult.VersionConflict` → **`409` with code
  `version_conflict`**, message "College list entry was modified concurrently"
  (`:76-78`, `:250-252`).
- DELETE: `?version=` parsed as Int; missing or non-integer →
  `400
  validation_failed` with
  `FieldError("version", "Missing or non-integer
  version")` (`:269-273`);
  mismatch → the same `409 version_conflict` (`:284-286`).
- Duplicate add → `409` code **`conflict`** (not `version_conflict`), "College
  is already on the list" (`:169-171`).
- The wire string is `version_conflict`
  (`rest-server/.../models/ErrorCode.kt:38`).

**iOS recovery**, for the web to mirror: `version_conflict`, `conflict` and
`not_found` are treated as one class — "someone else moved it" — and all three
trigger a full list reload plus surfacing the server's message; the detail
screen then pops rather than retrying
(`ios-app/UnicoachiOS/CollegeListViewModel.swift:116`, `:129-141`, docs at
`:102-105`). For SSR this maps to: re-read, re-render the form with fresh values
and a "this changed elsewhere" notice — never auto-retry with the stale version.

---

## 4. College search

`GET /api/v1/colleges`, GET only
(`rest-server/.../routing/CollegeRoutes.kt:36-40`). **Authenticated session
required, no student-profile gate** (`:42-47`, rationale `:22-28`).

Query params:

| Param   | Required | Rules                                                                                                                                                                                                                                                                                                                     |
| ------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `q`     | yes      | trimmed; null/empty → `400 validation_failed` `FieldError("q","Missing or blank query")` (`CollegeRoutes.kt:54-59`); `length > 100` → `400` `FieldError("q","Query must be at most 100 characters (got N)")` (`:60-70`, bound `CollegeSearchService.MAX_QUERY_LENGTH = 100` at `college/.../CollegeSearchService.kt:266`) |
| `limit` | no       | absent → `20` (`CollegeRoutes.kt:81`, `DEFAULT_LIMIT` at `:99`); non-integer → `400` `FieldError("limit","Non-integer limit: [$raw]")` (`:82-86`); out of range is **clamped, not rejected**, to `1..25` (`CollegeSearchService.kt:118`, `MIN_LIMIT`/`MAX_LIMIT` at `:255-256`)                                           |

Response `200 { "colleges": [ { id, name, city, state } ] }`
(`models/CollegeSearchResponse.kt:4-6`, `models/PublicCollegeSummary.kt:9-27`).
The id key is `id`, **not** `college_id` — deliberately, and documented as such
(`PublicCollegeSummary.kt:10-22`). It is the value that goes into
`CreateCollegeListEntryRequest.collegeId`.

No pagination, no offset, no total count. A zero-match query is an empty list,
not an error (`CollegeSearchService.kt:109-114`). An unbuilt search index is a
failure, logged and surfaced as a 500-class refusal
(`CollegeSearchService.kt:120-134`) — **an SSR picker must render that as a
refusal, not as "no colleges found"**.

---

## 5. Student

Routes `/api/v1/students` (POST) and `/api/v1/students/me` (GET/PATCH/DELETE)
(`rest-server/.../routing/StudentRoutes.kt:56-67`). All require an authenticated
user; none require a pre-existing student profile (they ARE the profile).

**`POST /api/v1/students`** — body
`CreateStudentRequest { expectedHighSchoolGraduationDate: String }`
(`models/CreateStudentRequest.kt:3-5`). This is the **only** field. Outcomes:
`201 { student }` (`StudentRoutes.kt:79-81`); `400` code **`validation_error`**
(not `validation_failed`) message "Invalid student parameters" with
`FieldError("expectedHighSchoolGraduationDate", "Invalid graduation date
format")`
(`StudentRoutes.kt:46-48`, `service/.../StudentService.kt:23-25,32-35`);
`409 student_already_exists` (`StudentRoutes.kt:87-89`).

**`PATCH /api/v1/students/me`** — body
`UpdateStudentRequest
{ expectedHighSchoolGraduationDate: String, version: Int }`
(`models/UpdateStudentRequest.kt:3-6`). Outcomes: `200 { student }`;
`400 validation_error` as above (`StudentRoutes.kt:129-131`);
`404 student_not_found` (`:42-44`, `:133-135`); `409 version_conflict` "Student
was modified concurrently" (`:137-139`).

**`GET /api/v1/students/me`** — `200 { student }` or `404 student_not_found`
(`StudentRoutes.kt:100-105`).

**`DELETE /api/v1/students/me`** — deletes the student AND the account, clears
the session cookie, `204` (`StudentRoutes.kt:150-153`, cookie clear at
`:162-173` — `SameSite=Strict`, `httpOnly`, `maxAge=0`).

`PublicStudent`:
`id, expectedHighSchoolGraduationDate, version, createdAt,
updatedAt`
(`models/StudentResponse.kt:10-16`).

### Graduation date — the exact format

`expectedHighSchoolGraduationDate` is a **`PartialDate`**, not a date. Accepted
wire forms are exactly the zero-padded canonical ISO strings `YYYY` | `YYYY-MM`
| `YYYY-MM-DD`
(`db/src/main/kotlin/ed/unicoach/db/models/PartialDate.kt:58,63,66-92`);
`EXPECTED_FORM` is the literal string `"YYYY | YYYY-MM | YYYY-MM-DD"` (`:63`).
An `<input type="date">` produces `YYYY-MM-DD` and so is valid, but it **forces
a precision the model does not require** — the iOS/onboarding meaning is usually
a graduation YEAR. A web onboarding form should offer a year select.

Note the error message is the same generic "Invalid graduation date format" for
every failure mode (`StudentService.kt:25`); `PartialDate.EXPECTED_FORM` is NOT
included in it. If the web wants to say "use YYYY", it composes that copy
itself.

---

## 6. The error model

One JSON shape everywhere:

```kotlin
ErrorResponse(code: ErrorCode, message: String, fieldErrors: List<FieldError>? = null)
```

(`rest-server/.../models/ErrorResponse.kt:5-9`), where
`FieldError(field: String, message: String)`
(`common/src/main/kotlin/ed/unicoach/error/FieldError.kt:3-6`).

`ErrorCode` is a closed enum whose wire form is lowercase snake_case via
`@JsonValue` (`models/ErrorCode.kt:13-15`). Codes relevant to forms:
`unauthorized` (`:17`), `validation_failed` (`:18`), `conflict` (`:19`),
`not_found` (`:23`), `student_profile_required` (`:24`), `validation_error`
(`:35`), `student_not_found` (`:36`), `student_already_exists` (`:37`),
`version_conflict` (`:38`), `bad_request` (`:41`), `payload_too_large` (`:42`),
`forbidden` (`:45`), `email_not_verified` (`:48`).

**Two validation codes exist and both are live.** `validation_failed` is the
shared helper used by money profile, college list and college search
(`rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt:31-33`, message always
the literal "Validation failed"). `validation_error` is the student family's
own, message "Invalid student parameters" (`StudentRoutes.kt:46-48`).
`ErrorCode.kt:32-34` records that this is a known un-unified legacy split. **Do
not write a slice that assumes one validation code.**

Field-level errors ARE available and ARE pooled — the money-profile PUT reports
every invalid field in one 400 (`MoneyProfileRoutes.kt:109-116`). The `field`
value is the **request JSON key** (`incomeBand`, `residencyState`, `livingPlan`,
`status`, `reasons`, `version`, `q`, `limit`,
`expectedHighSchoolGraduationDate`, `observationId`), so an SSR form can key its
per-input error slots directly off it — provided the form's input names match
those keys.

Not every 4xx carries `fieldErrors`. Conflicts and not-founds carry only a
message (`MoneyProfileRoutes.kt:64,88`; `CollegeListRoutes.kt:77,170`).
Malformed JSON, a wrong content type, or a JSON null body become
`400 bad_request` with **no** `fieldErrors` (`plugins/StatusPages.kt:30-49`); an
oversized body is `413 payload_too_large` (`:39-43`); an unhandled throwable is
`500 internal_error` (`:51-92`).

`405` is NOT this shape: `rejectUnsupportedMethods` responds **plain text** with
an `Allow` header (`rest-server/.../Routing.kt:35-44`).

---

## 7. Per-form table

| Form                                       | Fields (wire names)                                                                                                                                             | Validation                                                                                      | Error shape                                                                                                       | Endpoint                                                                                                                |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| **Sign-up / onboarding — student profile** | `expectedHighSchoolGraduationDate` (string)                                                                                                                     | `YYYY`\|`YYYY-MM`\|`YYYY-MM-DD` only (`PartialDate.kt:58,66-92`)                                | `400 validation_error` + `fieldErrors[expectedHighSchoolGraduationDate]`; `409 student_already_exists`            | `POST /api/v1/students` (`StudentRoutes.kt:58`)                                                                         |
| **Edit graduation date**                   | `expectedHighSchoolGraduationDate`, `version` (int, required)                                                                                                   | as above + OCC                                                                                  | `400 validation_error`; `404 student_not_found`; `409 version_conflict`                                           | `PATCH /api/v1/students/me` (`StudentRoutes.kt:63`)                                                                     |
| **Your details — money profile**           | `incomeBand`/`incomeBandDeclined`/`incomeBandClear`; `residencyState`/`residencyDeclined`/`residencyClear`; `livingPlan`/`livingPlanDeclined`/`livingPlanClear` | ≤1 of the 3 per field; enum membership; state ∈ 59 USPS codes (`MoneyProfileRoutes.kt:134-203`) | `400 validation_failed` + pooled `fieldErrors`; `404 not_found`                                                   | `PUT /api/v1/students/me/money-profile` (`MoneyProfileRoutes.kt:49`). Read: `GET` same path, **404 before first write** |
| **Picker options source**                  | —                                                                                                                                                               | —                                                                                               | 500 on an unlabelled residency code (`VocabularyService.kt:215`)                                                  | `GET /api/v1/vocabularies` (`VocabularyRoutes.kt:43`)                                                                   |
| **College search / add picker**            | `q` (≤100 chars, non-blank), `limit` (int, default 20, clamped 1..25)                                                                                           | `CollegeRoutes.kt:54-87`; `CollegeSearchService.kt:255-266`                                     | `400 validation_failed` + `fieldErrors[q]` / `[limit]`                                                            | `GET /api/v1/colleges`                                                                                                  |
| **Add college to list**                    | `collegeId` (UUID, required), `status` (default `considering`), `reasons`, `livingPlan`, `observationIds[]`                                                     | status ∈ 4 values; reasons non-blank ≤2048; livingPlan ∈ 3 values                               | `400 validation_failed` + `fieldErrors[status\|livingPlan\|reasons]`; `404 not_found`; `409 conflict` (duplicate) | `POST /api/v1/students/me/college-list`                                                                                 |
| **Edit college-list entry**                | `version` (int, required), `status` (string, required), `reasons`, `livingPlan` XOR `livingPlanClear`, `addObservationIds[]`                                    | as above + OCC + the livingPlan pair check                                                      | `400 validation_failed`; `404 not_found`; `409 version_conflict`                                                  | `PATCH /api/v1/students/me/college-list/{entryId}`                                                                      |
| **Remove college-list entry**              | `version` query param                                                                                                                                           | integer required                                                                                | `400 validation_failed` + `fieldErrors[version]`; `404`; `409 version_conflict`                                   | `DELETE /api/v1/students/me/college-list/{entryId}?version=N`                                                           |
| **Delete account**                         | —                                                                                                                                                               | —                                                                                               | `404 student_not_found`                                                                                           | `DELETE /api/v1/students/me` (clears cookie, `StudentRoutes.kt:162-173`)                                                |

---

## 8. What the web needs that does NOT exist server-side yet

Ordered roughly by how much a slice would be blocked without it.

1. **A session-cookie caller resolver reachable from `:public-web`.**
   `SessionConfig` and `ApplicationCall.resolveCaller` are in `:rest-server`
   (`rest-server/.../auth/SessionConfig.kt:6`,
   `.../auth/CallerResolution.kt:40-54`), which `public-web` does not depend on
   (`public-web/build.gradle.kts:11-18`). Either move them to `:web-common`/
   `:common` or write a second one. Same for the `CallerResolution` preamble
   (`rest-server/.../routing/CallerResolution.kt:31-59`).

2. **A `SessionsDao` write path for `metadata`.** There is `create`,
   `remintToken`, `extendExpiry`, `revokeByTokenHash`, `expireZombieSessions` —
   and no metadata update
   (`db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt:57-252`). Form drafts
   in `sessions.metadata` require a new DAO method **and** the write must
   respect `pg_column_size(metadata) <= 2048`
   (`db/schema/0002.create-sessions.sql:23`). Nothing today enforces that budget
   in Kotlin; a 2049-byte draft is currently a raw constraint violation, i.e.
   a 500.

3. **Any protobuf at all.** I found no `.proto` file, no protoc/`JsonFormat`
   dependency and no `protobuf-java-util` on the classpath in the modules I
   read. **UNVERIFIED across the whole build** — I checked
   `public-web/build.gradle.kts` and `auth/build.gradle.kts` only. Treat
   "canonical proto3 JSON via `JsonFormat`" as greenfield: schema, codegen
   wiring, and the 2 KiB size check are all new.

4. **A living-plan vocabulary and a dependency vocabulary.** Not registered
   (`VocabularyService.kt:107-111`). The web can read the enums directly, but
   iOS cannot — so if the slice is "serve every picker from one place", that is
   a new registry entry each.

5. **Dependency status on the REST money profile.** Absent from both request and
   response (`UpdateMoneyProfileRequest.kt:9-19`,
   `MoneyProfileResponse.kt:13-23`) although the service and schema support it.
   A web form can bypass REST; an iOS/web parity claim cannot.

6. **Picker-caption labels.** `LivingArrangement.label` and
   `DependencyStatus.label` are sentence fragments ("renting off campus",
   "dependent for federal aid"), documented as the spoken form
   (`LivingArrangement.kt:33-42`, `DependencyStatus.kt:19-23`). There is no
   short UI caption anywhere. The web must either write its own copy or add a
   second label — and CLAUDE.md's "one enum, one vocabulary" rule makes the
   second label a design decision, not a detail.

7. **An email-verification gate for the web.** `configureEmailVerificationGate`
   is installed by `rest-server` only (`plugins/EmailVerificationGate.kt:33`);
   `publicWebModule` installs request logging and routing and nothing else
   (`public-web/src/main/kotlin/ed/unicoach/web/Application.kt:72-84`, comment
   at `:20-23` states every public-web route is public today).

8. **Cache headers / conditional GET on `/vocabularies`.** Explicitly not
   implemented, and the note says inventing one was out of scope
   (`VocabularyService.kt:248-256`). SSR that re-renders a 59-entry select on
   every request will re-read it every time.

9. **Server-side "no partial PATCH" relief for the college list.**
   `UpdateCollegeListEntryRequest.status` is non-nullable
   (`UpdateCollegeListEntryRequest.kt:5`), so a web form that edits only
   `reasons` must still resend the current `status`. That is workable but it
   means every college-list form must carry the full current state as hidden
   inputs, alongside `version`.

10. **Pagination anywhere.** Neither college search (`CollegeRoutes.kt:89-96`)
    nor the college list (`CollegeListRoutes.kt:183-193`) paginates. Search is
    hard-capped at 25 rows; the list is unbounded.

---

## 9. Things I did not verify

- **Protobuf tooling**: I did not read the root `build.gradle.kts`,
  `settings.gradle.kts` or `gradle/libs.versions.toml`. My "no protobuf"
  statement rests on finding no `.proto` file and no protobuf import in the
  modules read. **UNVERIFIED.**
- **`api-specs/openapi.yaml`**: not read. It may or may not match the Kotlin;
  per CLAUDE.md the code wins either way.
- **Auth routes** (`AuthRoutes.kt`) — register/login/verify body shapes are
  another DISCOVER report's subject (`research/backend-and-auth.md` exists). I
  only established that `AuthService` lives in `:service`
  (`service/.../auth/AuthService.kt:39`) and exposes `register` (`:50`), `login`
  (`:260`), `resolveSession` (`:211`), `logout` (`:246`), `loginWithSso`
  (`:335`).
- **`ObservationSummary` / `observationIds`**: the observation surface exists in
  the college-list request/response but I did not trace where a web form would
  obtain observation ids. Likely chat-only.
- I ran **no build and no tests** — the root checkout is read-only per the task.
  Every claim is a source reading.
