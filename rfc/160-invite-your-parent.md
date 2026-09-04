# RFC 160: Invite your parent — the share nudge and share events

Slice: `first-value/06/invite-your-parent` (brief 0001, S6). Builds on RFC 155
(the Family Cost Report share surface) and RFC 93/97/104 (synthesis and its cron
sweep).

## Executive Summary

The Family Cost Report (RFC 155) is the wedge artifact, but today the coach may
only produce a share link when the student asks — the tool description and coach
prompt v15 explicitly forbid offering unasked. This RFC adds the sanctioned
moment: a **share-nudge commitment** written by the synthesis pass when a
student becomes eligible, delivered through the existing
open-explicit-commitment opener (RFC 93 pull delivery), plus an append-only
**`share_events`** log so share activity (mint, repeat ask, reissue, revoke)
stops being invisible.

Three deliberate shape choices:

1. **The nudge is deterministic code, not a new LLM lens.** Eligibility is
   boolean logic over DB state (active list entries, live share, prior nudge).
   The synthesis prompt today carries no money/cost/share context, and the
   freshness gate ignores share state — teaching the LLM pass all of that buys
   nothing over a cheap code check that runs on every sweep. Precedent: the
   fit-lens (RFC 98) is likewise its own non-LLM-lens pass. The commitment row
   still carries a distinct lens value (`share_report`) so delivery, metrics,
   and suppression can see it.
2. **Re-nudges are allowed; "never ask me again" is respected forever.** (Ian,
   at the RFC 160 gate, amending the drafted once-ever policy.) An active
   student whose list keeps growing may be nudged again — but only after a
   cooldown AND a list change since the last nudge, so the repeat has something
   new to point at. A student who says "never ask me again" gets a durable
   opt-out the coach records with a new tool, and no nudge ever fires again.
3. **`share_events` records what the share surface does, in the same
   transaction.** Kinds: `minted`, `repeat`, `reissued`, `revoked`. It is the
   history `cost_report_shares` cannot carry (a repeat ask touches nothing) and
   the substrate Beat 2's parent-claim path will read. Parent-side report
   _opens_ are deliberately not recorded (privacy; out of scope — see Open
   items).

The door: the student walks in for any conversation; the opener raises the nudge
naturally ("Since you last spoke, you have been reflecting on…"); the student
says yes and the existing `share_cost_report` tool returns the link. Coach
prompt **v18** amends the never-offer-unasked rule: a surfaced share-nudge
commitment IS the sanctioned offer. Nothing is gated on sharing (D11); a decline
simply ends the topic.

Rollback: `SYNTHESIS_SHARE_NUDGE_ENABLED=false` stops new nudges;
`COACHING_SYSTEM_PROMPT_VERSION=v17` restores the previous prompt;
`COACHING_SURFACE_COMMITMENTS=false` (existing) stops delivery of all
commitments. The synthesis cron row remains operator-gated as before.

## Detailed Design

### 1. Schema

Two DDL changes and one seed (migration numbers recomputed at commit; names
below use the next-free numbers as of writing).

**a. Widen the commitment lens vocabulary**
(`0080.widen-commitments-lens-share-report.sql`)

```sql
ALTER TABLE commitments DROP CONSTRAINT commitments_lens_check;
ALTER TABLE commitments ADD CONSTRAINT commitments_lens_check
  CHECK (lens IN ('gap','timing','contradiction','share_report'));
```

Kotlin: `CommitmentLens.SHARE_REPORT("share_report")` added to the enum (pure
values — no service-layer metadata on the shared db model). To keep the LLM from
inventing share nudges of its own, the forced-tool schema stops auto-enumerating
`CommitmentLens.entries`: `SynthesisService`'s companion declares the explicit
LLM-eligible set `LLM_PROPOSABLE_LENSES` (`gap|timing|contradiction`) beside the
tool schema it governs, and that one set drives BOTH the `record_synthesis`
schema enum and the validator, which rejects a non-proposable lens the same way
it rejects an unknown one today. A future lens is offered to the LLM only by a
deliberate addition to that set. The synthesis prompt (v2) is unchanged.

**b. `share_events` — append-only log** (`0081.create-share-events.sql`), the
D10 table, standard `postgres-log-table-design`:

```sql
CREATE TABLE share_events (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  share_id   UUID NULL REFERENCES cost_report_shares(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL,
  CONSTRAINT share_events_kind_check
    CHECK (kind IN ('minted','repeat','reissued','revoked','opted_out')),
  CONSTRAINT share_events_share_id_check
    CHECK ((kind = 'opted_out') = (share_id IS NULL))
);
CREATE INDEX share_events_student_idx ON share_events (student_id, created_at);
-- plus the repo-standard append-only guards (trigger_NN naming,
-- EXECUTE PROCEDURE): prevent_log_update BEFORE UPDATE and
-- prevent_log_delete BEFORE DELETE, exactly as on synthesis_runs.
```

Every share-surface kind names a concrete `cost_report_shares` row:
`minted`/`reissued` the new row, `repeat` the existing live row, `revoked` the
row being revoked. `opted_out` is the one row with no share: the student's
durable "never ask me again", recorded from chat (below) — it has no share row
by nature, hence the paired CHECK. Kotlin: `ShareEventKind` enum +
`ShareEventsDao` (`record(session, studentId, shareId, kind)`,
`hasOptOut(session, studentId)`, `listByStudent` for tests/Beat 2).

**c. Coach prompt v18 seed** (`0082.seed-coach-system-prompt-v18.sql`):
byte-identical v17 body + one appended paragraph: the coach still never opens
with the share offer on its own, but when a reflection (commitment) about
sharing the cost report has been surfaced, raising it once, naturally, is the
sanctioned moment; a decline or deferral closes the topic for that conversation
without residue; and if the student says they never want this suggested again,
the coach calls `stop_cost_report_offers` and confirms. service.conf pins
`coaching.systemPromptVersion = "v18"` with the standard rollback comment.

### 2. The nudge step in `SynthesisService`

A deterministic step inside the existing per-student pass
(`JobType.SYNTHESIZE_STUDENT`), running in the **read/write transactions the
pass already owns**, before the LLM phases and independent of their outcome:

- It runs even when the freshness gate would no-op the LLM phases (share
  eligibility is not a freshness input, by design — the gate's inputs are
  unchanged); this is what forces the insert into the read-phase transaction
  above.
- Config: `synthesis.shareNudgeEnabled` (env `SYNTHESIS_SHARE_NUDGE_ENABLED`),
  default **true** — the sweep itself is already operator-gated by the
  `periodic_jobs` row, so no dark-ship double gate — and
  `synthesis.shareNudgeCooldownDays` (env
  `SYNTHESIS_SHARE_NUDGE_COOLDOWN_DAYS`), default **14**.

**Eligibility** (all in one read, same session):

1. student is active (pass already checks);
2. ≥ 2 active college list entries (`CollegeListEntriesDao.listActiveByStudent`)
   — the report is a comparison; two schools make one (Ian, gate);
3. no live share (`CostReportSharesDao.findLiveByStudent` returns null) — a
   student with a live link needs no invitation to create one;
4. no `opted_out` share event (`ShareEventsDao.hasOptOut`) — "never ask me
   again" is forever;
5. no OPEN `share_report` commitment, and the **re-nudge condition** holds
   against the most recent `share_report` commitment if one exists
   (`CommitmentsDao`: new `latestByStudentAndLens`):
   - at least `shareNudgeCooldownDays` (default **14**) since it was created,
     AND
   - the college list has changed since (some active entry's `updated_at` is
     newer than that commitment's `created_at`) — the repeat nudge points at
     something new, not the same list;
6. open commitment count (all disclosures, matching the existing
   `maxOpenCommitments` gate's semantics) < `maxOpenCommitments`.

The step runs after the existing RFC-109 budget gate, so a budget-exhausted
student is not nudged — the nudge itself is free, but a blocked student cannot
receive delivery, and the gate deliberately runs before any further read.

When eligible, the step inserts one commitment **in the read-phase transaction**
(same session, under the held per-student advisory lock, before the
freshness/claims/cap LLM gates — the write phase only runs after a successful
LLM call, so a write-phase insert would never fire on a freshness no-op): lens
`share_report`, disclosure `explicit`, status `open`, `trigger_kind`
`next_session`, statement (fixed template, not LLM text):

> "Their college list now has real cost figures. When the moment is right,
> suggest sharing the family cost report with a parent — it is a live link,
> revocable any time, and only shows what they have already seen."

No `commitment_support` rows (no claims were reasoned over). `synthesis_runs` is
not written for a nudge-only pass that skips the LLM (runs log LLM work); the
commitment row itself is the record.

**Delivery is untouched**: `startConvo` already surfaces open explicit
commitments and marks them fulfilled on the first successful reply. AC "nudge
fires for eligible students" is met by insertion + the existing opener.

### 3. Share events in `CostReportShareService`

`share(studentId)` and `revoke(studentId)` record events **in the same
transaction** as the row mutation they describe:

- `Minted` → `minted` (the new row)
- `Existing` → `repeat` (the live row; the previously-invisible case)
- `Reissued` → `reissued` (the new row) — the stale row's revocation inside
  reissue is part of the reissue, not a separate `revoked` event
- `Revoked` → `revoked` (the revoked row); `NothingLive` records nothing
- `Unavailable` (no secret) records nothing — nothing happened.

AC "share event recorded" is met at the source of truth, not in the chat tool,
so admin/API callers (if any ever exist) are covered for free.

### 4. The opt-out tool

A new no-argument student-scoped chat tool, `stop_cost_report_offers`
(`StopCostReportOffersChatTool`, alongside the RFC 155 tools): the coach calls
it when — and only when — the student says they never want the share suggestion
again. It records one `opted_out` share event (idempotent: a second call records
nothing new and confirms), drops any open `share_report` commitment in the same
transaction (reason: student opted out) so an already-written nudge cannot ride
a later opener after "never", and returns
`{"cost_report_offers": {"stopped": true, "statement": ...}}` so the coach can
confirm in words. It does NOT revoke a live share and does NOT disable the
`share_cost_report` tool — a student who opted out of nudges can still ask to
share. Prompt v18 carries the instruction. There is deliberately no un-opt-out
tool: "never" means never, and an operator can act in the database if a student
genuinely recants (Open item).

### 5. What this RFC deliberately does not do

- No parent-page CTA: the report page is parent-facing and D-G forbids upgrade
  cues; the S5 BLOCKS note reads "the share CTA and its token live on the report
  surface" — S5 _provides_ them; S6 adds the trigger and the tracking.
- No report-open tracking (privacy; would also need bot filtering to mean
  anything). Open item for Beat 2.
- No per-decline tracking: a soft "not now" is not reliably observable and does
  not need to be — the cooldown + list-change condition keeps repeats rare, and
  a hard "never again" is the explicit tool above.
- No Beat 2 claim path — the token and the event log are shaped to serve it.

## Files Modified

New:

- `db/schema/0080.widen-commitments-lens-share-report.sql`
- `db/schema/0081.create-share-events.sql`
- `db/schema/0082.seed-coach-system-prompt-v18.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/ShareEventKind.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ShareEventsDao.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/report/StopCostReportOffersChatTool.kt`

Modified:

- `db/src/main/kotlin/ed/unicoach/db/models/CommitmentLens.kt` — `SHARE_REPORT`
- `db/src/main/kotlin/ed/unicoach/db/dao/CommitmentsDao.kt` —
  `latestByStudentAndLens` (open-or-latest read for suppression/cooldown)
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — register
  `stop_cost_report_offers`
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisService.kt` —
  nudge step + config
- `service/src/main/kotlin/ed/unicoach/coaching/report/CostReportShareService.kt`
  — event recording
- `service/src/main/resources/service.conf` — `synthesis.shareNudgeEnabled`,
  `synthesis.shareNudgeCooldownDays`, `coaching.systemPromptVersion = "v18"`
- synthesis `record_synthesis` tool schema/validator (LLM-proposable subset) —
  in `SynthesisService.kt` or its tool-definition site
- tests (below)

## Implementation Plan

1. Migrations (lens CHECK widening, `share_events`, prompt v18 seed) + Kotlin
   enums/DAOs.
2. `CostReportShareService` event recording (same-transaction) + tests.
3. `SynthesisService` nudge step (config, eligibility read, insert) + tests;
   restrict LLM-proposable lenses.
4. service.conf pins + prompt catalog test update.
5. Full-suite gate.

## Tests

- **Migrations/catalog**: `SystemPromptCatalogTest` covers v18; schema tests as
  per convention (log guards refuse UPDATE/DELETE on `share_events`; lens CHECK
  accepts `share_report`).
- **Share events**: minted / repeat / reissued / revoked each record exactly one
  row with the right `share_id`; `Unavailable`/`NothingLive` record none; event
  insert is atomic with the share mutation (failure rolls both back); the
  `share_id`/kind pairing CHECK refuses a shareless `minted` and a share-bearing
  `opted_out`.
- **Opt-out tool**: `stop_cost_report_offers` records one `opted_out` event, is
  idempotent, leaves any live share alone, and permanently suppresses the nudge;
  `share_cost_report` still works after opt-out.
- **Nudge eligibility matrix**: eligible student (≥2 active entries) gets
  exactly one commitment; each failed condition (0 or 1 entries, live share,
  opt-out event, open `share_report` commitment, cooldown not elapsed, list
  unchanged since last nudge, cap reached, config off) suppresses; a re-nudge
  fires when cooldown elapsed AND the list changed since the last `share_report`
  commitment; a second pass the same day is a no-op.
- **Freshness independence**: a student whose LLM phases no-op on freshness
  still receives the nudge when eligible.
- **LLM lens restriction**: `record_synthesis` input with lens `share_report` is
  rejected as invalid.
- **Delivery**: existing opener tests extended — a `share_report` commitment
  rides the opener and is marked fulfilled like any other (no special-casing).

## Landing note — composed on RFC 159's v17

RFC 159 (federal aid policy parameters) was open concurrently and landed first,
seeding its own coach prompt **v17** as v16 + one appended paragraph, and taking
migration numbers 0077–0079. This RFC therefore lands as migrations
**0080–0082** and seeds **v18** as RFC 159's v17 body byte-identical + the
share-nudge paragraph, so both paragraphs stand. Rollback is
`COACHING_SYSTEM_PROMPT_VERSION=v17` (RFC 159's body, without the share nudge).
