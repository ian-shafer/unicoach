# RFC 165 — served vocabularies, and the registry behind them

**Slice:** `profile/01/served-vocabulary` (brief 0007, gate 2 approved
2026-09-05). Substrate for `profile/02/your-details-screen`.

## Problem

Brief 0007 puts the household income band and the state of residence on an
explicit iOS screen. That screen must render two closed vocabularies, and it may
not invent either one:

- **RFC 142 forbids speaking a slug to a user.** REST returns `"48k_to_75k"` and
  nothing else; the spoken form (`"$48,001 to $75,000"`) lives only in
  `IncomeBand.kt:31` and, since RFC 158, in the `income_bands` table. A client
  building a picker today must hard-code the dollar ranges.
- **The residency set is a closed 59-code set that is not published.**
  `MoneyProfileService.parseResidencyState` (`:158`) accepts exactly
  `USPS_STATE_CODES` (`:145-150`); the DB backstop is only `^[A-Z]{2}$`
  (`0046.create-money-profiles.sql:46-47`) and OpenAPI publishes no enum
  (`openapi.yaml:1765-1768`). A client that guesses the set can offer a code the
  server rejects with a 400 the family cannot act on.

Brief 0007 D6: the vocabulary is **served**, not duplicated in the client. The
duplication is already happening — RFC 163 (in flight) added
`ios-app/UnicoachiOS/ResidencyStates.swift` and an `IncomeBand` Swift enum whose
dollar-range copy is a hand transcription. This RFC gives those copies a server
to come from.

And it will keep happening. Living arrangement, dependency status, college-list
status and the search filter codebooks are all closed vocabularies with the same
need. So the durable problem is not "publish two lists" — it is that **each
surface would otherwise invent its own vocabulary shape**, and the client would
grow one adapter per shape.

## Detailed Design

### One endpoint, a registry behind it

    GET /api/v1/vocabularies        200

```json
{
  "version": "b3f1c2d4e5a60718",
  "vocabularies": {
    "income_bands": {
      "entries": [
        { "value": "under_30k", "label": "$0 to $30,000" },
        { "value": "30k_to_48k", "label": "$30,001 to $48,000" },
        { "value": "48k_to_75k", "label": "$48,001 to $75,000" },
        { "value": "75k_to_110k", "label": "$75,001 to $110,000" },
        { "value": "over_110k", "label": "$110,000 or more" }
      ]
    },
    "residency_states": {
      "entries": [
        { "value": "AL", "label": "Alabama", "jurisdictionKind": "state" },
        {
          "value": "AS",
          "label": "American Samoa",
          "jurisdictionKind": "territory"
        }
      ]
    }
  }
}
```

**Every entry is `value` + `label`.** Those two keys are universal and required:
`value` is what the write path accepts, `label` is what a person reads. A
vocabulary may declare **extra** keys (`jurisdictionKind`), never fewer. This is
the whole point of the endpoint's shape — one client adapter for all present and
future vocabularies, rather than `{slug,label}` here, `{code,name}` there.

**Array order is display order.** The client sorts nothing; ordering is a
product decision and belongs on the server.

**A new top-level path, not an extension of the money-profile `GET`.** The
payload is identical for every student and for a student with no profile at all;
hanging it off `/api/v1/students/me/money-profile` would make a per-student
resource carry non-per-student data, and `profile/02` needs the picker
vocabulary in exactly the case where that resource 404s (brief 0007 D4).

**Auth: session required, no student-profile gate.**
`resolveUser() ?:
respondUnauthorized()` and nothing more — the
`CollegeRoutes.kt:22-27,44` precedent, whose reasoning transfers verbatim:
reading a published vocabulary is not an operation on your money profile. It
stays under the session umbrella rather than becoming public, so it needs no
`ClientKeyGate` allowlist entry (`rest-server.conf:57`).

**`version` is a content hash of the served document; there are no HTTP caching
headers.** A client that has already rendered a picker can compare one short
string instead of diffing every list it holds. It is deliberately not an ETag:
the tree sets no cache header on any GET (`ConvoRoutes.kt:334` is the lone
`no-store`, on the SSE stream), and inventing a caching convention inside a
substrate slice is how conventions get invented badly. The field is a hash, so
adding `If-None-Match` later is a route change and not a contract change.

### What may be registered, and what may not

A vocabulary belongs in this document when it is **closed, small,
user-independent and display-facing**. All four:

- _closed_ — a fixed set the write path validates against, not an open catalog;
- _small_ — tens of entries, because this is one bootstrap fetch;
- _user-independent_ — the same bytes for every caller, or it is profile data;
- _display-facing with a real label_ — a slug-only list is not a vocabulary a
  user can read (RFC 142), and would ship the very problem this route removes.

That admits living arrangement, dependency status and college-list status later;
it excludes the subjects taxonomy and the college catalog, which are large or
open.

**Only vocabularies with a consumer are registered.** This RFC registers exactly
two, because `profile/02` reads exactly two. Serving nine lists nobody reads is
speculative payload the API then owes compatibility on. Adding a third later is
one registration, no new route and no client change — which is the property this
shape is bought for.

### The registry

```kotlin
/** One published vocabulary: a stable name, and its entries in display order. */
data class Vocabulary(val name: String, val entries: List<VocabularyEntry>)

/** `value` is what the server accepts; `label` is what a person reads. */
data class VocabularyEntry(val value: String, val label: String, val extras: Map<String, String> = emptyMap())

/** The whole published document: the registry's vocabularies and the hash of exactly those bytes. */
data class PublishedVocabularies(val version: String, val vocabularies: List<Vocabulary>)
```

`VocabularyService.publishedVocabularies(): Result<PublishedVocabularies>`
assembles the registered vocabularies and hashes them. The registry is a list of
suppliers in one place, so "what does this server publish" is answerable by
reading one declaration.

The version travels **with** the vocabularies rather than being computed at the
route, so `version` is by construction the hash of the bytes actually served: a
route that hashed separately could publish one and serve the other.

### Where each vocabulary's content comes from

The acceptance criterion is that a value the client can pick can never be a
value the server rejects. The design gets that **by construction**, not by
hoping two lists agree:

| Served field                        | Source                                     |
| ----------------------------------- | ------------------------------------------ |
| income band `value`, `label`, order | `IncomeBand.entries` — `value`, `bracket`  |
| residency `value` (the set)         | `MoneyProfileService.USPS_STATE_CODES`     |
| residency `label`, `kind`, order    | `us_states` rows (RFC 150, migration 0060) |

The income-band half needs no DB read: `IncomeBand.bracket` **is** the dollar
range in words, and `MoneyVocabularyLoader.kt:350-354` already asserts
byte-equality between it and `income_bands.bracket_label` at load time, so the
enum and the table cannot drift. Serving `min_usd` / `max_usd` as numbers on top
of the label was rejected — a second representation of the same range is
something the client can reformat, which is the copy D6 exists to prevent.

The residency half reads labels from `us_states` because that is the only place
they exist (`0060.create-codebook-reference-tables.sql:92-103`) — but the
**set** served is the validator's set, enriched by lookup. `jurisdictionKind` is
served because that migration's own header says it must be: "a UI that says
'state' about Palau is wrong". A picker that groups its 59 rows needs the server
to say which are states.

**A code with no `us_states` row is a fault, not a filtered row.** The read
fails loudly (500 via `StatusPages`) rather than serving 58 states: silently
dropping a value the write path accepts is exactly the divergence this endpoint
removes. The condition is unreachable in a migrated database and is pinned by a
test.

### Code shape

- New `VocabularyService` in `service/`, holding the registry, the hash, and the
  two suppliers. It is the one place a future vocabulary is registered.
- `MoneyProfileService.USPS_STATE_CODES` becomes readable — it is the served
  set, and the service that owns the validation rule owns publishing it.
- `CodebooksDao` gains `usStates(session): Result<List<UsState>>` (code, name,
  jurisdiction kind, ordered by name) beside the codes-only `usStateCodes`
  (`:394`), which stays as RFC 150's filter vocabulary.
- New `VocabularyRouteHandler` registered in `Routing.kt` beside the other
  eight, mapping to Jackson response models (`plugins/Serialization.kt:14-33` —
  plain data classes, not `@Serializable`). `extras` is flattened into the entry
  object by the response mapper, so the wire shows `jurisdictionKind` at the
  entry's top level rather than nested.

## Files Modified

| File                                                                               | Change                                      |
| ---------------------------------------------------------------------------------- | ------------------------------------------- |
| `db/src/main/kotlin/ed/unicoach/db/dao/CodebooksDao.kt`                            | `usStates` read + `UsState` row type        |
| `service/src/main/kotlin/ed/unicoach/vocabulary/VocabularyService.kt`              | new — registry, entries, version hash       |
| `service/src/main/kotlin/ed/unicoach/coaching/moneyprofile/MoneyProfileService.kt` | expose the accepted residency set           |
| `rest-server/src/main/kotlin/ed/unicoach/rest/routing/VocabularyRoutes.kt`         | new route handler                           |
| `rest-server/src/main/kotlin/ed/unicoach/rest/models/VocabulariesResponse.kt`      | new response models                         |
| `rest-server/src/main/kotlin/ed/unicoach/rest/{Routing,Application}.kt`            | construct + register                        |
| `api-specs/openapi.yaml`                                                           | path, `security`, schemas, published enums  |
| `rest-server/src/test/.../VocabularyRoutingTest.kt`                                | new                                         |
| `rest-server/src/test/.../models/OpenApiVocabulariesTest.kt`                       | new                                         |
| `service/src/test/.../VocabularyServiceTest.kt`                                    | new — agreement + conformance               |
| `rest-server/src/test/.../models/OpenApiSpec.kt`                                   | `schema`/`operation`/`enumValues` accessors |
| `rest-server/src/test/.../models/OpenApiMoneyProfileTest.kt`                       | use the shared `enumValues` accessor        |
| `rest-server/src/test/.../models/OpenApiSubscriptionStatusTest.kt`                 | use the shared `enumValues` accessor        |
| `db/src/testFixtures/.../CodebookReferenceFixture.kt`                              | `seedOtherJurisdictions` + shared inserts   |

No migration. No new table. Nothing reaches the DDL gate (brief 0007 G4).

## Implementation Plan

1. `CodebooksDao.usStates` + `UsState`, ordered by `name`.
2. `Vocabulary` / `VocabularyEntry` / `VocabularyService` with the two suppliers
   and the content hash.
3. Response models and `VocabularyRouteHandler`; construct in `Application.kt`,
   register in `Routing.kt`, `rejectUnsupportedMethods(HttpMethod.Get)`.
4. OpenAPI path + schemas, income-band `enum` in declaration order.
5. Tests (below). `nix develop -c bin/test`.

## Tests

- **Registry conformance, over every registered vocabulary** — the test that
  stops shape drift as the registry grows: non-empty, unique `value`s, no blank
  `value` or `label`, a stable declared order, and a `snake_case` vocabulary
  name. It iterates the registry, so a future vocabulary is covered the day it
  is registered rather than the day someone remembers.
- **The served income-band entries equal `IncomeBand.entries` in order**, value
  and label, so the picker's words are the enum's words.
- **The served residency set equals the set `parseResidencyState` accepts**,
  both directions: every served value parses, and every accepted code is served.
  This is the slice's acceptance criterion, tested as an equality and not a spot
  check.
- **Every served residency entry carries a non-blank label**, and a code missing
  from `us_states` fails the read rather than shrinking the list.
- **`version` changes when content changes and is stable when it does not.**
- **Routing**: 200 body shape with a session cookie; **401** without one; **200
  for a user with no student profile** (the no-student-gate decision, asserted
  so a later refactor cannot quietly add the gate); unsupported method rejected.
- **OpenAPI**: the published income-band `enum` equals the Kotlin wire strings
  in declaration order (`OpenApiMoneyProfileTest` idiom), the published
  jurisdiction kinds equal `JurisdictionKind`'s wire strings, and the response
  schemas require the properties the route always sends.
