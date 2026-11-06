# RFC 273 — Settings and the allowance on the web

**Slice:** `web/10/settings-and-allowance` (brief 0013, _Unicoach on the web_).
**Status:** approved (Ian, 2026-11-05: "Approve all"). **Base:**
`main@35fd71fc`. **Lane:** rfc.

## Executive Summary

The signed-in account page at `/account` becomes **Settings**, reached from
"Settings" in the signed-in header — the iPhone app's own word for the screen.
It keeps what it has (who is signed in, the change-email link) and gains the one
section the slice is for: **Your coaching** — the same meter the iPhone app
shows, read from the same `Entitlement` the coach's gate blocks on, with the
sentence that says what the number means and, for an Apple-billed subscriber,
the link to where Apple manages the subscription.

The out-of-allowance notice `web/04` shipped as interim copy becomes this
slice's own. It now says the right next step for each basis: a family on the
free allowance is sent to the iPhone app to subscribe, in plain words that do
not imply a web purchase exists (brief 0013 D18); a subscriber who spent the
period is told when it resets and is NOT told to subscribe.

There is no Appearance control (D-B): the site has no dark theme, and a control
for a theme that does not exist would be a lie. No table, no migration, no
`SessionMetadata` change. Every read is in-process (D13).

## Detailed Design

### 1. What the code says, where it differs from the slice

- **The door the slice names does not exist on the web.** "Settings in the
  signed-in menu": the web shell has a wrapping header, not a menu (RFC 210; RFC
  272 D-A made the same call). The door is the header link, renamed from
  "Account" to "Settings" (D-A). The path stays `/account`: the `ONBOARDED`
  wall, the supporter redirects, the invite and claim notices all land there.
- **"The web has `prefers-color-scheme` plus a cookie"** — it does not.
  `site.css` has no dark palette, no `prefers-color-scheme` rule and no
  `color-scheme`. Every colour IS a `:root` token, so a dark theme is cheap to
  wire but still has to be designed. D-B leaves it out of this slice.
- **The meter's numbers are already one object.** `Entitlement` in `:service`
  carries `usedPercent`, `exhausted`, `resetsAt` and `basis`; REST's
  `toCoachingUsage()` copies the first three into a DTO. The web reads the same
  three properties of the same `Entitlement` in-process, so "the same numbers
  the iOS app shows" holds by construction and nothing moves out of
  `:rest-server`.
- **A lapsed subscriber is a free-tier account** to `BudgetService`
  (`SubscriptionsDao.findCurrent` returns only a current row). The web renders
  what the service answers: a lapsed subscriber reads the free-allowance words
  and no manage link. iOS shows "Manage subscription" in any bound state; the
  web would need a new "latest subscription in any state" read to match, which
  this slice does not add (open item for /chart).
- **The web has no supporter coaching surface** (RFC 254's note on `/account`
  stands). A supporter's Settings has no "Your coaching" section.
- **Sign out stays in the header.** iOS has a Log Out row in Settings because
  Settings is where its header is not; the web header carries sign-out on every
  page, this one included. A second control on the page would be a duplicate.

### 2. The page

`GET /account` — `getOnboarded`, as today. For a student the handler also calls
`budgetService.entitlement(studentId)`; a failed read renders the page with the
unavailable line, never a 503 (guided, not gated).

```
h1  Settings
    [notice]                               — the PRG receipt, as today
h2  Signed in as
    dl  Name / Email                        — as today
h2  Your coaching                           — students only
    .usage-meter   "Coaching used"  "N%"    — bar, role="meter", aria-valuenow
    caption        "One-time free allowance" | "Resets Sep 12, 2026"
    p              the explanation sentence (§3)
    p              [subscriber] a "Manage subscription" link, then
                   "Cancelling is handled by the App Store."
    p              [supporter note, as today, in place of this section]
h2  Email
    a              Change your email        — as today
```

Dates through `UsDate.of(resetsAt.atZone(COACH_ZONE).toLocalDate())`, the one
calendar rule on the web (RFC 272). The manage link is
`https://apps.apple.com/account/subscriptions`, Apple's subscription-management
page, with `rel="noopener"`; the CSP is unchanged (an anchor is not a resource).

### 3. The words — one home

`render/CoachingUsage.kt` holds every sentence about the meter, so the Settings
page and the chat notice cannot drift. `exhaustedSentence` moves there from
`ChatPage.kt`. The iPhone app's sentences are reused where they exist
(`PaywallView.swift`, `SubscriptionView.swift`, `UsageMeter.swift`,
`ManageSubscriptionLink.swift`); the two web-only sentences name the iPhone app
as the way to subscribe (D18).

| Where            | Basis / state           | Words                                                                                                                                                |
| ---------------- | ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| meter caption    | free, admin             | One-time free allowance                                                                                                                              |
| meter caption    | subscription            | Resets ⟨date⟩                                                                                                                                        |
| Settings         | free, not spent         | Free coaching is a one-time allowance. Subscribing for a fresh allowance every month happens in the Unicoach app on iPhone; it isn't on the web yet. |
| Settings         | free, spent             | You've used your free coaching. To keep going, subscribe in the Unicoach app on iPhone. Subscribing on the web isn't available yet.                  |
| Settings         | subscription, not spent | Your monthly subscription runs to ⟨date⟩, and your coaching allowance resets then.                                                                   |
| Settings         | subscription, spent     | You've used this period's coaching. It resets ⟨date⟩, when your subscription renews.                                                                 |
| Settings         | admin exemption         | This account is exempt from the coaching meter.                                                                                                      |
| Settings         | read failed             | Coaching usage is unavailable right now.                                                                                                             |
| Settings         | subscription (any)      | Manage subscription → apps.apple.com; then: Cancelling is handled by the App Store.                                                                  |
| chat notice `h2` | all                     | Coaching is paused                                                                                                                                   |
| chat notice      | free, spent             | You've used your free coaching.                                                                                                                      |
| chat notice      | subscription, spent     | You've used this period's coaching. It resets ⟨date⟩.                                                                                                |
| chat notice      | admin, spent            | You've used your coaching allowance.                                                                                                                 |
| chat next step   | free                    | To keep going, subscribe in the Unicoach app on iPhone. Subscribing on the web isn't available yet. Everything else here still works.                |
| chat next step   | subscription, admin     | Everything else here still works.                                                                                                                    |

The chat notice keeps the paywall's sentence (no "when your subscription
renews"), the Settings page the subscription sheet's — each the exact twin of
the iPhone surface it mirrors.

### 4. Wiring

`Application.kt` builds ONE `BudgetService` and hands it to both `WebCoach` (for
`CoachingService`) and `publicWebModule(budgetService = …)`: the page and the
gate read the same object. `WebAuthTestSupport` gains the same seam
(`budgetService(freeAllowanceUsd)`), and the test module takes it beside the
coaching service so a suite can turn the one knob for both.

### 5. Decisions

- **D-A — the door and the heading.** Header link "Account" → "Settings"; page
  heading "Your account" → "Settings"; path `/account` unchanged. Default.
- **D-B — no Appearance control.** The site is light-only; a dark theme is a
  slice of its own (reported to /chart). Default.
- **D-C — the words in §3**, all of them public copy. Default: as written.

## Files Modified

- `rfc/273-web-settings-and-allowance.md` — this document.
- `public-web/src/main/kotlin/ed/unicoach/web/render/AccountPage.kt` → renamed
  `SettingsPage.kt`: heading, the three sections, the coaching section for a
  student.
- `public-web/src/main/kotlin/ed/unicoach/web/render/CoachingUsage.kt` (new) —
  the meter fragment and every sentence in §3; `exhaustedSentence` moves here.
- `public-web/src/main/kotlin/ed/unicoach/web/render/ChatPage.kt` — the notice
  renders the per-basis next step from `CoachingUsage.kt`.
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt` — the header
  label reads the Settings page's own heading.
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt` — `budgetService`
  parameter; the `/account` handler reads the student's entitlement.
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt` — one shared
  `BudgetService`.
- `public-web/src/main/resources/static/site.css` — `.usage-meter` under
  components, tokens only.
- Tests: `SettingsRoutingTest.kt` (new); `ShellNavTest`, `WebAuthGateTest`,
  `GatedPathSpellingTest`, `ChatStreamTest` (the label, the heading, the
  body-only marker, the per-basis notice); `WebAuthTestSupport` (the budget
  seam, a subscription seeder, a spend seeder) and the test module builder.

## Implementation Plan

1. Build the one `BudgetService` in `Application.kt`; thread it through
   `publicWebModule` and the test module builder; the suite stays green.
2. `CoachingUsage.kt`: move `exhaustedSentence`, add the next-step and Settings
   sentences, the caption, the meter fragment and the manage link. Re-point
   `ChatPage.kt`; `ChatStreamTest` still passes, plus the subscriber case.
3. `SettingsPage.kt`: rename, sections, the coaching section; `Routing.kt` reads
   the entitlement for a student and passes `null` for a supporter or a failed
   read (typed: `CoachingMeter.Unavailable`, not a bare null).
4. The header label; `ShellNavTest`, `WebAuthGateTest`, `GatedPathSpellingTest`
   updated to the new words (marker: "Signed in as", body-only).
5. `site.css` `.usage-meter`. `nix develop -c bin/test` unscoped.
6. The D19 device matrix, by the coordinator, on a local stack: Settings at the
   six sizes, fresh and exhausted; the first-session test (a web-only account
   spends its allowance and reads the way on) in a real browser.

## Tests

`SettingsRoutingTest` (real DB, real `BudgetService`):

- a fresh student: 200, "Settings", "Signed in as", `Coaching used` `0%`,
  "One-time free allowance", the free-not-spent sentence, no manage link, the
  change-email link;
- a seeded spend of half the allowance renders the percent `Entitlement` reports
  (`50%`) — the page and the service agree on the number;
- an exhausted student (`freeAllowanceUsd = 0.00`): `100%` and the free-spent
  sentence naming the iPhone app and "isn't available yet";
- a subscribed student (a `subscriptions` row for a configured plan): "Resets
  ⟨date⟩" in Eastern through `UsDate`, "runs to ⟨date⟩", the manage link's exact
  href, "Cancelling is handled by the App Store.", and NO sentence naming the
  iPhone app as the way to subscribe;
- a subscribed, exhausted student: "It resets ⟨date⟩, when your subscription
  renews.";
- a supporter: 200, no "Your coaching", the supporter note as today;
- a budget read that fails (a `BudgetService` over a closed `Database`): 200,
  "Coaching usage is unavailable right now.", no meter, the rest of the page
  intact.

`ChatStreamTest`: the existing 402 case keeps its words; a subscribed, exhausted
student's notice carries "Everything else here still works." and does NOT name
the iPhone app. `ShellNavTest`: the header's last link before sign-out is
`<a href="/account">Settings</a>`. `WebAuthGateTest` / `GatedPathSpellingTest`
read the new heading and marker.
