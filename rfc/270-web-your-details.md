# RFC 270 — Your details on the web

**Slice:** `web/07/your-details` (brief 0013, _Unicoach on the web_).
**Status:** proposed. **Base:** `main@aee71ebe`. **Lane:** rfc.

## Executive Summary

A signed-in student gets a "Your details" page at `/account/details`, reached
from the signed-in header. It shows the four money answers — household income,
state of residence, where they would live, and FAFSA dependency — says what each
one improves, and lets the family give, decline or remove each answer on its
own, at any time. Every answer is optional; a half-answered profile is shown as
it is, with no meter and no nagging.

Each answer saves the moment it is picked, as the iOS screen does (RFC 171): one
field per write, through `MoneyProfileService.upsert` in-process (brief 0013
D13). So a family who answers one question and leaves finds it there when they
come back. A pick whose save fails is kept in the session draft (RFC 265) and
offered again.

JavaScript is assumed (Ian, at approval, D-B). A browser with it turned off sees
a notice saying unicoach needs JavaScript, on every page, instead of the page
(D-F).

## Detailed Design

### 1. What the code says, where it differs from the slice

- **The iOS screen writes each answer as it is picked** (`YourDetailsView.swift`
  has no Save button; `YourDetailsViewModel` sends exactly one field per `PUT`).
  The web does the same: picking an answer submits that field's form, through a
  small enhancement on the RFC 225 TypeScript rail (D-A, D-B).
- **The iOS screen shows two fields; the web shows four.** Living plan is left
  to chat on iOS, and dependency is unreachable over REST. D17 (gate 2) chose to
  show dependency on the web; living plan comes with it, with the D16 option
  captions.
- **The iOS screen has no per-field "what this improves" text** — only an intro
  sentence. The slice requires it, so each field gets a caption: income and
  state reuse the onboarding captions (RFC 268, copied from iOS onboarding);
  living plan and dependency get new copy (D-C).
- **Every money status is three-valued** — unanswered, answered, declined (the
  `money_profiles` CHECKs). `AnswerStatus.UNDECIDED` cannot occur on a money
  column; the page's exhaustive `when` names it and treats it as a fault.

### 2. Route

| Method + path           | Access                  | Does                                    |
| ----------------------- | ----------------------- | --------------------------------------- |
| `GET /account/details`  | `ONBOARDED`, a student  | the page, with any drafted picks        |
| `POST /account/details` | `ONBOARDED`, a student, | one field: save a pick, decline it, or  |
|                         | CSRF-checked            | remove the answer; then 303 back to GET |

`/account/details` is already `ONBOARDED` by `mapPathToAccess` (prefix
`/account/`). A supporter has no money profile: both methods redirect to
`AccountRole.homePath()` (`/account`), as the chat's student gate does.

**Three forms per field.** The pick form (hidden `field`, hidden `action=save`,
the select) is submitted by the script when the select changes to a real option;
a change back to the blank "Select" option submits nothing, as on iOS. "Prefer
not to say" and "Remove my answer" are each a one-button form (hidden `field`,
hidden `action`).

**The POST grammar.** Exactly one `field` ∈ `income_band`, `residency_state`,
`living_plan`, `dependency`, and exactly one `action` ∈ `save`, `decline`,
`remove`. For `save`, the control named after the field carries the pick, which
must be one of that control's options (`IncomeBand.fromValue`,
`MoneyProfileService.USPS_STATE_CODES`, `LivingArrangement.fromValue`,
`DependencyStatus.fromValue`).

| Posted                               | Result                                                        |
| ------------------------------------ | ------------------------------------------------------------- |
| not a form; unknown field or action; | 400, page re-rendered, nothing written, no draft — not this   |
| a pick no option carries             | page's submission (the change-email precedent)                |
| `save` with the blank option         | 400, "Choose an answer to save." under that field, no write   |
| `save` + pick                        | `FieldUpdate.Set(pick)` for that field only                   |
| `decline`                            | `FieldUpdate.Decline`                                         |
| `remove`                             | `FieldUpdate.Clear`                                           |
| write `Success`                      | that field emptied in the draft; 303 `?saved=<field>#<field>` |
| write failure, or `StudentNotFound`  | logged; 503, page re-rendered with "That didn't save. Try     |
|                                      | again." under the field; a `save` pick is kept in the draft   |

The blank-Save row is for a client other than the page's own script.

`?saved=<field>` is read through the field enum, so an unknown value renders
nothing (the `WebNotice` rule: no text from a URL reaches the page). The
fragment returns a phone to the field it just saved.

### 3. The draft (D11 — schema diff presented at approval)

```proto
message FormDrafts {
  ChangeEmailDraft change_email = 1;
  StudentSignupDraft student_signup = 2;
  MoneyProfileDraft money_profile = 3;
}

// The Your details form (public-web, /account/details, RFC 270). Each answer
// saves on its own, so a field here is a pick whose save did not succeed; it is
// emptied when that field next saves, and the message goes when every field is
// empty.
message MoneyProfileDraft {
  string income_band = 1;
  string residency_state = 2;
  string living_plan = 3;
  string dependency = 4;
}
```

Each control is a string, as posted (RFC 265). One `FormSlot`
(`MONEY_PROFILE_DRAFT`) beside the page code. Saving merges the one field into
the stored draft; a successful write empties that field, and an all-empty draft
is cleared through `clearDraft`. On GET a drafted field's select starts on the
drafted pick, and the field says "Not saved yet." with a "Save" button in the
pick form — re-choosing the same option fires no change event, so the button is
the way to retry.

### 4. The page

Server-rendered, `ShellWidth.WIDE`, under the signed-in header. Copy is the iOS
app's, verbatim, except where marked **new**.

| Element                  | Copy                                                                                                                        |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------- |
| Header link, heading     | Your details                                                                                                                |
| Intro                    | What unicoach knows about your family's money. Change any of it here — every price it shows you follows from these answers. |
|                          | (iOS says "these two answers"; the web has four)                                                                            |
| Intro, second line       | Every answer is optional, and each one saves as soon as you choose it. — **new**                                            |
| Answer line              | Not answered yet / You chose not to say / the option's caption                                                              |
| Select blank option      | Select                                                                                                                      |
| Decline button + caption | Prefer not to say — The coach stops asking about this. (shown unless already declined)                                      |
| Remove button + caption  | Remove my answer — Returns it to not answered. The coach may invite it again. (shown unless unanswered)                     |
| Receipt                  | Saved                                                                                                                       |
| Drafted pick             | Not saved yet. + a "Save" button — **new**                                                                                  |
| Blank Save               | Choose an answer to save. — **new**                                                                                         |
| Write fault              | That didn't save. Try again. — **new**                                                                                      |

The four fields, in this order:

| Field       | Label                      | What it improves (caption)                                                                                                                                         | Options                                                                 |
| ----------- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------- |
| Income      | Household income           | Net price varies a lot by income. With a bracket I can show your family's figure instead of the all-family average — about $1,376 a year for a middle bracket.     | `income_bands` vocabulary                                               |
| Residency   | State of residence         | Public colleges publish two prices. Knowing your state lets me show the one you'd actually pay — a median $6,300 a year difference.                                | `residency_states` vocabulary                                           |
| Living plan | Where you'd live — **new** | Housing and food is part of every college's price. Knowing where you'd live lets me use the right figure for it. — **new**                                         | D16: Living on campus / Renting off campus / Living at home with family |
| Dependency  | FAFSA dependency — **new** | On the FAFSA, a dependent student reports their parents' income. Knowing which you'll be lets me use the right federal loan limits and Pell Grant rules. — **new** | D16: I will be a dependent on the FAFSA / I will file as independent    |

The income and state labels are the onboarding controls' (`incomeSelect`,
`residencySelect` in `ProfileControls.kt`); their two captions move from
`GetStartedPage.kt` to `ProfileControls.kt` so both pages read one constant. The
D16 option captions are an exhaustive `when` over `LivingArrangement` and
`DependencyStatus` in `ProfileControls.kt` — web copy at the edge (D13), a
compile error if either enum grows. Each field is a `fieldset` with its label,
caption, answer line and its own `signedInForm`; on a phone the buttons wrap
under the select, each on the 44px tap floor.

**The enhancement.** `ts/autosave.ts`, mounted from `main.ts` like `backToTop`:
every `form[data-autosave]` submits itself (`requestSubmit()`) when its select
changes to a non-blank value. The rule — submit for a real option, not for blank
— is a pure function the Deno test exercises.

**The door.** `ShellNav.SignedIn` gains "Your details" between "Chat" and
"Account", its label and path read from the page's own constants.

### 5. JavaScript is required (D-F)

`siteLayout` — the one layout every page renders through — gains a `<noscript>`
in `head` linking `/noscript.css`, and a `<noscript>` at the top of `body` with
the notice. `noscript.css` hides `main` and shows the notice; a browser running
script loads neither. External stylesheet, so the content-security policy does
not change. If `kotlinx.html` cannot put `noscript` in `head`, the implementer
stops and says so rather than hiding `main` with a class the script removes
(that blanks every page until the bundle loads).

| Element        | Copy                                                                                               |
| -------------- | -------------------------------------------------------------------------------------------------- |
| No-script note | unicoach needs JavaScript. Turn it on in your browser's settings, then reload this page. — **new** |

Every page, signed in or not: Ian's words were "block any browser that does not
have javascript enabled". Search crawlers read the HTML, not the stylesheet, so
indexing is unaffected.

## Files Modified

- `db/src/main/proto/unicoach/session/v1/session_metadata.proto` —
  `MoneyProfileDraft`.
- `public-web/src/main/kotlin/ed/unicoach/web/auth/WebAuthGate.kt` —
  `YOUR_DETAILS_PATH`.
- `public-web/src/main/kotlin/ed/unicoach/web/details/YourDetailsRoute.kt` (new)
  — the two routes, the POST reading, the draft merge.
- `public-web/src/main/kotlin/ed/unicoach/web/render/YourDetailsPage.kt` (new) —
  the page and `MONEY_PROFILE_DRAFT`.
- `public-web/src/main/kotlin/ed/unicoach/web/render/ProfileControls.kt` — the
  two moved captions, living-plan and dependency selects with the D16 captions.
- `public-web/src/main/kotlin/ed/unicoach/web/render/GetStartedPage.kt` — reads
  the moved captions.
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt` — the header
  link.
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`, `Application.kt` —
  register the routes and pass `MoneyProfileService`.
- `public-web/src/main/resources/static/site.css` — the per-field form row;
  `noscript.css` (new) — the block.
- `public-web/src/main/ts/autosave.ts`, `autosave_test.ts` (new), `main.ts` —
  the enhancement.
- Tests under `public-web/src/test/kotlin/ed/unicoach/web/` (new
  `YourDetailsRoutingTest.kt`; `ShellNavTest.kt` for the link).

## Implementation Plan

1. Add `MoneyProfileDraft` to the proto; regenerate; add the `FormSlot`.
2. Move the two captions into `ProfileControls.kt`; add the living-plan and
   dependency selects with their D16 captions.
3. Write the page renderer per section 4.
4. Write the routes per section 2, wire them in `Routing.kt`/`Application.kt`,
   add the header link.
5. CSS for the field rows; `autosave.ts` and its test.
6. The no-script block in `siteLayout` and `noscript.css`.
7. Tests (below), then `nix develop -c bin/test` unscoped.

## Tests

All against the real database through the public-web test application, asserting
rendered HTML, redirects and stored rows, never source text.

1. **Door.** A student's signed-in header carries "Your details" →
   `/account/details`; the page renders all four fields, the two intro lines,
   each caption, and a CSRF field in every form.
2. **No profile row yet** → every field reads "Not answered yet", no remove
   button, a decline button on each.
3. **First-session test.** Save income only → 303 to
   `/account/details?saved=income_band#income_band`; the row has income answered
   and the other three unanswered; a later GET shows the income caption as the
   answer, "Saved" beside it on the redirect, and the other three still "Not
   answered yet".
4. **Each field, each action.** For all four fields: save a pick → answered with
   that value; decline → declined; remove → unanswered. A write leaves the other
   three fields as they were.
5. **D16 captions** render as the option labels and as the answer line for
   living plan and dependency.
6. **Refused postings** — unknown field, unknown action, a pick no option
   carries, a missing or doubled control, a body that is not a form → 400, no
   row change, no draft.
7. **Blank Save** → 400 with "Choose an answer to save.", no row change.
8. **Write fault** (a `MoneyProfileService` whose write fails) → 503 with "That
   didn't save. Try again." under the field; the draft holds the pick; a
   following GET preselects it and says "Not saved yet"; a successful save of
   that field empties it from the draft and leaves `sessions.metadata` with no
   `drafts`.
9. **Two drafted fields** — a successful save of one leaves the other's draft.
10. **Supporter** → GET and POST redirect to `/account`, nothing written.
11. **Wall and CSRF** — a verified user with no role is sent to `/get-started`;
    a POST without the token is refused and writes nothing.
12. **Unknown `saved` value** renders no receipt.
13. **Script contract.** Every pick form carries `data-autosave`, hidden `field`
    and `action=save`; no pick form has a Save button unless its field is
    drafted. Deno: the submit rule (real option yes, blank no) and `mount` on a
    page with no autosave form is a no-op.
14. **No-script block.** Every page (one signed-out, one signed-in) carries the
    head `noscript` stylesheet link and the body notice; `/noscript.css` is
    served.

## Decisions

- **D-A — each answer saves on its own.** Mirrors the iOS screen (one field per
  write). Meets the slice's first-session test with no new draft mechanism: an
  answered question is stored, so leaving the page loses nothing that was saved.
  Alternative: one form with one Save for all four, plus a script that saves the
  draft on every change so unsaved picks survive navigation — new mechanism, and
  a page-wide submit makes "remove one answer" awkward.
- **D-B — JavaScript is assumed; a pick saves on change.** Ian, at approval
  (2026-11-04): "We can assume that every browser has javascript. In fact, we
  should block any browser that does not have javascript enabled". So no Save
  button, as on iOS, except to retry a pick whose save failed.
- **D-C — new copy** marked **new** in section 4: the living-plan and dependency
  labels and captions, the second intro line, the retry Save button, the three
  status sentences, and the no-script notice.
- **D-D — the header link is shown to supporters too**, as "Chat" is today; a
  supporter who follows it lands on `/account`. Alternative: a role-aware
  header, which would need the role read on every signed-in page.
- **D-E — the D16 option captions live in `public-web`**, as an exhaustive
  `when`, not as a new served vocabulary. The iOS app does not show these two
  fields; when it does, promoting the captions to `VocabularyService` is that
  slice's work.
- **D-F — the no-script block is site-wide and lands in this RFC.** Ian's words
  name every browser, and this page is the first that does not work without
  script, so the block lands with it. Cost: a reader with script off (a few
  privacy-hardened browsers, some email link previews) sees only the notice on
  the marketing, legal, FAFSA and emailed cost-report pages too. Alternative:
  block only pages behind a session.
