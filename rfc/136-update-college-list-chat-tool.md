# RFC 136: `update_college_list` Chat Tool

## Status

Proposed

## Context

RFC 91 landed the college-list schema (`college_list_entries`, OCC-versioned,
soft-deleted, active-uniqueness per student+college), `CollegeListService`, and
the REST CRUD. RFC 133/134/135 then built the whole "know your real price" chain
(S1–S3 of product brief 0001) **on top of** that list: the cost tool reads the
student's listed schools, and the v3 coach prompt opens with "When the student
has schools on their college list…".

But there is no student-facing door to the list. No chat tool writes it, and no
iOS UI exists for it. A real student cannot put a school on their list, so the
condition guarding the entire S1–S3 cost feature is never true outside tests.
This slice — recorded as **S3.5** in `product/0001-v1-differentiator/spec.md` —
closes that gap conversationally: the coach can add a school the student is
excited about, restatus it as their journey progresses (considering → applying →
admitted/rejected), and remove it when the student changes their mind.

## Goals

- One student-scoped chat tool, `update_college_list`, that adds, restatuses,
  and removes college-list entries — mirroring `MoneyProfileChatTool` /
  `StudentScopedChatTool` (RFC 134) exactly in contract and shape.
- Honour the value-before-ask ethos (spec D12): the coach **offers** to track a
  school when the student shows interest; it never nags, never writes without
  the student's say-so, and the student can always change or remove an entry.
- A v4 coach prompt so the coach knows it now maintains the list (decision
  below).

## Non-Goals

- iOS list UI (a later slice; chat is the first door).
- Observation citations via chat. REST keeps the citation path (RFC 91); the
  coach mid-conversation does not hold observation ids, and threading them
  through the model buys nothing today. The tool never links support rows.
- Clearing `reasons` to null via chat (omit = unchanged; a student who wants the
  note gone can restate it or remove the entry).
- Any schema change. `college_list_entries` is untouched.

## Detailed Design

### The tool

`CollegeListChatTool` (service module, package
`ed.unicoach.coaching.collegelist`), `StudentScopedChatTool` subclass,
`TOOL_NAME = "update_college_list"`. Total by the `ChatTool` contract: every
malformed input, unknown enum value, or named service outcome returns a
structured `{ "error": … }` object; never a throw. A thin adapter: `execute`
orchestrates parse → resolve → write → render; the semantics stay in
`CollegeListService`, the single owner shared with REST.

Input schema (all model-facing ids are **college ids**, which the model already
holds from `search_colleges` and `college_cost_profile` results; entry ids and
OCC versions are REST/iOS concerns and never cross the model boundary):

- `action` — required string enum: `add` | `update` | `remove`.
- `college_id` — required uuid string.
- `status` — optional string enum from `CollegeListEntryStatus` (`considering`,
  `applying`, `admitted`, `rejected`). For `add`, defaults to `considering`. For
  `update`, omitted means unchanged. For `remove`, present is an error.
- `reasons` — optional string: the student's own words for why this school. For
  `add`/`update` it is written (omitted on `update` = unchanged); for `remove`,
  present is an error.

Unknown fields are rejected via the inherited `unknownFieldsReason`; strict
optional reads via the inherited `OptRead` readers. `action` and `college_id`
use the same readers with an explicit required-absence error.

### Dispatch per action

All three resolve the **active** entry for `(studentId, collegeId)` by scanning
`CollegeListService.listActiveWithNames` — the list is small by construction (a
student curates it by hand), and the same read supplies the college names the
echo and errors render.

- **add**:
  `service.addToList(studentId, collegeId, status ?: CONSIDERING,
  reasons, emptyList())`.
  A pre-write check on the resolved list returns an error naming the school as
  already listed (the model should `update` instead); the service's own
  `AlreadyOnList` outcome — reachable only against a concurrent writer in the
  resolve→write window — maps to the conflict-retry error. Other outcomes:
  `Success` → list echo; `CollegeNotFound` → error; `InvalidReasons` → error
  (reasons are non-empty and at most 2048 chars per RFC 91's CHECKs).
- **update**: resolve the active entry; absent → error ("not on the list — use
  add"). At least one of `status`/`reasons` must be present, else error.
  `service.updateEntry(studentId, entry.id, entry.version, status ?:
  entry.status, reasons ?: entry.reasons, emptyList())`.
- **remove**: resolve the active entry; absent → error.
  `service.removeFromList(studentId, entry.id, entry.version)`.

The read-then-write uses the entry's own just-read version (self-read OCC). A
`VersionConflict` — only possible against a concurrent writer, of which there
are none today (no iOS UI) — returns
`{ "error": "the list changed
underneath this call; read it again and retry" }`,
which is honest and model-actionable. Infrastructure failures log a warn and
return `{ "error": "college list write failed" }`, exactly as
`MoneyProfileChatTool` does.

### The echo

Every successful write echoes the **full post-write active list**, mirroring the
money-profile tool's full-profile echo, so the coach's next message reflects the
whole list:

```json
{
  "college_list": [
    {
      "college_id": "…",
      "name": "Brown University",
      "status": "applying",
      "reasons": "loves the open curriculum"
    }
  ],
  "count": 1
}
```

(`reasons` present only when set; a successful `remove` of the last entry echoes
an empty array.) College names require a name join the service does not expose
today, so `CollegeListService` gains one read:

```kotlin
suspend fun listActiveWithNames(studentId: StudentId):
  Result<List<EntryWithCollegeName>>   // entry + colleges.name, one session
```

implemented inside the service with `CollegesDao.findById` per entry (same
session, small list), so the tool never reaches past the service — the same rule
the service already documents for REST.

### The tool description carries the ethos contract

Verbatim on the wire (em-dashes rendered as ASCII hyphens in the Kotlin string,
`MoneyProfileChatTool`'s precedent), RFC 134's pattern:

> Maintain the student's college list — the schools they are considering,
> applying to, admitted to, or rejected from — when the student asks for it or
> agrees to it. When a student shows real interest in a school, offer to add it
> and say what tracking unlocks (their real cost numbers, their options in one
> place); never add, change, or remove an entry without the student's say-so,
> and never push if they'd rather not track a school. The student can change a
> school's status or remove it at any time — honour that immediately and without
> comment. Use the college id from a college search or cost result. The result
> echoes the full list after the write.

### Coach prompt: v4 — yes, and why

The tool alone is not enough, on direct precedent: the 0047 seed's own header
records that RFC 133/134's pieces "went unused in conversation" until the v3
prompt told the coach to use them. Tool advertisement makes a capability
_available_; it does not make the coach _offer_ it — and this slice's entire
point is the proactive offer, since v3's cost paragraph is conditioned on a list
that no student can currently populate. The offer policy (when to offer, what
value to name, never nag) is behavioral and belongs in the prompt; the mechanics
stay in the tool description.

`db/schema/0048.seed-coach-system-prompt-v4.sql`, mirroring 0044/0047's
convention exactly: the v3 body **byte-identical as a prefix**, a single-space
join, and exactly one appended paragraph (approved copy lives only in the seed;
approximate content):

> The student's college list is theirs, and you are its keeper in conversation
> via the update_college_list tool. When the student shows real interest in a
> school, offer to add it to their list and say what that unlocks — their real
> cost picture, and keeping their options in one place. Add, restatus, or remove
> a school only when the student asks or agrees; if they'd rather not track one,
> let it go without comment. When they tell you an application milestone — they
> applied, got in, were turned down — offer to update the school's status so
> their list stays true. They can always change their mind, and changing it back
> is always one message away.

Naming the tool in the prompt follows v3's precedent of naming
`college_cost_profile` (the action tool rides the prompt; v3 only omitted the
_secondary_ write tool's name). `service.conf` pins
`systemPromptVersion =
"v4"`; the v3 row stays in the immutable catalog, so
rollback is one env var.

### Wiring

`Application.kt`: construct `CollegeListService` **before** the `ToolRegistry`
(today it is constructed after) and register
`CollegeListChatTool(collegeListService)` in the registry list; REST routing
keeps using the same instance. `CoachingService` dispatch needs no change —
`StudentScopedChatTool` dispatch (RFC 134) already covers it.

### Product spec

`product/0001-v1-differentiator/spec.md` gains slice **S3.5** between S3 and S4,
recording the gap this slice closes (no student-facing door to the list made
S1–S3 unreachable) and its AC: a student can add, restatus, and remove a school
entirely in chat, and the coach offers — never nags.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListChatTool.kt`
  — **new**: the tool.
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListService.kt`
  — add `listActiveWithNames` (+ its small result type, own file per repo
  convention).
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — hoist
  `CollegeListService` construction; register the tool.
- `db/schema/0048.seed-coach-system-prompt-v4.sql` — **new**: v4 seed,
  v3-verbatim-plus-one-paragraph.
- `service/src/main/resources/service.conf` — pin `systemPromptVersion = "v4"`.
- `service/src/test/kotlin/ed/unicoach/coaching/collegelist/CollegeListChatToolTest.kt`
  — **new**: tool tests.
- `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` — v4
  structural test (v3 test stays; it asserts a catalog invariant, not the pin).
- `product/0001-v1-differentiator/spec.md` — S3.5 slice record.
- `rfc/136-update-college-list-chat-tool.md` — this document.

## Implementation Plan

1. `CollegeListService.listActiveWithNames` + result type.
2. `CollegeListChatTool` (definition, parse, dispatch, echo).
3. Wiring in `Application.kt`.
4. 0048 seed + `service.conf` pin + `SystemPromptCatalogTest` v4 test.
5. `CollegeListChatToolTest`.
6. spec.md S3.5 record.

## Tests

`CollegeListChatToolTest` (DB-backed, `MoneyProfileChatToolTest`'s exact harness
shape — truncate, raw-SQL fixtures, `runBlocking`):

- definition carries the name, the action/status enums, and the ethos contract
  markers ("offer to add", "never add, change, or remove an entry without the
  student's say-so", "change a school's status or remove it at any time").
- add without status → entry created as `considering`; echo carries name,
  status, reasons, count.
- add with status+reasons → echoed; second add of the same college → already-
  listed error naming no internals.
- add with unknown college id → error; malformed uuid → error; unknown
  `action`/`status` value → error; unknown field → error.
- update restatuses (reasons preserved when omitted); update with reasons only
  (status preserved); update with neither → error; update of an unlisted college
  → error steering to add.
- remove → echo without the school; remove of an unlisted college → error;
  `status`/`reasons` on remove → error.
- unscoped `execute(input)` → the misroute structured error (inherited final).
- student-scoping: student B's tool call cannot see or mutate student A's entry
  for the same college.

`SystemPromptCatalogTest`: v4 = v3 byte-identical prefix + single-space join +
one paragraph naming `update_college_list` and the offer/never-without-say-so
markers; the pin test picks up v4 from config automatically.
