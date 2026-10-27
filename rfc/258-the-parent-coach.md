# RFC 258 — The parent coach

**Slice:** `family/06/the-parent-coach` (brief 0014, _parents in the room_).
**Status:** proposed. **Base:** `main@b2653970`. **Lane:** A (rfc).

## Executive Summary

A linked parent gets a coach of their own. From the parent home on iOS they open
a conversation about the student they support. The coach reads that student's
college list and cost picture through the subject seam (RFC 250). It answers
what the list will cost the family and what the parent must do next. It
redirects any request to direct the student toward money, logistics and support
(brief 0014 D3).

Three facts in the code shape the design. They are recorded in
`.scratch/ship/rfc-258/research/`.

1. `convos.student_id` is `NOT NULL` with no author column (`0006:57`). The
   student cost view `student_llm_cost` bills every `convo_requests` row to
   `convos.student_id` (`0041:58-82`). A parent conversation stored as-is would
   bill the student and show up in the student's list.
2. Tools receive their student from the dispatcher (`CoachingService.kt:756`),
   and the one `ToolRegistry` advertises every tool on every turn. Nine of the
   twenty tools write the student's data.
3. The prompt catalog already looks up a body by name and version
   (`SystemPromptsDao.findByNameAndVersion`), so a second prompt is a second
   name. Each request pins its prompt on `convo_requests.system_prompt_id`.

The parent conversation reuses `convos`, `convo_requests` and the turn loop.
`convos` gains a party and an author. The student-side reads, the student cost
view and extraction are all narrowed to the student party. The parent gets a
separate read-only registry, a separate prompt, and a budget gate on their own
account. Each call writes the parent's cost to `supporter_llm_costs`.

## Detailed Design

### 1. Data model

**`convos` gains a party discriminator and an author.** `student_id` keeps its
meaning of "about whom". The cost spine and every index on it survive (X9).
Party lives on `convos`, not on `convo_requests.kind` (X11).

Migration `convos-party.sql`, appended to `db/schema/ORDER`:

```sql
ALTER TABLE convos ADD COLUMN party TEXT NOT NULL DEFAULT 'student';
ALTER TABLE convos ALTER COLUMN party DROP DEFAULT;
ALTER TABLE convos ADD CONSTRAINT convos_party_check
  CHECK (party IN ('student', 'supporter'));

ALTER TABLE convos ADD COLUMN supporter_id UUID NULL REFERENCES supporters(id);
ALTER TABLE convos ADD CONSTRAINT convos_party_author_check
  CHECK ((party = 'supporter') = (supporter_id IS NOT NULL));

CREATE INDEX convos_supporter_id_idx ON convos (supporter_id, student_id)
  WHERE supporter_id IS NOT NULL;

-- party, supporter_id and student_id are frozen after insert.
CREATE FUNCTION prevent_convos_party_updates() RETURNS TRIGGER ...;
CREATE TRIGGER trigger_00c_prevent_convos_party_updates
  BEFORE UPDATE ON convos FOR EACH ROW
  EXECUTE PROCEDURE prevent_convos_party_updates();

-- The student's cost no longer counts a supporter's conversation.
CREATE OR REPLACE VIEW student_llm_cost AS
  ... -- 0041's body, with the convo_requests arm narrowed to c.party = 'student'
```

- The default exists only to backfill existing rows. It is then dropped, so
  every insert names its party.
- `supporter_id` carries no `ON DELETE` action, like every reference to
  `supporters` (`supporters` refuses a physical delete).
- `student_llm_cost` keeps its column list, so `CREATE OR REPLACE` leaves
  `account_llm_cost` (`account-llm-cost.sql:32-55`) standing.
- No new table.

**The parent's cost row is written per call.** `supporter_llm_costs`
(`supporter-llm-cost.sql:20-41`) gets one row per parent `convo_requests` row,
`(llm_request_id, supporter_id)`. It is written in the same transaction that
appends the request row. This is the first production writer of that table.
`account_llm_cost` then attributes the parent's spend to the parent's account
and never to the student's.

**Kotlin.** `db/.../models/ConvoParty.kt` holds
`enum class ConvoPartyKind(val value: String)` with `fromValue`, per the schema
convention. `Convo` gains `party: ConvoParty`, a sealed type:

```kotlin
sealed interface ConvoParty {
  data object Student : ConvoParty
  data class Supporter(val supporterId: SupporterId) : ConvoParty
}
```

The DAO maps the two columns into this type and nowhere else, so the redundant
pair never reaches a caller. `NewConvo` gains `party: ConvoParty`.

### 2. Every student-side read is narrowed to the student party

These existing paths add `party = 'student'` or check `ConvoParty.Student`. Each
one is a leak if missed, and each has a test in §8.

| Path                                                   | Change                                                                                      |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| `ConvosDao.listByStudent`, `listByStudentWithActivity` | `AND party = 'student'`                                                                     |
| `CoachingService.loadOwned`                            | owned only if `party is Student` and the student matches                                    |
| `ExtractionService` run for a convo                    | a supporter convo ends with a named `SupporterConversation` skip outcome and writes nothing |
| `student_llm_cost` view                                | the `convo_requests` arm narrowed (§1)                                                      |
| admin `StudentsResource` convo list                    | via `listByStudentWithActivity`, so student party only                                      |

The global admin `ConvosResource` lists every convo and shows the party and the
supporter id as columns. `ConvoRequestsResource` is unchanged: its rows are
turns, and each links to its convo, which shows the party.
`LlmCallsDao.listUnlinkedCalls` adds `supporter_llm_costs` to the owner tables
it excludes.

### 3. The turn loop is shared, not copied

`CoachingService`'s provider loop moves into `coaching/CoachTurnLoop.kt`
(internal). That covers `openUserTurn`, `openContinuation`, `appendRequestRow`,
`buildReplyFlow`, `collectCall` and the tool-dispatch failure handling. Message
validation, convo naming, replay history and `renderDateBlock` move to
`coaching/CoachConversation.kt`, so both coaches share one copy. The loop is
parameterised by one interface:

```kotlin
internal interface TurnParty {
  val tools: ToolRegistry
  suspend fun dispatch(tool: ChatTool, input: JsonObject): JsonObject
  fun onRequestAppended(session: SqlSession, request: ConvoRequest)
  suspend fun onSucceeded()
}
```

- The student party dispatches a `StudentScopedChatTool` with the convo's
  student. `onRequestAppended` does nothing. `onSucceeded` marks disclosed
  commitments and surfaced fit suggestions. That is today's behaviour, byte for
  byte, and every existing `CoachingService` test stays green unedited.
- The supporter party dispatches a `SubjectScopedChatTool` with the
  `Subject(viewer, studentId)`. `onRequestAppended` inserts the
  `supporter_llm_costs` row on the same session. `onSucceeded` does nothing.

### 4. The parent coaching service

`service/.../family/SupporterCoachingService.kt` is the parent's sibling to
`CoachingService`. Its constructor takes `database`, `llmCallLog`,
`SupporterCoachingConfig`, `budgetService`, `subjects: SubjectReadService`,
`supporters: SupporterService`, `tools: ToolRegistry` and `clock`. The registry
is the parent registry (§5). Each method returns `Result<sealed outcome>`.

```kotlin
suspend fun listConvos(userId: UserId): Result<SupporterConvoList>
suspend fun listTurns(userId: UserId, convoId: ConvoId): Result<SupporterTurns>
suspend fun startConvo(userId: UserId, studentId: StudentId, message: String): Result<SupporterTurnStart>
suspend fun postTurn(userId: UserId, convoId: ConvoId, message: String): Result<SupporterTurnStart>
```

`SupporterTurnStart` has these arms: `Started` (carrying the reply flow),
`NoSupporter`, `StudentNotFound`, `ConvoNotFound`, `BudgetExhausted` and
`Invalid`. `SupporterConvoList` answers `Found` or `NoSupporter`;
`SupporterTurns` answers `Found`, `NoSupporter` or `ConvoNotFound`.

**Pre-flight, one transaction, every turn.** These steps run in order:

1. Resolve the caller's live `supporters` row, else `NoSupporter`.
2. For `postTurn`, load the convo. It must be `Supporter(that supporter)`, else
   `ConvoNotFound`.
3. Check the link with `SupporterStudentLinksDao.findLiveForUser`, else
   `StudentNotFound`. This runs on every turn, so a severed link refuses the
   very next message (D14).
4. Run the budget gate on the parent's account, else `BudgetExhausted`.
5. Read the shared college list (`CollegeListService.listShared`) and the roster
   entry: the student's display name and the relationship word.
6. Resolve the prompt `parentCoaching.systemPromptName` / `systemPromptVersion`.
7. Create the convo (start only). Then compose the system text.

The pre-flight reads on the caller's session: `SupporterService.findInSession`
and `rosterEntryInSession`, and `SubjectReadService.collegeListInSession`, so
steps 1–5 are one session. "What was authorised is what is read" stays RFC 250's
rule.

**System text.** It is the parent prompt body, then the date block (the coach's
existing `renderDateBlock`). Then a subject block: the student's display name,
the relationship word, and the shared list (school, status, living plan), or a
line saying the list is empty. Nothing else from the student is composed in. It
never includes commitments, fit suggestions, the money profile, test scores,
claims or observations.

**The budget gate.** `BudgetService` gains
`verdictForAccount(session, userId): Result<BudgetVerdict>`. It reuses
`entitlementForAccount`, the account-keyed core that `entitlement(userId)`
already calls. This is the role-keyed gate the `BudgetService` KDoc names as
this slice's (`BudgetService.kt:115-121`). The refusal is the student's own:
`402 coaching_budget_exhausted`.

**No extraction, no memory.** A parent turn enqueues nothing. Cut point 4 holds
by construction, and the `ExtractionService` skip (§2) is the backstop. The
parent has no coaching-memory rows in this slice. Their context is the current
conversation's own history.

### 5. The parent tool registry

The parent registry is a separate `ToolRegistry` instance built in
`Application.kt` (D15). It is read-only: no tool in it writes anything.

**`SubjectScopedChatTool`** (`service/.../family/SubjectScopedChatTool.kt`) is a
new base type. It lives in `:service` because `Subject` holds `:db` ids and
`:chat` does not depend on `:db`.

```kotlin
data class Subject(val viewer: UserId, val studentId: StudentId)
abstract class SubjectScopedChatTool : ChatTool {
  abstract suspend fun execute(subject: Subject, input: JsonObject): JsonObject
  final override suspend fun execute(input: JsonObject): JsonObject // error object
}
```

The model never supplies a subject. Every subject-scoped tool reads through
`SubjectReadService`, which re-checks the link on each call.

`SupporterToolRegistry.of(tools)` refuses at construction to hold a
`StudentScopedChatTool`. It throws `IllegalArgumentException` naming the tool,
so a write tool cannot be added to the parent registry by accident.

| Tool                        | Kind    | What it reads                                                                                                                                                                                    |
| --------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `family_cost_picture` (new) | subject | `SubjectReadService.costPicture`, rendered by the same payload builder `college_cost_profile` uses, so the coach reads the same keys. `SubjectsWithheld` adds `intended_subjects_shared: false`. |
| `fafsa_deadlines`           | subject | the existing tool's payload, with residency read through a new `SubjectReadService.residency(viewer, studentId)`. The residency is already visible to a parent inside the cost picture.          |
| `fafsa_process`             | plain   | unchanged rulebook. Its base changes from `StudentScopedChatTool` to `ChatTool`, because it never read the student id.                                                                           |
| `find_college`              | plain   | unchanged.                                                                                                                                                                                       |

The list itself is in the system text (§4), so no list tool is needed.

Excluded, with reasons:

- **The nine write tools** are excluded because a parent never edits the
  student's data (D3).
- **`college_cost_profile`, `college_admissions_profile`,
  `admission_test_timeline`, `college_automatic_awards`, `psat_national_merit`
  and `federal_aid_policy`** read the student's raw inputs (scores, dependency,
  the money profile write path), which sit outside the parent's ceiling.
- **`search_colleges` and `similar_colleges`** price results from the student's
  raw money profile.

`FafsaDeadlineChatTool`'s payload logic becomes a shared function over a
residency read. The student tool and the subject tool are thin callers of it.

### 6. The parent prompt

The prompt is a new name, `parent-coach`, version `v1`. Its body is the file
`prompts/parent-coach-system-prompt.md`, verbatim, written with this RFC and
presented at approval. It covers who the coach is talking to, what it can and
cannot see (the `SupporterCeiling` lines), value before ask, the money words,
the cost rules for a reader who cannot change the student's inputs, the
inversion with its two sources, and FAFSA.

- **The seed is a hand-written migration**,
  `seed-parent-coach-system-prompt-v1.sql`. It inserts the file's text as a
  dollar-quoted literal. This follows the extraction, synthesis and fit-lens
  precedent. `bin/prompt-seed` matches `seed-coach-system-prompt` only and is
  unchanged.
- **A Kotlin test binds the served row to the file**, so the two cannot drift.
- **Pin:** `parentCoaching { systemPromptName = "parent-coach" }` and
  `parentCoaching.systemPromptVersion = "v1"` in `service.conf`, with env
  overrides `PARENT_COACHING_SYSTEM_PROMPT_NAME` / `_VERSION`. The version is a
  dotted path, not a line in the block: `bin/prompt-seed` requires exactly one
  `systemPromptVersion = "vN"` line in the file (the coach's).
- **Model and token settings:** `parentCoaching.model`, `maxTokens` and
  `maxToolRounds` fall back to `coaching.*`. The boot price check
  (`requireExplicitlyPriced`) covers the parent model too.
- **Shared sentences are bound by a test.** The money-vocabulary sentences are
  copied verbatim from the coach prompt. Two audiences need two bodies. A
  catalog test asserts each of the four sentences appears byte-identical in both
  served bodies, so editing one copy fails the build.

### 7. REST

`rest-server/.../routing/SupporterConvoRoutes.kt` delegates
`CallerAuthentication`. Frames, headers and the pre-stream refusal mapping are
the same as `ConvoRoutes`. The SSE writer is shared, not copied.

| Method and path                                                             | Body                   | Answers                                                             |
| --------------------------------------------------------------------------- | ---------------------- | ------------------------------------------------------------------- |
| `GET /api/v1/supporters/me/conversations`                                   | —                      | 200 `{conversations:[{id, studentId, name, createdAt, updatedAt}]}` |
| `POST /api/v1/supporters/me/conversations/stream`                           | `{studentId, message}` | SSE, the student stream's frames                                    |
| `GET /api/v1/supporters/me/conversations/{conversationId}/messages`         | —                      | 200, the student turns shape                                        |
| `POST /api/v1/supporters/me/conversations/{conversationId}/messages/stream` | `{message}`            | SSE                                                                 |

Refusals, all before any SSE byte:

| Status | Code                        | When                                                  |
| ------ | --------------------------- | ----------------------------------------------------- |
| 401    | —                           | no session                                            |
| 403    | `email_not_verified`        | the global gate                                       |
| 404    | `supporter_not_found`       | the caller has no supporters row                      |
| 400    | —                           | malformed id or body                                  |
| 404    | `student_not_found`         | link not live, never linked, closed; one body for all |
| 404    | `not_found`                 | convo not this supporter's                            |
| 402    | `coaching_budget_exhausted` | the parent's allowance is spent                       |

No new error code is added.

- **The subject is a body field on the one route that creates.** On a turn, the
  subject comes from the convo row. No route binds `{studentId}`, so
  `SubjectSeamRouteSweepTest` tests 1–3 do not apply.
- **Both POSTs are added to `OWN_ACCOUNT_WRITES`.** The reason is that they
  write the caller's own conversations. The sweep's `{}` body would otherwise
  read as an unclassified 400.
- **After an unlink**, the parent's conversations about that student stay listed
  and readable. They are the parent's own words. A new turn answers
  `student_not_found`.

`api-specs/openapi.yaml` documents the four paths, following the existing
conversation paths. `bin/test-fuzz` already excludes `conversations`.

### 8. iOS

- **Client.** `SupporterConversationClientProtocol`,
  `SupporterConversationClient` and `PreviewSupporterConversationClient` cover
  the four routes. The SSE plumbing moves out of `ConversationClient` into a
  path-parameterised `ConversationStreams` (`run`, `pump`, `decodeFrame`), so
  both clients share one decoder.
- **View model.** `ConversationViewModel` takes a new `ConversationThreadClient`
  (start, post, fetch), split out of `ConversationClientProtocol`, which refines
  it. `SupporterThreadClient` binds the parent client to its student, so the
  parent client carries no list, delete or archive door. `ServerErrorCode` gains
  `supporterNotFound` and `studentNotFound` arms in its exhaustive switch.
  `studentNotFound` renders "You are no longer connected to <name>" and disables
  the composer.
- **Shared views.** The bubble, streaming indicator, thread and composer move
  out of `ConversationView` into `ChatThreadViews.swift` as `ChatMessageBubble`,
  `ChatStreamingIndicator`, `ChatThread` and `ChatComposer`, reading nothing
  from the student rail. `ConversationView` uses them with no visual change, and
  its existing snapshots must show zero drift.
- **`SupporterCoachView`** is the parent's screen, titled "Coach". It lists
  conversations about the selected student, filtered on the client, plus "New
  conversation". It opens a thread. The empty thread offers one tappable
  starter, "What would <name>'s list cost our family?", which sends that
  message. Nothing else is asked of the parent.
- **The meter** reuses `SupporterCoachingViewModel`. A 402 shows the parent's
  Coaching block copy, not the student paywall.
- **The door.** `SupporterHomeView.Destination` gains
  `case coach(LinkedStudent)`. The `actions` stack gains "Talk to your coach
  about <name>" above college search, shown only when a student is selected.

**Reachability.** A parent signs in, lands on the parent home, picks a student
if they support more than one, taps "Talk to your coach about <name>", taps the
starter, and gets the cost answer.

**Value before ask (standing D12).** The first session asks the parent nothing.
The starter delivers the cost answer, and the prompt closes it with the parent's
next step.

**Guided, not gated (D11).** An empty list gets a labelled answer, not a refusal
(prompt). A withheld subject is labelled as withheld.

### Dependencies

- RFC 250 is the subject seam. RFC 241 is the roster and the parent home. RFC
  244 is the account-keyed budget and `supporter_llm_costs`. RFC 249 is
  `SupporterCeiling`.
- No new library, configuration file or job type.

## Tests

All server tests use the real database and the existing fake `ChatProvider`
transport seam (RFC 107). Each cut-point test is written first and seen failing
against the pre-change code.

**Cut points** (`SupporterCoachingCutPointTest`, `:rest-server`, end to end over
the served routes):

1. `studentConversationNeverReachesParentCoach`: the student holds a
   conversation containing a unique sentinel. The parent starts a conversation,
   and the fake provider calls `family_cost_picture` then `fafsa_deadlines`.
   Every `llm_requests` body recorded for the parent's calls (system, messages,
   tool results) lacks the sentinel.
2. `coachingMemoryNeverReachesParentCoach`: the student holds `claims` and
   `observations` rows containing a sentinel, and a list entry whose `reasons`
   carries it. The same assertion as test 1.
3. `parentTurnNeverWritesStudentState`: a full parent conversation with every
   parent tool called. Then the student's rows are compared byte for byte before
   and after. That covers `students`, `college_list_entries`, `money_profiles`,
   test scores, `commitments`, `claims`, `observations`, `sharing_permissions`,
   `cost_report_shares` and `supporter_invites`. The student's served
   `GET /students/me/*` reads are unchanged too.
4. `parentTurnNeverExtracts`: after a completed parent turn, no
   `EXTRACT_CONVERSATION` job exists. `ExtractionService` run directly on the
   parent convo returns `SupporterConversation` and writes no row.

**Student-side narrowing** (`ConvoRoutingTest`, new cases):

- A user holding both a student and a supporter row sees only their student
  conversations at `GET /conversations`.
- `GET`, `PATCH` and `DELETE /conversations/{parentConvoId}` and its messages
  routes answer 404 for the student about whom the convo is.

**Cost** (`SupporterCoachingCostTest`, `:rest-server`):

- A parent turn with one tool round writes two `supporter_llm_costs` rows.
- `student_llm_cost` holds none of them.
- `account_llm_cost` attributes them to the parent's user.
- An exhausted parent allowance answers 402 before any convo row exists.
- The student's own allowance is untouched by the parent's spend.

**DAO** (`ConvosDaoTest`):

- `create` with each party round-trips.
- The CHECK refuses a supporter party with no author, and a student party with
  one.
- The trigger refuses an update to `party`, `supporter_id` or `student_id`.
- `listByStudent*` omit supporter rows.
- `listBySupporterWithActivity` returns only that supporter's rows.

**Service** (`SupporterCoachingServiceTest`):

- Each outcome arm: no supporter row, never linked, severed between two turns
  (the second turn refuses), a closed student, another supporter's convo, the
  budget, and an empty message.
- The system text contains the display name, the relationship word and the
  shared list. It contains no money profile, score, commitment or claim.

**Registry:**

- `SupporterToolRegistryTest`: building with a `StudentScopedChatTool` throws.
  The production parent registry holds exactly the four tools in §5.
- `SubjectScopedToolTest`: each subject tool answers the not-found error object
  for a severed link, and `family_cost_picture` labels `SubjectsWithheld`.

**Prompt** (`ParentCoachPromptTest`):

- The served `parent-coach` `v1` body equals the file byte for byte.
- The pin resolves.
- The four money-vocabulary sentences are byte-identical in the served `coach`
  pin and `parent-coach` pin.
- **The redirect:** the served body contains the inversion paragraph, located by
  its opening words, and its two sources. It contains no sentence instructing
  the parent to persuade or direct the student.

**Routes:**

- `SupporterConvoRoutingTest`: every row of the §7 refusal table, the SSE frame
  order, the list shape, and unlink-then-read (200) / unlink-then-post (404).
- `SubjectSeamRouteSweepTest`: stays green with the two new `OWN_ACCOUNT_WRITES`
  entries.
- `OpenApiSupporterConversationsTest`: the documented shape matches the served
  one.

**iOS** (`bin/test-ios`, system Xcode):

- `SupporterConversationClientTest` (paths, frames, error decode).
- `ConversationViewModelTests`: the new arms.
- `SupporterHomeViewModel`/view test: the entry shows only with a subject.
- Snapshot scenes: parent home with the entry; the empty thread with the
  starter; a thread; the 402 state; and "no longer connected". Every existing
  scene shows zero drift except the parent-home scenes with a selected student
  (`supporter-home-one-student`, `supporter-home-switcher-open`), which gain the
  entry.

## Implementation Plan

1. **Schema.** `convos-party.sql` plus its `ORDER` line, and the parent prompt
   seed `seed-parent-coach-system-prompt-v1.sql` plus its `ORDER` line. Verify:
   `nix develop -c bin/test db`.
2. **`:db`.** `ConvoParty`, `Convo`/`NewConvo`, the `ConvosDao` mapping and
   narrowing, `listBySupporterWithActivity`, a `SupporterLlmCostsDao.insert`,
   and the `LlmCallsDao` exclusion. Add the DAO tests. Verify:
   `nix develop -c bin/test db`.
3. **Student-side narrowing in `:service`.** `loadOwned`, the
   `ExtractionService` skip outcome, and the cut-point 4 backstop test. Verify:
   `nix develop -c bin/test service`.
4. **Extract `CoachTurnLoop`.** `CoachingService` delegates to it, and existing
   tests stay green unedited. Verify: `nix develop -c bin/test service`.
5. **Tools.** `SubjectScopedChatTool`, `SupporterToolRegistry`,
   `family_cost_picture`, the subject `fafsa_deadlines`,
   `SubjectReadService.residency` and `collegeListInSession`, and
   `fafsa_process` to plain `ChatTool`. Add the tests. Verify:
   `nix develop -c bin/test`.
6. **Budget and service.** `BudgetService.verdictForAccount`,
   `SupporterCoachingConfig` and its `service.conf` block, and
   `SupporterCoachingService`. Add the service and prompt tests. Verify:
   `nix develop -c bin/test`.
7. **REST.** The shared SSE writer, `SupporterConvoRoutes`, the wiring in
   `Routing.kt`/`Application.kt`, the sweep entries, OpenAPI, and the route,
   cost and cut-point tests 1–3. Verify: `nix develop -c bin/test`.
8. **iOS.** The client, the SSE extraction, the shared chat views, the view
   model arms, `SupporterCoachView`, the home entry, `project.pbxproj` entries,
   and the tests and snapshots. Verify: `bin/test-ios` and `bin/snapshot-ios`,
   outside the dev shell.
9. **Full suite.** `nix develop -c bin/test`, unscoped.

## Files Modified

- `db/schema/convos-party.sql`,
  `db/schema/seed-parent-coach-system-prompt-v1.sql`, `db/schema/ORDER`
- `db/src/main/kotlin/ed/unicoach/db/models/` — `ConvoParty.kt`, `Convo`,
  `NewConvo`
- `db/src/main/kotlin/ed/unicoach/db/dao/` — `ConvosDao.kt`,
  `SupporterLlmCostsDao.kt` (new), `LlmCallsDao.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/` — `CoachingService.kt`,
  `CoachTurnLoop.kt` and `CoachConversation.kt` (both new),
  `budget/BudgetService.kt`, `aid/FafsaDeadlineChatTool.kt`,
  `aid/FafsaProcessChatTool.kt`, `costs/CollegeCostChatTool.kt` (payload builder
  shared), `extraction/ExtractionService.kt`
- `service/src/main/kotlin/ed/unicoach/family/` — `SupporterCoachingService.kt`,
  `SupporterCoachingConfig.kt`, `SupporterToolRegistry.kt`,
  `SubjectScopedChatTool.kt`, `FamilyCostPictureChatTool.kt`,
  `SubjectFafsaDeadlineChatTool.kt` (new), `SubjectReadService.kt`,
  `SupporterService.kt`
- `service/src/main/resources/service.conf`
- `prompts/parent-coach-system-prompt.md` (new)
- `rest-server/src/main/kotlin/ed/unicoach/rest/` —
  `routing/SupporterConvoRoutes.kt` (new), `routing/ConvoRoutes.kt` (SSE writer
  shared), `Routing.kt`, `Application.kt`, `models/`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/ConvosResource.kt`
- `api-specs/openapi.yaml`
- `ios-app/UnicoachiOS/` — `SupporterConversationClient.swift`,
  `SupporterCoachView.swift`, `ChatThreadViews.swift` (new),
  `ConversationClient.swift`, `ConversationView.swift`,
  `ConversationViewModel.swift`, `SupporterHomeView.swift`, `Models.swift`,
  `AppViewModel.swift`, `UnicoachiOSApp.swift`;
  `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`
- Tests beside each module, as listed under Tests.

## Rollback

Revert the code. The migration is additive: two columns, a CHECK, an index, a
trigger, and a view narrowed to the rows it always meant. Its rows are harmless
under the old code once the parent routes are gone. To stop a misbehaving prompt
without a deploy, set `PARENT_COACHING_SYSTEM_PROMPT_VERSION` to a later catalog
row. There is no earlier row to fall back to.
