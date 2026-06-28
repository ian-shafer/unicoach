# RFC 85: Compact id display in the admin conversation views

## Executive Summary

The admin render layer compacts any column declared `FieldType.UUID` to an
ellipsis plus the last `AdminDisplay.idTailChars` characters, with the full
value reachable through a hover `title`, a click-to-copy button (`data-full`),
and the trailing `refSlug` link glyph (`renderIdValue` in
`admin-server/src/main/kotlin/ed/unicoach/admin/render/CellRender.kt`). The
conversation admin views — `ConvosResource`, `ConvoRequestsResource`, and the
Conversations panel built by `StudentsResource.buildConversationsPanel` — were
written before `FieldType.UUID` existed and declare their id columns
`FieldType.TEXT`, so they render full 36-char UUIDs while every other admin
surface compacts them.

This RFC flips the UUID-valued id columns in those three views to
`FieldType.UUID`, leaving the BIGINT `convo_requests.id` column
`FieldType.TEXT`. `FieldType.UUID` and `refSlug` are orthogonal (a column may
compact, link, or both), so every existing `refSlug` is preserved: the columns
gain compaction without losing their navigation glyph. No render-layer, engine,
or schema change is required — the behaviour already exists; this RFC only
changes which columns opt into it. Scope is `admin-server` only.

## Detailed Design

### Data Models

No data-model, schema, or migration change. The flip is a column-type
declaration change in three resource files. The cell projections (`cells()` and
the panel row builders) already stringify these columns to their UUID text and
are unchanged.

Column types are reconciled against the applied schema
(`db/schema/0006.create-coaching-conversations.sql`,
`db/schema/0007.create-system-prompts.sql`): `convos.id` and `convos.student_id`
are `UUID`; `convo_requests.id` is `BIGINT GENERATED ALWAYS AS IDENTITY`;
`convo_requests.convo_id` and `convo_requests.system_prompt_id` are `UUID`. A
column is flipped to `FieldType.UUID` if and only if it carries a UUID; the
BIGINT primary key stays `FieldType.TEXT`.

### API Contracts

Three changes, all type-declaration flips that preserve the existing `refSlug`:

1. **`ConvosResource.fields`** — `id` (`refSlug = "convo"`) and `studentId`
   (`refSlug = "student"`) change from `FieldType.TEXT` to `FieldType.UUID`.

2. **`ConvoRequestsResource.fields`** — `convoId` (`refSlug = "convo"`) and
   `systemPromptId` (`refSlug = "system-prompt"`) change from `FieldType.TEXT`
   to `FieldType.UUID`. `id` (`refSlug = "convo-request"`) stays
   `FieldType.TEXT` and gains a short comment marking it the BIGINT primary key,
   so a later reader does not mistake the lone TEXT id among compacted siblings
   for an oversight.

3. **`StudentsResource.buildConversationsPanel`** — the Conversations panel's
   `EdgePanel.Table.Column("ID", refSlug = "convo")` gains the `FieldType.UUID`
   type argument
   (`EdgePanel.Table.Column("ID", FieldType.UUID, refSlug = "convo")`). The
   panel row projection already supplies the full convo id string; only the
   column type changes.

No route, DI-registration, or resource-registry change: all three resources are
already registered and rendering. The `EdgePanel.Table.Column` constructor
already defaults `type` to `FieldType.TEXT`, so the only edit is adding the
explicit `FieldType.UUID` argument.

### Error Handling / Edge Cases

All edge behaviour is inherited from the render path unchanged — the resources
choose only the column type. Per `renderIdValue`: a blank value renders nothing;
a value no longer than `idTailChars` renders whole with no leading ellipsis; a
longer value renders `…` plus the last `idTailChars` characters. In every
non-blank case the full value stays reachable through the span `title`, the copy
button's `data-full`, and (where `refSlug` names a supported resource) the
trailing glyph `href` that `renderRefLink` builds from the full value. These
invariants are already covered at the render layer by `CellRenderTest` and are
not re-tested per resource.

The BIGINT `convo_requests.id` continues through the `FieldType.TEXT` branch:
raw text, no compaction, no copy button, with its `convo-request` link glyph
intact.

### Dependencies

None. No new types, config keys, or libraries. `FieldType.UUID`,
`AdminDisplay.idTailChars`, `renderIdValue`, and the `EdgePanel.Table.Column`
type parameter all already exist.

## Tests

Resource-level HTTP tests assert the rendered HTML, mirroring the existing
compaction assertion in `StudentsResourceTest` (the observations-panel Convo ID
case): the compacted span text `…<last 8>`, the `title="<full>"` hover, the
`data-full="<full>"` copy button, the preserved `refSlug` href, and the absence
of the raw UUID as visible cell text (`>$uuid<`). `idTailChars` is `8` under the
test display config.

**`ConvosResourceTest` — new test
`GET convo id compacts the id and studentId
UUID fields`:** seed a convo via the
existing `seedConvoWithTurn` helper; GET `/convo/{convoId}`. Assert for both
`convoId` and `studentId`: the body contains `…${value.takeLast(8)}`,
`title="$value"`, and `data-full="$value"`; the body still contains the
`refSlug` hrefs `/convo/$convoId` and `/student/$studentId`; and the body does
not contain `>$convoId<` or `>$studentId<` (the raw UUID is no longer visible
cell text).

**`ConvoRequestsResourceTest` — new test
`GET convo-request id compacts convoId
and systemPromptId but leaves the BIGINT id raw`:**
seed a convo and request inline by calling `seedConvoRequest` directly (its
returned `ConvoRequest` exposes `convoId`, `systemPromptId`, and the BIGINT
`id`); GET `/convo-request/{id}`. Assert `convoId` and `systemPromptId` each
compact (`…<tail>`, `data-full="<full>"`) and keep their `/convo/...` and
`/system-prompt/...` hrefs. Assert the BIGINT `id` still renders raw: the body
contains the literal request-id string and does NOT contain
`data-full="$requestId"` (no copy button is emitted for the TEXT id). This is
the regression guard that the BIGINT column was not flipped.

**`StudentsResourceTest` — new test
`Conversations panel compacts the convo ID
column`:** seed user + student +
convo only (no observation or extraction run, so the convo id appears compacted
in exactly one place — the Conversations panel — making the assertion
unambiguous); GET `/user/{userId}`. Assert the body contains the `Conversations`
panel label, the compacted `…${convoId.takeLast(8)}` text, `title="$convoId"`,
`data-full="$convoId"`, and the `/convo/$convoId` href; and that `>$convoId<`
does not appear (the raw UUID is no longer visible panel cell text).

Existing tests that assert the `refSlug` hrefs (`/convo/{id}`, `/student/{id}`,
`/system-prompt/...`, `/convo-request/{id}`) must continue to pass unchanged:
the flip alters the displayed value text, not the link target.

## Implementation Plan

1. **Flip `ConvosResource` id columns.** In
   `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ConvosResource.kt`,
   change the `AdminField` `type` for `id` and `studentId` from `FieldType.TEXT`
   to `FieldType.UUID`, leaving their `refSlug` values (`"convo"`, `"student"`)
   and all other fields unchanged.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`

2. **Flip `ConvoRequestsResource` FK columns; annotate the BIGINT id.** In
   `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ConvoRequestsResource.kt`,
   change the `AdminField` `type` for `convoId` and `systemPromptId` from
   `FieldType.TEXT` to `FieldType.UUID`, keeping their `refSlug` values. Leave
   the `id` field `FieldType.TEXT` and add a brief comment on it noting it is
   the BIGINT primary key and therefore stays `FieldType.TEXT`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`

3. **Flip the Conversations panel ID column.** In
   `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`,
   in `buildConversationsPanel`, add the `FieldType.UUID` type argument to
   `EdgePanel.Table.Column("ID", refSlug = "convo")` so it reads
   `EdgePanel.Table.Column("ID", FieldType.UUID, refSlug = "convo")`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`

4. **Add the three compaction tests.** Add the new test in each of
   `ConvosResourceTest`, `ConvoRequestsResourceTest`, and `StudentsResourceTest`
   as specified in Tests, reusing each suite's existing seed helpers and the
   `…<tail>` / `title=` / `data-full=` assertion pattern.
   - Verify: `nix develop -c bin/test admin-server -f` (confirm the suite
     reports executed tests, not an all-cache no-op, and that the three new
     tests pass alongside the existing conversation-view tests)

## Files Modified

- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ConvosResource.kt` —
  flip `id` and `studentId` fields to `FieldType.UUID`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ConvoRequestsResource.kt`
  — flip `convoId` and `systemPromptId` fields to `FieldType.UUID`; comment the
  BIGINT `id` as intentionally `FieldType.TEXT`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
  — flip the Conversations panel `"ID"` column to `FieldType.UUID`.
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ConvosResourceTest.kt`
  — add the `id`/`studentId` compaction test.
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/ConvoRequestsResourceTest.kt`
  — add the `convoId`/`systemPromptId` compaction test that also asserts the
  BIGINT `id` stays raw.
- `admin-server/src/test/kotlin/ed/unicoach/admin/resources/StudentsResourceTest.kt`
  — add the Conversations-panel ID compaction test.
