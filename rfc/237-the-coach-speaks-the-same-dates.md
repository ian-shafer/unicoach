# RFC 237 — `fafsa_deadlines`: the coach speaks the same FAFSA dates

Slice `fafsa/05/the-coach-speaks-the-same-dates` (brief 0012). Lane A.

The coach's door onto `FafsaDeadlineService` (RFC 230), which has had no caller
since it landed. A family asking "when is my FAFSA due?" in chat gets the dates
we actually hold for their own state, with the publisher, that publisher's own
page and the day a human read it — and never a date from the model's memory. The
public `/fafsa` page (`fafsa/04`) will render the same service, so the two doors
cannot tell one family two different stories.

The door: the coach chat, in the first session, with no profile, no college list
and no account state set up beyond being signed in.

## Detailed Design

One new chat tool, one new coach-prompt paragraph, one registration line. No
table, no migration, no new read path, no config.

### `FafsaDeadlineChatTool` — `:service`, `ed.unicoach.coaching.aid`

Beside `FafsaProcessChatTool` (RFC 216), and modelled on
`AdmissionTestTimelineChatTool` (RFC 197), which is the landed precedent for a
tool that speaks dates: every date an ISO string, every optional key present and
explicitly `null`, a refusal object that carries no date at all, and gaps stated
as OUR gaps rather than as claims about the world.

```kotlin
class FafsaDeadlineChatTool(
  service: FafsaDeadlineService,
  moneyProfiles: MoneyProfileService,
  private val readDeadlines: suspend (ZoneId) -> Result<FafsaDeadlines> = { zone -> service.get(zone) },
  private val readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult> = moneyProfiles::getForStudent,
) : StudentScopedChatTool()
```

`TOOL_NAME = "fafsa_deadlines"`. Student-scoped, because the answer is per-state
and the state we hold is the family's residency answer in `money_profiles`.
`readProfile` is the same function-typed seam `ResidencyScopedChatTool` uses,
for the one branch a Postgres-backed test cannot ask for on demand: a profile
read that FAILS must still serve the federal dates, not turn a working answer
into an error. `readDeadlines` is that same seam over the DEADLINE read, for the
same reason: a concrete service on a real database cannot be faulted on demand,
and that branch decides whether a database fault reaches a family as an error or
as a refusal claiming we hold no deadlines at all.

The service is constructed with the coach's own zone, not the device's:
`service.get(COACH_ZONE)`. `CoachClock` is the one home for "what day it is for
the coach" (RFC 186 D15, `America/New_York`), and the payload names it as
`as_of_zone` exactly as the timeline tool does. RFC 230's `FALLBACK_ZONE`
(`America/Los_Angeles`) is the WEB door's answer to "the device did not say";
the coach door has a real answer and passes it.

### The state comes from the profile, and from nowhere else

The tool takes **no arguments** (`noArgumentToolDefinition`, the `fafsa_process`
shape). The family's state is read from `money_profiles`, through the same
`readProfile` seam the search tools use, and its rule is kept verbatim: the
state "comes from `money_profiles` and must not be something the model can
contradict".

Ian settled this at the approval gate: there are exactly two ways a state gets
into unicoach — the family tells the coach and the coach RECORDS it with
`update_money_profile`, or the family edits their details screen. A state named
only in one chat turn, held in a tool argument, is a fact unicoach never learns:
the next answer, the cost report and the page would all still not know it. Until
a state is on file we say nothing about which state they live in.

So the four residency arms map straight onto the answer, and none of them fails
the tool (`guided, not gated`):

| Residency                     | What is served                   | `state_gap`     |
| ----------------------------- | -------------------------------- | --------------- |
| on file                       | federal rows + that state's rows | `null`          |
| never asked                   | federal rows only                | `"not_asked"`   |
| declined                      | federal rows only                | `"declined"`    |
| profile unreadable/corrupt    | federal rows only                | `"unavailable"` |
| on file, but not one we serve | federal rows only                | `"not_served"`  |

**Value before ask.** The federal dates — when the form opens, the federal
deadline, and the last day to correct a filed form — bind every family and are
served before anything is asked for. The ask that follows is the ordinary
residency offer: the coach asks what state the family lives in, records it with
`update_money_profile`, and then answers the state half. It says what the answer
unlocks, which is the landed shape of the residency offer at prompt line 27.

That is a deliberate difference from the admission-test timeline paragraph,
which says "where we do not know what state they live in, do not ask for it
here" (line 100): there the state is a side ingredient of a testing answer, and
here it IS the question the family asked. The offer is still an offer — a family
that declines gets the federal dates and a labelled gap, for ever, and nothing
is locked behind answering.

Any input key at all is refused by `unknownFieldsReason(input, emptySet())`, the
landed closed-schema shape — including a `state` the model may try to pass,
which is the point: there is no argument for it to contradict the profile with.

### The payload

```jsonc
{
  "as_of": "2026-09-24",
  "as_of_zone": "Eastern Time",
  "filing_year": "2026-27",
  "state": "CA",                  // or null
  "state_name": "California",     // or null
  "state_gap": null,              // see below
  "federal_deadlines": [ ... ],
  "federal_events_absent": [],       // the federal events we hold no live row for
  "state_deadlines": [ ... ],
  "source_note": "..."
}
```

Each deadline row, federal and state alike, is the service's row with nothing
added and nothing dropped:

```jsonc
{
  "award_year": "2026-27", // every date names its cycle (D23)
  "award_year_status": "other_cycle", // "current" | "other_cycle"
  "award_year_statement": "This date belongs to the 2026-27 cycle, not ...", // null when "current"
  "program": "Cal Grant",
  "event": "priority_deadline",
  "kind": "on_date", // "on_date" | "in_words" | "none_published"
  "date": "2027-03-02", // null unless kind is on_date
  "as_published": "March 2, 2027, 11:59 p.m. Pacific",
  "publisher": "California Student Aid Commission",
  "source_url": "https://...",
  "archive_url": null,
  "checked_on": "2026-09-14"
}
```

Six rules the renderer obeys, each from a landed decision:

- **Every live row is served, never the earliest one.** A state's extra
  application is a separate ROW with its own `program` and the publisher's own
  words (D14; RFC 223 seeds Tennessee as `Tennessee Promise` and
  `Tennessee Promise (state application)`, and New Jersey as an
  `NJFAMS state
  record`). Any `firstOrNull()` or "the deadline" collapse would
  tell a family half the work. There is no typed extra-application flag in the
  data, and this RFC does not invent one.
- **`as_published` rides on every row, including a dated one**, and a row whose
  kind is `in_words` has no `date` — the coach speaks the words and invents
  nothing (`kind` is what the model branches on, `date` is never a silent null).
- **The citation is per row** — publisher, that publisher's own page, the
  archived address where the agency blocks every client we have, and
  `checked_on`, the day a human read it.
- **A cycle that is not the one being filed is MARKED, never served bare.** The
  live set is the newest cycle PER JURISDICTION (D23), so one answer routinely
  carries rows from two cycles, in either direction: an earlier one nobody has
  re-read, or a later one a publisher posted early. Each row therefore carries
  `award_year_status` plus, when it is not `current`, the `award_year_statement`
  sentence that says which cycle it is and where to ask for theirs — the landed
  `FederalAidPolicyChatTool` code-plus-statement convention. Two bare cycle
  labels would leave the model to compare them and decide for itself.
- **A federal event we hold no live row for is NAMED**, in
  `federal_events_absent`. Empty is the normal answer. The list is an ALLOWLIST:
  one exhaustive `when` over `AidDeadlineEvent` classifies each member as
  federally issued or not (a priority deadline is a state instrument), so a
  fifth event fails the tool's own compile rather than defaulting into the
  federal half and being announced for ever as a federal date we do not hold —
  and "when is the FAFSA due" is never answered by a silently short array.
- **`event` and `kind` ride as their stored codes plus the publisher's words**;
  no bare source code reaches the family, which `BareSourceCodeGuard` asserts in
  the tool's test (the `fafsa_process` precedent).

`state_gap` is the wire code of a typed vocabulary, `StateGap`, and never a
claim about the world. The enum owns both the code and the legend sentence that
explains it, and the tool's description is rendered from its entries, so a sixth
gap cannot reach a family unexplained:

| Value            | Meaning                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `null`           | we served that state's rows                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `"not_asked"`    | the family has not been asked which state they live in                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `"declined"`     | they were asked and chose not to say; we do not ask again                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `"unavailable"`  | their profile could not be read                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `"not_verified"` | that state is COVERED by the read and we hold no live row for it, so we hold no deadline for it that a human has verified against its own agency's page (D9). It is said as what the data owns — the list records no attempt, so the payload never claims somebody was blocked by that agency. A code the read accounts for nowhere lands here too, because a gap of OURS is the honest answer when our own two lists cannot place the state a family gave us                                                       |
| `"not_served"`   | on file, and OUTSIDE what the read covers at all (D13). TESTED, never inferred: the read must first have accounted for all `SERVED_JURISDICTIONS` (51) across its served states and its `missingJurisdictions`, and only then is a code it names nowhere a territory. Reached by an `else`, a `us_states` row that went missing would tell a Californian their state is not one of the fifty; an incomplete read is logged and served as `not_verified` instead, because that is OUR gap and not a claim about them |

A verified absence is NOT a gap: a state that publishes no deadline is a ROW,
`kind = "none_published"`, with the citation that proves we checked. `state_gap`
never says a state publishes none, and the tool's description says so, because
that is the sentence the model would otherwise supply.

### The refusal

`FafsaDeadlines.NoDeadlines` carries only the cycles we hold and one of three
reasons, and the door writes the sentence (RFC 230). The refusal object carries
NO date — not even today — following the timeline tool: a refusal exists to
withhold dates.

```jsonc
{
  "no_deadlines": true,
  "reason": "expired", // "empty" | "superseded_cycle" | "expired"
  "held_award_years": ["2026-27"], // [] for empty
  "refusal": "<one of the three sentences below>"
}
```

- `empty` — "We do not hold FAFSA filing deadlines at all, so I will not name
  one from memory."
- `superseded_cycle` — "Every FAFSA deadline we hold belongs to an earlier
  filing cycle, so it is not the one you would be filing under, and I will not
  repeat last cycle's date as if it were this cycle's."
- `expired` — "Our FAFSA deadlines are out of date: too long has passed since a
  human last checked them against the publisher, so I will not repeat them."

Each is followed by the same second sentence, which is what the family should do
instead: file at `studentaid.gov`, which is free, and ask their own state's
student aid agency for the state deadline. The refusal cannot name that agency —
with no rows there is no publisher to name — and it says so rather than
inventing one.

A service `Result.failure` is a different thing again and stays the landed
shape: log it and return `errorObject("FAFSA deadline read failed")`.

### The coach prompt

One new paragraph in `prompts/coach-system-prompt.md`, one sentence per line,
placed immediately after the `fafsa_process` paragraph (RFC 216, lines 208-215
today) and before the share nudge — the same seam RFC 216 used, so the two FAFSA
paragraphs sit together. It says, in the landed shapes:

- route a FAFSA deadline, filing-open or corrections question to the
  `fafsa_deadlines` tool, and **never name a FAFSA date from memory** (the
  `admission_test_timeline` sentence at line 71 is the model);
- speak the publisher's own words when the tool gives words and no date, and
  never turn a rule into a date;
- say the cycle the date belongs to, and the day we checked it, with the date;
- when a row's cycle is not the one being filed, never give that date as their
  deadline: speak the row's own `award_year_statement` and send them to that
  publisher for theirs;
- when the tool lists a federal event in `federal_events_absent`, say plainly
  that we do not hold that date and send them to `studentaid.gov` for it;
- when the tool says which state it answered for, say so — and when it names a
  gap beside it, say whose gap it is, and never that the state publishes no
  deadline;
- when `state_gap` is `not_verified`, say the gap is ours and send them to that
  state's own agency — the prompt says nothing about anybody reading a page,
  because the data records no attempt;
- when the tool refuses, say what the refusal says and add nothing to it: the
  prompt carries no count of the reasons and restates none of them, because
  `NoDeadlinesReason` owns both and the `refusal` sentence is already on the
  wire;
- when `state_gap` is `not_asked`, give the federal dates first, then ask what
  state the family lives in and RECORD it with `update_money_profile` — saying
  what it unlocks, which is their own state's deadline — and never carry a state
  that was said in chat but not recorded;
- when `state_gap` is `declined`, give the federal dates, say plainly that we do
  not hold their state, and do not ask again;
- when `state_gap` is `unavailable`, give the federal dates and say that we
  cannot SEE their state right now — never that we do not hold it, because a
  failed read is no evidence about the family's record, which is the one
  sentence the legend forbids on this arm — and let the state half wait rather
  than asking again in this conversation;
- when `state_gap` is `not_served`, give the federal dates, say plainly that the
  state deadlines we hold are the fifty states' and DC's (D13), and point them
  at their own jurisdiction's student aid agency — never at the `not_verified`
  sentence, which would tell a territory family we could not read a page we
  never hold rows for.

The prompt file is the ONLY thing edited by hand. `bin/prompt-seed` cuts the
version label, writes the seed migration, its `db/schema/ORDER` line and the
`systemPromptVersion` pin — this run invokes it after authoring, after any
rebase, and again at land, and writes no version number into any of the three
(RFC 231's rule; the prompt is v43 on this base, so the label this run lands is
whatever the script cuts).

### Registration

One line in `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`'s
`ToolRegistry(listOf(...))`, beside `FafsaProcessChatTool()`:

```kotlin
// RFC 237: the coach speaks the FAFSA dates the page will show.
ed.unicoach.coaching.aid
  .FafsaDeadlineChatTool(
    ed.unicoach.coaching.aid
      .FafsaDeadlineService(database),
    moneyProfileService,
  ),
```

Both names are written out in full because `Application.kt` imports neither and
reaches the whole `ed.unicoach.coaching.aid` package fully-qualified.

**Rollback is unregistering the tool.** Removing that entry removes the door;
nothing else in the product reads the service yet, no migration is involved, and
the prompt paragraph left behind would route to a tool that is not offered — so
a rollback removes the paragraph too and re-runs `bin/prompt-seed`, which is one
commit and no schema change.

### What this slice does NOT do

`fafsa/04/the-fafsa-page` is not built yet, so the slice's "a test proves the
same state renders the same text through both doors" has one door to test today.
What is testable now — and is tested — is that the coach's rows ARE the
service's rows, field by field, with no second read path and no re-derivation.
The page-side half of that equivalence arrives with the page, which renders the
same `FafsaDeadlineService` values. This is reported to Ian rather than quietly
narrowed.

## Files Modified

| File                                                                                                        | Change                                                                                                   |
| ----------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `service/src/main/kotlin/ed/unicoach/coaching/aid/FafsaDeadlineChatTool.kt`                                 | NEW — the tool, its wire-key consts and its description                                                  |
| `service/src/test/kotlin/ed/unicoach/coaching/aid/FafsaDeadlineChatToolTest.kt`                             | NEW — the tool's tests (Postgres-backed)                                                                 |
| `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`                                               | one registration entry                                                                                   |
| `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoToolLoopRoutingTest.kt`                                  | one case: the wired registry offers `fafsa_deadlines`                                                    |
| `prompts/coach-system-prompt.md`                                                                            | one new paragraph after the `fafsa_process` paragraph                                                    |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`                                   | the new paragraph's enum entry + its routing assertions; its three CDS sweeps move onto the shared guard |
| `service/src/test/kotlin/ed/unicoach/coaching/aid/FafsaProcessChatToolTest.kt`                              | drops its own CDS-field-id regex onto `BareSourceCodeGuard`                                              |
| `chat/src/testFixtures/kotlin/ed/unicoach/chat/PayloadDateGuard.kt`                                         | NEW — the shared "this answer names no date of ours" sweep                                               |
| `service/src/main/kotlin/ed/unicoach/coaching/ResidencyOffer.kt`                                            | the shared residency read takes a `caller` and logs it                                                   |
| `service/src/main/kotlin/ed/unicoach/coaching/ResidencyScopedChatTool.kt`                                   | passes its own tool name as that `caller`                                                                |
| `chat/src/testFixtures/kotlin/ed/unicoach/chat/BareSourceCodeGuard.kt`                                      | `CDS_FIELD_ID` + its positive control, shared not re-typed                                               |
| `service/src/test/kotlin/ed/unicoach/coaching/testing/AdmissionTestTimelineChatToolTest.kt`                 | drops its own date regex onto `PayloadDateGuard`                                                         |
| `db/schema/seed-coach-system-prompt-v<n>.sql`, `db/schema/ORDER`, `service/src/main/resources/service.conf` | GENERATED by `bin/prompt-seed`; never hand-written                                                       |
| `db/src/testFixtures/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineFixture.kt`                               | only if the tool test needs a row shape it does not expose                                               |

## Implementation Plan

1. `FafsaDeadlineChatTool` with its definition, a closed no-argument schema, and
   the residency-to-`state_gap` table above. No rendering logic in the service;
   no SQL in the tool.
2. The three refusal sentences and the `state_gap` vocabulary, as consts the
   test imports by name (the `fafsa_process` precedent — a test never retypes a
   wire key).
3. Registration in `Application.kt`.
4. The prompt paragraph, authored one sentence per line, then
   `nix develop -c bin/prompt-seed -b "$BASE_SHA"`.
5. The paragraph's entry in `SystemPromptCatalogTest`'s paragraph enum, so the
   standing copy bans (no "room and board", no "sticker", no "award", no bare
   codebook code) apply to the new copy mechanically, plus the two routing
   assertions the testing-timeline paragraph already has.
6. Full suite through `ship-run`.

## Tests

Postgres-backed, on `FafsaFilingDeadlineFixture` + `CodebookReferenceFixture`,
with an injected `InstantSource` so every cycle and expiry boundary is asked for
on purpose:

1. **Same rows as the service.** For one seeded corpus, the tool's federal and
   state rows equal `FafsaDeadlineService.get(COACH_ZONE)`'s rows field by
   field, including `as_published` verbatim. This is the one-call-path claim.
2. **A known state.** Residency on file → the federal rows plus that state's
   rows, `state_gap` null, each row carrying its publisher, page and
   `checked_on`.
3. **The model cannot name a state.** Any input key, `state` included, is
   refused; the payload still comes from the profile.
4. **Never asked, declined, and unreadable** → three tests, each serving the
   federal rows with its own `state_gap` (`not_asked`, `declined`,
   `unavailable`), no state rows invented and no error.
5. **A rule in words** → `kind = "in_words"`, `date` null, the publisher's
   wording verbatim, and a date sweep (with a positive control) proving the ONLY
   date anywhere in that row is `checked_on`, the day we read the page.
6. **A state with an extra application** (Tennessee's two rows) → both rows,
   both programs. A regression test against collapsing a state to one date.
7. **A state we could not read** (in `missingJurisdictions`) →
   `state_gap = "not_verified"`, no rows, and the payload nowhere says that
   state publishes none.
8. **A verified absence** → a `none_published` ROW with its citation, and
   `state_gap` null: the two are not the same answer.
9. **Three refusals, three sentences.** `empty`, `superseded_cycle` (seed only
   last cycle's rows and turn the clock over — the named failure this slice
   exists to prevent), `expired` (push the clock past `expires_on`), each with
   no date anywhere in the payload, asserted by the date sweep.
10. **A failing profile read still answers.** The `readProfile` seam returns a
    failure → federal rows served, `state_gap = "unavailable"`, no error. A
    residency on file that we do not serve (a territory, D13) →
    `state_gap = "not_served"`.
11. **Closed schema and scope** — any key is refused; an unscoped `execute`
    refuses with the student-scoped message; `BareSourceCodeGuard` finds nothing
    in the payload, with a positive control.
12. **The prompt paragraph** routes to `fafsa_deadlines` by the tool's own
    `TOOL_NAME` const and forbids a date from memory, the testing-timeline
    assertion's shape.
13. **A read that faulted.** The `readDeadlines` seam returns a failure →
    `errorObject("FAFSA deadline read failed")`, and NOT a refusal: the payload
    carries no `no_deadlines` key and no rows, because a database that would not
    answer is no evidence that we hold nothing.
14. **The wired registry offers the tool**, on `ConvoToolLoopRoutingTest`'s
    landed precedent: the tool list the `appModule` advertises on a real turn
    names `fafsa_deadlines`, because a paragraph routing to a tool nobody is
    offered is a door that does not open.
15. **A federal event we hold no live row for is named.** The corpus holds the
    opening day and the federal deadline, so `federal_events_absent` is exactly
    `["corrections_deadline"]`; seed the third and it is empty, which also
    proves a priority deadline is never counted as a federal absence.
16. **A row from another filing cycle is marked.** Seed the federal rows at the
    cycle being filed and one state's at the cycle before: the federal row is
    `award_year_status = "current"` with a null statement, and the state's is
    `"other_cycle"` carrying `otherCycleStatement`'s own sentence.
17. **Every key the legend explains, and no other.** The answer's key set equals
    `LEGEND_KEYS` and a refusal's equals `REFUSAL_KEYS`, the way a row's already
    equals `ROW_KEYS` — a remembered list of keys otherwise explains only the
    keys somebody remembered to add to it.
18. **The date sweep is `PayloadDateGuard`'s**, in `:chat`'s test fixtures
    beside `BareSourceCodeGuard`, so the pattern and its positive control are a
    call rather than a literal each suite re-types.
    `AdmissionTestTimelineChatToolTest` — the suite that had hand-typed it —
    moves onto the guard in the same run, so the fixture's own warrant is true.
19. **The CDS-field-id sweep is the shared guard's too**
    (`BareSourceCodeGuard.CDS_FIELD_ID` + `cdsFieldIdPatternFires()`), for the
    reason that guard already states: a raw-string regex that can never match
    reads as covered. The two suites that had hand-typed it — three copies in
    `SystemPromptCatalogTest` and one in `FafsaProcessChatToolTest` — move onto
    the guard in the same run, each gaining the positive control, so the
    fixture's own warrant is true the way test 18's is.
20. **The legend is derived from `StateGap.entries`**, and the definition case
    asserts BOTH the quoted code and its legend sentence for every entry, so a
    sixth gap cannot ship with the legend silently short.
21. **The read-failure sentence is a const the suite imports**
    (`READ_FAILED_REASON`), and the refusal envelope's key is read from
    `StudentScopedChatTool.ERROR_KEY`, never re-typed — a probe on a renamed
    literal goes vacuous rather than failing.
