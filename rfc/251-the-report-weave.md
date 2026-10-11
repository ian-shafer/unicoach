# RFC 251 — The report weave

**Slice:** `beyond/07/the-report-weave` (brief 0011, "beyond money"), the last
slice of the brief. **Lane:** A (rfc) — a new thing a parent sees, a new student
control, a new chat tool and a coach prompt change. **Base:** `main@a81472d9`.
**No new table, no new column, no migration** other than the coach prompt seed
`bin/prompt-seed` generates.

The parent asked what college will cost. The same page should now answer what it
returns, in the student's own major. This RFC weaves the field-of-study figures
`beyond/05` (RFC 231) already computes into the Family Cost Report's existing
per-college money paragraph, behind the permission `beyond/10` (RFC 248) already
stores, and gives the student the two doors to that permission the slice owns: a
control on "Your details" and the coach's one-time question at the share moment.

## What the code says (measured at design time, 2026-10-11)

Two read-only research passes over `main@a81472d9`; findings with `path:line`
citations in `.scratch/ship/rfc-251/artifacts/research-*.md`.

| #   | Fact                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F1  | **The report already RECEIVES the major answer and drops it.** `ServiceCostReportSource.getOutcome` calls `CollegeCostService.readInSession`, which builds `CollegeCostProfile.programReturn` on every call; `CostReportPage.kt` never reads it. So D20 ("no stored field ⇒ the page is unchanged") is today's state, and the permission gate is a **subtraction at the source**: `profile.copy(programReturn = null)` unless granted. No `CollegeCostService` change. |
| F2  | **No Kotlin sentence speaks a programme figure.** RFC 231's speaking rules live in the coach prompt; Kotlin holds only three ABSENCE statements, as `const val`s in `CollegeCostChatTool`'s companion. The slice's `Needs:` premise ("the sentences must exist in `coaching/costs` first") is not met by RFC 231, so this RFC authors them there.                                                                                                                      |
| F3  | **Placement is forced.** Inside `debtBlock`'s `section("report-debt")` the federal median debt sentence names its own publisher, and `borrowingParagraphs` opens with a header that attributes everything beneath it to the school's Common Data Set (RFC 190). A Treasury or NSLDS figure placed after that header would be mis-attributed, so the weave goes **between** the two.                                                                                    |
| F4  | **Stored subjects are already in a neutral order** — `IntendedSubjectsDao` orders by subject name — and `ProgramReturn` keeps it. One subject reaches 1 programme at a college in 72% of (subject, college) pairs, up to 19 at most. A programme carries only its 4-digit CIP code; its published title (`college_programs.cip_title`) exists but is not read.                                                                                                         |
| F5  | **Coverage, measured on a loaded dev DB** (68,634 bachelor's programmes, 2,416 colleges): earnings reported 39.4%, suppressed 60.6%; debt and the 10-year payment reported 37.3%, suppressed 56.1%, not reported 6.6%. 42.3% of programmes have any figure. Suppression is the COMMON case — under D23 it earns the once-per-page sentence, and a per-college gap is a short mark.                                                                                     |
| F6  | **The share tool's honesty sentence becomes false after a grant.** `ShareCostReportChatTool.WHO_CAN_SEE` and prompt line 206 say the link shows "the student's college list and its cost figures". With a grant it also shows the subjects. That sentence must depend on the permission.                                                                                                                                                                               |
| F7  | **Every student door is in the iOS app.** Shares are minted only by the `share_cost_report` chat tool; the web has no details page and no chat yet (`web/04`, `web/07` unbuilt). No chat tool can grant or refuse today, and the iOS design system has no on/off control.                                                                                                                                                                                              |
| F8  | **Two static source sweeps read `CostReportPage.kt`** — `CostReportPageTest`'s "exactly one `href`, no `.url`" check and `RenderPathMakesNoNetworkCallTest`'s source read. Both violate Ian's standing rule (tests never do static analysis). The first breaks the moment a second anchor is added.                                                                                                                                                                    |
| F9  | **RFC 191's tier block cannot name a programme figure**: `CoveredFigure.field` is a `CostField`. `AssuranceTier.ADMINISTRATIVE_RECORD`'s sentence ("federal loan and tax records … covers only students who got federal aid, and small numbers are blurred a little to protect privacy") is exactly true of the Treasury and NSLDS programme figures.                                                                                                                  |
| F10 | **The comparison-word guard does not read the page** (spec Z3): `FIGURE_COMPARISON_WORDS` is asserted against the `cited_as` sentences and the coach prompt only. `public-web` does not depend on `:college`'s test fixtures.                                                                                                                                                                                                                                          |

## Gate decisions (Ian, 2026-10-11)

Ian: _"'2. Which figures each programme shows' Option B. Approve all else"_.

1. **Two doors** — the "Your details" control AND the coach's one-time ask at
   the share moment with a new `update_sharing_permission` tool (§5).
2. **All three figures per programme** (Option B, Ian's override of the
   recommended two): earnings, the publisher's own 10-year monthly payment, AND
   the median federal loan debt at completion — each its own sentence (§2).
3. **One link to the College Scorecard** in "Sources and what this is not",
   emitted only when a programme block is printed — brief 0011 D34's Option A,
   Ian's recorded answer; D46's "in words, with no link" is treated as a
   misquote of it. Target `https://collegescorecard.ed.gov/data/` (checked 200
   on 2026-10-11), held as a typed constant beside the field-of-study registry
   (D46: a thing that changes only when a human edits it belongs in the type
   system). The static `href` sweep in `CostReportPageTest` is deleted and
   replaced by a behavioural anchor test over the rendered page.
4. **The consent words** in §5 as drafted.

## Detailed Design

### 1. The gate: one subtraction, in the report's one session

`ServiceCostReportSource` gains a `SharingPermissionService`. Inside the same
`withConnection` that resolves the token and reads the profile:

```kotlin
val profile = costs.readInSession(session, share.studentId)
val subjectsShown = permissions.isGranted(session, share.studentId, SharingPermission.INTENDED_SUBJECTS)
CostReportOutcome.Found(if (subjectsShown) profile else profile.copy(programReturn = null))
```

`public-web`'s `Application.kt` constructs `SharingPermissionService(database)`
beside the source. Never in `CollegeCostService.readInSession`, which the chat
also uses (V1). Only `granted` shows; no row (never asked) and `refused` both
hide. Because the report is a live read per request, a grant or a withdrawal
takes effect on the next load of **every** link, including links minted before
it — the acceptance criterion holds by construction, not by a sweep over shares.
The page therefore never receives subjects it may not print: the gate is a
property of the model, not of the renderer's restraint.

### 2. The copy: `ProgramReturnCopy` in `coaching/costs`

A new `service/.../coaching/costs/ProgramReturnCopy.kt`, beside
`ProgramReturn.kt`, owns every sentence the page speaks about programme figures
(the `MoneySourceCopy` / `FigureStatusCopy` precedent; the page only lays out).
Exhaustive `when`s with no `else`, so a new figure status or measure is a
compile error at the copy seam.

- **Figure sentences**, one per shown measure, each naming its own publisher and
  its cohort, built from the typed provenance (`publisher`,
  `cohortFirstYear`/`cohortLastYear`, `inflationBaseYear`) rather than from the
  raw publisher cell, which is jargon on a parent's page. Earnings states its
  2024-dollar base; debt states none (the publisher names none); the payment is
  "the publisher's own estimate of the monthly payment on a standard 10-year
  plan" and names no rate (registry KDoc). Each sentence is self-contained — its
  own publisher and cohort, no "their" / "same students" back-reference —
  because the neighbouring figure may be a gap mark; the programme is named by
  the paragraph's title label. They print in the registry's measure order, the
  same order the chat payload uses. As served:
  - earnings — "Graduates working four years after finishing earned a median of
    $41,200 a year, according to the U.S. Treasury (students who finished in
    2017-18 and 2018-19, in 2024 dollars)."
  - debt at completion — "Graduates who borrowed federal loans finished with a
    median federal loan debt of $26,000, according to the federal student loan
    system (students who finished in 2018-19 and 2019-20)."
  - payment — "For graduates who borrowed federal loans, the median monthly
    payment on a standard 10-year plan is $291, by the federal student loan
    system's own estimate (students who finished in 2018-19 and 2019-20)."
- **The publisher, typed.** `FieldOfStudyVariable.publisher: String` becomes
  `publishedBy: FieldOfStudyPublisher` (`TREASURY` | `NSLDS`), so the plain
  words a family hears for each publisher ("the U.S. Treasury", "the federal
  student loan system") are one exhaustive `when` in `ProgramReturnCopy`;
  `publisher` stays as an accessor derived from it, for the chat wire.
- **Short gap marks** (D19's "short mark", Z4: a woven gap KNOWS whose silence
  it is): suppressed → "withheld by <publisher> to protect students' privacy";
  not reported → the existing not-reported wording; `NoFigure` and
  `NoPublishedFigures` → the existing RFC 231 statements; `NoProgram` → the
  existing "This school does not run that program." A subject whose every
  programme is suppressed gets ONE mark, not one per programme.
- **The coverage sentence, once per page** (D19), emitted only when at least one
  college has a matched programme for a stored subject (a page whose every
  subject block is "does not run that program" prints neither this nor the
  sources sentence, and so no Scorecard link) — the `AWARD_KIND_SENTENCE` "once
  for the page" precedent. Its kind-of-number half is NOT restated: the figures
  join the ADMINISTRATIVE_RECORD tier group (§4), whose sentence already says
  "covers only students who got federal aid … blurred to protect privacy". The
  coverage sentence adds only what is new: "Figures by field of study come from
  the graduates of each program, and the publishers withhold a program's figure
  when too few of its graduates received federal aid to keep them anonymous." It
  states no share: the measured 60.6% / 56.1% (F5) would go stale on the next
  load with nothing to re-check it, so the dated measurement lives in the KDoc
  as the reason the sentence exists. A programme figure on the page — with a
  number or withheld — joins the administrative-record kinds group, so the one
  tier sentence always covers the field-of-study figures and is never restated.
- **The sources sentence** (D34, gate decision 3), emitted only with a programme
  block, carrying the page's one link to the College Scorecard, scoped to these
  figures and separate from the existing "The cost, price and federal debt
  figures come from …" sentence. It also extends the "never added together and
  never compared" clause to the programme figures.
- **The three RFC 231 absence constants MOVE here byte-for-byte** from
  `CollegeCostChatTool`'s companion; the tool and its description read them from
  the new home. Moved, not reworded: the chat bytes do not change.

No sentence uses a word from `FIGURE_COMPARISON_WORDS` (§6 test). No sentence
adds, subtracts or divides a figure by another (D27, no ratio); earnings and the
payment are always two sentences, never one row-set (prompt line 162's rule,
applied to the page).

### 3. The render: every subject, every programme, nobody's favourite

Ian's binding constraint: never pick a favourite on the family's behalf. So, per
college, inside `section("report-debt")`, between the federal median debt
paragraph and `borrowingParagraphs`:

- one block per stored subject, **all of them (up to three), in stored order**
  (alphabetical by subject name — a neutral order nobody chose);
- inside it, every matched programme **by its published title** (the `cip_title`
  widening below; CIPDESC's trailing period is dropped on the page and kept
  verbatim on the chat wire), in CIP order; no programme promoted, sorted by
  value, summarised or dropped;
- each programme's three figure sentences, in the registry's measure order —
  earnings, the debt at completion, the publisher's own 10-year monthly payment
  (gate decision 2) — or its short gap mark;
- a college with no matching programme for a subject gets one short mark for
  that subject.

`schoolSection` receives the college's slice of the answer, derived ONCE in
`respondCostReportPage` (the `residencyBases` precedent) through a
`coaching/costs` view — `ProgramReturn.forCollege(collegeId)` — so the renderer
does no grouping. No new `h2`, no new section: the woven paragraphs sit inside
the existing money block (D12). With `programReturn == null` nothing new is
emitted anywhere on the page (D20).

**Programme titles.** `ProgramMoneyDao.bachelorProgramsBySubject` selects
`p.cip_title` too and answers each match with its title (a
`Map<SubjectProgramMatch, String>`), and `ProgramReturnProgram` carries it (not
`SubjectProgramMatch`, which is the join key). The chat payload gains it as
`program_title` beside `cip_code`, so the coach also names a programme instead
of reading a bare code.

### 4. The tier block covers the woven figures (RFC 191)

RFC 191's rule — every figure the page speaks a kind for is covered by exactly
one kind sentence — stays true by widening `CoveredFigure`'s identity from a
`CostField` to a sealed figure identity (`CostField` | programme measure). The
three field-of-study variables get `ADMINISTRATIVE_RECORD` as a registry fact on
`FieldOfStudyVariable` (the tier's own KDoc names NSLDS and Treasury). The woven
figures join the existing administrative-record group, so the page still says
that sentence once. `scopeOf`'s group label grows to name them whenever a
programme figure is printed; a page that prints none keeps today's label, so the
existing label assertions stand and the grown label is asserted by new tests.
Public-web cannot reach the registry, so the tier also rides on
`ProgramFigureProvenance.assuranceTier`.

### 5. The student's two doors

The default is OFF, so D18 ("told in plain words before the first share that
includes them") holds if every GRANT door carries the words before the grant is
written. The share moment is where the coach ASKS.

**Door A — "Your details" (iOS), required by V3.** Under "What you want to
study", after the subjects row: a `SegmentedSelector<SharingChoice?>` with two
segments, "Show them" / "Keep them to me", wrapped in `DSCaptioned`. The tag is
optional, so never asked shows NEITHER segment selected, and tapping can only
write `granted` or `refused` — never-asked cannot be written (V4). No new design
system component, no stock `Toggle` (DESIGN.md §8). A new
`SharingPermissionsClient` over the RFC 248 REST pair, plumbed like
`IntendedSubjectsClient`. The control is shown always: the permission is its own
data (D36), and one state machine is simpler than a rule coupling it to the
subjects answer. A failed permission READ fails the screen, as every other
answer read on this screen does — showing "kept to me" over an unread `granted`
would be the false statement the screen exists to avoid. Copy (decision 4):

> Off until you say yes. If you say yes, people you share with can see the
> subjects you chose and what graduates of them earn and owe — that includes
> anyone holding a Family Cost Report link you've already sent. Your coach uses
> them either way, and you can change this whenever you like.

Above the selector, drawn like `DSSubjectsRow`'s "Subjects" label: "Who can see
them" (so "Show them" has a referent), then — only while never asked — the line
"You haven't said yet, so nobody you share with sees them.", following the
screen's rule that a field reads its state before the control that changes it. A
status this build has no case for, or no `intended_subjects` entry, shows
neither segment and no never-asked line, and is logged — the
`DSSubjectsAnswer.unreadable` precedent: never "nobody sees them" over an answer
the app cannot read, and a newer server vocabulary does not fail the screen. A
failed write snaps the control back to the stored answer.

**Door B — the coach (decision 1).**

- `share_cost_report` reads the permission and the subjects answer BEFORE
  minting, through two function seams wired at registration to
  `SharingPermissionService.read` and `IntendedSubjectsService.getForStudent`
  (so a failed read can be exercised without a broken database). A failed
  PERMISSION read mints nothing and returns
  `{ "error": "cost report share
  failed" }` — a reach sentence it cannot state
  truthfully is a failed share, not a guess. A failed SUBJECTS read only decides
  the ask: the link is minted without `your_subjects`. `who_can_see` becomes
  TRUE for the state: unchanged when not granted; with a grant it adds "…and
  what the student wants to study, with what graduates of those subjects earn
  and owe". When subjects are answered and the permission is never asked, the
  payload carries
  `your_subjects: { permission: "not_asked", ask: "<the D18 words>" }`; on
  `refused` or `granted` it carries that `permission` and no ask. With no stored
  subjects the field is absent (nothing would change, D20).
- A new tool `update_sharing_permission`, input
  `{ permission: <SharingPermission values>, status: "granted" | "refused" }`,
  both enums derived from the Kotlin enums, `additionalProperties: false`,
  through `SharingPermissionService.set`. Its description carries the consent
  words themselves, so a grant made in chat outside a share moment is still
  preceded by them, and it derives every permission and status word it teaches
  from the enums. "Never asked" has one wire spelling,
  `SharingPermissionStatus.NOT_ASKED_WIRE` in `:db`, read by both the REST view
  and the share tool. Returns the stored status and the reach sentence.
  Registered in `rest-server`'s `Application.kt` (no test pins the registered
  tool set, so registration is checked by a behavioural tool-list test added
  here).
- Prompt lines beside the share rules (one sentence per line): ask ONCE, only
  when the share result carries an ask, in its words; a no is recorded with
  `refused` and never raised again; nothing is held back on either answer; the
  student can change it any time, in chat or in Your details; a student who lets
  the question pass without a yes or a no is not asked again in that
  conversation, even if a later share result carries the ask (the store records
  answers, not asks). Line 206 keeps its asserted phrase "anyone who has it can
  see" and says the subjects are seen only if the student has said yes. The seed
  and pin are generated by `bin/prompt-seed` (expected v50, final at land).

### 6. What does not change

No table, column or constraint. `CollegeCostService.readInSession` and the chat
`program_return` section are unchanged except the added `program_title`.
Supporter (parent account) screens get no control and read nothing. The three
existing "no" shapes (RFC 248 §4) are untouched.

## Files Modified

- `public-web/src/main/kotlin/ed/unicoach/web/report/ServiceCostReportSource.kt`
  — the gate (§1)
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt` — construct
  `SharingPermissionService`
- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` — the
  woven blocks, the once-per-page coverage and sources sentences (§3)
- `public-web/build.gradle.kts` —
  `testImplementation(testFixtures(project(":college")))`
- `service/src/main/kotlin/ed/unicoach/coaching/costs/ProgramReturnCopy.kt` —
  new (§2)
- `service/src/main/kotlin/ed/unicoach/coaching/costs/ProgramReturn.kt` —
  `programTitle`, `forCollege`
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt` —
  constants moved out, `program_title` on the wire
- `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/AssuranceTierGrouping.kt`
  — figure identity widened (§4)
- `college/src/main/kotlin/ed/unicoach/college/ScorecardFieldOfStudyColumns.kt`
  — tier per variable, the `FieldOfStudyPublisher` enum, the `DATA_PAGE` link
  constant
- `db/src/main/kotlin/ed/unicoach/db/dao/ProgramMoneyDao.kt` — select
  `cip_title`; the programme read answers each match with its title
- `service/src/main/kotlin/ed/unicoach/coaching/report/ShareCostReportChatTool.kt`
  — state-true `who_can_see`, `your_subjects`
- `service/src/main/kotlin/ed/unicoach/coaching/SharingPermissionChatTool.kt` —
  new `update_sharing_permission`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — register the
  tool, pass the service
- `prompts/coach-system-prompt.md` — share-moment lines; generated seed +
  `service.conf` pin via `bin/prompt-seed`
- `ios-app/UnicoachiOS/SharingPermissionsClient.swift` — new;
  `YourDetailsView.swift`, `YourDetailsViewModel.swift`, `AppViewModel.swift`,
  `UnicoachiOSApp.swift`, `AuthenticatedRootView.swift`,
  `UnicoachiOS.xcodeproj/project.pbxproj`; tests
  `UnicoachiOSTests/MockSubjectClients.swift` (mock), a new VM test file,
  `SnapshotScenes.swift` (seed param + one `granted` scene); `ios-app/DESIGN.md`
  if the selector use extends a treatment
- Tests listed below; `CostReportPageTest.kt` loses its static `href` sweep,
  `RenderPathMakesNoNetworkCallTest`'s source read is left alone unless decision
  3 adds a link through a new helper it names

## Implementation Plan

1. Model: `cip_title` through the DAO into `ProgramReturnProgram`; `forCollege`;
   tier on `FieldOfStudyVariable`; widen `CoveredFigure`.
2. Copy: `ProgramReturnCopy` with the moved constants; re-point the chat tool.
3. Gate: `ServiceCostReportSource` + `Application.kt`; DB-backed gate test.
4. Page: woven blocks, coverage and sources sentences; page tests; delete the
   static `href` sweep if an anchor is added.
5. Chat: `who_can_see`, `your_subjects`, `update_sharing_permission`,
   registration; prompt lines; `bin/prompt-seed -b <BASE_SHA>`.
6. iOS: client, plumbing, control, VM tests, snapshot scene; `bin/test-ios` and
   `bin/snapshot-ios`.
7. Full `bin/test`.

## Tests

Behavioural only (Ian's standing rule).

- **Gate (public-web, DB-backed)**: a student with answered subjects and a live
  share, three cases — no row, `refused`, `granted` — `getByShareToken` returns
  `programReturn == null`, `null`, non-null; then grant→withdraw on the SAME
  token flips the next read (links already minted). Needs no
  `program_money_stats` seeding: a `NoProgram` college still yields a non-null
  section.
- **Page**: a profile with `programReturn == null` renders no woven element, no
  coverage sentence and no field-of-study sources sentence (D20, with the
  unchanged existing suite as the rest of the evidence); every subject appears,
  in stored order, every programme by title; each shown figure has its publisher
  and cohort in the same paragraph; each gap arm renders its own mark; an
  all-suppressed subject renders one mark; the coverage sentence appears exactly
  once on a three-college page; every woven paragraph precedes the borrowing
  header inside `report-debt`; the tier block covers programme figures with
  exactly one kind sentence; `FIGURE_COMPARISON_WORDS` match nothing in
  `visibleProseOf` the rendered page.
- **Copy**: every `ProgramReturnFigure` arm × measure yields a sentence
  (exhaustive by construction; one test walks the entries).
- **Chat**: `share_cost_report` `who_can_see` per state; `your_subjects` present
  with an ask only for answered + never asked, without an ask for `refused`,
  absent with no stored subjects; `update_sharing_permission` grant/refuse round
  trip, rejects `not_asked` and unknown permissions, and appears in the
  registered tool list; RFC 231 chat tests pass with the constants moved.
- **Prompt**: `SystemPromptCatalogTest` entries for the new lines; the pinned
  body equals the source file after `bin/prompt-seed`.
- **iOS**: VM — never asked selects neither; choosing writes `granted` and
  adopts the response; withdrawing writes `refused`; a failed write keeps the
  stored answer and surfaces the error; a failed read fails the screen; 409
  escalates. Client — decodes `not_asked`/`granted`/`refused` through
  `MockURLProtocol`. Snapshots — the three loaded your-details scenes move
  (never asked), plus one `granted` scene; each measured under the 1,500pt
  canvas ceiling.

## Rollback

Revert the code commit. The coach prompt rollback is PARTIAL: repinning
`systemPromptVersion` to v49 removes the ask, but the share payload's
`your_subjects` field and the new tool still ship — revert the code commit too.
No data migrates: grants already written stay in `sharing_permissions` and are
read by nothing after a revert.
