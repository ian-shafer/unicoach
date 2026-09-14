# DISCOVER — coaching stack: coupling to student identity, and what a parent convo costs

Brief 0014 "parents in the room". Static read of the worktree
`/Users/ian/Work/unicoach-docs-0014-parents-in-the-room` at current `main`.
Every claim below is `path:line`. No build, no tests were run.

---

## 1. The ownership chain: `students` is the ONLY subject the stack knows

`students` is 1:1 with `users` and that is enforced totally, not partially:

- `db/schema/0005.create-students.sql:13` —
  `user_id UUID NOT NULL REFERENCES users(id)`.
- `db/schema/0005.create-students.sql:35` —
  `CREATE UNIQUE INDEX students_user_id_unique_idx ON students (user_id)`,
  declared total ("even across soft-deletes", `0005:32-34`).

`convos` hangs off `students`, not off `users`:

- `db/schema/0006.create-coaching-conversations.sql:57` —
  `student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE`. There is
  **no `user_id`, no author column, no party column** on `convos`.
- `db/schema/0041.add-llm-response-cost.sql:42-43` — the index was widened to
  `convos (student_id)`.

The DAO has no read that is not student-keyed or convo-keyed:

- `db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt:146-163` `listByStudent`,
  `:290-334` `listByStudentWithActivity`, `:164-184` `create` binds `student_id`
  from `NewConvo` (`db/src/main/kotlin/ed/unicoach/db/models/NewConvo.kt`).
- Ownership is checked in the service, not the DAO:
  `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt:802-809`
  `loadOwned` returns the convo only `if (convo.studentId == studentId)`.

The REST edge resolves a caller to a student and dies otherwise:

- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CallerResolution.kt:50`
  — `resolveStudent(user) = studentService.getStudentForUser(user.id)`.
- `rest-server/.../ConvoRoutes.kt:133, 170, 178, 189, 202, 213, 224, 268, 297` —
  every handler is `resolveUser()` then
  `resolveStudent(user) ?: return
  respondStudentProfileRequired()` /
  `respondNotFound()`.

**Correction #1 for the brief.** A parent user is, today, a user with **no
`students` row**. Every coaching, college-list, money-profile, subscription and
usage route therefore answers `student_profile_required` or 404 for them
(`ConvoRoutes.kt:133`, `CollegeListRoutes.kt:139,185,197,214,266`,
`MoneyProfileRoutes.kt:56,71`, `SubscriptionRoutes.kt:49`,
`CoachingUsageRoutes.kt:42`). "Parents get real accounts" is not a small
addition to the chat layer; it is a second caller KIND that the whole REST edge
currently cannot represent. There is no `user_type`/`role` column anywhere —
only `users.is_admin` (`db/schema/0012.add-users-is-admin.sql`).

**Correction #2.** There is **no date-of-birth column** anywhere in the schema
(`grep -n "date_of_birth|dob|birth" db/schema/*.sql` returns nothing; `users`
columns are `email, name, display_name, password_hash, sso_provider_id` —
`db/schema/0001.create-users.sql:13-19`, `0017` drops `sso_provider_id`). The
13+ DOB gate is a NEW column + migration + signup-payload change + iOS/web form
change, not a validation tweak.

---

## 2. How a turn is processed — synchronously, in-request

Not queued. `startConvo` / `postTurn` run the whole LLM loop inside the HTTP
request and stream back:

- `CoachingService.kt:243-317` `startConvo`, `:320-...` `postTurn`.
- Pre-flight in one connection: budget verdict (`:272-281`), system prompt
  (`:282`), `ConvosDao.create` (`:283`), visible history (`:284`), then the four
  context loads (`:288-291`) into `Prepared` (`:292-300`).
- `convo_requests.kind` is `user` | `tool_result` only
  (`db/schema/0027.add-convo-requests-kind.sql:21-24`). It is the _loop_
  discriminator, **not** a conversation-type discriminator.

**Correction #3.** The brief's question "queue? convo_requests kind?" — neither
is a lever. `kind` cannot carry "parent turn": its allowlist is a CHECK
(`0027:24`) whose stated purpose is excluding synthetic tool-result rows from
every projection. Only extraction is queued, AFTER the turn:
`ConvoRoutes.kt:148, 244` → `enqueueExtraction` → `JobType.EXTRACT_CONVERSATION`
(`queue/src/main/kotlin/ed/unicoach/queue/JobType.kt:11`) with payload
`{convoId, throughRequestId}` (`ConvoRoutes.kt:440-461`). That job derives
`student_id` from the convo, so a parent convo enqueued the same way would write
parent utterances into the STUDENT's memory (see §5).

---

## 3. System prompt selection is GLOBAL, from config

- `CoachingService.kt:811-820` `resolveSystemPrompt` calls
  `SystemPromptsDao.findByNameAndVersion(session, config.systemPromptName,
  config.systemPromptVersion)`
  — no convo, no student, no type argument.
- `service/src/main/resources/service.conf:6` `systemPromptName = "coach"`,
  `:314` `systemPromptVersion = "v35"`; fields at
  `service/src/main/kotlin/ed/unicoach/coaching/CoachingConfig.kt:27-28,39-40`.
- `system_prompts` is an insert-only catalog keyed `(name, version)`
  (`db/schema/0007.create-system-prompts.sql:46-60`); latest coach body is
  `db/schema/seed-coach-system-prompt-v35.sql`.
- Multi-NAME precedent already exists: `extraction` (`service.conf:472`),
  `synthesis` (`:495`), fit-lens and forced-tool-use prompts (`db/schema/0032`,
  `0037`).

So a `parent_coach` prompt family is cheap at the catalog level — a seed
migration plus a config key — but **selection** is the problem:
`resolveSystemPrompt` takes no per-conversation input, so a slice must thread a
prompt selector from the convo row (or party) into that function. That is the
smallest honest change, ~5 lines plus its callers at `:282` and `:349`.

The composed system text is more coupled than the prompt body. `composeSystem`
(`CoachingService.kt:902-963`) appends: the date block built from the
**student's** graduation date (`:878-893`, `:976-993`), the student's open
commitments (`:826-833`), the student's fit suggestions (`:842-851`), and the
student's money profile (`:860-866`). All four reads are `StudentId`-keyed.

---

## 4. The tool surface — 12 registered tools, 11 of them student-scoped

Registry built once at the composition root and handed to one CoachingService:

- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt:355-411` builds
  `toolRegistry`; `:414`
  `CoachingService(database, llmCallLog, coachingConfig,
  budgetService, toolRegistry)`.
- **`chat/src/main/kotlin/ed/unicoach/chat/ToolRegistry.kt:5-11`: "no per-turn
  subsetting — every turn advertises the full registry."**

| tool name                    | class                                                 | identity                                          | write?    |
| ---------------------------- | ----------------------------------------------------- | ------------------------------------------------- | --------- |
| `search_colleges`            | `CollegeChatTool.kt:27-28`                            | `ResidencyScopedChatTool` → student money profile | read      |
| `find_college`               | `FindCollegeChatTool` (`Application.kt:365-367`)      | none (`DelegatingChatTool.kt:23-29`)              | read      |
| `similar_colleges`           | `SimilarCollegesChatTool.kt:25-26`                    | `ResidencyScopedChatTool` → student               | read      |
| `update_money_profile`       | `MoneyProfileChatTool.kt:41-42, 292`                  | student                                           | **WRITE** |
| `college_cost_profile`       | `costs/CollegeCostChatTool.kt:44-45, 1330`            | student                                           | read      |
| `update_college_list`        | `collegelist/CollegeListChatTool.kt:39-40, 541`       | student                                           | **WRITE** |
| `college_admissions_profile` | `admissions/CollegeAdmissionsChatTool.kt:46-47, 211`  | student                                           | read      |
| `federal_aid_policy`         | `aid/FederalAidPolicyChatTool.kt:39-40, 171`          | student (dependency status)                       | read      |
| `share_cost_report`          | `report/ShareCostReportChatTool.kt:25-26, 110`        | student                                           | **WRITE** |
| `revoke_cost_report_share`   | `report/RevokeCostReportShareChatTool.kt:22-23, 63`   | student                                           | **WRITE** |
| `stop_cost_report_offers`    | `report/StopCostReportOffersChatTool.kt:29-30, 70`    | student                                           | **WRITE** |
| `admission_test_timeline`    | `testing/AdmissionTestTimelineChatTool.kt:41-44, 241` | student                                           | read      |

Scoping mechanism: `StudentScopedChatTool`
(`service/.../StudentScopedChatTool.kt:33-39`) declares
`execute(studentId, input)` and **finalises** the one-argument `execute` to a
hard error — "tool [$name] requires a student-scoped dispatch" (`:39`).
Dispatch: `CoachingService.kt:759`
`is StudentScopedChatTool ->
tool.execute(studentId, toolUse.input)`. The model
never sees or supplies an id (`:732-736`).

**Correction #4.** The brief's phrasing "would need a 'which student is this
parent working on' parameter" is the wrong shape and the code already says why.
The invariant these tools are built on is that **the id comes from the
dispatcher, never from the model** (`StudentScopedChatTool.kt:22-26`,
`RevokeCostReportShareChatTool.kt:25`). Adding a `student_id` input field would
invert exactly that invariant and hand the model a cross-tenant lever. The
honest shape is the opposite: the **convo row** names the subject student, the
dispatcher reads it, and the tool signature is unchanged.

**Correction #5.** "Parent gets a read-only tool surface" is not achievable by
configuration. `ToolRegistry` advertises everything, always
(`ToolRegistry.kt:5-11`), and `CoachingService` holds exactly one registry
(`CoachingService.kt:88`). A read-only parent surface needs either a second
`ToolRegistry` + second `CoachingService` instance at the root, or a per-turn
registry selection — a real change to `ToolRegistry`/`CoachingService`, not a
flag. Five of the twelve tools WRITE student data, which the parent ceiling
forbids.

---

## 5. Coaching memory (0019) — student-keyed, and extraction is the leak

- `observations.student_id` `db/schema/0019.create-coaching-memory.sql:28`, plus
  `convo_id` `:29` and `source_request_id` `:30`.
- `claims.student_id` `:63`; `claims.subject` allows `family` already
  (`:68, :82`) but the ROW is still the student's.
- `extraction_runs.student_id` `:152`, denormalised from the convo (`:149-151`).
- Readers: `ExtractionService.kt:187, 447, 461`, `SynthesisService.kt:166, 457`,
  `FitLensService.kt:232` — all `listActiveByStudent(session, studentId)`.

**Could a parent get their own memory?** Only by adding a subject dimension.
Today `student_id` on `observations`/`claims` is both "who said it" and "who it
is about", conflated. A parent turn extracted through the existing path becomes
`observations` rows attributed to the student — verbatim parent quotes in the
student's memory, surfaced back to the student through the commitments opener
(`CoachingService.kt:826-833`, `db/schema/0025.create-synthesis.sql:29-41`) and
the fit-lens (`FitLensService.kt:232`).

**Correction #6 (the sharpest one).** The coach turn does **not** read
`claims`/`observations` directly — grep for `ClaimsDao.` outside tests returns
only `ExtractionService`, `SynthesisService`, `FitLensService` and admin
resources. Memory reaches the coach only as _derived_ `commitments` and
`fit_suggestions`. So the containment job is narrower than the brief implies:
block parent convos from the extraction enqueue and from the commitments/
fit-suggestion opener, and no student memory can appear in a parent turn.

---

## 6. Metering and subscriptions — student-keyed, parent spend has no home

- `subscriptions.student_id` `db/schema/0042.create-subscriptions.sql:33`;
  `BudgetService.kt:48-52, 60-77` `verdict`/`entitlement` take `StudentId`.
- `student_llm_cost` (`db/schema/0041.add-llm-response-cost.sql:58-82`) is a
  four-way UNION that attributes every call to a student, chat via
  `convo_requests JOIN convos` on `c.student_id` (`:60-62`).
- `CoachingUsageRoutes.kt:41-43` — `resolveStudent(user)` then
  `budgetService.entitlement(student.id)`.
- Gate order in the turn: budget BEFORE any row is written
  (`CoachingService.kt:270-281`, `:339-348`).

**Correction #7.** "Per-user billing unchanged" is not what the code does:
billing is per-STUDENT (`0042:33`). If a parent convo is billed to the parent's
own subscription, `student_llm_cost` cannot express it — its spine is
`student_id NOT NULL`. If it is billed to the student, a parent can exhaust the
student's coaching budget, and `GET /api/v1/students/me/coaching-usage` will
show the student a bar they did not spend.

---

## 7. Minimal honest shape for a parent convo

**Tables.** Do NOT create `parent_convos`; every projection, the extraction
watermark, `student_llm_cost` and the admin console would need a second copy.
Instead, on `convos` (migration `0095`+):

1. `party TEXT NOT NULL DEFAULT 'student' CHECK (party IN ('student','parent'))`
   — the project's TEXT+CHECK enum convention (CLAUDE.md schema conventions,
   precedent `0027:21-24`), plus one `enum class ConvoParty` in
   `db/src/main/kotlin/ed/unicoach/db/models`.
2. `author_user_id UUID NULL REFERENCES users(id)` — WHO is talking.
   `student_id` stays NOT NULL and keeps meaning WHO IT IS ABOUT, so
   `student_llm_cost` (`0041:60-62`), `loadOwned` and every index keep working
   unchanged. A partial CHECK ties the two: `party='parent'` requires
   `author_user_id IS NOT NULL`.
3. Ownership check moves from `convo.studentId == studentId`
   (`CoachingService.kt:807`) to a link lookup on the new relationship table;
   `loadOwned` is the single place that changes.

**Tool surface for a parent.** A second `ToolRegistry` built at
`Application.kt:355` from the read-only subset — `search_colleges`,
`find_college`, `similar_colleges`, `college_cost_profile`,
`college_admissions_profile`, `federal_aid_policy`, `admission_test_timeline` —
plus a parent-owned money-input tool that writes a PARENT row, never
`money_profiles` (which is `UNIQUE (student_id) WHERE deleted_at IS NULL`,
`db/schema/0046.create-money-profiles.sql:58-59`, so it has no second author
slot). `update_college_list`, `update_money_profile` and the three share/offer
tools are excluded. `CoachingService` must take a registry per party, or the
root must construct two service instances.

**MUST NOT leak.**

- Student convo transcripts: guaranteed by `loadOwned` + `listByStudent` only if
  the party check is added there; a link-only check would expose the student's
  convos to the parent.
- `claims`/`observations`: never injected into a turn today (§6) — keep it that
  way by NOT adding a memory block for parents.
- The commitments and fit-suggestion openers (`CoachingService.kt:288-289`) must
  be skipped for `party='parent'`; they are student memory rendered as prose.
- `enqueueExtraction` (`ConvoRoutes.kt:148, 244`) must be skipped for parent
  convos, or `ExtractionService` will write parent quotes as the student's
  observations (`0019:28-33`).
- The date block (`CoachingService.kt:976-993`) reveals the student's graduation
  date — probably fine for a linked parent, but it is a disclosure decision, not
  a default.
