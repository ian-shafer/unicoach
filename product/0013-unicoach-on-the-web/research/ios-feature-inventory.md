# iOS feature inventory — the checklist for "Unicoach on the web"

**Brief:** 0013 "Unicoach on the web" — duplicate iOS app functionality into the
public web (server-side rendered, fully responsive).

## Method

I read every non-vendored Swift source under `ios-app/UnicoachiOS/` in the root
checkout `/Users/ian/Work/unicoach` (76 files, ~713 KB; vendored SDKs under
other `ios-app/` paths were ignored). For each screen/flow I read the View +
ViewModel pair, and for endpoints I read the client layer (`APIClient`,
`AuthClient`, `StudentClient`, `ConversationClient`, `CollegeListClient`,
`MoneyProfileClient`, `CoachingUsageClient`, `SubscriptionClient`,
`VocabularyClient`) and cross-checked with `grep -rn '/api/v1'` over the app
sources. Every endpoint listed below was found as a literal path in client code;
screen behavior comes from the view/view-model source and its doc comments. I
did **not** run the app or the backend; claims about server behavior are what
the client code asserts about it.

**Honest gaps:** I did not verify against the server's route files or
`api-specs/openapi.yaml` (a sibling research task's territory). I could not find
any "cost report" or "birthday" surface in the iOS app (see §Not-in-the-app
below), and no account-deletion, push-notification, App Check/attestation, or
Keychain usage exists in these sources — absence claims are grep-verified.

## 0. App skeleton and cross-cutting machinery

**Root state machine** (`UnicoachiOSApp.swift`, `AppViewModel.swift`,
`UserAuthState.swift`). On launch the app calls `GET /api/v1/auth/me`; the
answer routes to one of: `loading` (spinner) / `unauthenticated`
(login+register) / `onboarding` (account exists, no student profile) /
`verificationRequired` (email not verified) / `authenticated` (main app) /
`serverError` / `unexpectedError` / `noConnectivity` (each a full-screen
`ErrorView` with a Retry). The `.authenticated` vs `.onboarding` split is
decided by `GET /api/v1/students/me` (404 → onboarding). Web equivalent: the
same state machine as server-side routing/redirects.

**Transport conventions** (`APIClient.swift`):

- Base URL and a static client key are baked in at build time
  (`BackendURL.swift`, `ClientKey.swift`); every request carries
  `X-Unicoach-Client-Key` when a key is present. A first-party web app served by
  the same origin **needs a decision** on whether this header gate applies to it
  at all.
- Session auth is a **cookie** (`HTTPCookieStorage`; logout deletes cookies
  client-side as a backstop, `AppViewModel.swift:179`). This transfers to web
  natively.
- Errors are a uniform JSON `ErrorResponse {code, message, fieldErrors}`
  vocabulary (`Models.swift`, `ServerErrorCode` enum: `unauthorized`,
  `student_profile_required`, `version_conflict`, `conflict`, `not_found`,
  `coaching_budget_exhausted` (402), `validation_failed`, …). The web layer must
  branch on the same codes.
- Streaming: SSE over `POST` with `Accept: text/event-stream, application/json`,
  60 s inter-chunk idle timeout, hand-rolled frame assembly (`SSEParser.swift`,
  `ConversationClient.swift`). Browsers' native `EventSource` cannot POST — the
  web client needs `fetch()` + ReadableStream (or a different server contract).
  **Adapt.**
- Any `409 student_profile_required` from a turn/list/profile write routes the
  whole app back to onboarding (`onProfileRequired` threaded through every
  screen).

**Design system** (`DesignSystem/Theme.swift`, `Components.swift`):
token-driven; hairline borders, no shadows/elevation, one accent. Transfers as
CSS tokens.

## 1. Auth: login, registration, SSO

**Login** (`LoginView.swift`, `LoginViewModel.swift`). Email+password form, plus
**Sign in with Apple** and **Sign in with Google** buttons above an "or"
divider; link to switch to Register. Infra failures get a full-screen retryable
error; auth failures an inline banner.

- `POST /api/v1/auth/login` (200 → `LoginResponse{user}`, sets session cookie)
- `POST /api/v1/auth/google` body `{idToken}` (`SsoSignIn.swift:126`)
- `POST /api/v1/auth/apple` body `{idToken, name?}` (`SsoSignIn.swift:127`)

Platform-specific: `GoogleSignInProvider.swift` uses the native GoogleSignIn
SDK; `AppleSignInProvider.swift` uses `ASAuthorizationController`. On web these
become Google Identity Services (web) and **Sign in with Apple JS** — both still
produce an ID token, but the web flows are redirect/popup-based and the Apple
`name`-on-first-auth quirk (`AppleNameStore.swift` persists it in UserDefaults
and resends) needs a web equivalent (Apple's web flow also discloses name only
once). **Adapt; token-endpoint reuse needs verification** (the backend may need
web client IDs/audiences registered).

**Registration** (`RegistrationView.swift`). Email, name, password; per-field
server errors (`fieldErrors`) rendered under fields; switch-to-login link.

- `POST /api/v1/auth/register` (201). New account lands in email verification.

**Logout**: `POST /api/v1/auth/logout` (204), from Settings.

## 2. Email verification (`VerificationRequiredView.swift`, `VerificationViewModel.swift`)

Blocking screen for an unverified account: shows the address the link went to,
**Check Again** (re-runs `GET /api/v1/auth/me`), **Resend Email**
(`POST /api/v1/auth/resend-verification`, 204), **Change Email** (opens the
change-email sheet), and **Log Out**. iOS silently re-checks on every
return-to-foreground (`scenePhase`) because the user leaves the app to tap the
link (`UnicoachiOSApp.swift`). Web equivalent: re-check on tab focus/interval —
or better, the emailed link can land directly in the web app. **Adapt**
(foreground detection → visibilitychange).

## 3. Onboarding (`OnboardingView.swift`, `OnboardingViewModel.swift`)

Shown when the account has no student profile. "Welcome, ⟨name⟩", a 3-step
indicator, then:

- **Required:** expected high-school graduation date, with selectable precision
  Year / Year+Month / full date (day/month clamped calendrically); sent as an
  ISO-8601 string.
- **Optional (never "declined" from this screen):** household income band and
  state of residency — static pickers (`ResidencyStates.swift` ships a fallback
  list).
- `POST /api/v1/students` body `{expectedHighSchoolGraduationDate}` (201)
- `PUT /api/v1/students/me/money-profile` for whichever optional answers were
  given (failures on this second write are deliberately swallowed — onboarding
  still completes).

Note: this graduation-date question is the closest thing to the task prompt's
"birthday" — there is **no birthday field anywhere in the app**.

## 4. Authenticated root, chat-first shell (`AuthenticatedRootView.swift`)

The app opens **directly into a blank conversation** (chat-first). Chrome: a
brand top bar with a hamburger button opening the **slide-over menu**
(`SlideOverMenu.swift`) over a scrim: **New conversation**, up to 3 **Recent**
conversations (MRU, degrades under large text sizes), **Your details**, **My
colleges**, **All conversations**, **Settings**. All other screens are pushed
onto a single navigation stack. Web equivalent: persistent sidebar on wide
viewports, drawer on narrow — straightforward responsive layout. The root also
owns:

- the session-long StoreKit `Transaction.updates` listener (**does not
  transfer**, see §9),
- the initial coaching-usage fetch and a re-fetch on every return-to-foreground,
- the single paywall/subscription sheet presentation (`PaywallGate.swift`).

## 5. Conversation view + streaming (`ConversationView.swift`, `ConversationViewModel.swift`)

What the user sees: a message thread (user bubbles right, coach replies rendered
from a custom **Markdown renderer** — headings, lists, quotes, code, tables,
hairline rules; `Markdown/MarkdownView.swift`), a multiline composer with a send
button and a **coaching budget glance** (`CoachingBudgetButton.swift`: "62%
left" / "Out of coaching"; tap opens the subscription explanation sheet).
Behaviors:

- First message on a blank page: `POST /api/v1/conversations/stream` (SSE;
  opener frame `conversation` carries the created conversation + echoed user
  message, then `delta` text frames, then terminal `message` frame). Nothing is
  created server-side until the first send.
- Follow-up: `POST /api/v1/conversations/{id}/messages/stream` (opener
  `user_message`).
- History load for an existing conversation:
  `GET /api/v1/conversations/{id}/messages` (flat user/coach turn pairs), with
  inline Retry on failure.
- Client-side validation: trimmed non-empty, ≤ 100,000 chars
  (`ConversationViewModel.swift:95`).
- A failed turn stays in the thread with **Retry**; a 402
  `coaching_budget_exhausted` turn shows the paywall instead ("See options"),
  and Retry returns when the meter reports open.
- Per-bubble **copy menu** (`MessageCopy.swift`): user messages copy verbatim;
  coach replies offer "Copy" (rendered plain text) and "Copy as Markdown". Web:
  context menu/hover actions + Clipboard API. **Adapt.**
- Keyboard/focus choreography (RFC 127: focus intent consumed once, drawer
  lowers keyboard) is iOS-specific; web needs its own, simpler focus rules.
  **Adapt.**

## 6. Conversation list (`ConversationListView.swift`, `ConversationListViewModel.swift`)

"Your Conversations": unarchived conversations in MRU order, tap to open,
compose button for a fresh one. Swipe/context-menu actions: **Archive**
(`PATCH /api/v1/conversations/{id}` body `{archived: true}`) and **Delete** with
a confirmation dialog (`DELETE /api/v1/conversations/{id}`, 204). List read:
`GET /api/v1/conversations`. There is **no UI to view or un-archive archived
conversations** — archive is one-way from this client. Swipe gestures become
explicit buttons/menus on web. **Adapt.**

## 7. College list (`CollegeListView.swift`, `AddCollegeView.swift`, `CollegeEntryDetailView.swift`)

- **My colleges**: entries as cards; swipe **Remove** with confirmation
  (`DELETE /api/v1/students/me/college-list/{id}?version=N`, OCC, 204); read
  `GET /api/v1/students/me/college-list`; refreshes on every appearance to pick
  up moves made by the chat tool.
- **Add a college**: debounced name search `GET /api/v1/colleges?q=…` ("City,
  ST" captions), tap-to-add `POST /api/v1/students/me/college-list` body
  `{collegeId}` (201; server defaults status `considering`; duplicate → 409
  shown inline).
- **Entry detail**: segmented status picker
  (considering/applying/admitted/rejected), reasons editor (2,048-char cap
  mirroring the server CHECK), read-only "From your conversations" citations
  (`supportingObservations`), Save via
  `PATCH /api/v1/students/me/college-list/{id}` body
  `{version, status, reasons}` (OCC; `version_conflict`/`not_found` recovered by
  a fresh read). The wire also supports a per-college living-plan override
  (`LivingPlanUpdate`, RFC 164) — encoder built, **no production UI yet**
  (`CollegeListModels.swift:94`).

All of this is plain forms + lists; transfers directly.

## 8. Money profile / "Your details" (`YourDetailsView.swift`, `YourDetailsViewModel.swift`)

Drawer entry showing what unicoach knows about the family's money: **household
income band** and **state/territory of residence**, each an immediate-write
picker (no Save button; per-field "Saved" receipt, VoiceOver-announced). Picker
options come from `GET /api/v1/vocabularies` (`income_bands`,
`residency_states`), falling back to shipped lists with a visible "list may be
incomplete" note. Reads `GET /api/v1/students/me/money-profile` (404 →
all-unanswered); writes `PUT /api/v1/students/me/money-profile` with a tri-state
set/declined/clear encoding per field (`Models.swift`). Footer links to My
colleges. The `living` (living-plan) field rides the same wire but has no UI on
this screen. Transfers directly.

## 9. Paywall, subscription, usage meter (StoreKit rail)

Server truth: `GET /api/v1/students/me/coaching-usage` →
`{usedPercent 0–100, exhausted,
resetsAt|null}` — the one entitlement signal
every surface renders (`CoachingUsageClient.swift`). Surfaces:

- **Usage meter** (`CoachingUsageMeter.swift`, `DesignSystem/UsageMeter.swift`,
  `CoachingBudgetRing.swift`): shown in Settings and on the paywall; ring + "N%
  left"; distinct free-tier copy (lifetime allowance, `resetsAt == nil`) vs
  period copy.
- **Composer budget glance** (§5), opening the read-only **subscription
  explanation sheet** (`SubscriptionView.swift`): a situation matrix (free
  available/spent, active, billing-failing, ended, unknown status) — pure copy +
  the offer block.
- **Paywall** (`PaywallView.swift`): raised by a 402 on a turn; basis-specific
  copy ("free coaching used" vs "resets ⟨date⟩"); contains the same offer block.
  One sheet at a time, presented at the root (`PaywallGate.swift`).
- **Offer block** (`SubscriptionOffer.swift`): **Subscribe** (StoreKit price
  `displayPrice` — the only price ever shown), **Restore Purchases**, notices;
  plus **Manage subscription** link out to Apple's management UI
  (`ManageSubscriptionLink.swift`; in-app cancellation is impossible on iOS).
- Purchase rail (`StoreKitSubscriptionStore.swift`, `TransactionRecorder.swift`,
  `SubscriptionViewModel.swift`): StoreKit 2 purchase → signed JWS →
  `POST /api/v1/subscriptions/verify` `{signedTransaction}` →
  `PublicSubscription{status,
  productId, currentPeriodEnd}`. `/verify` is
  idempotent and is also the **only read** of subscription state (no GET
  exists). A background `Transaction.updates` listener records
  renewals/Ask-to-Buy.

**Platform-specific — does not transfer:** StoreKit purchase, Restore Purchases,
`/verify`'s JWS contract, Apple's manage-subscription page, and the transaction
listener are all Apple-billing artifacts. Web needs its own billing rail (Stripe
or similar), a real subscription-state GET, and web-side paywall copy — **needs
decision** (the biggest single piece of new backend/product work in this brief).
The usage meter and 402-driven paywall _presentation_ transfer as-is once a
reading and a "subscribe" door exist.

## 10. Settings (`SettingsView.swift`)

"Signed in as" (name + email), **Appearance** (System/Light/Dark segmented
control; `AppearancePreference.swift` persists in UserDefaults and applies
app-wide — web: cookie/localStorage + `prefers-color-scheme`; **adapt**), the
**Subscription section** (§9), **Change Email**, **Log Out**.

## 11. Change email (`ChangeEmailView.swift`)

Sheet with one email field, reachable from both Settings and the verification
screen. `POST
/api/v1/auth/change-email` → returns the user with `emailVerified`
cleared, so the app drops to the verification screen immediately. Transfers
directly.

## Not in the iOS app (task prompt asked; grep-verified absent)

- **Cost report**: no cost-report screen or endpoint call exists in
  `ios-app/UnicoachiOS/` (no hit for a cost-report path; the only "cost" hits
  are coaching-budget copy). If the web version should include a cost report, it
  is web-new, not an iOS duplicate.
- **Birthday**: no birthday field; onboarding collects an expected graduation
  date (§3).
- **Account deletion**: no UI or endpoint call (brief 0002 territory).
- **Push notifications, App Check/device attestation, Keychain**: none. The only
  anti-abuse header is the static `X-Unicoach-Client-Key`; the only local
  persistence is UserDefaults (appearance, Apple-disclosed name) and the cookie
  jar.

## Transfer table

| Feature                                               | Transfers to web | Notes                                                                                                                       |
| ----------------------------------------------------- | ---------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Root auth-state routing (`/me`-driven)                | yes              | Server-side redirects/SSR fit naturally                                                                                     |
| Email+password login                                  | yes              | Cookie session already                                                                                                      |
| Registration + field errors                           | yes              |                                                                                                                             |
| Google sign-in                                        | adapt            | Native SDK → Google Identity Services web; same `POST /auth/google` idToken contract expected, audiences need verification  |
| Apple sign-in                                         | adapt            | `ASAuthorizationController` → Sign in with Apple JS; name-on-first-auth persistence needs a web home                        |
| Email verification screen                             | adapt            | Foreground re-check → visibilitychange/poll; link can land in web app directly                                              |
| Onboarding (graduation date + optional money answers) | yes              | Precision picker is plain forms                                                                                             |
| Chat-first root + slide-over menu                     | adapt            | Drawer → responsive sidebar                                                                                                 |
| Conversation streaming (SSE over POST)                | adapt            | `EventSource` can't POST; use fetch + ReadableStream against the same endpoints                                             |
| Conversation history / list / archive / delete        | yes              | Swipe actions → buttons/menus                                                                                               |
| Markdown rendering of coach replies                   | yes              | Same block/inline subset, as HTML                                                                                           |
| Copy message / copy as Markdown                       | adapt            | Clipboard API                                                                                                               |
| Composer focus/keyboard choreography                  | adapt            | Simpler web focus rules                                                                                                     |
| College list CRUD + OCC conflict recovery             | yes              |                                                                                                                             |
| College search + add                                  | yes              |                                                                                                                             |
| Your details (money profile, served vocabularies)     | yes              |                                                                                                                             |
| Coaching usage meter + 402 paywall presentation       | yes              | Reading endpoint is platform-neutral                                                                                        |
| StoreKit purchase / Restore / `/verify` JWS           | no               | Apple-billing only                                                                                                          |
| Web subscription purchase + state read                | needs-decision   | New billing rail (e.g. Stripe) + a subscription GET; product + backend work                                                 |
| Manage-subscription link (App Store)                  | needs-decision   | Web must link to whatever the web billing rail's portal is; an Apple-billed subscriber on web still needs pointing at Apple |
| Appearance preference                                 | adapt            | localStorage/cookie + `prefers-color-scheme`                                                                                |
| Client key header                                     | needs-decision   | Baked bundle key has no web analogue; decide the web's client gate                                                          |
| Push notifications / App Check / Keychain             | n/a              | Not present in the iOS app                                                                                                  |
