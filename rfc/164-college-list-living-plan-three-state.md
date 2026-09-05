# RFC 164 — An omitted `livingPlan` keeps it: three-state college-list updates

Product brief 0007 (`product/0007-explicit-profile-view`), gate 1, decision D9,
approved 2026-09-04. Evidence: `research/living-plan-hazard.md` in that brief.

## Motivation

`PATCH /api/v1/students/me/college-list/{id}` with a body that omits the
`livingPlan` key returns **200 OK** and sets the entry's per-college living-plan
override to `NULL`. The shipped iOS client
(`ios-app/UnicoachiOS/CollegeListModels.swift`,
`struct UpdateCollegeListEntryRequest` with only `version`/`status`/`reasons`)
omits that key on every restatus and every edit-reasons Save. So the shipped app
silently deletes a fact the family stated in chat — no error, no warning, a
version bump, and nothing in the UI that mentions living plans at all.

The KDoc on `UpdateCollegeListEntryRequest.livingPlan` claims the key is
"REQUIRED on the wire". Nothing enforces that. `Serialization.kt` enables
`FAIL_ON_MISSING_CREATOR_PROPERTIES`, but Jackson applies it only to creator
properties it considers required, and a **nullable** Kotlin constructor
parameter is optional by definition. The absent key is materialised as `null`,
indistinguishable from an explicit `null`, and the wholesale write applies
`null` = "clear the override".

The defect is the **wire contract**, not the iOS omission. A contract where the
safest possible client behaviour — say nothing about a field you do not manage —
is the destructive one is inverted. Its own KDoc names the property it fails to
deliver: "clearing stays an act a caller performs rather than one an omitted key
performs for it."

Two places in this repo already got this right, and this RFC makes REST the
third:

- `CollegeListChatTool`'s private `LivingPlanUpdate` (Set / Clear / absent),
  whose comment states the reasoning: "'leave it alone' and 'drop it back to the
  usual plan' are two different writes onto the same nullable column and a bare
  null cannot tell them apart."
- The money profile's `FieldUpdate<T>` (Set / Decline / Clear, absent =
  untouched) with explicit `*Clear` booleans on `UpdateMoneyProfileRequest` and
  a 400 when a value and its `*Clear` flag arrive together.

### The rollout property this RFC is built around

The change must be **non-breaking for the already-shipped iOS build**. That
build omits the key; after this change its Save must _preserve_ the living plan.
Therefore: no `@JsonProperty(required = true)`, and an omitted key is never a
400. An earlier recommendation in `living-plan-hazard.md` proposed exactly that
one-line fix; **D9 supersedes it**, because it converts today's silent
corruption into a hard 400 for every shipped client. No iOS change is required
by this RFC, and none is made.

## Detailed Design

### D1. The wire: omitted keeps, `livingPlanClear` clears

`UpdateCollegeListEntryRequest` gains one field and a default:

```kotlin
val livingPlan: String? = null,
val livingPlanClear: Boolean = false,
```

Three states, read off the body:

| Body                          | Meaning                                |
| ----------------------------- | -------------------------------------- |
| neither key                   | **keep** the stored override           |
| `"livingPlan": "with_family"` | **set** it                             |
| `"livingPlanClear": true`     | **clear** it back to the family's plan |
| both                          | **400**, `FieldError("livingPlan", …)` |

`"livingPlan": null` is treated as "not stated" — identical to omitting the key,
because Jackson cannot tell them apart and this RFC declines to invent a
distinction that the wire cannot carry. A client that means "clear" says so with
the flag. That is the same reading `UpdateMoneyProfileRequest` gives an explicit
`null`, and the mutual-exclusion error is worded to match `MoneyProfileRoutes`
("At most one of …").

This is source-compatible for any client that already sends a value, and
**rescues** the shipped client that sends nothing.

### D2. The vocabulary moves down into the service

`CollegeListService.updateEntry` currently takes
`livingPlan: LivingArrangement?` — a nullable _absolute_: `null` always means
clear. A caller that means "leave it alone" has to read the current value and
echo it back, which is what `CollegeListChatTool.resolveLivingPlan` does today.
That workaround is the same read-then-write shape the chat tool's own comment
warns about, and it exists only because the service has no word for "keep".

Give it one. A new public sealed type in the `collegelist` package:

```kotlin
sealed interface LivingPlanUpdate {
  data class Set(val plan: LivingArrangement) : LivingPlanUpdate
  data object Clear : LivingPlanUpdate
  data object Keep : LivingPlanUpdate
}
```

`updateEntry` takes a non-null `LivingPlanUpdate` and resolves it against the
row it has already read for the OCC check:

```kotlin
livingPlan = when (livingPlan) {
  is LivingPlanUpdate.Set -> livingPlan.plan
  LivingPlanUpdate.Clear -> null
  LivingPlanUpdate.Keep -> existing.livingPlan
}
```

**Non-nullable on purpose.** `FieldUpdate<T>?` spells "keep" as `null`, which is
right there because the field is one member of a many-field update object. Here
the update is a positional argument next to `status` and `reasons`, and every
existing call site passes a nullable `LivingArrangement?` meaning _clear_. A
nullable parameter would let all of them keep compiling while silently flipping
meaning — the exact class of defect this RFC exists to remove. `Keep` as a
distinct object makes the compiler visit every call site.

`Decline` is not part of this vocabulary: an entry override has no "declined"
state in the schema (`college_list_entries.living_plan` is a plain nullable
enum), so reusing `FieldUpdate<T>` would force an unreachable `when` branch at
every use. One narrow type beats one wide type with a dead arm.

### D3. Both callers speak it; the chat tool's echo is deleted

- **REST** (`CollegeListRoutes.handleUpdate`) folds `livingPlan` +
  `livingPlanClear` into one `LivingPlanUpdate`, returning 400 on the pair and
  on an unknown value (existing `mapLivingPlan` refusal wording, unchanged).
  `mapLivingPlan` itself narrows to `(String) -> LivingArrangement`: with three
  states the update path needs a non-null arrangement inside `Set`, and the
  alternative is a `!!`. Absence stops being that helper's business and becomes
  each handler's — `?.let` on create, where absent means "no override", and the
  `when` above on update, where absent means `Keep`. The refusal still has one
  home, so create and update cannot word it two ways.
- **Chat tool**: its private `LivingPlanUpdate` is _replaced_ by the service's —
  the private sealed interface is deleted and `ParsedInput.Ok.livingPlan` holds
  the service type, with `Keep` where it held `null`. `resolveLivingPlan` and
  its echo-the-current-value comment go away; the call site passes
  `parsed.livingPlan` straight through. The add path keeps its own mapping
  (`Set -> plan`, `Keep -> null`, `Clear -> unreachable`, refused in
  `parseInput`) because `addToList` writes a brand-new row and genuinely has
  nothing to keep.

End-to-end chat behaviour is unchanged: the three states resolve to the same
three writes, one layer lower.

### D4. `reasons` and the POST path — checked, and `reasons` must NOT change

Asked as part of D9. The answer is sharper than "a latent hazard we defer":
**`reasons` looks like the same defect and is its exact opposite.**

- **`reasons` on the same PATCH** has the same _shape_ — `reasons: String?` with
  no default, so an omitted key nulls the stored note — but omission there is
  **load-bearing, not accidental**. The shipped iOS client clears the note by
  omitting the key: `CollegeEntryDetailView.normalizedReasons` returns `nil` for
  a whitespace-only field ("the emptied field must be sent as nil"), and
  `UpdateCollegeListEntryRequest` in `CollegeListModels.swift` is a plain
  synthesized `Codable` encoded with a stock `JSONEncoder`, whose synthesized
  `encode(to:)` writes an optional with `encodeIfPresent` — so a `nil` `reasons`
  leaves the key out of the body entirely. Omitted-means-clear IS the Clear
  button.

  So this RFC changes nothing about `reasons`, and the "apply this RFC's shape
  to `reasons` later" recipe an earlier draft recorded is **withdrawn**: a
  `reasonsClear` flag with omitted-means-keep would silently disable clearing on
  every build already in the field — the same class of break this RFC exists to
  avoid, pointed the other way.

  The asymmetry is not an inconsistency, it is the point. `reasons` is the field
  that screen owns, so its silence is a statement; `livingPlan` is a field that
  screen has never heard of, so its silence is not.

- **`POST` / create** (`CreateCollegeListEntryRequest`) already defaults
  `livingPlan` to `null`, and that is correct: a brand-new row has no stored
  override to lose, so "omitted" and "no override" really are the same state.
  Nothing to fix.
- **`addObservationIds` / `observationIds`** are additive (`emptyList()`
  default, link-only) — an omitted key adds nothing and removes nothing. Safe.

### D5. OpenAPI

`UpdateCollegeListEntryRequest` in `api-specs/openapi.yaml` adds
`livingPlanClear` (boolean, default false) and re-words the `livingPlan`
description to state the new contract.

`required` becomes **`[version, status]`** — both `livingPlan` and `reasons`
leave it. `livingPlan` leaves because omitting it is now legal and means keep.
`reasons` leaves because it was never true: nothing enforced it, the server has
always read an omitted `reasons` as `null`, and D4 shows the shipped client
_relies_ on that to clear the note. Publishing "required" for a key the one real
client deliberately omits is the same documented-but-unenforced fiction this RFC
was opened to remove, so the spec is corrected to say what the server does, with
`reasons` carrying a description that spells out omitted = null = cleared.
`OpenApiMoneyProfileTest` asserts the published enum vocabulary only, so it is
unaffected and still passes.

## Files Modified

| File                                                                                   | Change                                                                                   |
| -------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `service/src/main/kotlin/ed/unicoach/coaching/collegelist/LivingPlanUpdate.kt`         | new file: the `LivingPlanUpdate` sealed interface, one type per file as its siblings are |
| `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListService.kt`       | `updateEntry` takes `LivingPlanUpdate` and resolves `Keep`; KDoc rewritten               |
| `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListChatTool.kt`      | private `LivingPlanUpdate` deleted, service type used; `resolveLivingPlan` deleted       |
| `rest-server/src/main/kotlin/ed/unicoach/rest/models/UpdateCollegeListEntryRequest.kt` | `livingPlan` defaulted, `livingPlanClear` added, KDoc rewritten                          |
| `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CollegeListRoutes.kt`            | fold the two keys into one `LivingPlanUpdate`; mutual-exclusion 400                      |
| `api-specs/openapi.yaml`                                                               | D5                                                                                       |
| `service/src/test/kotlin/ed/unicoach/coaching/collegelist/CollegeListServiceTest.kt`   | call sites + a Keep/Clear/Set test                                                       |
| `rest-server/src/test/kotlin/ed/unicoach/rest/CollegeListRoutingTest.kt`               | the regression test, inverted; clear/mutual-exclusion tests                              |

No migration. No schema change. No iOS change.

## Implementation Plan

1. Add `LivingPlanUpdate` beside `CollegeListService`; change `updateEntry`'s
   parameter and resolve against `existing`. Rewrite its KDoc — the current one
   documents the defective contract as if it were the protection.
2. Update `CollegeListChatTool` to the service type; delete `resolveLivingPlan`.
3. Update `UpdateCollegeListEntryRequest` and `CollegeListRoutes.handleUpdate`.
4. Update `api-specs/openapi.yaml`.
5. Tests (below).
6. `nix develop -c bin/format`, then `nix develop -c bin/test check`.

## Tests

`rest-server` — `CollegeListRoutingTest` (raw JSON string bodies; the Kotlin
data class would always emit the key):

- **the regression test, inverted**: a PATCH whose body is
  `{"version":1,"status":"applying","reasons":"…"}` returns 200 and the entry's
  `with_family` override is **still there** afterwards (GET-verified). Same
  name, opposite assertion — it is the defect's own reproduction, kept.
- `{"livingPlanClear": true}` → 200, stored override is `null`.
- `{"livingPlan":"on_campus"}` → 200, stored override is `on_campus`.
- `{"livingPlan":"on_campus","livingPlanClear":true}` → 400 whose `fieldErrors`
  entry has `field == "livingPlan"` — asserted on the parsed field, not on the
  message text, which contains the word anyway and would pass regardless.
- `{"livingPlan": null}` with no `livingPlanClear` → 200 and the stored override
  is **untouched**. D1 says an explicit `null` reads as "not stated"; without
  this case a regression to "explicit null clears" stays green.
- the existing unknown-value 400 test still passes unchanged.
- a PATCH that omits `reasons` still **clears** the note (200, stored `reasons`
  is null). D4's asymmetry is deliberate and load-bearing for the shipped
  client's Clear button, so it gets a guard of its own rather than a paragraph.

`service` — `CollegeListServiceTest`: `updateEntry` with `Keep` leaves a stored
override intact, `Clear` nulls it, `Set` writes the new value; existing call
sites updated (each was `null` = clear, so each becomes
`LivingPlanUpdate.Clear`, preserving what that test asserted).

`service` — `CollegeListChatToolTest`: the existing chat tests cover Set and
Clear and must pass **unchanged**. They do not cover `Keep` — no existing case
updates only the status of an entry that has a stored override — which is
exactly the arm that replaced `resolveLivingPlan`, so one case is added: a chat
update naming a status and no living plan leaves the override in place.

The gate is `nix develop -c bin/test check` on the branch tip.
