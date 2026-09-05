# RFC 163: Onboarding that fits on one screen

Instruction: Ian, 2026-09-05 — "the registration flow is cumbersome... it takes
up a lot of space, one must scroll on top of the selection widgets... we should
explain what we do with each of the inputs... we should collect the state of
residence and parents' income bracket... only name and graduation date are
required."

## Executive Summary

The iOS onboarding screen (RFC 42) asks one question — when do you graduate —
and spends **1613pt**, about 1.9 iPhone viewports, doing it. The year control
alone renders 13 full-height `OptionCard`s: **976pt**, taller than the entire
device screen, and 61% of the form. The precision selector defaults to **Full
Date**, so month and day rows are on screen at first paint even though most
students know only the year. The result is a one-field form that reads as an
endurance test.

This RFC rebuilds that screen as **one short form** — measured at **~930pt**, a
little over a single 874pt viewport (§2) — and, because the space is now there,
uses it to collect the two answers that demonstrably change the numbers we can
show a family. 1.9 screens becomes 1.06: the Create Profile button sits about
30pt below the fold, one flick away, instead of a screen and a half of cards
away. The `ScrollView` is therefore load-bearing at default Dynamic Type, not
only at accessibility sizes. An earlier draft claimed the form fitted outright
with no scrolling; the snapshot corpus says otherwise and the corpus wins.

Four shape choices:

1. **Compact controls, one motif.** The 13 year cards collapse to a single 64pt
   menu row. The private `pickerRow` helper already in `OnboardingView` is
   promoted to a real design-system component, `DSPickerRow`, and Year, Month,
   Day, State and Income all render as the same object. 976pt becomes 64pt.
   Default precision moves from `.full` to `.year`, so the first paint is one
   row, not three.

2. **Every input says what it buys, in the same breath.** This is not new
   product policy — it is RFC 145's `precision_offer` rule ("name what it
   unlocks") applied to a screen instead of a chat turn, with the same honest
   figures the coach already quotes.

3. **Optional means unanswered, not declined — sign-up may not take a permanent
   decision.** (Ian, at the RFC 163 gate.) A user at sign-up has no reason to
   trust us yet; trust is earned later. A control that converts that initial,
   rational reticence into a permanent refusal spends something the user never
   meant to spend. The mechanism makes this concrete: in this codebase
   `declined` is forever — every `precision_offer` keys off
   `AnswerStatus.UNANSWERED` precisely so a declined topic is never reopened. So
   onboarding only ever writes values, and **never** sends `incomeBandDeclined`,
   `residencyDeclined`, or any `*Clear`. Skipping is silent, costs nothing, and
   leaves the door open. "Prefer not to say" stays a chat action, at a point
   where the user knows what they are turning down and has met the thing doing
   the asking.

4. **No district field yet — but the idea is sound.** A locality answer would be
   genuinely valuable: community colleges publish a three-tier price —
   **in-district / in-state / out-of-state** (IPEDS `IC_AY` `TUITION1/2/3`) —
   and College Scorecard collapses the in-district tier away, so a family inside
   the taxing district is quoted the wrong, higher number today. That is a real
   answer we cannot currently give.

   It is deferred on **sequencing and shape**, not on merit. Sequencing: we do
   not yet store an in-district price for a locality to be compared against;
   that arrives with slice `shape/02/ipeds-ic-ay`. Collecting the input before
   the figure exists buys a family nothing, and asks them to hand over their
   locality for no visible return — the opposite of this screen's bargain.
   Shape: eligibility is defined by the _college's_ own service or taxing
   district, so the usable key is **ZIP or county**, a value that can be tested
   against a college's district. A free-text school-district name cannot be
   computed with at all. When `shape/02` lands, this returns as a ZIP question
   with a number attached to it. See §6.

Nothing about what is _required_ changes: name (registration) and graduation
date (onboarding) were already the only required inputs, and remain so. No
screen is gated on the new fields.

## Detailed Design

### 1. `DSPickerRow` — the one compact control

`OnboardingView.pickerRow` (a private helper, the app's only
`.pickerStyle(.menu)` site) moves into `DesignSystem/Components.swift` as a
public component:

```swift
struct DSPickerRow<Content: View>: View {
    init(
        label: String,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        @ViewBuilder picker: () -> Content
    )
}
```

There is no `SelectionValue` parameter: the `Picker` and its binding live inside
the caller's `@ViewBuilder`, so the row never names the selected type.

Two convenience initializers sit beside it and are what the screen actually
calls — `options:`/`selection:`/`title:` for a required selection, and the same
with a `Binding<Value?>` and an `unansweredTitle` (defaulted to `"Select"`) for
an optional one. Without them each row is a hand-written `Picker` + `ForEach` +
`.tag`, and a `.tag` whose type does not match its binding **silently binds
nothing at runtime**: the row simply never changes, and no view-model test can
see it. Written once, the tags cannot be wrong. The `@ViewBuilder` form stays
for anything these two do not cover.

It keeps the shape it already has — leading `dsLabel` caption, `Spacer`,
trailing tinted menu, `DSControl.height` (64), `DSRadius.control`,
`dsFieldBorder` stroke, `dsSurface` fill — so it is visually the same object as
`LabeledField` and `OptionCard`, and reads tokens only (DESIGN.md §0/§4).

That chrome is now a modifier rather than a fourth copy of it. `LabeledField`,
`OptionCard`, `SegmentedSelector` and `DSPickerRow` are the same 64pt outlined
box, and all four had written out the same frame / background / clip / stroke —
which is how a radius or a border width comes to change on three controls out of
four. `View.dsControlBox(minHeight:borderColor:alignment:)` owns it;
`borderColor` is a parameter because it is the part that legitimately varies (a
field's border turns `dsError`, a selected card's darkens), and padding stays at
the call site, as it already does for `dsOutlinedCard`. The corpus is the proof
this was a refactor: captured before and after with `bin/snapshot-ios -b`, drift
nil.

The height is applied as a `@ScaledMetric` minimum rather than the fixed frame
the private helper used, which is what DESIGN.md §3 requires of every control:
at default Dynamic Type it is the same 64pt, and at larger sizes the box grows
instead of clipping. That growth is **observed**, not asserted — the
`onboarding-accessibility-type` scene (§Tests) captures the screen at an
accessibility text size.

It scales `relativeTo: .body`, which is this row's own tallest text (the
picker's selected value, set in `dsBody`). Each 64pt control in this app scales
against the style of its **own** content rather than one shared style —
`LabeledField` `.body`, `OptionCard` `.title3` for its `dsOption` label,
`SegmentedSelector` `.subheadline` for its `dsLabel` titles, `LoadingButton`
`.headline` for `dsButton` — so that every box grows at the rate the text inside
it grows.

**On losing `OptionCard` here.** DESIGN.md calls `OptionCard` the reference's
signature control, and this screen is where it was introduced. Trading it away
on this one screen is a deliberate visual-identity decision, stated here rather
than buried: a control that costs 976pt to offer 13 mutually exclusive values is
the wrong instrument, and `OptionCard` remains the signature control everywhere
it is actually apt (`AddCollegeView`, the fit and money surfaces).
`SegmentedSelector` keeps the precision choice, so a card-shaped selection is
still the first thing on the screen.

### 2. The screen

```
BrandTopBar
  Welcome, <name>                            (overline)
  StepIndicator(3, current: 1)

  When do you graduate?                      (dsDisplay)
  [ Year | Year & Month | Full date ]        SegmentedSelector, default .year
  [ Year                        2028 ⌄ ]     DSPickerRow
  [ Month                       June ⌄ ]     at .yearMonth and .full
  [ Day                           15 ⌄ ]     at .full only
  Sets your application deadlines and the award years I
  price against.                              (dsCaption, secondary)

  Optional — you can add these any time       (dsOverline)
  Answer now or later; just ask me in chat. I'll work without them.

  [ State of residence      California ⌄ ]   DSPickerRow
  Public colleges publish two prices. Knowing your state lets
  me show the one you'd actually pay — a median $6,300 a year
  difference.                                 (dsCaption)

  [ Household income   $48,001 to $75,000 ⌄ ] DSPickerRow
  Net price varies a lot by income. With a bracket I can show
  your family's figure instead of the all-family average —
  about $1,376 a year for a middle bracket.   (dsCaption)

  [ Create Profile ]                          LoadingButton
```

**Measured height, not estimated.** At default Dynamic Type the year-only first
paint is **~930pt** of content (the Create Profile button's bottom edge lands at
905.5pt, plus the 24pt bottom padding), measured off the `onboarding-year` scene
rendered tall enough to show the whole form; the scene ships on the pinned
402×874pt iPhone 17 Pro canvas, which is what makes it the evidence for how much
fits. The full-date shape, with two more rows, is ~1082pt. So the button sits
about 30pt below the fold: this is **one short screen with a flick at the end**,
not the 1613pt — 1.9 viewports — the screen cost before. It is 1.06 screens.

An earlier draft of this section claimed ~620pt against ~745pt of viewport and
concluded the form fitted outright. The corpus says otherwise, and the corpus
wins: a `dsDisplay` heading wraps to two lines, and three captions and an
overline pair are each real height. The prose is corrected here rather than the
screen being squeezed to make an estimate true.

Three things were cut to get from the first build (~1000pt) to ~930pt, and no
more. The intro line under the welcome overline is **gone** — the `dsDisplay`
heading already states the task and the optional section says "answer now or
later" better and at the point of use, so a third restatement was two wrapped
lines of viewport. The graduation caption drops its subject ("Sets your
application deadlines…"), because the row above it is labelled Year. And the
vertical rhythm is tighter: the screen stack is `DSSpacing.md` rather than the
`lg` screen margin, and each control is paired with its own caption at
`DSSpacing.sm`, so a caption reads as belonging to the row above it instead of
floating between two.

What was **not** cut: the heading (it is the motif and the thing that orients
the reader), the welcome overline, the step indicator, and both optional
captions — those captions are the feature, not the padding. Shrinking
`dsDisplay` here would buy the last 30pt by breaking the type scale everywhere.

It stays inside a `ScrollView`, which is now load-bearing at default type as
well as at accessibility sizes.

The two figures in the copy are the ones the codebase already stands behind —
RFC 145's median $6,300/yr residency correction and the ~$1,376/yr middle-band
net-price correction. They are stated as what the answer _unlocks_, never as a
demand.

**Default precision `.year`.** A US high-school student reliably knows the year
and often not the month. `.year` is the honest default and the cheapest first
paint; `PartialDate` has accepted `YYYY` since RFC 42, so nothing downstream
changes.

**"Select" is the unanswered row, not a decline.** Each optional picker's first
entry is the word `Select`, which is what the row shows until the student
chooses. It is deliberately colourless: "Prefer not to say" would be the decline
this RFC refuses to offer here (§Executive Summary choice 3), and anything
warmer ("Add later") would editorialise about a choice the student has not made
yet. Selecting nothing writes nothing.

**The date's invariants live on the properties, and its arithmetic is
`Calendar`'s.** Deleting `setYear`/`setMonth` in favour of a picker bound
straight to the state moved a real obligation: those setters had been the only
clamp, and a `@Published var` with no `didSet` accepts month 13 and day 0. So
`year`, `month` and `day` each clamp in their own `didSet` — month into
`monthRange`, day into `dayRange`, at **both** ends, because `2028-06-00` is as
sendable a string as `2028-06-31` was.

The month-length table and leap rule that made those clamps possible are gone
too, replaced by `Calendar.range(of: .day, in: .month, for:)` in the view model
**and** in `RandomFixtures`, which had grown its own second copy. The calendar
is explicitly `Calendar(identifier: .gregorian)`, not `.current`: the wire
format is an ISO-8601 proleptic-Gregorian date, so a device set to the Japanese
or Buddhist calendar must not change what February 2028 is. Month _names_ still
come from the user's own calendar — that is a reading, not arithmetic.

**Year window.** `yearWindowBack` 4 / `yearWindowForward` 8 is retained as-is.
The window was chosen for reach and is now free: 13 items in a menu costs the
same 64pt as 3. Retaining it also keeps this RFC's diff about layout, not about
who may use the product.

### 3. State and income on the wire

Both are optional `nil`-by-default selections. Nothing is sent unless the user
picked something.

**Sequencing is forced by the server.** `PUT
/api/v1/students/me/money-profile`
answers **409 `student_profile_required`** when no student row exists. So
`submit()` is strictly:

1. `POST /api/v1/students` with `expectedHighSchoolGraduationDate`.
   - `201` → continue.
   - `409 student_already_exists` → continue (today's behaviour, preserved).
   - any other error → surface it, do not complete. The date is required.
2. If **and only if** at least one of state / income was chosen,
   `PUT
   /api/v1/students/me/money-profile` with only the chosen keys.
3. `onComplete()`.

**A failed money-profile PUT does not block completion.** The student profile
exists, the required data is captured, and the optional answers are by
construction re-askable — the coach's own `precision_offer` will raise them
again, because their status is still `UNANSWERED`. Failing the screen here would
trade a required outcome for an optional one. The failure is logged at `.error`
and swallowed. This is the one place the design deliberately drops an error, and
it is why the fields must never be _required_ to reach chat.

That log line is then the **only** record the write was attempted, so it carries
what diagnosing it needs without a reproduction: the code, the HTTP status, the
`fieldErrors` — a `validation_failed` here is diagnosable by nothing else — and
the two answers being dropped.

**Never `*Clear`, never `*Declined`.** Onboarding writes `incomeBand` and/or
`residencyState` or omits them. Per §Executive Summary choice 3.

**Skip the PUT entirely when nothing was chosen**, so we do not create a
`money_profiles` row that is all `unanswered` — an empty row and no row are the
same fact, and the absent row is the cheaper one.

### 4. Vocabularies come from the server's own copy

- **Income bands**: the five `IncomeBand` values, labelled with the _exact_
  `bracket` strings the backend already owns ("$0 to $30,000" … "$110,000 or
  more"). One home for the copy; the picker restates the coach's words. The enum
  itself is the **wire** vocabulary (`Models.swift`) and that dollar-range copy
  is an extension at the **UI boundary**, because display copy on a DTO is how a
  wire key ends up read aloud to a family.
- **States**: the picker offers the 50 states + DC. `MoneyProfileService`
  accepts a wider Scorecard `STABBR` set (AS FM GU MH MP PR PW VI); offering
  fewer than the server accepts is safe, and a territory holder can still set it
  in chat. Offering _more_ would be a 400 with no recourse.

  The menu is a `struct ResidencyState` (code + name, `fileprivate` init) and a
  `ResidencyStates.offered` table, in their own
  `ios-app/UnicoachiOS/ResidencyStates.swift` — **not** in `Models.swift`,
  because a reading menu assembled from part of a server vocabulary is not a
  wire contract. The typed value is what the money-profile request takes, so
  `"XX"`, `"ca"` and a ZIP code stop being things this app can send: an
  unrepresentable wrong answer needs no validation.

Closed server vocabularies decode as **raw `String` plus a `known…` accessor**
over a Swift enum, per the existing `PublicSubscription.status` /
`ErrorResponse.code` convention — never a bare `enum: String, Codable`, which
would throw when the server adds a case.

**Two drift guards, because nothing in the build derives these vocabularies.** A
renamed band or a narrowed state set would otherwise reach a student as a 400
they cannot act on. `MoneyProfileVocabularyTests` checks all three enums against
`api-specs/openapi.yaml`, and `ResidencyStateTableTests` reads
`MoneyProfileService.USPS_STATE_CODES` out of the Kotlin source, both via
`#filePath` on the `StoreKitConfigurationTests` precedent — the thing under test
is the checked-in contract, not a copy of it. Neither guard is allowed to pass
on a partial read: the vocabulary check requires the spec to have yielded some
enum at all, and the state check asserts the server's set is **exactly** the 51
codes this app offers plus the eight territories it does not — a floor would
pass on half a scrape, and an unparseable file throws rather than returning an
empty set, so "the file moved" cannot masquerade as "the server accepts
nothing".

### 5. `APIClient.put`

`APIClient` has `post`, `get`, `delete`, `patch`, `stream` — and no `put`. One
method is added:

```swift
func put<B: Encodable>(_ path: String, body: B) async throws -> (data: Data, response: HTTPURLResponse)
```

Identical in shape to `patch` down to the type-parameter name and the
**labelled** tuple, because `APIClient.decode(data:response:expectedStatus:)`
reads those labels at every call site.

While there, `APIClient` was made to keep the promise every client protocol in
this app prints: "throws `ErrorResponse`". Two paths did not — an unbuildable
URL and a body the encoder rejected threw a raw `URLError`/`EncodingError` from
outside the mapping, reached a view model's general `catch`, and put a
`localizedDescription` in front of a student. Both now map to
`ErrorResponse.unexpected` with the real cause logged. This touches all six
clients, which is the point: the promise was false for all six.

`MoneyProfileClient` binds the **write only**, though the server also offers
`GET`. §6 says this app grows no money-profile read or edit surface — chat is
the edit path — so a read method here would have no reader, and would be kept
alive by a test written to give it one. The read verb lands with its first
reader, and the `StudentClient.fetchProfile` precedent (404 is `nil`, not a
throw) is waiting for it when it does.

**The Swift type is narrow; the JSON is exactly what the server declares.** The
wire admits states the domain does not — `incomeBand: "48k_to_75k"` beside
`incomeBandDeclined: true` is representable JSON and is a 400 — so the request
is not three `String?`s and six `Bool`s. It is three optional
`MoneyProfileFieldUpdate<Value>` properties, one per field, over

```swift
enum MoneyProfileFieldUpdate<Value: MoneyProfileFieldValue> {
    case set(Value), declined, clear
}
```

which is the server's own `FieldUpdate` sealed interface in Swift: one
vocabulary, two languages. `nil` is "untouched". Onboarding's rule — a sign-up
screen may never spend a permanent decision (§Executive Summary choice 3) — then
lives in which cases the screen can construct, rather than in tests hoping
nobody sets a flag.

`encode(to:)` is hand-written so the bytes are unchanged by that typing: all six
booleans always emitted (`false` unless their field says otherwise), a value key
omitted when there is no value. The server sets
`FAIL_ON_UNKNOWN_PROPERTIES =
true` and
`FAIL_ON_MISSING_CREATOR_PROPERTIES = true`, so the client must send no extra
key and should not rely on a Kotlin default for an omitted one. The "exactly
these keys" test that guards this is asserted at the raw JSON and its assertions
did not change when the type did — which is the evidence the two claims are
independent.

`livingPlan` is on the REST surface but is **not** collected here. It is the
weakest of the three (RFC 152 D2: a chosen plan leads, never filters), it is the
most situational question to ask a stranger, and the screen's whole point is
brevity. The client struct carries the field so the transport is complete;
onboarding leaves it nil.

### 6. What this RFC deliberately does not do

- **No district or ZIP field.** Per §Executive Summary choice 4 — deferred on
  sequencing, not merit. Revisit as soon as `shape/02/ipeds-ic-ay` lands the
  in-district price tier, and then as ZIP or county (testable against a
  college's district) rather than a school-district name.
- **No change to `RegistrationView`.** Email, name and password are self-evident
  and already minimal; adding explanatory captions to a three-field credential
  form is noise. "Explain each input" is honoured where the inputs are
  non-obvious and genuinely optional.
- **No decline affordance.** §Executive Summary choice 3.
- **No `livingPlan` or `dependency` capture.** `dependency` is not even on the
  REST surface.
- **No editing of these fields elsewhere in the app.** There is no iOS
  money-profile settings screen today and this RFC does not add one; chat
  remains the edit path, and the copy says so.

## Files Modified

**New**

- `ios-app/UnicoachiOS/MoneyProfileClient.swift` — protocol + implementation
  (the write verb only; see §5).
- `ios-app/UnicoachiOS/ResidencyStates.swift` — `ResidencyState` and the offered
  menu, in their own file because they are not a wire contract (§4).
- `ios-app/UnicoachiOSTests/MoneyProfileClientTests.swift` — holds
  `ResidencyStateTableTests` and `MoneyProfileVocabularyTests` too: all three
  are about the same wire vocabulary.
- `ios-app/UnicoachiOSTests/MockMoneyProfileClient.swift`
- `rfc/163-onboarding-that-fits-on-one-screen.md` (this file)

**Edited**

- `ios-app/UnicoachiOS/DesignSystem/Components.swift` — add `DSPickerRow` with
  its two `options:`/`selection:` initializers, and `dsControlBox`, applied to
  the four controls that had each written that chrome out (§1).
- `ios-app/UnicoachiOS/OnboardingView.swift` — new layout, per-field copy,
  optional section; `pickerRow` removed in favour of `DSPickerRow`; init gains
  `moneyProfileClient`; a second `init(viewModel:userName:)` snapshot seam (the
  `AddCollegeView` convention) so a scene can host a seeded view model, since
  precision and both optional answers are view-model state; previews updated.
- `ios-app/UnicoachiOS/OnboardingViewModel.swift` — `residencyState` (a
  `ResidencyState?`), `incomeBand` state; `precision` default `.year`; two-step
  `submit()` over `ensureStudentProfile()`; the clamps moved onto the `year`,
  `month` and `day` properties so a `Picker` bound straight to them cannot route
  around them; month lengths from `Calendar` (§2).
- `ios-app/UnicoachiOS/APIClient.swift` — add `put`; map an unbuildable URL, an
  unencodable body and an unencodable query component into `ErrorResponse`,
  which is what every client protocol's "throws `ErrorResponse`" already
  promised (see §5).
- `ios-app/UnicoachiOS/Models.swift` — `MoneyProfileFieldUpdate` and its
  `MoneyProfileFieldValue` protocol, `UpdateMoneyProfileRequest` with its
  hand-written `encode(to:)`, `PublicMoneyProfile` and its `answering(_:)`
  factory (the "value present iff answered" projection, which three doubles were
  each hand-building), `MoneyProfileResponse`, and the three transcribed server
  vocabularies `IncomeBand`, `AnswerStatus` and `LivingPlan` — named as the
  server names them, with no `Value` suffix to distinguish them from nothing.
- `ios-app/UnicoachiOS/AppViewModel.swift` — `moneyProfileClient` property, init
  param, default wiring.
- `ios-app/UnicoachiOS/UnicoachiOSApp.swift` — pass the client to
  `OnboardingView`.
- `ios-app/UnicoachiOSTests/OnboardingViewModelTests.swift` — three construction
  sites, plus new cases.
- `ios-app/UnicoachiOSTests/APIClientTests.swift` — the two request-building
  failures now map to `ErrorResponse` (§5).
- `ios-app/UnicoachiOSTests/RandomFixtures.swift` — `moneyProfileResponseJSON`;
  its month-length table replaced by the same `Calendar` call the view model
  makes (§2).
- `ios-app/UnicoachiOSTests/SnapshotScenes.swift` — the four onboarding scenes
  and the `SnapshotSeed.onboarding(...)` helper behind them, plus
  `SnapshotScene.maxHeight` / `tallCanvas(height:)`: the jetsam ceiling is a
  system constraint, and it was a comment with three retyped numbers under it.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — four entries per new file
  (`PBXBuildFile`, `PBXFileReference`, group `children`, target Sources phase).
  The project uses explicit file references, not synchronized groups; a new
  `.swift` that is not registered here silently does not build.

No Kotlin, no schema, no migration, no OpenAPI change. The backend contract this
consumes has existed since RFC 134.

## Implementation Plan

1. **Baseline and transport.** First add the onboarding snapshot scenes (§Tests)
   against the **current** screen and capture the corpus — that corpus is the
   baseline the refactor in step 2 is checked against, and it cannot be captured
   after the screen has changed. Then `APIClient.put`; the money-profile Codable
   models and vocabulary enums in `Models.swift`; `MoneyProfileClient` +
   protocol. Register all new files in `project.pbxproj`. Tests:
   `MoneyProfileClientTests` (URLProtocol stubs, per the
   `AuthClientTests.MockURLProtocol` convention).
2. **Design system.** Promote `pickerRow` to `DSPickerRow` in
   `Components.swift`; repoint the existing Month/Day rows at it. This step
   alone must leave the screen behaviourally identical — a pure refactor,
   verifiable by `bin/snapshot-ios -b` against step 1's baseline showing no
   drift on the Month/Day rows.

   **This is not what the implementing run did.** It captured the scenes at the
   end, in step 6, so the refactor never had a before-corpus and no `-b`
   comparison of it was possible. What stood in for it is weaker and worth
   naming: the unit suite stayed green and the final PNGs were reviewed by eye
   against the old screen. That catches a broken build and a gross visual
   change; it cannot catch the small unintended shift a `-b` report would have
   printed a number for. The ordering above is the fix for the next
   screen-shaped change, not a description of this one.
3. **View model.** `precision` default `.year`; `residencyState` / `incomeBand`
   optional state; `submit()` becomes create-then-maybe-PUT with the swallowed
   money-profile failure. `MockMoneyProfileClient` for the tests.
4. **Layout and copy.** Rebuild `OnboardingView`'s body: year as a
   `DSPickerRow`, per-field caption text, the optional section with its overline
   and "you can add these any time" line. Update previews.
5. **Wiring.** `AppViewModel` + `UnicoachiOSApp`.
6. **Visual gate.** Re-capture the corpus over the rebuilt screen, and add the
   accessibility-text-size scene (§Tests) — the one that observes the
   `@ScaledMetric` growth §1 claims.

Steps 1–2 are independent of 3–5 and may be done in parallel; step 4 depends on
2 and 3.

## Tests

**`MoneyProfileClientTests` (new).** URLProtocol-stubbed, asserting the wire
contract, not just the happy path:

- `PUT` path is `/api/v1/students/me/money-profile`, method `PUT`,
  `Content-Type: application/json`.
- A body with only `residencyState` set encodes **exactly** the expected keys:
  the six booleans present and `false`, `incomeBand`/`livingPlan` **absent**.
  This is the test that catches an accidental `*Clear` or `*Declined`.
- `200` decodes into `PublicMoneyProfile` with statuses as raw strings and
  working `known…` accessors.
- An unknown future `incomeBand` string decodes without throwing and yields
  `nil` from `knownIncomeBand` — the reason the raw-string convention exists.
- `400 validation_failed` surfaces its `fieldErrors`;
  `409
  student_profile_required` surfaces that code; `401` surfaces
  `unauthorized`.
- An unbuildable request and an unencodable body each surface as
  `ErrorResponse.unexpected` rather than a raw `URLError`/`EncodingError`
  (`APIClientTests`).
- Each of `declined` and `clear` sets exactly its own flag and sends no value —
  neither is reachable from onboarding, but both are reachable from the
  transport.
- The decoded profile and the outgoing request are each asserted as a **whole
  value**, not field by field: a per-field assertion passes while the field
  nobody thought to name decodes — or is sent — wrong. That is what earns
  `Equatable` on `PublicMoneyProfile` and `UpdateMoneyProfileRequest`; the
  conformances exist for those two assertions and for no other reason.

**`OnboardingViewModelTests` (extended).** The existing 13 cases are kept — they
cover `isoDate` per precision and the day-clamping arithmetic, all of which is
unchanged, though they now assign `year`/`month` rather than calling setters
that no longer exist. Added:

- Default `precision` is `.year`. (Guards the regression this RFC exists to
  prevent.)
- The clamps, at both ends and on both properties: day 0 becomes day 1, month 13
  becomes December and month 0 becomes January — the strings `2028-06-00` and
  `2028-13-31` were each reachable once the setters were deleted.
- `dayRange` follows `Calendar`'s leap rule, checked at 1900 and 2000 — the two
  years a hand-written rule gets wrong.
- `yearRange` spans `year-4 ... year+8`; assigning `year` clamps the day, and so
  does assigning `month` — both untested before, and both now bound directly to
  a picker, which is exactly why the clamp had to move onto the properties.
- Neither optional answered → `submit()` calls `createStudent` and **does not
  call** `moneyProfileClient` at all. (Asserted by a call counter on the mock.)
- Only state answered → PUT body carries `residencyState` and a nil
  `incomeBand`; only income → the mirror.
- Money-profile PUT fails → `onComplete()` is still invoked and no
  `errorResponse` is set.
- `createStudent` fails with `student_already_exists` → still completes, and the
  money-profile PUT is still attempted (the student exists).
- `createStudent` fails otherwise → `onComplete()` is **not** invoked, the error
  is surfaced, and the PUT is **not** attempted.

**Drift guards (`MoneyProfileVocabularyTests`, `ResidencyStateTableTests`).**
The server's accepted `STABBR` set is read out of `MoneyProfileService.kt` via
`#filePath`, never retyped, and asserted to be **exactly** the 51 codes this app
offers plus the eight territories it does not — so the test fails when the
server narrows its set, and cannot pass on a half-read file the way a floor
would. The offered list is checked for duplicates. The three transcribed
vocabularies are checked against `api-specs/openapi.yaml` the same way, with the
same anti-vacuity rule: a scrape that finds no enum at all fails, and a file
whose shape has moved throws rather than returning an empty set.

**Snapshot scenes (the visual gate).** `SnapshotScenes.swift` has no onboarding
scene today — a real gap, since this screen is entirely visual. Add
`onboarding-year` (default first paint), `onboarding-full-date` (all three date
rows), and `onboarding-answered` (both optional fields chosen), each rendered
light and dark by the existing harness. `onboarding-year` keeps the **device**
canvas, because that scene is the answer to "how much of this fits?" and a
taller window would answer a question nobody asked; the other two get a 1200pt
canvas, since the harness window does not scroll and a clipped form cannot be
reviewed — expressed as `SnapshotScene.tallCanvas(height:)`, with the ceiling
below enforced in `SnapshotScene.init` where the scene's name is known. A fourth
scene, `onboarding-accessibility-type`, renders the default first paint at
`.accessibility1` on a 1500pt canvas: Dynamic Type is a SwiftUI environment
value, so the scene sets it on the hosted view and the harness needs nothing
new. It measures ~1420pt against the ~930pt default paint — every row taller,
the two long picker labels wrapped to two lines inside a grown box, and nothing
clipped. 1500pt is `SnapshotScene.maxHeight`, the corpus's existing ceiling, and
it is a system constraint rather than a style rule: a first attempt at 2000pt
(804×4000px at `captureScale` 2) got the test process SIGKILLed by jetsam
mid-walk, which surfaces as "Restarting after unexpected exit" and an empty
corpus rather than as a failing assertion. It is the only capture that observes
§1's `@ScaledMetric` claim — that the rows grow rather than clip — and the only
place the whole form's behaviour at that size is visible at all.

There is no before/after corpus for this screen: onboarding had **no** scene
until this RFC, so the "before" is the 1613pt measurement quoted in the
Executive Summary, not a set of PNGs. `bin/snapshot-ios -b <baseline>` is the
tool for the next change to this screen, which now has a baseline to be compared
against. Drift is reported, never failed.

**Gates.** `nix develop -c bin/test` (the Kotlin suite must stay green; this RFC
touches no Kotlin, so the executed count is the evidence it did not).
`bin/test-ios` and `bin/snapshot-ios` run under **system Xcode, not** the dev
shell.
