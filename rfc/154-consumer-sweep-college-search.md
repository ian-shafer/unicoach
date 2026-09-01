# RFC 154 — The consumer sweep

Status: proposed\
Slice: `search/05/consumer-sweep` (brief 0004, S5)\
Base: `main@1d94cb9a` · Branch: `pipeline/rfc-154`

## Summary

The index is only THE index if everything that finds a college goes through it.
This slice audited every call site that searches, filters or name-resolves a
college and found the sweep mostly already done: RFC 150 re-pointed both
search-shaped DAO entry points onto `college_search_index`, and no bypassed
legacy query path survives to delete.

One real gap is left, and it is not SQL. Fuzzy name resolution exists in exactly
one place — `CollegesDao.searchByName` — and that place is reachable only over
REST, by the iOS picker. The coach's only search tool, `search_colleges`, takes
no free text, and `update_college_list` demands a UUID. So "add Mizzou to my
list" said in chat has no path from a name to a `college_id`, while the same
words typed into the picker resolve fine.

This RFC closes that gap with a new chat tool, `find_college`, in `:college`,
mirroring `CollegeSearchTool` and calling `CollegeSearchService.searchByName` —
the same code path the picker uses. No new SQL, no new table, no DDL. It also
records the one ruling the sweep must not leave implicit (the admin browse stays
on `colleges`), writes the module convention the slice asked for into the KDoc
that owns it, and states the "nothing to delete" finding with its evidence.

The only migration seeds coach prompt v12, which tells the coach that a college
named in words is looked up with `find_college` and used by id everywhere else.

## Decisions

### D-A. The name path becomes a NEW tool, not a field on `search_colleges`

`find_college` is its own tool: one required `name` string, an optional `limit`,
and a result list of `{college_id, name, city, state}`.

Rejected: an optional free-text `name` field on `search_colleges`. It keeps the
tool count flat, and that is its only merit. It would mix two different result
shapes behind one name — `search_colleges` answers with `CollegeMatch` rows
under the D21 response contract (`total_matches`, `excluded_unknown`,
`source_years`, `sort_by`), and a name lookup answers with `CollegeSummary` rows
that have none of those facts and cannot honestly carry them. A call that
supplied `name` would have to suppress or fake three keys the contract says
every response carries.

The separate tool also leaves three things untouched that a shared field would
disturb: `CollegeSearchTool.KNOWN_FIELDS` (which refuses unknown fields on
purpose, RFC 150 D53), the `GET /api/v1/colleges` response schema pinned by
`OpenApiCollegeSearchTest`, and the `tool_use` fixtures in
`AnthropicTestFixtures` that replay a block literally named `search_colleges`. A
sweep slice should not need to renegotiate the search tool's contract to add a
lookup.

Two tools also read better to the model than one polymorphic one: attribute
questions ("small liberal arts colleges in Ohio") go to `search_colleges`, and
"the student said a school's name" goes to `find_college`. That is the division
prompt v12 states.

### D-B. It is a chat-free tool in `:college` with a thin `:service` bridge

Exactly the RFC 67 shape `CollegeSearchTool` already has, for the same reason:
`:college` must not depend on `:chat`. `FindCollegeTool` speaks plain
`JsonObject` on both ends and has a total `execute`; `FindCollegeChatTool` in
`:service` is a verbatim delegate implementing `ChatTool`, mirroring
`CollegeChatTool` line for line. Registration is one appended entry in the
`ToolRegistry` list in `rest-server`'s `Application.kt`, sharing the one
`CollegeSearchService` instance already built there.

The tool needs no `Codebook`: a name lookup has no coded vocabulary to render.

### D-C. Honest emptiness is the picker's rule, spoken

`CollegeSearchService.searchByName` returns a failure carrying
`SearchIndexNotBuiltException` when the index has never been built, because the
picker has no page shape to carry the fact. The tool must not turn that into "no
colleges match": an unbuilt index would report an empty result out of a full
database and no reader could tell it from a real zero. `find_college` maps that
one cause to the same sentence `search_colleges` already uses
(`CollegeSearchTool.INDEX_NOT_BUILT`), and every other failure to the same
`search_failed` error object `CollegeSearchTool` already emits. A genuine
zero-match name is `{"colleges": [], "count":
0}` — a valid outcome, not an
error.

The service still owns the LENGTH rule — longer than `MAX_QUERY_LENGTH = 100`
fails, and no second constant is written in the tool. What the review changed is
only how the answer travels and what shape the tool picks for it (tier-2):

- the rejection is its own type,
  `QueryTooLongException(maxLength,
  actualLength)`, thrown by
  `CollegeSearchService.searchByName` and asked about through
  `CollegeSearchService.rejectedInput`. It carries the two NUMBERS, not a
  sentence — `find_college` words it for the field IT has (`[name]`, not
  `query`), the same move RFC 150 made when it took the program-filter refusal
  string out of the DAO and left `CollegeSearchTool.refusalSentence` to word it.
  A supertype test (`error is IllegalArgumentException`) was wrong here:
  `handleFailures` catches `Exception` around the DAO and the JDBC driver, so
  any `IllegalArgumentException` from down there was reported to the coach as a
  fault in the words it wrote. `QueryTooLongException` still EXTENDS
  `IllegalArgumentException`, so nothing that already maps that type — the REST
  route among them — changes behaviour;
- a BLANK `name` is refused at the tool boundary, and only there. The service's
  own rule is untouched: a blank query is still an empty success for the picker,
  whose user can see their own empty box. The coach cannot, and would read
  `{"colleges": [], "count": 0}` as "no school by that name exists" — a claim
  about the world, for an input that named nothing. The service owns the rule;
  the tool chooses the shape its own reader can act on.

### D-D. Admin-web's college browse stays on `colleges`

`CollegesResource` → `CollegesDao.list` is an unfiltered, paginated BROWSE, not
a search: no predicate, no user text, and it renders every raw source column of
the entity including `version` and `created_at`, which the index does not carry
and must not (D60 — a column lives on the index only if something filters, sorts
or indexes on it). Moving it would be a strictly worse read. Admin has no name
filter anywhere.

Recorded here explicitly so the slice's acceptance — "zero search-shaped SQL
against `colleges`" — is not read as contradicted by a `SELECT * FROM colleges`
that is a reader, not a finder.

### D-E. There is no legacy query path to delete

The slice says "delete every bypassed legacy query path". RFC 150 D17 already
did: `search` and `searchByName` both read `college_search_index` today, the old
paths were deleted rather than kept in parallel, and `0056` dropped the
`pg_trgm` extension and its index outright. A repo-wide grep for `similarity(`,
`pg_trgm`, `ILIKE` and `LIKE '%` finds them in non-test main source only inside
`CollegesDao`'s index-backed paths. `similar_colleges` does not exist yet (S4).

So this RFC states the deletion as an AUDITED FINDING with its inventory (§
Detailed Design, "The audit"), rather than inventing a deletion to perform. The
sweep is one gap closed, one convention written, one ruling recorded, and
everything else verified.

### D-F. The module convention lives in KDoc, cross-referenced from the DAO

The slice asks for "a one-line module convention stating that college _search_
goes through the service". There is no file that owns such a rule: `college/`
and `db/` have no README, per-directory `SPEC.md`/`INVARIANTS.md` are retired
(CLAUDE.md), and every existing convention of this kind in this area is already
carried as KDoc — the `DefaultUniverse` "one home" rule, the "nothing else in
this file may write a search predicate" rule, the service's "the only domain
rule here is the clamp" rule.

So the convention goes where a future caller will actually be standing: the KDoc
header of `CollegeSearchService`, with a one-line cross-reference from
`CollegesDao`'s header. Three clauses:

- college SEARCH — structured or by name — goes through `CollegeSearchService`
  over `college_search_index`;
- point-reads by `id` / `ipeds_unit_id` stay on `colleges`, which holds the
  facts;
- ingest and versioning WRITE `colleges` and rebuild the index from it.

Rejected: a new `college/README.md`. A file nobody's editor opens while adding a
DAO method is a rule stated where it will not be read, and the retirement of
per-directory docs was a ruling, not an oversight.

### D-G. No DDL. One migration, one appended prompt paragraph

`db/schema/0068.seed-coach-system-prompt-v12.sql` inserts one row: v11 verbatim
plus one appended paragraph, the additive shape of `0047`, `0048`, `0058`,
`0063`, `0065` and `0066`. The pin moves to `v12` in `service.conf`; rollback is
`COACHING_SYSTEM_PROMPT_VERSION=v11`, and the immutable v11 row stays in the
catalog.

**The migration number is recomputed immediately before the commit.** `0068` is
the next free number after the codebook-FK migration landed as `0067`, but
parallel `/ship` runs claim numbers from the same sequence; whoever lands takes
the next free one and the version word follows it.

### D-H. Scope

In scope: the tool, its wiring, the prompt seed, the convention KDoc, and the
audit record. Out of scope: brief 0003's money vocabulary (untouched here), any
change to `search_colleges`'s schema or response contract, any change to
`update_college_list`'s UUID-only `college_id` contract, and `similar_colleges`
(S4, does not exist). Fit-lens must pass its suite unchanged — it holds a
`CollegeSearchService` and calls `search`, which this RFC does not modify.

## Detailed Design

### The gap, stated once

| surface       | name → id                                                                                                                                    | today       |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| iOS picker    | `AddCollegeViewModel` → `GET /api/v1/colleges?q=` → `CollegeRouteHandler` → `CollegeSearchService.searchByName` → `CollegesDao.searchByName` | works       |
| coach in chat | `search_colleges` has no free-text field; `update_college_list` parses `UUID.fromString` and refuses anything else                           | **no path** |

The prompt does not paper over it either: v10 mentions `update_college_list`
once and gives no name-lookup instruction.

### `FindCollegeTool` (`:college`)

New file `college/src/main/kotlin/ed/unicoach/college/FindCollegeTool.kt`, built
as `FindCollegeTool(service: CollegeSearchService)`.

```json
{
  "name": "find_college",
  "description": "...",
  "input_schema": {
    "type": "object",
    "properties": {
      "name": {
        "type": "string",
        "description": "The school as the student said it ..."
      },
      "limit": {
        "type": "integer",
        "description": "Maximum matches; clamped to 1..25. Defaults to 5."
      }
    },
    "required": ["name"]
  }
}
```

`execute` is total, and rejects unknown fields the way `CollegeSearchTool` does
(`KNOWN_FIELDS = {"name", "limit"}`), so a model that writes a filter word here
is told to use the other tool rather than being quietly ignored. The success
shape:

```json
{
  "colleges": [{ "college_id": "…", "name": "…", "city": "…", "state": "…" }],
  "count": 1
}
```

`college_id` is the key name `update_college_list` and the cost tool already
name in their own schemas ("the `college_id` field of a college search result,
copied verbatim"), so the id travels between tools under one word.

The description states the division of labour in the tool contract itself, not
only in the prompt: use this when the student names a school in words; use
`search_colleges` when they describe the KIND of school they want; the returned
`college_id` is what every other college tool takes.

The failure map (D-C): `SearchIndexNotBuiltException` →
`errorObject(CollegeSearchTool.INDEX_NOT_BUILT)`; anything else → the
`search_failed` error object `CollegeSearchTool` already emits —
`{"error": {"kind": "search_failed", "category": …, "transient": …, "detail": …}}`.
There is no `retryable` key anywhere in this codebase's tool errors.

**The envelope has one home (tier-1 review).** It was first written as a
byte-for-byte copy in each tool with a comment saying the two must fail
identically — a comment with nothing enforcing it, which is exactly the kind of
agreement that survives until the first edit to one side. Both the error-object
builders and the unknown-field refusal sentence now live in one internal file,
`college/src/main/kotlin/ed/unicoach/college/ToolErrors.kt`, and both `:college`
tools call it, so "the two tools fail identically" is structural. This widens
the RFC's original "`CollegeSearchTool` not modified" promise to its call sites
only: the JSON it emits is unchanged, and its 44 existing tests are the proof.
`:service`'s own equivalents on `StudentScopedChatTool` are deliberately left
alone — merging across modules is a separate decision.

One further review outcome sits beside it: a query longer than
`MAX_QUERY_LENGTH` is a rejected INPUT, not a failed SEARCH, so `find_college`
renders the service's typed `QueryTooLongException` as the flat
`{"error": "<sentence>"}` a malformed field gets. Reporting it as
`search_failed`/`permanent` would tell the coach the search is down when the
only fault is the words it wrote. The service still owns the length rule (D-C);
the tool only chooses the shape, and words it from the rejection's numbers.

`execute` reads as three same-level steps — `parseRequest` → `searchByName` →
`matchesObject` — with the three-branch failure map one level down in
`failureObject`, the shape the sibling `CollegeSearchTool.parseQuery` already
set. That failure branch is also the ONE place a failed name lookup is recorded:
it logs the name, the limit and the throwable, because the model-facing envelope
carries none of them and the coaching loop logs a tool's input only when the
tool THROWS, which this total `execute` never does. The envelope itself is
unchanged.

### Wiring (`:service`, `:rest-server`)

`service/src/main/kotlin/ed/unicoach/coaching/FindCollegeChatTool.kt` — the
verbatim delegate, mirroring `CollegeChatTool`:

```kotlin
class FindCollegeChatTool(private val tool: FindCollegeTool) : ChatTool {
  override val name: String = FindCollegeTool.TOOL_NAME
  override val definition: JsonObject = tool.definition
  override suspend fun execute(input: JsonObject): JsonObject = tool.execute(input)
}
```

`rest-server/.../Application.kt` adds one entry to the existing
`ToolRegistry(listOf(...))`, reusing the `collegeSearchService` already hoisted
there for the search tool and the REST route. Nothing else in the coaching loop
changes. The queue-worker builds its own `CollegeSearchService` for the fit lens
and registers no chat tools; it is untouched.

### The convention note

`CollegeSearchService`'s KDoc header gains the three clauses of D-F.
`CollegesDao`'s file header gains one line pointing at it, so a reader who
arrives at the SQL first is sent to the rule before adding a fourth search
method.

### The audit

Read-only inventory of every call site that reads `colleges`, at
`main@1d94cb9a`. FINDERs must be index-backed; READERs must not move.

| class  | site                                                                                                                                                | verdict                                                                                  |
| ------ | --------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| FINDER | `CollegesDao.search` → `FROM college_search_index`                                                                                                  | index-backed (RFC 150). No change.                                                       |
| FINDER | `CollegesDao.searchByName` → words + `search_text` on the index; `JOIN colleges` for projection only (D60)                                          | index-backed. No change to the SQL.                                                      |
| FINDER | `CollegeSearchService.search` / `.searchByName`                                                                                                     | the one connection + clamp boundary. Gains the convention KDoc; gains a second consumer. |
| FINDER | `CollegeSearchTool` → `service.search` (`search_colleges`)                                                                                          | structured only — **the gap**. Unchanged; the gap is closed beside it.                   |
| FINDER | `FitLensService` → `collegeSearchService.search`                                                                                                    | index-backed. Must pass unchanged.                                                       |
| FINDER | `CollegeRoutes` → `searchByName` (`GET /api/v1/colleges?q=`)                                                                                        | index-backed. No change.                                                                 |
| FINDER | iOS `CollegeListClient.searchColleges`                                                                                                              | HTTP only. The reference fuzzy consumer. No change.                                      |
| BROWSE | `CollegesResource` → `CollegesDao.list`                                                                                                             | stays on `colleges` (D-D).                                                               |
| READER | `findById`, `listByIds`, `listNamesByIds`, `findByIpedsUnitId`, `listVersions`                                                                      | facts by key. No change.                                                                 |
| READER | `StudentCollegeSelection` (behind the cost and admissions tools), `CollegeListService` name echo, `FitSuggestionsDao` / `CdsAdmissionsDao` FK joins | keyed reads. No change.                                                                  |
| INGEST | `upsert`, `updateAliases`, `rebuildNameWords`, `rebuildSearchIndex`, `rankPercentiles`, `CollegeIpedsDao` match set, the loaders                    | write `colleges` / build the index. Out of scope.                                        |

No `similarity(` or `pg_trgm` call survives in main source. `similar_colleges`
does not exist.

### Prompt v12

`0068.seed-coach-system-prompt-v12.sql` follows `0066` exactly: a header comment
stating why the append is correct and what it must preserve byte-identically,
then
`INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v12',
…)`
with the v11 body verbatim and one appended chunk whose first character is the
single joining space. The appended paragraph says, in the prompt's voice:

- When the student names a school in words — a nickname, an abbreviation, a
  misspelling — call `find_college` with those words to get its `college_id`.
- Use that `college_id` verbatim for `update_college_list`, for
  `college_cost_profile` and for the admissions tool. Never construct or guess
  an id, and never ask the student for one.
- When several schools come back, ask which one they mean, using the city and
  state to tell them apart; when one clearly matches, use it.
- `search_colleges` is for the other kind of question — the KIND of school the
  student wants, by subject, place, size, selectivity or price — not for looking
  up a school already named.
- When the lookup says the search is unavailable, say that; never say the school
  does not exist.
- When nothing comes back, say no school by that name was found and offer to try
  the name another way — an empty list is an honest answer, not an error.

Worded positively and naming no retired money term, so the appended span stays
assertable by absence and the served-body guards keep passing.

## Files Modified

| File                                                                       | Change                                                                                                                        |
| -------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `college/src/main/kotlin/ed/unicoach/college/FindCollegeTool.kt`           | new — definition + total `execute` over `searchByName`                                                                        |
| `college/src/main/kotlin/ed/unicoach/college/ToolErrors.kt`                | new — the one home for the module's tool-error envelope (review outcome)                                                      |
| `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt`         | calls the shared builders; its emitted JSON is byte-identical                                                                 |
| `college/src/main/kotlin/ed/unicoach/college/CollegeSearchService.kt`      | KDoc: the three-clause module convention (D-F); `isIndexNotBuilt`; `QueryTooLongException` + `rejectedInput` (review outcome) |
| `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`                     | file-header KDoc: one-line cross-reference to the convention                                                                  |
| `service/src/main/kotlin/ed/unicoach/coaching/FindCollegeChatTool.kt`      | new — `ChatTool` delegate                                                                                                     |
| `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`              | register `FindCollegeChatTool` in the existing `ToolRegistry`                                                                 |
| `db/schema/0068.seed-coach-system-prompt-v12.sql`                          | new — v11 + one appended name-lookup paragraph (number claimed at commit)                                                     |
| `service/src/main/resources/service.conf`                                  | prompt pin `v12` + comment block                                                                                              |
| `college/src/test/kotlin/ed/unicoach/college/FindCollegeToolTest.kt`       | new — schema, execute, failure mapping, the blank and over-long refusals                                                      |
| `college/src/test/kotlin/ed/unicoach/college/CollegeSearchServiceTest.kt`  | the typed rejection carries the numbers; no other `IllegalArgumentException` is one                                           |
| `service/src/test/kotlin/ed/unicoach/coaching/FindCollegeChatToolTest.kt`  | new — the delegate is verbatim                                                                                                |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingConfigTest.kt`       | pin literal `v12`                                                                                                             |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`  | v12 append test + wording assertions                                                                                          |
| `rfc/154-consumer-sweep-college-search.md`                                 | this RFC                                                                                                                      |
| `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoToolLoopRoutingTest.kt` | the door: one `find_college` tool round through the real registry                                                             |

Not modified: `CollegeSearchTool`'s `KNOWN_FIELDS`, response contract and
emitted JSON, `CollegeQuery`, `CollegesDao`'s SQL, `CollegeListChatTool`,
`api-specs/openapi.yaml`, `AnthropicTestFixtures`, any table or DDL, `bin/*`,
the iOS app.

## Implementation Plan

1. **Verification only.** Re-run the audit greps at the branch tip and confirm
   the table in "The audit" still holds (`FROM|JOIN|INTO|UPDATE colleges`,
   `similarity(|pg_trgm|ILIKE|LIKE '%`, `similar_colleges`). No edit. If a new
   finder has appeared since `1d94cb9a`, it is reported before any code is
   written.
2. **Code.** `FindCollegeTool` + `FindCollegeToolTest`: definition,
   unknown-field refusal, result shape, zero-match, index-not-built, database
   failure. Green.
3. **Code.** `FindCollegeChatTool` + its test; register it in `Application.kt`;
   add the `find_college` round to `ConvoToolLoopRoutingTest` — the door. Green.
4. **Docs only.** The convention KDoc on `CollegeSearchService` and the
   cross-reference line on `CollegesDao`. No behaviour.
5. **Code.** Claim the next free schema number, write
   `00NN.seed-coach-system-prompt-v12.sql`, move the `service.conf` pin, update
   `CoachingConfigTest` and `SystemPromptCatalogTest`. Green.
6. `nix develop -c bin/test` (full suite; let Gradle's cache scope it), then the
   pre-commit gate.

## Tests

**New.**

- `FindCollegeToolTest` (`:college`), mirroring `CollegeSearchToolTest`: the
  definition names `find_college`, requires `name`, and advertises exactly
  `name` and `limit`; an unknown field is refused by name rather than ignored; a
  non-string `name` is a validation error, not a throw; a match list is rendered
  as `college_id` / `name` / `city` / `state` with `count`; a zero-match name is
  `{"colleges": [], "count": 0}` and NOT an error; an unbuilt index returns the
  `INDEX_NOT_BUILT` sentence and never an empty list; a database failure returns
  the `search_failed` error object (permanent, `transient: false`); a blank
  `name` is refused by name and never answered with an empty list; an over-long
  `name` is a rejected input worded for `[name]` and carrying both numbers; the
  description names `search_colleges` as the other tool and `college_id` as what
  the caller carries onward.
- `FindCollegeChatToolTest` (`:service`), mirroring `CollegeChatToolTest`: the
  `ChatTool` name and definition are the wrapped tool's own, unreshaped, and
  `execute` delegates verbatim.
- `SystemPromptCatalogTest` gains a v12 case, the same shape as the existing
  `coach v11 is v10 plus one appended comparison paragraph`: v12 is v11 plus
  exactly one appended paragraph; the v11 body is a byte-identical prefix; RFC
  142's source-jargon sentence and RFC 141's glossary pairs survive; the
  appended span contains none of the retired money terms; the served-body guard
  (every "subtract" preceded by "never ") still passes; the `service.conf` pin
  exists in the catalog. (`0066` did have such a test — this is the same test
  extended, not a new file.)
- `CoachingConfigTest` pins the literal `v12`.

**Chat-level path.** `ConvoToolLoopRoutingTest` (`:rest-server`) already replays
a `tool_use` block through the real registry. That test scripts its own provider
with an inline `tool_use` literal and does not use `AnthropicTestFixtures` at
all, so the new `find_college` block goes in LOCALLY beside the existing
`search_colleges` one, which stays byte-identical; the shared fixtures file is
not touched (a sibling fixture there would be dead code). The new case asserts
the loop dispatches the new tool and that the registry serves it. This is the
acceptance criterion's door: the name goes in, a `college_id` comes back on the
same fuzzy path the picker uses.

**Must stay green, unchanged.**

- `CollegesDaoTest` — 88 tests, including the literal SQL-string assertion that
  the search payload contains `JOIN colleges c ON c.id = i.college_id`. No SQL
  is touched by this RFC, so it must not move.
- `CollegeSearchToolTest` — 44 tests over `KNOWN_FIELDS` and the unknown-field
  refusal. Unchanged is the evidence for D-A.
- `AnthropicTestFixtures` + `ConvoToolLoopRoutingTest`'s existing
  `search_colleges` replay — no rename, no fixture edit.
- `OpenApiCollegeSearchTest` and `CollegeSearchRoutingTest` — the REST name
  search and its pinned schema are untouched.
- `FitLensServiceTest` and `FitLensHandlerTest` — explicit slice acceptance.
- `CollegeListChatToolTest` — the UUID-only `college_id` contract is unchanged;
  the RFC changes how the coach OBTAINS the id, not what the writer accepts.
- `CollegeSearchServiceTest`, `CollegeSearchIndexRebuildTest`,
  `CodebookVocabularyTest`, the ingest suites, and the iOS picker tests.
