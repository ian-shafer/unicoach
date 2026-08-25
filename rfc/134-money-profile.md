# RFC 134: Money profile

## Executive Summary

"Know your real price" (product brief 0001, S3) needs two typed facts about a
student's family: the household income band (to pick `net_price_q1..q5`, RFC
133) and the state of residency (to pick in-state vs out-of-state tuition).
Today neither has a durable, typed, student-correctable home: `claims` carries
free-text finance statements that are neither student-editable nor machine-
consumable (the RFC 91 argument, verbatim), and `students` models identity, not
family finances.

This RFC adds `money_profiles`: one row per student, two profile fields, each
carrying a tri-state answer status (`unanswered | answered | declined`) so the
product ethos — value before ask, never force — is a schema fact, not a UX
aspiration. `declined` is first-class: the coach can see "asked and declined"
and stop asking; `unanswered` is resumable at any time; every consumer degrades
gracefully when a field is anything but `answered`.

The entity ships as a vertical slice: schema + DAO (RFC 91's versioning
composition), student-facing REST CRUD, a coach-side chat tool
(`update_money_profile`) so the profile is built conversationally, profile
context injected into the coaching system prompt, and a read-only admin
resource. Extraction never infers money facts: income is explicit-only, written
solely through the student-driven tool path or REST.

Out of scope, deliberate: iOS profile UI (the chat surface is v1; S3/S5 add
visual cost surfaces), FAFSA/EFC modeling, parent linkage (Beat 2).

## Detailed Design

### Schema (migration 0046)

    CREATE TABLE money_profiles (
        id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
        version        INTEGER     NOT NULL DEFAULT 1,
        created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        deleted_at     TIMESTAMPTZ NULL,

        student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

        -- Household income band, enumerated with self-describing labels that
        -- name the Scorecard NPT4 brackets (RFC 133); the label -> net_price_qN
        -- mapping lives in code. TEXT + CHECK per the house enum pattern
        -- (claims.kind/status/topic, college_list_entries.status) -- the schema
        -- has no native PG ENUM types, and this stays consistent.
        income_band        TEXT NULL,
        income_band_status TEXT NOT NULL DEFAULT 'unanswered',

        -- Two-letter USPS state of residency (in/out-of-state tuition).
        residency_state    TEXT     NULL,
        residency_status   TEXT     NOT NULL DEFAULT 'unanswered',

        CONSTRAINT money_profiles_income_band_check
            CHECK (income_band IS NULL OR income_band IN
                   ('under_30k','30k_to_48k','48k_to_75k','75k_to_110k','over_110k')),
        CONSTRAINT money_profiles_income_band_status_check
            CHECK (income_band_status IN ('unanswered','answered','declined')),
        CONSTRAINT money_profiles_residency_state_format_check
            CHECK (residency_state IS NULL OR residency_state ~ '^[A-Z]{2}$'),
        CONSTRAINT money_profiles_residency_status_check
            CHECK (residency_status IN ('unanswered','answered','declined')),
        -- Value present exactly when answered: a declined/unanswered field can
        -- never smuggle a stale value to a consumer.
        CONSTRAINT money_profiles_income_band_value_iff_answered_check
            CHECK ((income_band IS NOT NULL) = (income_band_status = 'answered')),
        CONSTRAINT money_profiles_residency_value_iff_answered_check
            CHECK ((residency_state IS NOT NULL) = (residency_status = 'answered'))
    );

    CREATE UNIQUE INDEX money_profiles_student_active_idx
        ON money_profiles (student_id) WHERE deleted_at IS NULL;

Versioning composition mirrors `college_list_entries` (0024) exactly: a
`money_profiles_versions` history table, `enforce_versioning()`, version-log
trigger, soft-delete, the 4-timestamp pattern, and the same trigger slots.
Re-answering, changing, or declining a previously answered field is a plain
versioned UPDATE — history preserves the trail.

### DAO and models

`MoneyProfile` / `NewMoneyProfile` / `MoneyProfilesDao` in `:db`, mirroring
`CollegeListEntriesDao`'s OCC shape. The DAO owns `upsertForStudent`: one atomic
`INSERT ... ON CONFLICT (student_id) WHERE deleted_at IS NULL DO
UPDATE` with
per-field apply-or-keep arms, so concurrent first writes cannot race the partial
unique index; per the OCC-entity convention the `DO UPDATE` bumps `version`
unconditionally and logs history on every write. `MoneyProfileService` (in
`:service`) owns field-update orchestration — `Set`/`Decline`/`Clear` per field
— and is the single write path shared by REST and the chat tool. Field statuses
and the income band are Kotlin enums
(`AnswerStatus.UNANSWERED/ANSWERED/DECLINED`;
`IncomeBand.UNDER_30K/K30_TO_48K/K48_TO_75K/K75_TO_110K/OVER_110K`) serialized
to their TEXT columns; `IncomeBand` owns the `netPriceQN` selection so the band
-> column mapping has exactly one home.

### REST surface

`MoneyProfileRoutes` under the student-auth umbrella, mirroring
`CollegeListRoutes`: `GET /money-profile` (200 with profile or 404 before first
write), `PUT /money-profile` (idempotent create-or-update of any subset of
fields; each field arrives as value-or-declined-or-clear). OpenAPI spec updated;
schemathesis covers it via the existing contract job.

### Chat tool: update_money_profile

A `ChatTool` (RFC 94 registry) the coach calls when the student volunteers or
declines money facts mid-conversation:

    input: { income_band?: "under_30k".."over_110k", income_band_declined?: true (literal; false is a structured error),
             residency_state?: "CA", residency_declined?: true }

Setting a value and declining the same field in one call is a structured error.
The tool result echoes the full post-write profile so the coach's next message
reflects it. The tool DESCRIPTION carries the ethos contract verbatim: ask only
when cost comes up naturally, accept decline without pushing, never re-ask a
declined field unless the student reopens the topic.

### Coaching context injection

Coach context assembly (where claims/list already inject) gains a one-line
money-profile block per field: `answered(value) | declined | unanswered`, so the
coach knows what it may use, what not to re-ask, and what remains open.

### Admin

Read-only `MoneyProfilesResource` mirroring `CollegeListEntriesResource`
(browse + detail + version history). Income is sensitive: the two value columns
are marked `sensitive = true` so the admin UI applies its existing
sensitive-field handling.

### Extraction non-participation

`extraction` prompts are NOT taught to write money facts; the profile's only
writers are the chat tool and REST. A claims row about finances remains
legitimate color for the coach; the typed profile is the single source S3
consumes.

## Files Modified

- `db/schema/0046.create-money-profiles.sql` — new
- `db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile*.kt`,
  `db/src/main/kotlin/ed/unicoach/db/dao/MoneyProfilesDao.kt` — new
- `rest-server/.../routing/MoneyProfileRoutes.kt` + `Routing.kt` wiring — new
- `api-specs/openapi.yaml` — new paths
- `service/.../coaching/MoneyProfileChatTool.kt` — new; `CoachingService` tool
  registration + context assembly
- `admin-web/.../resources/MoneyProfilesResource.kt` + registry — new
- system-prompt seed migration IF the coach prompt needs the money-profile
  guidance line (0047, catalog convention per RFC 129)
- Tests per below

## Implementation Plan

1. Migration 0046 (+0047 prompt seed if needed) — schema + versioning.
2. Models + DAO + tests.
3. REST routes + OpenAPI + tests.
4. Chat tool + registration + context injection + tests.
5. Admin resource + tests.

## Tests

- DAO: create, get-by-student, versioned update (answer -> decline -> re-answer
  writes history), value-iff-answered CHECK violations rejected, soft-delete
  uniqueness (delete then recreate).
- REST: GET 404-before-first-write, PUT create, PUT partial update, PUT decline
  clears value, auth isolation (student A cannot read B).
- Chat tool: set, decline, both-in-one-call error, echo shape, unknown-field
  error.
- Context assembly: all three statuses render; declined field renders as
  declined (not absent).
- Admin: browse/detail/history render, sensitive marking on value columns.
