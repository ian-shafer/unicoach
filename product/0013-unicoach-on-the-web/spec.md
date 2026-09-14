# Spec — brief 0013, Unicoach on the web

Slices for the web duplication of the iOS app. Brief handle: `web`. Gate 1
decisions D1-D14 are binding here and are not re-litigated.

## Preamble — what DISCOVER corrected

Written against real code, so slices are not built on assumptions. Citations are
in `research/discover-*.md`.

1. **`public-web` cannot import the REST server's types.** `ErrorCode`,
   `ErrorResponse`, `SessionConfig` and `resolveCaller` live in `:rest-server`,
   which `public-web` does not depend on. `FieldError` (`:common`) and
   `AuthService` (`:service`) ARE reachable. Session-cookie resolution for the
   web is therefore NEW code, not reuse — that work belongs to `web/02`.
2. **The web reaches the product in-process (D13), so REST DTOs do not bound
   it.** The money profile has FOUR tri-state fields — income, residency, living
   plan, dependency — and the REST API exposes three. The web can offer the
   fourth.
3. **There is no living-plan vocabulary.** `VocabularyService` registers exactly
   `income_bands` and `residency_states`. Living-plan and dependency labels
   exist only as coach-SENTENCE fragments ("renting off campus") and are
   unusable as picker captions. New copy is required (D14, and D16 below).
4. **The money profile has no optimistic-concurrency control.** Its PUT is an
   apply-or-keep upsert. College list and student DO use it: a plain integer
   version in the PATCH body (`?version=` on DELETE), answered with 409
   `version_conflict`. There is no ETag/If-Match. Every college-list form must
   carry the version, and `status` is non-nullable on PATCH, so a form must
   resend it even when unchanged.
5. **`sessions.metadata` is capped at 2048 bytes** by
   `sessions_metadata_size_check`, and nothing in Kotlin enforces it today. That
   2 KiB is the REAL draft budget, and canonical proto3 JSON is verbose — 64-bit
   integers render as strings, fields as lowerCamelCase. Raising the cap is
   load-bearing work in `web/05`, not a formality.
6. **Graduation date is a partial date** — `YYYY`, `YYYY-MM` or `YYYY-MM-DD`. A
   browser date input over-specifies it and must not be used bare.
7. **College search** takes `q` (<=100 chars) and `limit` (default 20, clamped
   1..25). There is NO pagination. A results page must not promise more.
8. **Streaming, in-process, is a `Flow<ReplyEvent>`** — `Delta(text)`, and the
   terminals `Completed(convoRequest, content, createdAt)` and
   `Failed(retriable, reason)` (`service/.../coaching/ReplyEvent.kt:17-34`).
   Budget exhaustion is NOT an event: it is a sibling result
   (`StartConvoResult.BudgetExhausted` / `PostTurnResult.BudgetExhausted`),
   known BEFORE the stream opens, so the web renders a page rather than breaking
   a stream mid-flight. The free allowance is `freeAllowanceUsd = 5.00`
   (`service/src/main/resources/service.conf:357`), spent once cumulatively, no
   monthly reset.
9. **The web defines its own browser-facing streaming format.** It does not
   consume the REST SSE endpoints (D13), so the REST event names are not a
   constraint — only `ReplyEvent` is.
10. **CALLING THE SERVICE DIRECTLY CAN SKIP WORK THE REST ROUTE DOES.** The
    extraction enqueue lives in `ConvoRoutes`, not in `CoachingService`. A web
    page that calls the service in-process (D13) would silently NOT enqueue it,
    and the divergence would be invisible until data went missing. This is the
    D13 risk made concrete, and `web/04` must close it by MOVING the behaviour
    into the service layer rather than copying the call into the web tier. A
    rule that has to be remembered in two places is a rule that will be wrong in
    one of them.
11. **A FAILED turn is invisible to the message list.** `ConvoProjection` omits
    failed turns, so on a plain re-render the student's OWN typed message
    appears to vanish. For a product whose stated rule is that no typing is ever
    lost, that is a defect, not a quirk — `web/04` owns it.
12. **The session cookie has NO Max-Age**, so it is a browser-session cookie:
    closing the browser signs the user out. On iOS this is invisible; on the web
    it is the difference between an app you stay signed into and one you do not.
    See D21.
13. **`SameSite=Strict` is hard-coded and there is no CSRF token anywhere.**
    Strict is what carries CSRF safety today. Any slice that loosens it — SSO
    redirect flows are the likely pressure — must add real CSRF protection in
    the same slice. See D22.
14. **Streaming admission failures are plain JSON BEFORE the stream opens**
    (401/409/404/400/402/413); only a provider failure is mid-stream at HTTP
    200. A fetch must send `Accept: text/event-stream, application/json` or
    errors return an empty 406 — the iOS client learned this the hard way. There
    is no `id:`/`retry:`/heartbeat framing, so there is no resume.

## Slices

Each slice is a self-contained /ship instruction. `Needs:` lines are read by
`slice-board`; every edge names a KIND and a REASON.

---

### `web/01/the-shell`

**Needs: —**

**What and why.** The responsive shell every later page renders into: layout,
navigation, and the CSS system that makes the 90% claim achievable. Today
`public-web` has one stylesheet, a single `max-width` column and one 640px
breakpoint — enough for a marketing page, not for an app.

**Foundations.** `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt`,
`src/main/resources/static/site.css`. Viewport meta is already correct.

**Decided here.** Fluid-first layout covering 320-1440px continuously; at most
three breakpoints; dynamic viewport units reserved for the chat composer;
signed-out and signed-in shells share one layout function.

**Left to /ship's design.** The CSS organisation (single file vs layered) and
the navigation component's markup.

**Acceptance criteria.**

- Every existing page (home, legal, cost report, verify-email, errors) renders
  through the new shell with no visual regression at 375px, 768px and 1280px.
- Mechanism tests pin what tests can honestly pin: viewport meta present,
  breakpoint rules present in the served stylesheet, no fixed pixel widths on
  layout containers. Tests MUST NOT claim to prove rendered responsiveness (D9).
- A content-security policy is added before any inline-script habit forms.
- **Door:** `app.<domain>` — already reachable; this slice changes how it looks
  at every width.

**First-session test.** A parent opening the site on an Android phone sees a
readable single column; the same URL on a desktop is not a stretched phone page.

---

### `web/02/sign-in`

**Needs: `web/01` — PREFER: sign-in pages can be built against today's minimal
layout, but the shell slice would restyle them immediately; building them in the
other order pays for the same markup twice.**

**What and why.** A student or parent signs in on the web and stays signed in.
This is the gate every later slice sits behind.

**Foundations.** `AuthService` (`:service`, reachable) for register/login/
verify/resend/change-email; the `UNICOACH_SESSION` cookie contract in
`rest-server.conf`; admin-web's cookie-auth gate as the shape to model, NOT to
import.

**Decided here.** Email+password only (D7 — Google/Apple land in `web/11`). The
web shares the existing session cookie (D2). Session resolution is new code in
`public-web` (preamble 1). Post-redirect-get everywhere.

**Left to /ship's design.** Where the resolved-caller type lives — new code in
`public-web` vs a type moved into `:web-common` so both servers share it. /ship
decides on the merits and says which it chose.

**Acceptance criteria.**

- Register, log in, log out, resend verification, change email all work; an
  unverified user is held at the verification screen exactly as the API's gate
  requires, and the page says what to do next.
- A validation error re-renders the form with the user's typed values. This does
  NOT satisfy D4 and the code comments must not claim it does (D12).
- Signing in on the web and then using the iOS app does not require signing in
  again, and the reverse holds.
- **Door:** a "Sign in" link in the shell's navigation.

**First-session test.** A student who has only ever used the iOS app opens the
site, signs in with the same credentials, and lands in the app — not on a
marketing page.

---

### `web/03/the-typescript-rail`

**Needs: `web/01` — PREFER: the rail's first real consumer is the chat view, and
its output is a static asset served by the shell; landing it before the shell
would pin asset paths that the shell then moves.**

**What and why.** The build path for every line of client-side TypeScript we
will ever ship. It exists as its own slice because the chat view is hard enough
without also inventing the toolchain inside it.

**Foundations.** Deno 2.7.12 in `flake.nix` (verified in the dev shell,
2026-09-14; `deno bundle` present), `deno.json` at the repo root, the existing
static mount in `public-web`.

**Decided here.** Deno owns check, lint, test and bundle (D8). Bundles are
content-hashed into the static mount and built by `bin/build-web-assets`,
following the `bin/` conventions: `getopts`, short options only, all logging to
stderr, nothing on stdout for a files-on-disk run.

**Left to /ship's design.** The source layout, the hashing scheme, and how the
bundle name reaches the rendered page.

**Acceptance criteria.**

- `bin/build-web-assets` produces a hashed bundle; the page references it by the
  built name, never a hand-typed string that can drift from the artifact.
- `deno check`, `deno lint` and `deno test` run in the existing gate, and a
  deliberately broken TypeScript file fails the build.
- The slice ships ONE trivial progressive enhancement end-to-end to prove the
  rail, and the page it enhances still works with JavaScript disabled.
- **Door:** none directly — this slice enables `web/04`. That is stated honestly
  rather than dressed up as user value.

**First-session test.** Not user-visible. The evidence is a passing gate and a
hashed asset served at the right URL.

---

### `web/04/the-chat`

**Needs: `web/02` — BLOCKS: every coaching call resolves a student from the
session; there is no anonymous coaching path.** **Needs: `web/03` — BLOCKS: the
streaming client is TypeScript and needs the build rail to reach the browser.**

**What and why. This is the slice where Unicoach becomes usable in a browser.**
A student types to the coach and watches the reply arrive.

**Foundations.** `CoachingService` in-process (D13); `Flow<ReplyEvent>` with
`Delta` / `Completed` / `Failed` (preamble 8); the Markdown subset the iOS
client renders, documented in `research/discover-markdown-subset.md`.

**Decided here.** The page is server-rendered and readable without JavaScript;
TypeScript upgrades it to live streaming. The web defines its own browser-facing
event format (preamble 9). Coach Markdown is rendered as HTML matching the iOS
subset — no wider, so the two clients cannot disagree about what the coach said.

**Left to /ship's design.** The browser-facing stream format, reconnection
behaviour, and the composer's focus and scroll choreography.

**Acceptance criteria.**

- A reply streams token-by-token; the composer stays usable while it streams.
- `Failed(retriable=true)` and `Failed(retriable=false)` are distinguishable to
  the user; neither loses the text the student already typed.
- Coach Markdown renders as the documented subset; anything outside it is
  escaped, never injected as HTML. The web needs a sanitisation policy the iOS
  client never required, and the slice states it explicitly — coach output is
  model-generated text reaching a browser, which is an injection surface iOS did
  not have.
- With JavaScript disabled the page still sends a message and shows the reply,
  via the non-streaming path. That path takes JSON today, so the slice adds
  form-encoded handling; it blocks for the whole turn, so the slice states the
  timeout it renders rather than hanging on the load balancer's 300s bound.
- **A failed turn never swallows the student's own words** (preamble 11). The
  text a student typed is still on screen after a failure, by the session draft
  from `web/05` or by an explicit failed-turn rendering — /ship picks one and
  says which. "It is in the database" is not an answer; the test is what the
  student sees.
- **The extraction enqueue moves into the service layer** (preamble 10), so the
  web and the iOS app cannot diverge on it. The slice proves it with a test that
  fails if either caller skips it.
- Budget exhaustion renders the out-of-allowance page (`web/10` owns the copy);
  it is known before the stream opens, so the student never sees a stream break
  mid-sentence.
- **Door:** the signed-in root is the chat, exactly as the iOS app opens on it.

**First-session test.** A student signs in on a laptop, types a question, and
sees the coach answer arrive live — the same product the iOS app gives, in a
browser.

---

### `web/05/session-form-state`

**Needs: `web/02` — BLOCKS: form state is keyed on a session and D5 mints no
anonymous sessions, so there is nothing to key on before sign-in.**

**What and why.** The mechanism behind D4 and D12: wherever a session exists,
every form stores its data in it, so a validation error or a page navigation
never costs a family their typing.

**Foundations.** `sessions.metadata` (JSONB, currently capped at 2048 bytes and
written by nobody — preamble 5); `SessionsDao`; protobuf's `JsonFormat`.

**Decided here.** Real protobuf at runtime, canonical proto3 JSON at rest in the
existing JSONB column (D4b). `protoc` enters `flake.nix`; the build gains a
generation step. The column keeps its type; its CHECK limit is raised.

**THIS SLICE PRESENTS TWO THINGS AT ITS APPROVAL GATE, EXPLICITLY, NOT BURIED IN
THE RFC**: the migration's DDL (standing D10 of brief 0001) and the full
`.proto` schema (D11, standing). A /ship run that presents only one has not met
the instruction.

**Left to /ship's design.** The message shape, how a form identifies itself, and
the read-through/clear points in the request lifecycle.

**Acceptance criteria.**

- The new size cap is **measured, not guessed**: the run records the encoded
  size of the largest form in scope and sets the CHECK with stated headroom. The
  measurement appears in the RFC and in the run report.
- Overflow behaviour is explicit and tested. A draft that exceeds the cap must
  fail loudly in a way the user can act on — never silent truncation, never a
  silently dropped draft. Silent loss is the exact failure D4 exists to prevent.
- Writing a draft costs ONE update of the session row per POST, and the slice
  states how that interacts with the session-expiry refresh.
- `NewSession.metadata` / `Session.metadata` move from an untyped `String?` to
  the typed message. No backfill is needed and the RFC says why: every
  production caller passes null today.
- **Door:** none directly; the first user-visible effect lands in `web/06`.

**First-session test.** Not user-visible alone. Its evidence is `web/06`'s.

---

### `web/06/onboarding`

**Needs: `web/05` — BLOCKS: D12 requires session-backed drafts wherever a
session exists, and this is the first form behind sign-in.**

**What and why.** After first sign-in, the web invites the student to say when
they graduate and, optionally, answer the money questions — the same shape the
iOS app uses.

**Foundations.** `POST /students` and `PATCH /students/me` equivalents in
`:service`; graduation date is a PARTIAL date (preamble 6).

**Decided here.** Value before ask (standing, brief 0001 D12): an invitation,
never a wall. Start, stop, resume, later. Declining narrows what the coach can
do and the page says so honestly. Nothing is gated on completion.

**Acceptance criteria.**

- Every field can be skipped, and skipping reaches the chat immediately.
- A partially completed form survives leaving the page and coming back (D4/D12
  in action, on the foundation from `web/05`).
- The graduation-date control accepts year alone, year and month, or a full
  date; a bare browser date input is not used (preamble 6).
- **Door:** offered after first sign-in, and re-reachable later from the menu —
  declining must not hide it forever.

**First-session test.** A new student signs up on the web, skips every question,
and is talking to the coach within one screen.

---

### `web/07/your-details`

**Needs: `web/05` — BLOCKS: the money form is the largest draft in the product
and is the reason the form-state budget is measured.** **Needs: `web/06` —
CONFLICTS: both edit the onboarding/money form components; expect a rebase, not
an ordering.**

**What and why.** The iOS "Your details" screen on the web: the money profile a
family can review and change at any time.

**Foundations.** `MoneyProfileService` (all four fields reachable in-process —
preamble 2); `income_bands` and `residency_states` vocabularies (59 USPS codes);
NO optimistic concurrency on this surface (preamble 4).

**Decided here.** Captions for living plan and dependency are the approved copy
from D16 below, not the coach sentence fragments. Whether the fourth field
(dependency) is shown is D17 below.

**Acceptance criteria.**

- Every answer is optional and individually clearable; a half-answered profile
  is a legitimate state that the page describes without nagging.
- The page says plainly what each answer improves, before asking for it.
- Drafts survive errors and navigation (D4/D12).
- **Door:** "Your details" in the signed-in menu.

**First-session test.** A parent opens Your details on a tablet, answers only
the income question, navigates away mid-form, returns, and finds their answer
still there.

---

### `web/08/college-list`

**Needs: `web/05` — BLOCKS: the add/edit forms must hold drafts.** **Needs:
`web/07` — CONFLICTS: shared form components; a rebase risk, not an order.**

**What and why.** The student's college list on the web: browse, search, add,
edit, remove.

**Foundations.** College-list CRUD in `:service`; search takes `q` (<=100) and
`limit` (default 20, clamped 1..25) with NO pagination (preamble 7); optimistic
concurrency via an integer version answered with 409 `version_conflict`, and
`status` is non-nullable on PATCH (preamble 4).

**Acceptance criteria.**

- Every edit form carries the version and resends `status`; a 409 is recovered
  the way the iOS app recovers it — reload and tell the user the list moved,
  never a silent overwrite and never a dead end.
- The results list does not offer paging the API cannot serve, and says when
  results were capped.
- Data-dense views become cards below the small breakpoint rather than scrolling
  tables sideways.
- **Door:** "My colleges" in the signed-in menu.

**First-session test.** A student on a phone searches a college, adds it, and
sees it in their list without a horizontal scrollbar anywhere.

---

### `web/09/conversations`

**Needs: `web/04` — BLOCKS: the list's only purpose is navigating into the chat
page, which that slice creates.**

**What and why.** History: past conversations, openable, archivable, deletable.

**Acceptance criteria.**

- Archive and delete are reachable without a swipe gesture, which does not exist
  on a desktop.
- Deleting asks once and says what is lost.
- **Door:** the slide-over menu's conversation list, in the shell.

**First-session test.** A returning student opens yesterday's conversation from
a desktop browser and keeps talking.

---

### `web/10/settings-and-allowance`

**Needs: `web/02` — BLOCKS: every setting is a property of the signed-in
account.**

**What and why.** Settings on the web, plus the honest page a web user meets
when the free allowance runs out.

**Foundations.** Appearance (iOS persists it locally; the web has
`prefers-color-scheme` plus a cookie); change email and logout via
`AuthService`; the coaching-usage read; `freeAllowanceUsd = 5.00` (preamble 8).

**Decided here.** No web billing (D6). An iOS subscriber is entitled on the web
automatically. A web-only user who exhausts the allowance is told the truth:
subscribing happens in the iOS app today. The exact wording is D18 below,
because this is the one page where our platform gap is visible to a family.

**Acceptance criteria.**

- The usage meter reads the same numbers the iOS app shows.
- The out-of-allowance page names the door that DOES work, and does not imply a
  web purchase exists.
- An Apple-billed subscriber sees where to manage their subscription.
- **Door:** "Settings" in the signed-in menu; the allowance page appears in the
  chat flow at exhaustion.

**First-session test.** A parent who has never used the iOS app spends the free
allowance and is told, without spin, exactly how to continue.

---

### `web/11/sso`

**Needs: `web/02` — BLOCKS: shares the sign-in pages and the session it
establishes.**

**What and why.** Google and Apple sign-in on the web, so an iOS user who signed
up with Apple can get in at all.

**Foundations.** `POST /auth/google` and `/auth/apple` verify ID tokens with
env-configurable audience lists; web client IDs and the Apple Services ID are
CONFIG, not code. Apple's name-on-first-authorization needs a web home, as the
iOS client already handles.

**Acceptance criteria.**

- A user who created their account with Apple on iOS can sign in on the web and
  reach the same data.
- The first-authorization name is captured on the web too, or its absence is
  handled without an empty-name account.
- Audience configuration is documented; a misconfigured audience fails with a
  diagnosable error, not a blank page.
- **Door:** buttons on the sign-in page from `web/02`.

**First-session test.** A student who has only ever tapped "Sign in with Apple"
opens the site and gets in.

---

### `web/12/password-reset`

**Needs: `web/02` — BLOCKS: shares the auth surface.**

**What and why.** There is NO password-reset route anywhere in the backend
today. On iOS that is survivable behind SSO; on the web it strands users.

**Foundations.** Email verification is the pattern to mirror — token issuance,
storage, expiry, single use, and the email send path.

**Decided here.** Reset is built server-side as a sibling of verification, not
bolted onto it. Any new table comes with visible DDL at the /ship gate (standing
D10).

**Acceptance criteria.**

- A token is single-use and expires; using it twice fails safely.
- The request form reveals nothing about whether an address has an account.
- Resetting a password invalidates existing sessions, or the slice states why it
  does not.
- **Door:** "Forgot your password?" on the sign-in page.

**First-session test.** A parent who forgot their password recovers it without
contacting anyone.

## Waves

Slices in a wave can run in parallel; a CONFLICTS pair may share a wave and
simply rebases.

- **Wave 1** — `web/01`
- **Wave 2** — `web/02`, `web/03`
- **Wave 3** — `web/04`, `web/05`
- **Wave 4** — `web/06`, `web/09`, `web/10`, `web/11`, `web/12`
- **Wave 5** — `web/07`, `web/08` (conflicting pair, shared form components)

## Gate 2 — decisions

**D15 — slice order and waves as written above.** Twelve slices; the product
becomes usable in a browser at `web/04`. COMMITTED (gate 2, Ian: "approve all
decisions").

**D16 — living-plan and dependency captions.** New copy, because none exists
(preamble 3). Proposed:

| Value                   | Caption                            |
| ----------------------- | ---------------------------------- |
| Living plan: on campus  | Living on campus                   |
| Living plan: off campus | Renting off campus                 |
| Living plan: at home    | Living at home with family         |
| Dependency: dependent   | I will be a dependent on the FAFSA |
| Dependency: independent | I will file as independent         |

COMMITTED (gate 2, Ian: "approve all decisions").

**D17 — show the fourth money field (dependency) on the web?** The web can offer
it in-process; the iOS app cannot show it today, so answering here makes the web
briefly richer than the app. Default: YES, show it, because the question changes
what the coach can say about aid. COMMITTED (gate 2, Ian: "approve all
decisions").

**D18 — what the out-of-allowance page says to a web-only family.** This is
where our platform gap becomes visible. Default: name the iOS app as the way to
continue, state plainly that web purchase is not available yet, and keep every
non-coaching surface working. COMMITTED (gate 2, Ian: "approve all decisions").

**D19 — the device matrix that evidences the 90% claim.** Default: iPhone SE
(375x667), a common Android phone (412x915), iPad portrait and landscape
(820x1180, 1180x820), laptop (1440x900), and a short desktop window (1280x720)
to exercise height. Checked per slice that adds a surface. COMMITTED (gate 2,
Ian: "approve all decisions").

**D21 — should the web keep a family signed in after they close the browser?**
The session cookie has no Max-Age today, so it dies with the browser session
(preamble 12). Default: YES — give the web cookie the same 7-day life the server
already grants the session, so the web behaves like the app a family already
uses. COMMITTED (gate 2, Ian: "approve all decisions").

**D22 — CSRF protection now, or only when something loosens `SameSite`?**
Default: add CSRF tokens in `web/05` alongside the form rail, rather than
waiting for the SSO slice to discover it needs them. A forms-heavy product
should not have its safety depend on one hard-coded cookie attribute nobody has
revisited. COMMITTED (gate 2, Ian: "approve all decisions").

**D20 — does the chat page have to work with JavaScript disabled?** Default: YES
— the non-streaming path already exists server-side, so the fallback is cheap,
and it is also the honest answer to a flaky phone network. COMMITTED (gate 2,
Ian: "approve all decisions").
