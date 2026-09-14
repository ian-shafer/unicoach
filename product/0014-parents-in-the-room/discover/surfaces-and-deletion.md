# DISCOVER — surfaces and deletion (brief 0014)

Method: static reading of the worktree at
`/Users/ian/Work/unicoach-docs-0014-parents-in-the-room` (main). Four parallel
agents, one per question; every claim below cites `path:line` that was actually
read. No builds, no tests were run.

## 0. Corrections — where the brief is WRONG

The brief's foundations table is accurate about what EXISTS. It is wrong or
silent about what that existence lets you do. Ranked by how much a slice would
suffer:

| #  | Brief assumes                                                           | Reality                                                                                                                                                                                                                                                                                                                                                                                                                                                               | Cite      |
| -- | ----------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| C1 | "Account deletion (brief 0002) — cascade rules per table … RFCs landed" | **Brief 0002 was never implemented.** No spec, no slices, no ledger. What exists is RFC 31's `DELETE /students/me`: a two-row soft delete, no purge, no grace period, no traversal of content, and **no in-app UI anywhere** (App Store 5.1.1(v) unmet). D9 "extends brief 0002's deletion work" extends nothing — the slice BUILDS the partition.                                                                                                                    | §3.1      |
| C2 | "Subscriptions — per-user, Apple IAP"; D8 billing unchanged             | Billing is **per-STUDENT**, not per-user: `subscriptions.student_id` FK, and `/subscriptions/verify`, `/coaching-usage` and every convo route 409 a caller with no `students` row. A parent cannot subscribe, cannot hold a coaching meter, and has nowhere to record LLM spend. A shared/Family-Sharing Apple ID returns the SAME `originalTransactionId`, which is already owned by the student → hard 409 with no path to pay. D8 is not a no-op; it is a blocker. | §4        |
| C3 | The share link is "the invite wedge that already exists"                | The link carries **no identity at all** and cannot learn one: multi-use, forwardable, no expiry, one live row per student, raw token re-derivable from `(secret, id)`. `share_events` logs only student actions. It cannot be reused as an invite; the invite needs its own `verification_tokens`-shaped table. The report page also deliberately forbids the CTA a claim on-ramp needs (RFC 155 D-F/D-G, enforced in code) — a new RFC must supersede them.          | §2        |
| C4 | A parent is "representable today" as a user with no `students` row      | Representable in the DB, unusable in the app. iOS routes any user without a student profile to `.onboarding` — the **student** form — so a parent account has no valid client state. And `EmailVerificationGate` is a global interceptor: an invite-accept route must be exempted or a parent cannot accept before verifying email.                                                                                                                                   | §1(a),(c) |
| C5 | Parent visibility is "a per-surface authorization decision"             | There is **no seam to decide at**. `resolveStudent(user)` = `getStudentForUser(user.id)`; no code path anywhere resolves a student other than the caller's own, and no client ever sends a student id. D1/D4 require adding a subject parameter to `college-list`, `money-profile` and `conversations`, plus a link-table check — in the API, the service layer and every iOS client.                                                                                 | §1(a),(c) |
| C6 | D5 13+ DOB gate                                                         | No date-of-birth field exists — not in `students`, not in onboarding. Net-new column, migration, API field and iOS UI.                                                                                                                                                                                                                                                                                                                                                | §1(a)     |
| C7 | D2 "either side unlinks anytime"                                        | On the cost-report surface there is no per-parent lever: revoke kills the single live link for every holder at once.                                                                                                                                                                                                                                                                                                                                                  | §2        |

---

## 1. The doors (iOS, public-web, REST surface)

### (a) iOS — one student, implied by cookie

`UnicoachiOSApp.swift:12-78` is one `WindowGroup` switching on
`AppViewModel.authState` (`AppViewModel.swift:5-6`):
`loading | unauthenticated | onboarding | authenticated |
verificationRequired | serverError | unexpectedError | noConnectivity`.
**No tabs.** One `NavigationStack` rooted on chat
(`AuthenticatedRootView.swift:11-48,156-161`), five push destinations —
`conversation`, `conversations`, `collegeList`, `yourDetails`, `settings`
(`:42-48`, built `:328-366`). Drawer `SlideOverMenu.swift:21-47,79-108`: New
conversation, Recent (≤3, `:122-146`), Your details, My colleges, All
conversations, Settings.

**Subject resolution is purely implicit.** `APIClient` sends only base URL,
`X-Unicoach-Client-Key` and the session cookie
(`APIClient.swift:9-13,58-60,195-205`); **no path or query carries a
user/student id anywhere in the client** — calls are `/api/v1/students/me/...`
or bare `/api/v1/conversations` (`StudentClient.swift:19,30`).
`PublicUser.id`/`PublicStudent.id` decode (`Models.swift:9-10,155-156`) but are
never sent back.

**A switcher + "parents supporting you" touches:** `UserAuthState.swift`
(viewed-subject value), `AppViewModel.swift:113-217`,
`AuthenticatedRootView.swift:42-48,118-153,328-366`,
`SlideOverMenu.swift:79-108`, and **every client** — `ConversationClient`,
`CollegeListClient`, `MoneyProfileClient`, `CoachingUsageClient`,
`VocabularyClient`, `StudentClient` all hardcode `me`. Parent list = new
`LinkClient.swift` + view/view-model + `Destination` case; invite/unlink fits
`SettingsView.swift:51-70`.

**Flags.** (1) **13+ DOB gate does not exist** — no birth field in iOS, no
column in schema (`db/schema/0005.create-students.sql`); onboarding asks
graduation date and income band only
(`OnboardingView.swift:83,177-181,246-250`). Net-new UI + column + migration.
(2) **A parent has no valid iOS state**: `resolveProfileState`
(`AppViewModel.swift:217`) routes a user with no student profile to
`.onboarding` (`UnicoachiOSApp.swift:27-33`) — the _student_ form. (3)
`ClientKeyGate.kt:17-23` guards REST, so browser invite acceptance must be a
public-web page.

### (b) public-web — 6 user-facing routes

All in `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`: `GET /`
(`:116`), `GET /terms` (`:117`), `GET /privacy` (`:118`), `GET /verify-email`
confirm form, side-effect free (`:122-132`), `POST /verify-email` burns the
single-use token (`:134-146`), `GET /report?token=` logged-out Family Cost
Report (`:148`, impl `:201-232`). Renderers in `render/`: `HomePage`, `Terms`,
`Privacy`, `VerifyEmail`, `CostReport`, `ErrorPages`, `Layout`. `/report` is the
hashed-share-link precedent: route-scoped `secretQueryParams`, link-holder
privacy headers, uniform branded 404 for blank/repeated/unknown/revoked
(`Routing.kt:203-229`). An invite page (`GET`+`POST /invite`) should copy the
two-step `verify-email` shape (`:122-146`).

### (c) REST route groups — all session-scoped, all `me`

`rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt:63-100` wires ten
handlers. One scoping seam: `resolveUser()` reads the session cookie only
(`routing/CallerResolution.kt:30,48`; `auth/CallerResolution.kt:41-54`),
`resolveStudent(user)` = `getStudentForUser(user.id)`
(`routing/CallerResolution.kt:33,50`) — **no code path resolves a student but
the caller's own.**

| group               | file:line                          | covers                                                                 | scoped                                         |
| ------------------- | ---------------------------------- | ---------------------------------------------------------------------- | ---------------------------------------------- |
| health              | `Routing.kt:82-89`                 | `GET /healthz`                                                         | no (public)                                    |
| auth                | `AuthRoutes.kt:68-104`             | register/login/google/apple/me/logout/verify-email/resend/change-email | mixed; `/me`,`/logout`,`/change-email` session |
| students            | `StudentRoutes.kt:56-64`           | `POST /students`, `GET\|PATCH\|DELETE /students/me`                    | yes (`:71,94,109,146`)                         |
| conversations       | `ConvoRoutes.kt:90-113`            | list/create/get/patch/delete, messages, 2 SSE streams                  | yes (`:133,170,178,189,202,213,224,268,297`)   |
| college-list        | `CollegeListRoutes.kt:50-60`       | `/students/me/college-list` CRUD                                       | yes (`:139,185,197,214,266`)                   |
| colleges            | `CollegeRoutes.kt:36-37`           | `GET /colleges` search                                                 | auth only, no student (`:43`)                  |
| money-profile       | `MoneyProfileRoutes.kt:47-49`      | `GET\|PUT /students/me/money-profile`                                  | yes (`:56,71`)                                 |
| coaching-usage      | `CoachingUsageRoutes.kt:34-35`     | `GET /students/me/coaching-usage` meter                                | yes (`:42`)                                    |
| subscriptions       | `SubscriptionRoutes.kt:41-42`      | `POST /subscriptions/verify` StoreKit receipt                          | yes (`:49`)                                    |
| apple-notifications | `AppleNotificationRoutes.kt:49-52` | Apple S2S webhook                                                      | no — signature verified (`:131`)               |
| vocabularies        | `VocabularyRoutes.kt:42-43`        | `GET /vocabularies` static enums                                       | auth only (`:49`)                              |

**New groups needed:** `/api/v1/links` (list, unlink), `/api/v1/invites` (mint,
list, revoke, accept), `/api/v1/parents/me`. **Needing a subject parameter:**
`college-list`, `money-profile`, `conversations` — each gains
`/students/{studentId}/...` (or a subject header) plus a link-table
authorisation check replacing today's unconditional `resolveStudent(user)`.
`coaching-usage` and `subscriptions` stay `me` (per-user billing unchanged).
`students` needs the DOB column and the 13+ check in `handleCreate`
(`StudentRoutes.kt:57-59`). **Flag:** `EmailVerificationGate` is a global
interceptor (`plugins/EmailVerificationGate.kt:32-42`, installed
`Application.kt:423`) blocking every authenticated-but-unverified caller; an
invite-accept route must be exempted, or a parent cannot accept a link before
verifying email.

---

## 2. The share link today

### Schema

`cost_report_shares` (RFC 155) —
`db/schema/0073.create-cost-report-shares.sql:15-31`:
`id UUID PK DEFAULT uuidv7()`, `created_at`, `row_created_at`,
`student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE`,
`token_hash BYTEA NOT NULL`, `revoked_at TIMESTAMPTZ NULL`. No `expires_at`, no
`consumed_at`, no OCC `version`, no `_versions` history (`:1-9`). Indexes:
unique on `token_hash` (`:25-26`) and the partial unique
`cost_report_shares_one_live_per_student_idx ON (student_id) WHERE revoked_at IS NULL`
(`:30-31`).

Token model: the row stores only `SHA-256(token)`; the raw token is DERIVED,
`HMAC-SHA256(shareTokenSecret, id)` (`0073...sql:11-13`;
`service/.../report/ShareTokenDeriver.kt:32-43`), base64url, 43 chars, no
padding (`ShareTokenDeriver.kt:57-73`). Rotating the secret is a global revoke
(`ShareTokenDeriver.kt:26-29`).

`share_events` (RFC 160) — `db/schema/0081.create-share-events.sql:30-51`:
`id BIGINT IDENTITY PK`, `created_at`, `student_id` (FK students, CASCADE),
`share_id UUID NULL` (FK cost_report_shares, CASCADE), `kind TEXT` CHECK IN
`minted|repeat|reissued|revoked|opted_out` (`:36-37`), plus the paired CHECK
`(kind='opted_out') = (share_id IS NULL)` (`:41-42`). Index
`(student_id, created_at)` (`:46`). Append-only: `prevent_log_update` /
`prevent_log_delete` triggers (`:48-51`).

### Intended semantics (RFC)

- Live page, not a snapshot — `rfc/155-family-cost-report.md:32` (D-A); the page
  says so once:
  `"This report is live — it updates as your student updates their list."`
  (`public-web/.../render/CostReportPage.kt:97`).
- One live share per student; a repeat ask returns the SAME link — `rfc/155:53`,
  `:82`.
- **No expiry at all** — `rfc/155:103-113`. Revocation is the only control,
  student-driven and immediate.
- No student identity on the page, no upgrade cue — `rfc/155:167`, `:180`;
  `CostReportPage.kt:68-76`.
- Door is chat only; an iOS share sheet is explicitly a follow-up —
  `rfc/155:197-205`.

### Kotlin: mint / revoke / resolve

- Mint + reuse: `service/.../report/CostReportShareService.kt:176-195`
  (`share`), `:283-293` (`mintOrReuse`), `:351-388` (`mintLink`, writes `minted`
  or `reissued` event in the same txn). Outcomes
  `Existing | Minted | Reissued | Unavailable` (`:60-99`); `Unavailable` = no
  secret configured, a success, not a failure.
- Revoke: `CostReportShareService.kt:205-218` → `CostReportSharesDao.revokeLive`
  compare-and-swap
  `UPDATE ... WHERE student_id=? AND revoked_at IS NULL RETURNING *`
  (`db/.../dao/CostReportSharesDao.kt:112-130`). Second revoke = `NothingLive`,
  ordinary.
- Opt-out ("never ask again"): `CostReportShareService.kt:229-265`; touches no
  share row.
- Resolve: `public-web/.../report/ServiceCostReportSource.kt:64-82` — blank →
  `BLANK_TOKEN`; shape gate `ShareToken.isWellFormed` → `MALFORMED_TOKEN`; hash
  → `CostReportSharesDao.findLiveByTokenHash` (`CostReportSharesDao.kt:93-103`),
  which hides revoked rows behind the same null as unknown (`:87-92`). One
  connection for share lookup + cost read (`ServiceCostReportSource.kt:19-28`).
- Callers: three chat tools only — `share_cost_report`,
  `revoke_cost_report_share`, `stop_cost_report_offers`
  (`service/.../report/ShareCostReportChatTool.kt:110`,
  `RevokeCostReportShareChatTool.kt:63`, `StopCostReportOffersChatTool.kt:70`),
  wired in `rest-server/.../Application.kt:332-354`, `:397-400`. **There is no
  REST route for share/revoke** and no rest-server share endpoint (grep of
  `rest-server/src/main`: only the composition root).
- Tool payload the coach speaks: `link_created`, `url`, `who_can_see`,
  `previous_link_no_longer_works` (`ShareCostReportChatTool.kt:81-86`,
  `:115-123`).

### The public page

`GET /report?token=<raw>` in `public-web`, registered at `Routing.kt:172-202`;
path/param constants `REPORT_PATH`/`REPORT_TOKEN_PARAM` at `Routing.kt:16,23`,
composed by `tokenLink(base, raw)` in `common/.../config/UrlBase.kt:57-70`; base
is `costReport.shareUrlBase = ${publicWeb.urlBase}"/report"`
(`service/src/main/resources/service.conf:413-414`).

**The page is fully unauthenticated.** The token IS the whole credential;
nothing identifies the holder. Heading is `"Your student's college list"`
(`CostReportPage.kt:91`), title `"Cost report"` (`:88`) — no name, no email, no
id (`:68-71`). Expired does not exist (no expiry). Revoked, unknown, malformed,
blank and repeated-`token` all render the SAME branded 404 body, byte for byte;
the reason is logged only (`Routing.kt:166-199`,
`report/CostReportSource.kt:37-79`). A real DB fault renders the 503, never the
404 (`Routing.kt:168-170`). The page is side-effect free, so a prefetching mail
client changes nothing (`ServiceCostReportSource.kt:16-17`). The only outbound
anchors are CDS source links (`CostReportPage.kt:1496-1505`) — **there is no
sign-up, log-in or "claim" CTA anywhere on the page**.

### iOS

Nothing. Grep of `ios-app/**/*.swift` for `ShareLink`,
`UIActivityViewController`, `share_cost_report` returns zero hits; RFC 155
states this outright and defers the share sheet (`rfc/155:197-205`, `:818`). A
student shares by asking the coach in chat and copying the URL the coach speaks.

### The token→identity gap, and what a "claim an account" slice touches

The gap is total. The share token resolves to a `student_id` and stops there
(`ServiceCostReportSource.kt:75-80`). Nothing on the link path knows, stores, or
can ever learn WHO opened it: no viewer row, no per-recipient token, no session,
no cookie, no rate limiting on the page. The link is also multi-use and
forwardable by design, so "the parent who opened it" is not a fact the system
holds — which contradicts the brief's assumption that an invite carries an
identity edge. `share_events` logs only what the STUDENT did
(`0081...sql:11-24`); there is no `viewed` kind and no accept/claim state.

Concretely, a claim-an-account slice must:

1. **Not reuse `cost_report_shares`.** Its invariants are wrong for an invite:
   no expiry (`rfc/155:103`), one live row per student (`0073:30-31`),
   multi-use, and the raw token is re-derivable from `(secret, id)` so any
   holder of both reproduces it. An invite needs per-invite randomness, an
   accept/consume state, and N invites per student. Follow the
   `verification_tokens` (0014) precedent instead — new table
   (`parent_invites`), new DAO in `db/src/main/kotlin/ed/unicoach/db/dao/`, new
   schema file under `db/schema/`.
2. **Add the identity edge**: the role-typed `parent`↔`student` link table the
   brief assumes does not exist today — grep found no such table;
   `cost_report_shares.student_id` is the only parent-facing edge, and it names
   no parent.
3. **Add authenticated routes.** `public-web` today has exactly one
   state-mutating route (`POST /verify-email`, `Routing.kt:105-117`) and no
   session at all. A claim flow needs either a new public-web accept page plus a
   `rest-server` signup/accept endpoint, or an app deep link — `rest-server` has
   no share/invite route to extend.
4. **Change the report page's contract** if the link becomes an on-ramp: D-F (no
   identity, `rfc/155:167`) and D-G (no upgrade cue, `rfc/155:180`) forbid the
   CTA a claim on-ramp needs, and both are enforced in
   `CostReportPage.kt:68-76`. A new RFC must supersede them; the current page
   deliberately renders no account affordance.
5. **Decide what unlink does to the share link.** Unlink "never deletes" per the
   brief, but revoking the cost-report share kills the link for EVERY holder at
   once (`CostReportSharesDao.kt:112-130`) — there is no per-parent revoke, so
   the brief's "either side unlinks anytime" has no per-parent lever on this
   surface today.

---

## 3. Deletion

### 3.1 What exists today

Brief 0002 is **FRAMED, never implemented** —
`product/0002-account-data-deletion/` holds only `brief.md` (no spec, no slices,
no ledger, so no SHAs and no RFC numbers). Its gate-1 questions D1-D6
(`product/0002-account-data-deletion/brief.md:63-84`) are all still open. What
exists predates it, from RFC 31 (`rfc/31-student-profile.md:247,267,375-382`):

- **Route** `DELETE /api/v1/students/me` —
  `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt:64,143-160`;
  clears the session cookie (`:161-174`), 204.
- **Service**
  `service/src/main/kotlin/ed/unicoach/student/StudentService.kt:117-142`: one
  transaction — `StudentsDao.delete` + `UsersDao.delete` +
  `SessionsDao.revokeByTokenHash`.
- **Pure soft delete.** `UsersDao.delete` is
  `softDeleteReturning(deleted = true)`
  (`db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt:273-284`): sets
  `deleted_at`, bumps version. Nothing is hard-deleted, nothing anonymised, no
  PII scrubbed.
- **No queue/job path** — `JobType` has no purge variant
  (`queue/src/main/kotlin/ed/unicoach/queue/JobType.kt:8-28`). **No grace
  period, no purge phase.**
- **Access ends at once** because reads filter `deleted_at IS NULL`
  (`UsersDao.kt:115,188,231`; `StudentsDao.kt:130,229`).
- **No in-app surface.** No account-delete UI in `ios-app/`, `public-web/`,
  `admin-web/`. App Store 5.1.1(v) unmet.

Gaps in the existing path: only the **current** session token is revoked
(`SessionsDao.kt:233-243`), so other live sessions survive;
`user_auth_identities` rows survive; the subscription is untouched; every
student-owned row (convos, claims, college list, money profile, LLM log) stays
physically present.

### 3.2 Cascade rules — every FK to `users(id)`

Physical DELETE on `users` is refused by `trigger_00_prevent_physical_delete`
(`db/schema/0001.create-users.sql:93-96`, function
`db/schema/0000.shared-functions.sql:37`), so these actions are **latent**,
never exercised.

| table                  | column    | ON DELETE            | migration:line                                      |
| ---------------------- | --------- | -------------------- | --------------------------------------------------- |
| `users_versions`       | `id`      | RESTRICT             | `db/schema/0001.create-users.sql:48`                |
| `sessions`             | `user_id` | CASCADE              | `db/schema/0002.create-sessions.sql:12`             |
| `students`             | `user_id` | _(none → NO ACTION)_ | `db/schema/0005.create-students.sql:13`             |
| `verification_tokens`  | `user_id` | _(none → NO ACTION)_ | `db/schema/0014.create-verification-tokens.sql:13`  |
| `user_auth_identities` | `user_id` | CASCADE              | `db/schema/0018.create-user-auth-identities.sql:21` |

That is the complete set — a repo-wide grep of `db/schema/*.sql` for
`REFERENCES users` and `FOREIGN KEY` finds no others. **Everything else
user-owned hangs off `students(id)`**, all CASCADE except `students_versions`
(RESTRICT, `0005:39`): `convos` (`0006:57`),
`claims`/`observations`/`commitments` (`0019:28,63,152`), `college_list_entries`
(`0024:35`), synthesis (`0025:36,122`), fit-lens (`0031:38,88`), `subscriptions`
(`0042:33`), `money_profiles` (`0046:27`), `cost_report_shares` (`0073:20`),
`share_events` (`0081:33`). `students` also carries `prevent_physical_delete`
(`0005:80`), so that cascade is latent too.

### 3.3 What author-partitioned deletion must add

`parent_student_links` makes the ownership graph a DAG with **two roots**; the
current traversal models one.

1. **Unlink is a row state, not a delete.** Soft `unlinked_at` + partial unique
   index on `(parent_user_id, student_id) WHERE unlinked_at IS NULL` — the
   `cost_report_shares.revoked_at` precedent
   (`db/schema/0073.create-cost-report-shares.sql:22,30-32`).
2. **Parent-owned data hangs off `users(id)`**, not `students(id)`: parent
   workspace, suggestions, parent-side convos, invites. That is the first real
   extension of the 5-row table in §3.2; each new table decides its ON DELETE at
   creation.
3. **Invites**: hashed-token table modelled on `verification_tokens`
   (`0014:13`), with an inviter `user_id` and a nullable invitee side — another
   new FK row in §3.2.
4. **Contradiction to flag.** "Author-partitioned deletion" assumes a partition
   mechanism that does not exist: today's delete touches two rows and traverses
   no content. A slice must BUILD the traversal first. Brief 0002's D1 (erase vs
   retain) and D3 (how the append-only `prevent_delete` guards are breached) are
   prerequisites, not details.
5. **Blocker**: a parent has no `students` row, so `StudentService.kt:120-130`
   returns `NotFound` and `StudentRoutes.kt:143` would make a parent account
   **undeletable**.
6. **Sites a slice edits**: `StudentService.deleteStudentAndAccount`
   (`StudentService.kt:117-142`) becomes link-aware (auto-unlink live links,
   never touch the other party's rows); a parent-account delete route beside
   `StudentRoutes.kt:64`; a revoke-all-sessions-for-user form of
   `SessionsDao.kt:233`; a new `db/schema/00NN.create-parent-student-links.sql`;
   and the drift guard 0002 asks for (`brief.md:95-99`) must walk BOTH roots.

---

## 4. Subscriptions and gating

### What exists today

- One Apple auto-renewable subscription, server-recorded in `subscriptions`,
  keyed by Apple's `original_transaction_id` and **bound to a STUDENT row**, not
  a user: `db/schema/0042.create-subscriptions.sql:33`
  (`student_id UUID NOT NULL REFERENCES students(id)`), unique transaction id at
  `:50`. Statuses `active|expired|billing_retry|grace|revoked` (`:55`);
  entitling = `active` + `grace` (`:41-42`, index `:61-62`).
- `students` is 1:1 with `users`: `db/schema/0005.create-students.sql:33-36`
  ("One student per user, total"). So **every billing identity in the code is a
  student identity**.
- Plans are a config table productId -> period budget =
  `budgetRatio x priceUsd`: `service/src/main/resources/service.conf:361-375`
  (one product, `coach.uni.UnicoachiOS.monthly10`, $9.99, ratio 0.5), parsed by
  `service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionPlans.kt:19-42`.
- Free tier = a **lifetime** $5.00 LLM-spend allowance per student, no reset:
  `service.conf:353-358`,
  `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetConfig.kt:23-26`,
  basis `EntitlementBasis.FreeAllowance` in
  `service/src/main/kotlin/ed/unicoach/coaching/budget/Entitlement.kt:16`.

### The one gate

The gate is **not** "is there a subscription". It is "has this student's LLM
SPEND passed the allowance for whichever meter applies":
`service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetService.kt:60-75`
picks free branch (`:84-96`) vs subscription branch (`:105-124`) from
`SubscriptionsDao.findCurrent`; `Entitlement.exhausted` is `spent >= allowance`
(`Entitlement.kt:59`); `verdict()` maps it (`BudgetService.kt:48-54`).

Refusal points (all coaching, all LLM spend):

- **Chat turn, 402 `coaching_budget_exhausted`** — the only user-visible
  refusal:
  `rest-server/src/main/kotlin/ed/unicoach/rest/routing/ConvoRoutes.kt:382-391`,
  reached from the pre-flight in
  `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt:272-280` and
  `:339-347`.
- Background passes skip silently: synthesis
  `service/.../coaching/synthesis/SynthesisService.kt:137-145`, fit lens
  `.../fitlens/FitLensService.kt:221-229`, extraction
  `.../extraction/ExtractionService.kt:144`.
- `GET /api/v1/students/me/coaching-usage` reports the same meter:
  `rest-server/.../routing/CoachingUsageRoutes.kt:42-44`.

`grep PaymentRequired|402` over `rest-server/src/main` returns exactly one site
(`ConvoRoutes.kt:388`). **Nothing else is paywalled.** College list
(`CollegeListRoutes.kt`) and money profile (`MoneyProfileRoutes.kt`) import no
budget/subscription symbol at all; they gate only on session + student profile.
Cost report likewise. So: college list, money profile, cost report are FREE for
any account with a student profile; only LLM coaching costs money.

### iOS

- The paywall is **reactive only**, raised by the 402 — never on launch, never
  on signup: `ios-app/UnicoachiOS/PaywallGate.swift:86`
  (`handleBudgetExhausted()`), sheet assigned at `:122,:127`, wired at
  `ConversationView.swift:61,86`, presented at
  `AuthenticatedRootView.swift:196`. RFC 121 states the gate "lives at the
  action" and is deliberately not an auth state
  (`rfc/121-ios-paywall-gate.md:101,120`).
- Purchase posts the signed transaction to `POST /api/v1/subscriptions/verify`:
  `rest-server/.../routing/SubscriptionRoutes.kt:41-54`.

### What "per-user billing unchanged" concretely means for a parent

1. **A parent account with no student profile cannot subscribe at all.**
   `SubscriptionRoutes.kt:49` does
   `resolveStudent(user) ?: return respondStudentProfileRequired()` -> 409
   `student_profile_required` (`CallerResolution.kt:56-58`). Same for
   `/coaching-usage` (`CoachingUsageRoutes.kt:42`) and convo routes
   (`ConvoRoutes.kt:133`). **CONTRADICTION with the product decisions**: a
   parent with "own workspace" and private coach conversations needs an
   entitlement, but every subscription, usage and convo route is keyed on
   `students`, and the `subscriptions.student_id` FK (`0042...sql:33`) has no
   user-level alternative.
2. **A parent does NOT hit a paywall on signup.** Nothing checks subscription at
   signup or login; the parent hits it only on their first coaching turn — and
   today they cannot even get that far without a student row.
3. **Free tier for a parent would be a second $5.00 lifetime allowance** metered
   off `student_llm_cost` for their own id (`BudgetService.kt:84-96`). Parent
   and student each get $5, and a household with N linked parents multiplies the
   free LLM spend by N+1. No per-household or per-link cap exists. There is no
   Apple free TRIAL modeled anywhere: grep for `trial`/`introductory` in
   `service/` and `ios-app/` returns nothing; the free tier is entirely
   server-side.
4. **Apple IAP / shared Apple ID is the sharp risk.** Binding is first-come and
   permanent: "whichever student first verifies an `originalTransactionId` owns
   it" (`service/.../SubscriptionService.kt:44-46`, `:68-70`); a later different
   student verifying the SAME transaction gets `OwnedByOtherStudent` -> 409
   `subscription_owned_by_other_account` (`SubscriptionService.kt:281-288`,
   `SubscriptionRoutes.kt:104-109`). Family Sharing and a shared Apple ID both
   deliver the SAME `originalTransactionId` to the parent's device. So a parent
   on the family Apple ID who taps Subscribe or Restore gets a hard 409 and **no
   path to pay**: they cannot buy a second copy of the same product under one
   Apple ID, and the one they have is owned by the student's account. No
   `appAccountToken` is used anywhere (grep in `appstore/src/main`,
   `service/.../subscriptions`), so there is no mechanism to disambiguate which
   household member a purchase is for.

### What a slice would have to touch

- `db/schema/0042.create-subscriptions.sql` is immutable history; a slice needs
  a NEW schema file either adding a nullable `user_id` owner beside
  `student_id`, or (cleaner) re-keying entitlement to `users`. `subscriptions`
  is versioned with `ON DELETE RESTRICT` history (`:69-70`) and a
  physical-delete guard (`:104-105`), so any owner change is an additive
  column + backfill, never an in-place re-point.
- `SubscriptionsDao`
  (`db/src/main/kotlin/ed/unicoach/db/dao/SubscriptionsDao.kt`), `BudgetService`
  (`findCurrent`/`lifetimeCost`/`windowedCost` all take `StudentId`),
  `StudentLlmCostDao` — the cost ledger itself is keyed by student, so parent
  LLM spend has nowhere to be recorded today.
- `SubscriptionRoutes.kt:49`, `CoachingUsageRoutes.kt:42`, `ConvoRoutes.kt:133`
  — replace `resolveStudent` with a caller-principal resolution that accepts a
  parent.
- Decide and encode: does a parent's own coaching spend meter against the
  STUDENT's subscription period budget (household billing, contradicts "per-user
  billing unchanged"), or against the parent's own free/paid meter (needs point
  4 solved first)?
- Product decision "per-user billing unchanged" is only coherent if a parent can
  actually BUY. Given the Apple ID collision, the honest options are: a distinct
  parent productId, an `appAccountToken`-based disambiguation, or an explicit
  decision that a linked parent rides the student's subscription. None exist in
  code.
