# RFC 268 — Web onboarding

**Slice:** `web/06/onboarding` (brief 0013, _Unicoach on the web_). **Status:**
proposed. **Base:** `main@5160de7d`. **Lane:** rfc.

## Executive Summary

A verified web user with no student profile is sent to `/get-started`, which
today renders a placeholder telling them to use the iPhone app (RFC 262 D-B).
This RFC replaces it with real onboarding, in the iOS app's shape (RFC 241, RFC
163, RFC 222): first "Who are you here for?" — the student, or someone
supporting a student — then, for a student, one form: date of birth, expected
high-school graduation date (year, year and month, or a full date), and two
optional money answers, state of residence and household income.

Onboarding is REQUIRED (Ian, at approval, D-E). An account that is neither a
student nor a supporter can reach only onboarding, sign-out and the pages that
never needed an account; the chat and every account page send it to
`/get-started`.

The student form is on RFC 265's session-draft rail: a refused submission keeps
what the student picked, so leaving and coming back finds it again. The date of
birth is the one control the draft never holds (D-B).

## Detailed Design

### 1. What the code says, where it differs from the slice

- **Two answers are required.** RFC 222's 13+ age floor means
  `StudentService.createStudent` makes no student without a date of birth, and
  `students.expected_high_school_graduation_year` is `NOT NULL`
  (`db/schema/0005.create-students.sql`). Both are preconditions of a student,
  and the chat needs a student (`ChatStudentGate`) (D-A).
- **Onboarding is a wall, not an invitation** (D-E). The slice's "every field
  can be skipped, and skipping reaches the chat immediately", its "nothing is
  gated on completion", and its menu door ("declining must not hide it forever")
  are replaced: there is nothing to decline, so there is no door back to find.
  The two money answers stay optional inside the form.
- **The web has no role choice.** A parent who registers on the web without an
  invite is sent to the student form today — the iOS defect RFC 241 fixed (brief
  0014 correction X4). This RFC brings the same choice to the web.
- **Living plan and dependency are not asked here.** The iOS onboarding asks
  only state and income; the other two belong to `web/07`'s Your details screen
  with the D16 captions.
- **The age refusal sentence lives in `:rest-server`** (`StudentRoutes`). The
  web must say the same sentence, so it moves to `:service` beside
  `MINIMUM_AGE_YEARS` and both callers read it (brief 0013 D13: one home). The
  supporter-closed sentence (`SupporterRoutes.respondSupporterClosed`) moves the
  same way.

### 2. The account's role — one read, in `:service`

A new `AccountRoleService.roleOf(userId): Result<AccountRole>`, where
`AccountRole` is sealed: `Student(student)`, `Supporter(supporter)`, `None`. A
student row wins when both exist (it is what `ChatStudentGate` checks first
today). This is the server-side twin of iOS's `resolveProfileState`, and the one
home every web check below reads — `ChatStudentGate`, the onboarding wall, and
`/get-started` itself — so the three cannot disagree about who has onboarded.

### 3. The wall

A route that requires onboarding is DECLARED so where it is registered, and
enforced after routing, on RFC 224's rule (a raw-path match is an optimisation,
never the authority). A fourth `WebAccess` class, `ONBOARDED` — verified AND
`AccountRole` is not `None` — with `getOnboarded`/`postOnboarded` beside
`getVerified`. A caller who fails only the role check is redirected to
`/get-started` (302 on GET, 303 otherwise, via `respondGateRedirect`).

| Path                                                             | Access after this RFC                                                                                            |
| ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `/chat`, `/chat/{id}`                                            | `ONBOARDED` (+ `ChatStudentGate`)                                                                                |
| `/account`, `/account/email`                                     | `ONBOARDED`                                                                                                      |
| `/get-started`, `/get-started/student`, `/get-started/supporter` | `VERIFIED`                                                                                                       |
| `/sign-out`, `/verify-email/resend`                              | `SIGNED_IN` (unchanged)                                                                                          |
| `/invite`, `/report`, `/report/connect`, marketing, legal        | unchanged — an invite or a connect request makes its own supporter row, so it is itself a way through onboarding |

`ChatStudentGate` keeps its job — a supporter is not a student and goes to
`/account` — but reads `AccountRole`. `mapPathToAccess` classifies the new paths
for the early redirect.

**The nav during onboarding.** `ShellNav` gains an `Onboarding(csrf)` variant
that renders only "Sign out": "Chat" and "Account" would each bounce straight
back. Every page under `/get-started` renders with it.

### 4. Routes

**`GET /get-started`** — `AccountRole.Student` → 302 `/chat`;
`AccountRole.Supporter` → 302 `/account`; `None` → the role choice.

**`POST /get-started/supporter`** (CSRF-checked) — `SupporterService.ensure`:
`Created`/`AlreadyPresent` → 303 `/account`; `Closed` → re-render the role
choice at 409 with the `:service` sentence. A student → 303 `/chat`.

**`GET /get-started/student`** — Student → 302 `/chat`; Supporter → 302
`/account`; `None` → the form, prefilled from the `student_signup` draft.

**`POST /get-started/student`** (CSRF-checked):

1. Student → clear draft, 303 `/chat`. Supporter → 303 `/account`.
2. A body that is not a form, or a select value outside its option list (income
   band, residency code, a month or day out of range, a year that is not four
   digits), is not this form's submission: re-render at 400, no draft saved —
   the change-email precedent. A four-digit year outside the rendered window is
   left to `:service`, whose rule it is; a page rendered on 31 December must
   still post on 1 January.
3. Date of birth: all three parts chosen, or the form refuses with the iOS
   message "Select your date of birth." before `:service` is called.
4. Graduation date: the year is required; a day without a month is refused at
   the form ("Choose a month to give a day."). The parts are assembled to
   `YYYY`, `YYYY-MM` or `YYYY-MM-DD` for `createStudent`, which owns validity
   (`PartialDate.parse` refuses 31 February).
5. `createStudent` outcome:
   - `Success` → if either optional answer was given, one
     `MoneyProfileService.upsert` with `FieldUpdate.Set` for each given answer
     and nothing for a blank one. A failure of that second write is logged at
     WARN and swallowed, as iOS swallows it: the student exists and the coach
     can ask. Clear the draft, 303 `/chat`.
   - `ValidationFailure` → re-render 400 with `:service`'s field messages, save
     the draft.
   - `UnderMinimumAge` → re-render 403 with the `:service` sentence, the whole
     form blank, the draft CLEARED. Nothing about the attempt is stored (RFC
     222).
   - `AlreadyExists` → clear draft, 303 `/chat`.

A form-level refusal at step 3 or 4 also saves the draft. Validation messages
are never stored (RFC 265).

### 5. The draft (D11 — schema diff presented at approval)

```proto
message FormDrafts {
  ChangeEmailDraft change_email = 1;
  StudentSignupDraft student_signup = 2;
}

// The student signup form (public-web, /get-started/student, RFC 268). The date
// of birth is deliberately absent: RFC 222 keeps an under-13 child's
// information out of the database, and a draft is written on a refused POST —
// including one refused for another field while the date of birth is a
// child's.
message StudentSignupDraft {
  string graduation_year = 1;
  string graduation_month = 2;
  string graduation_day = 3;
  string residency_state = 4;
  string income_band = 5;
}
```

Each control is a string, as posted (RFC 265). One `FormSlot`
(`STUDENT_SIGNUP_DRAFT`) beside the page code. The role choice has no typing to
keep and no draft.

### 6. The pages

Server-rendered, no JavaScript. Copy is the iOS app's, verbatim, except where a
web control needs words iOS does not (marked **new**).

**Role choice** (`/get-started`): heading "Welcome, ⟨name⟩"; "Who are you here
for?"; two equal buttons, "I'm the student" (a link to `/get-started/student`)
and "I'm supporting a student" (a POST form). Neither is styled as the default
(RFC 241: no wrong answer).

**Student form** (`/get-started/student`):

| Element                     | Copy                                                                                                                                                           |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Heading                     | Welcome, ⟨name⟩                                                                                                                                                |
| Date of birth legend        | When were you born?                                                                                                                                            |
| DOB parts                   | Year / Month / Day selects, all starting blank, no caption (RFC 222's neutral age screen)                                                                      |
| Graduation legend           | When do you graduate?                                                                                                                                          |
| Graduation caption          | Sets your application deadlines and the award years I price against.                                                                                           |
| Graduation parts            | Year (current year − 4 … + 8, blank start), Month (optional), Day (optional) — **new**: the "(optional)" labels replace iOS's precision picker                 |
| Optional overline           | Optional — you can add these any time                                                                                                                          |
| Optional body               | Answer now or later; just ask me in chat. I'll work without them.                                                                                              |
| State caption               | Public colleges publish two prices. Knowing your state lets me show the one you'd actually pay — a median $6,300 a year difference.                            |
| Income caption              | Net price varies a lot by income. With a bracket I can show your family's figure instead of the all-family average — about $1,376 a year for a middle bracket. |
| State / income blank option | Not answered — **new**                                                                                                                                         |
| Submit                      | Create Profile                                                                                                                                                 |
| Back                        | I'm not the student (a link to `/get-started`)                                                                                                                 |

Income options are `IncomeBand.bracket` labels and residency options the
`residency_states` vocabulary, both through `VocabularyService`. Blank means
unanswered, never declined (RFC 163). Birth years run from the current year back
100 (the iOS window). Month names are the abbreviations `UsDate` renders (`Jan`
… `Dec`): RFC 227 lets only `UsDate` name a month. No `<input type="date">`
anywhere.

**Reusable controls (web/07 CONFLICTS).** A labelled `<select>` joins
`FormControls.kt`; a partial-date group (year/month/day selects in a
`fieldset`/`legend`) and the residency and income selects are their own
functions in `render/ProfileControls.kt`, taking the current value and the
error, so `web/07` reuses them. CSS: selects get the existing field styling and
the 44px tap floor; a date group lays its selects out in one row that wraps
under 375px; the two role buttons stack under 640px.

## Files Modified

- `db/src/main/proto/unicoach/session/v1/session_metadata.proto` —
  `StudentSignupDraft`.
- `service/src/main/kotlin/ed/unicoach/student/AccountRoleService.kt` (new) —
  `AccountRole` and `roleOf`.
- `service/src/main/kotlin/ed/unicoach/student/StudentService.kt`,
  `service/src/main/kotlin/ed/unicoach/family/SupporterService.kt` — the age and
  supporter-closed refusal sentences, moved here.
- `rest-server/.../routing/StudentRoutes.kt`, `SupporterRoutes.kt` — read them.
- `public-web/src/main/kotlin/ed/unicoach/web/auth/RouteAccess.kt`,
  `WebAuthGate.kt` — `ONBOARDED`, the new paths.
- `public-web/src/main/kotlin/ed/unicoach/web/signup/GetStartedRoute.kt` — the
  four routes.
- `public-web/src/main/kotlin/ed/unicoach/web/chat/ChatRoutes.kt` — `ONBOARDED`;
  `ChatStudentGate` reads `AccountRole`.
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`, `Application.kt` —
  `/account` routes `ONBOARDED`; services wired.
- `public-web/src/main/kotlin/ed/unicoach/web/render/GetStartedPage.kt` — role
  choice and form; placeholder constants deleted.
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt` —
  `ShellNav.Onboarding`.
- `public-web/src/main/kotlin/ed/unicoach/web/render/FormControls.kt`,
  `ProfileControls.kt` (new) — selects and the date group.
- `public-web/src/main/resources/static/site.css` — select, date-group and role
  button rules.
- Tests under `public-web/src/test/kotlin/ed/unicoach/web/` (new
  `GetStartedRoutingTest.kt`; existing chat, account and CSRF routing tests
  updated where a test user now needs a role), `service` tests for
  `AccountRoleService`, `rest-server` tests touching the moved sentences.

## Implementation Plan

1. Move the two refusal sentences to `:service`; point the REST routes at them.
2. Add `AccountRoleService`; switch `ChatStudentGate` to it.
3. Add `WebAccess.ONBOARDED` and its wrappers; register `/chat` and `/account`
   routes with them; fix existing tests whose verified test user has no role.
4. Add `StudentSignupDraft` to the proto; regenerate; add the `FormSlot`.
5. Add `ShellNav.Onboarding`, the select control, `ProfileControls.kt`, CSS.
6. Replace the placeholder with the role choice and the student form, and write
   the four handlers per section 4.
7. Tests (below), then `nix develop -c bin/test` unscoped.

## Tests

All against the real database through the public-web test application, asserting
rendered HTML, redirects and stored rows, never source text.

1. **The wall.** A verified user with no role: `GET /chat`, `GET /account`,
   `GET /account/email` and `POST /account/email` all redirect to
   `/get-started`, and the POST changes nothing. The same user reaches
   `/get-started`, `/get-started/student`, sign-out, `/invite` and the public
   pages. A student and a supporter reach `/account`.
2. **Role read.** `AccountRoleService`: none, student only, supporter only, both
   (→ student).
3. **Role choice.** GET with no role renders both choices and only "Sign out" in
   the nav. With a student → 302 `/chat`; with a supporter → 302 `/account`.
4. **Supporter path.** POST `/get-started/supporter` → a supporter row exists,
   303 `/account`, and `/account` now renders. A closed supporter → 409 with the
   `:service` sentence, no new row.
5. **Student form GET.** No `type="date"` input; three graduation and three
   birth selects; CSRF field. A supporter → 302 `/account`.
6. **Happy path at each precision** — year only, year and month, full date —
   creates the student with that `PartialDate` and 303s to `/chat`, which now
   renders.
7. **Optional answers.** Both given → money profile has both answered; neither →
   no money profile row, still 303 `/chat` (the first-session test: one screen
   after the role choice, to the coach).
8. **Missing date of birth** → 400, "Select your date of birth.", no student;
   the draft holds the graduation and money values and NO date of birth; a
   following GET prefills them.
9. **Under 13** → 403 with the `:service` sentence; no student;
   `sessions.metadata` holds no draft and no birth date.
10. **Under 13 plus an invalid graduation date** → 400 for the graduation date;
    the saved draft holds no birth date.
11. **Day without month** → 400 with the form message; draft saved.
12. **Off-vocabulary income or residency** → 400, nothing written.
13. **CSRF** — both POSTs without the token are refused and write nothing.
14. Success clears the draft.
15. The REST under-13 and supporter-closed responses carry the same sentences as
    before.

## Decisions

- **D-A — the date of birth and the graduation date are required.** The age
  floor (RFC 222) and the schema make both preconditions of a student. Making
  graduation optional would be its own slice (DDL on `students`, every reader,
  the iOS model).
- **D-B — the date of birth never enters the draft.** An exception to brief 0013
  D12's "every form stores its data", for RFC 222's reason.
- **D-C — no menu door back to onboarding.** Superseded by D-E: nothing can be
  declined, so nothing needs a way back. The optional money answers are
  re-reached through `web/07`'s Your details and, until then, in chat.
- **D-D — the draft is kept on a refused submission only** (RFC 265's rail as
  built). Typing that is never submitted is not kept.
- **D-E — onboarding is required, for students and supporters.** Ian, at
  approval (2026-11-02): "We should force the student (or supporter) through the
  onboarding steps. It makes no sense for them to be able to "use" the app
  without onboarding". Overrides, for the web, the slice's value-before-ask
  framing (brief 0001 D12) and its skip and menu-door criteria. The supporter's
  onboarding is the role choice, as on iOS (RFC 241).
