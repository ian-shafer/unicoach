# Living-plan hazard: an omitted `livingPlan` key silently clears the override

Verdict: **(b) — silent data loss.** The PATCH is accepted with **200 OK** and
the family's per-college living plan is set to `NULL`.

## Question

`UpdateCollegeListEntryRequest`
(`rest-server/src/main/kotlin/ed/unicoach/rest/models/UpdateCollegeListEntryRequest.kt`)
declares `val livingPlan: String?` with no default. Its KDoc states the key is
REQUIRED on the wire, because `null` clears the override (RFC 152 D2a). The iOS
client struct `UpdateCollegeListEntryRequest` in
`ios-app/UnicoachiOS/CollegeListModels.swift` sends only `version`, `status`,
`reasons` — it omits `livingPlan`. What does the server do?

## Method

One focused test added to the existing rest-server routing test:

- File: `rest-server/src/test/kotlin/ed/unicoach/rest/CollegeListRoutingTest.kt`
- Test:
  `PATCH with a body that OMITS livingPlan entirely (the iOS client's shape)`

It creates an entry with `livingPlan = "with_family"`, then PATCHes with a **raw
JSON string** body (not the Kotlin data class, which would always emit the key),
then re-reads the entry with GET.

Raw body used:

```json
{ "version": 1, "status": "applying", "reasons": "Good fit" }
```

Run:
`nix develop -c bin/test -t "ed.unicoach.rest.CollegeListRoutingTest" rest-server`

## Executed result

- PATCH status: **200 OK**
- PATCH response entry: `"livingPlan" : null`, `"version" : 2`
- GET after PATCH: `livingPlan` is `null` — the stored `"with_family"` override
  is gone.

Test evidence:
`rest-server/build/test-results/test/TEST-ed.unicoach.rest.CollegeListRoutingTest.xml`
reports `tests="16" skipped="0" failures="0" errors="0"`, and the suite contains
the testcase
`PATCH with a body that OMITS livingPlan entirely (the iOS client's shape)()`.
The source file matches: `grep -c "@Test"` returns 17, of which one hit is a
prose mention of `@Test` in a comment on line 121, so 16 real annotations —
equal to the XML count.

## Why it happens

`Serialization.kt` sets `FAIL_ON_MISSING_CREATOR_PROPERTIES = true`, but that
feature only fires for creator properties Jackson considers _required_. A
**nullable** Kotlin constructor parameter is treated as optional, so an absent
key is materialised as `null`. The routing layer cannot tell "absent" from
"explicitly null", and the wholesale write applies `null` = "clear the
override". The KDoc's "REQUIRED on the wire" is a stated intent with **no
enforcement behind it**.

## Impact

Every iOS list-screen Save — restatus or edit-reasons — deletes the per-college
living plan the family stated in chat, with no error, no warning, and a version
bump. RFC 152 added the field after the iOS screen shipped, so the client was
never updated.

## Smallest correct fix

**Both sides. Server first — the server is the one that lost the data.**

1. Server (the real fix): make absence distinguishable from null. Smallest
   correct change is to bind the field as `JsonNullable`/`Optional`-style
   wrapper, or the cheaper equivalent used elsewhere in this codebase: read the
   raw body into a `JsonNode` (or annotate the parameter
   `@JsonProperty(required = true)`) and reject a PATCH whose object has no
   `livingPlan` member with a 400 `FieldError` naming `livingPlan`.
   `@JsonProperty(required = true)` on the constructor parameter is the one-line
   version and makes `FAIL_ON_MISSING_CREATOR_PROPERTIES` actually apply. Note
   this turns today's silent corruption into a hard 400 for the shipped iOS
   build, so it must land together with, or behind, step 2.
2. Client: add `livingPlan` to the Swift `UpdateCollegeListEntryRequest` and
   always send the entry's current value on a restatus / edit-reasons Save, so
   the round-trip preserves it.

If a staged rollout is needed, ship 2 first (stops the bleeding for updated
clients), then 1 (stops it for everyone and makes the invariant enforceable).

## Scope note

No production code was changed. The test above is left uncommitted in the
working tree as evidence.
