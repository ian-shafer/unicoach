# RFC 100: Admin Manual Run Triggers

## Executive Summary

Admin has no way to manually kick off an extraction, synthesis, or fit-lens run
outside their automatic triggers (per-turn debounced enqueue for extraction; RFC
97's periodic sweeps for synthesis and fit-lens). This RFC adds three
admin-triggered actions — one button each on the Convos resource (extraction)
and the Students resource (synthesis, fit-lens) — that enqueue the same job
types used by the automatic paths, letting an operator force a specific
conversation or student through the pipeline without waiting for its natural
trigger.

No new job types, no gate bypass: manual triggers enqueue the identical payload
the automatic path would, so they hit the same watermark/freshness/
circuit-breaker checks — a click can be a no-op if there's nothing new to
process. Students has no standalone admin page today (embedded-only, nested
under Users); delivering the two student-scoped triggers requires first
promoting `StudentsResource` to a real top-level resource, including
implementing its currently-stubbed `list()`.

## Detailed Design

### Data Models

No new tables or columns.

New DAO methods:

1. `ConvosDao.maxRequestIdForConvo(session: SqlSession, convoId: ConvoId): Result<ConvoRequestId?>`
   — latest `convo_requests.id` for a conversation, or null if it has none.
   Backs the extraction trigger's `throughRequestId`.
2. `StudentsDao` gains `SoftDeleteListable<Student>`:
   `list(session, scope, limit, offset): Result<List<Student>>`, modeled on
   `UsersDao.list()` (`WHERE scope.predicate()`, `ORDER BY created_at DESC, id`,
   `LIMIT ? OFFSET ?`).

### API Contracts

Three new admin POST routes, each a `CustomAction` on an existing resource
(mirrors `PeriodicJobsResource`'s pattern):

| Route                                  | Resource         | Job enqueued                                                            |
| -------------------------------------- | ---------------- | ----------------------------------------------------------------------- |
| `POST /convo/{id}/trigger-extraction`  | ConvosResource   | `EXTRACT_CONVERSATION` / `ExtractionPayload(convoId, throughRequestId)` |
| `POST /student/{id}/trigger-synthesis` | StudentsResource | `SYNTHESIZE_STUDENT` / `SynthesisPayload(studentId)`                    |
| `POST /student/{id}/trigger-fit-lens`  | StudentsResource | `FIT_LENS` / `FitLensPayload(studentId)`                                |

All three: `disabledReason = { null }` (always enabled — no in-flight/spam
guard; downstream gates already handle redundant runs). Each carries fixed,
static `helpText` describing what makes it a no-op (below) — not a live per-row
gate check, just documentation rendered next to the button:

| Action             | helpText                                                                                                                        |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| trigger-extraction | "No-ops if this conversation has no turns past its last applied extraction (watermark)."                                        |
| trigger-synthesis  | "No-ops if nothing has changed in this student's claims or college list since the last applied synthesis run (freshness gate)." |
| trigger-fit-lens   | "Skipped if too few claims, no changes since the last applied run, or 3 consecutive failures (circuit breaker)."                |

`CustomAction<ROW>` gains an optional `helpText: String? = null` field (default
preserves `PeriodicJobsResource`'s existing actions unchanged);
`DetailView.kt`'s action-button block renders it as a caption under the button
when non-null.

Enqueue via `QueueService.enqueue(jobType, payload, delay = null)` — not
session-threaded (fire-and-forget; not a required enqueue under ASYNC_WORK.md's
Rule 2). On success: plain redirect to the row's own detail page (`/convo/{id}`
/ `/student/{id}`), no confirmation banner — matches `PeriodicJobsResource`
exactly; no flash/toast infra exists anywhere in Admin today and this RFC does
not add one.

`trigger-extraction` handler looks up `ConvosDao.maxRequestIdForConvo` first;
null (no requests yet) short-circuits to an error response, no job enqueued.

StudentsResource promotion (prerequisite for the two student-scoped triggers):

- `kind`: `EMBEDDED_ENTITY` → `ENTITY`
- `topLevel`: `false` → `true`
- `list()`: stub `Result.success(emptyList())` → delegates to
  `StudentsDao.list()`
- Stays embedded under `UsersResource` via its existing `AdminEdge.Embedded`
  edge — dual-registration precedent: `ConvosResource` is already topLevel=true
  and separately rendered as an embedded panel elsewhere.
- create/update/delete/undelete stay `null` (unchanged) — mutations still flow
  only through `UsersResource`'s nested endpoints; this RFC adds a read/trigger
  surface only, not new write paths.

### Error Handling/Edge Cases

- Convo with zero requests: `maxRequestIdForConvo` returns null →
  trigger-extraction handler responds with an error, no job enqueued.
- Gate no-ops (freshness gate, watermark, circuit-breaker) are unchanged —
  manual and automatic triggers run identical service/handler code. This RFC
  introduces no new no-op semantics, just a new entry point into existing ones.
- Double-click / concurrent trigger: no new guard. Per-student advisory locks
  (extraction, synthesis) already serialize same-student runs; fit-lens's
  `UNIQUE(student_id, college_id)` backstop already prevents duplicate
  suggestions.

### Dependencies

None beyond existing modules (admin-web, db, queue).

## Tests

1. `trigger-extraction`, convo with ≥1 request: asserts a `jobs` row with
   `job_type = 'EXTRACT_CONVERSATION'` and payload `{convoId, throughRequestId}`
   matching the convo's latest request id; asserts redirect to `/convo/{id}`.
2. `trigger-extraction`, convo with zero requests: asserts no job row inserted,
   asserts an error response (not a redirect).
3. `trigger-synthesis`: asserts a `jobs` row with
   `job_type = 'SYNTHESIZE_STUDENT'` and payload `{studentId}`; asserts redirect
   to `/student/{id}`.
4. `trigger-fit-lens`: same shape for `job_type = 'FIT_LENS'`.
5. `StudentsResource` list route (`GET /student`): asserts real paginated rows
   (not the empty stub); asserts nav lists "Student" (mirrors
   `SystemPromptsResourceTest`'s nav-presence assertion).
6. `StudentsDao.list()`: seeds active + soft-deleted students, asserts
   `SoftDeleteScope.ACTIVE`/`ALL`/`DELETED` filter correctly and
   `limit`/`offset` paginate correctly.

## Invariants

**Rule:** Admin manual-trigger routes MUST enqueue the unmodified existing job
type/payload for that feature (`EXTRACT_CONVERSATION`/
`SYNTHESIZE_STUDENT`/`FIT_LENS`) — no bypass flag that skips the freshness gate,
watermark, or circuit-breaker a normal run would hit.

**Why:** These gates prevent duplicate billed LLM calls and duplicate writes
(RFC 66/93/98); a manual "force" path would silently reintroduce the
double-billing/double-write risk those gates exist to close.

**Target directory:**
`admin-web/src/main/kotlin/ed/unicoach/admin/resources/INVARIANTS.md`

## Implementation Plan

1. Add `ConvosDao.maxRequestIdForConvo`. Verify: `ConvosDaoTest` — convo with
   0/1/N requests.
2. Add `SoftDeleteListable<Student>` to `StudentsDao`. Verify: `StudentsDaoTest`
   — scope filtering + pagination (Tests #6).
3. Promote `StudentsResource` (`kind`, `topLevel`, real `list()`). Verify:
   `GET /student` returns real rows (Tests #5); embedded panel under Users still
   renders.
4. Add `CustomAction.helpText: String? = null` + render it in `DetailView.kt`
   next to each action button. Verify: existing `PeriodicJobsResourceTest` still
   passes unchanged (default null renders nothing).
5. Add `trigger-extraction` CustomAction (with helpText) + handler to
   `ConvosResource`. Verify: Tests #1, #2, and the helpText string renders on
   `/convo/{id}`.
6. Add `trigger-synthesis` + `trigger-fit-lens` CustomActions (with helpText)
   - handlers to `StudentsResource`. Verify: Tests #3, #4, helpText renders on
     `/student/{id}`.
7. Add the Invariants rule to
   `admin-web/src/main/kotlin/ed/unicoach/admin/resources/INVARIANTS.md`.
8. Run full `bin/test check` gate.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/ConvosResource.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/INVARIANTS.md`
- `admin-web/src/main/kotlin/ed/unicoach/admin/engine/CustomAction.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/render/DetailView.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/StudentsResourceTest.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/ConvosResourceTest.kt`
