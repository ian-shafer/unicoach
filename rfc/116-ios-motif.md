# RFC 116: Bring the whole iOS app to the design motif

## Summary

`ios-app/DESIGN.md` specifies a complete visual language — an orange→pink brand
gradient, flat white surfaces, 64pt outlined controls — derived from a style
reference. **The specification was committed; the implementation never was.**
The app still ships LinkedIn blue.

This RFC implements the spec's visual language (§1–6), extends it to the screens
the reference does not cover (§8), and lands the missing reference image as a
committed artifact. It deliberately excludes the navigation restructure in
`DESIGN.md` §7, which moves to RFC 117.

## Background: the spec is real, the code is not

`e5532856 Add the iOS design specification` landed `DESIGN.md` alone. Every
token it mandates still holds its pre-spec value:

| Token         | In `Assets.xcassets` today | `DESIGN.md` §2 |
| ------------- | -------------------------- | -------------- |
| `BrandAccent` | `#0A66C2` (LinkedIn blue)  | `#EE732F`      |
| `Surface`     | `#F2F2F7`                  | `#FFFFFF`      |
| `TextPrimary` | `#1C1C1E`                  | `#000000`      |
| `FieldBorder` | `#C7C7CC`                  | `#B0B0B0`      |

`Theme.swift` likewise lacks `DSRadius.control`, `DSControl.height`,
`dsDisplay`, `dsOverline`, `dsOption`, `ControlFill`, and `ControlOnFill`; none
of the four signature components (option card, top bar, step indicator, logo
mark) exist in any form.

So this is not a redesign and there is no new taste question to settle. The
decisions were made and recorded when `DESIGN.md` was written; this RFC executes
them.

### The reference image

`DESIGN.md` line 3 cites a style reference at `iOS.jpg` that was never committed
— the spec's own evidence was missing, so no reviewer could check a measurement
against it. That file is landed here as
[`artifacts/116/iOS.jpg`](artifacts/116/iOS.jpg), and `rfc/README.md` gains the
convention that put it there (`rfc/artifacts/<NN>/`, immutable with its RFC).
`DESIGN.md`'s dangling citation is repointed at it.

The reference shows **two** screens: login, and one onboarding step.

## Detailed Design

### 1. Token layer

`Assets.xcassets/*.colorset` moves to the §2 light values and the §2.1 derived
dark values. Two new colorsets are added, `ControlFill` and `ControlOnFill`,
which **invert between modes** — light is a `#030303` fill with a white label,
dark is a `#FFFFFF` fill with a black label. This inversion is the same one Sign
in with Apple performs, so it reads as native rather than accidental.

`TextSecondary` ships at `#6E6E6E`, not the `#787878` measured off the
reference: §6 records that the measured value lands at 4.42:1 on white, just
under AA, and `#6E6E6E` clears it. The reference is a mockup, not an
accessibility audit.

`Theme.swift` gains:

- `DSRadius.control = 16` — one radius for buttons, fields, and option cards,
  replacing the current split of `button = 12` / `field = 10`. Emphatically not
  a capsule; a capsule at 64pt would be 32.
- `DSControl.height = 64` — buttons and option cards share one control rhythm.
- `DSControl.stackGap = 12` — vertical gap between stacked controls.
- `DSSpacing.lg = 24` as the screen horizontal margin (unchanged value, newly
  load-bearing).
- Type tokens `dsDisplay` (`.largeTitle`/`.heavy`), `dsOverline`
  (`.caption`/`.semibold`, uppercased, ~0.08em tracking), `dsOption`
  (`.title3`/`.bold`); `dsTitle` firms from `.semibold` to `.bold`.
- `DSGradient.brand` — the `#EE7330`→`#E94577` pair, with the direction rule
  from §1: leading→trailing for horizontal chrome, topLeading→bottomTrailing for
  the logo mark.

Type stays SF Pro at heavier weights rather than a bundled geometric sans. That
was decided when `DESIGN.md` was written and is reversible **only** while every
view reads type from these tokens — so an inline `.font(.system(...))` is a
defect, not a shortcut. The codebase currently has zero; keep it that way.

### 2. Components

Four new components in `DesignSystem/Components.swift`, all token-driven:

- **`OptionCard`** — the reference's signature control and the one with no
  existing equivalent. 64pt, 16pt radius, white fill, 1pt border, leading ~22pt
  radio (hollow grey ring unselected, solid `#EE732F` selected), trailing
  `dsOption` label; the selected card's border darkens.
- **`BrandTopBar`** — full-bleed brand gradient extending under the status bar,
  `uni.COACH` wordmark in white, leading-aligned, ~44pt content height.
- **`StepIndicator`** — N circles joined by a 1pt rail, current step filled.
- **`LogoMark`** — circular gradient (topLeading→bottomTrailing) with a heavy
  white `U`, sized as a fraction of container width (~62% on login).

Existing styles are retargeted, not rewritten: `PrimaryButtonStyle` moves from
`brandAccent` fill to `ControlFill` at 64pt/16pt, and `LabeledField` adopts
`DSRadius.control` with a 20pt leading text inset. **`brandAccent` stops being a
button fill** — the gradient is chrome and selection, never a large tappable
surface, because white-on-gradient fails contrast (§6, 2.95:1).

The white `uni.COACH` wordmark on the gradient bar is the single sanctioned
exception, defensible as a logotype. Nothing else goes on the gradient: any
other text placed there must be black, or the gradient darkened for that
surface.

### 3. Screens the reference covers

`LoginView` and `OnboardingView` are brought to the reference directly — logo
mark, `ControlFill` SSO buttons, the email field, the overline greeting, step
indicator, and option cards. These two are the only screens with a ground truth
to match, so they are the ones the visual gate judges hardest.

`RegistrationView` shares login's vocabulary and follows it.

### 4. Screens the reference does not cover (§8)

`DESIGN.md` §8 names these as undesigned: the conversation surface, the
conversation list, empty and loading states, and error presentation. The rule
for all of them is **extrapolate from tokens; invent no new visual language**:

- Flat white surfaces; separation by 1pt `FieldBorder` hairline.
- **No shadows and no elevation anywhere** — the reference communicates depth by
  outline alone, and a single shadow would read as a different design.
- 16pt radius on any container that reads as a control.
- The brand gradient appears only as top chrome and selection accent.

Concretely: message bubbles are outlined rather than filled, with the user's
turn distinguished by fill weight rather than by a saturated colour; the
composer is a `DSRadius.control` field matching `LabeledField`; list rows are
outlined cards on white; error presentation keeps the existing `dsError`
semantics with the new radius and border.

`HomeView` is restyled too, even though RFC 117 deletes it. An interim state
where the app's own screens disagree with each other is worse than the small
waste of styling a doomed view.

Whatever is extrapolated here is **written back into `DESIGN.md`**, which is a
living document — §8's list of undesigned surfaces shrinks accordingly. That
write-back is what stops the next round from re-deriving these choices from
scratch, and it is the mechanism by which "the whole app matches the motif"
becomes checkable rather than a matter of opinion.

### 5. Explicitly out of scope

`DESIGN.md` §7 — chat-first root, slide-over menu, `HomeView` deleted, four
`NavigationStack`s collapsed into one, a new Settings/Profile destination — is
**deferred to RFC 117**. It is a behaviour change, not a visual one. Bundled
here it would produce a diff in which a screenshot regression is
indistinguishable between a bad token and a broken navigation graph, disarming
the only gate this work has (see Tests).

## Files Modified

**Added**

- `rfc/artifacts/116/iOS.jpg` — the style reference, committed at last
- `ios-app/UnicoachiOS/Assets.xcassets/ControlFill.colorset/Contents.json`
- `ios-app/UnicoachiOS/Assets.xcassets/ControlOnFill.colorset/Contents.json`

**Modified**

- `rfc/README.md` — the `rfc/artifacts/<NN>/` convention
- `ios-app/DESIGN.md` — repoint the `iOS.jpg` citation; record §4
  extrapolations; shrink §8
- `ios-app/UnicoachiOS/DesignSystem/Theme.swift` — new radius, control, type,
  and gradient tokens
- `ios-app/UnicoachiOS/DesignSystem/Components.swift` — four new components;
  retarget existing styles
- `Assets.xcassets/{BrandAccent,Surface,TextPrimary,TextSecondary,FieldBorder}.colorset/Contents.json`
- `ios-app/UnicoachiOS/{LoginView,RegistrationView,OnboardingView,ConversationView,ConversationListView,VerificationRequiredView,ErrorView,HomeView,AuthFlowView}.swift`
- `ios-app/UnicoachiOS/{GoogleSignInButton,AppleSignInButton}.swift` — match the
  `ControlFill` SSO treatment
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — **required** for any new
  file

> `project.pbxproj` has no file-system synchronization: a new `.swift` or
> `.colorset` that is not registered there **silently never compiles**, and the
> build stays green while the component is simply absent. This has to be
> verified by grep, not assumed.

## Implementation Plan

1. **Tokens.** Colorsets to §2/§2.1 values; `Theme.swift` gains the new tokens.
   Build. Expect the app to look half-migrated — that is correct at this step.
2. **Components.** Add the four new components and retarget `PrimaryButtonStyle`
   / `LabeledField`. Register every new file in `project.pbxproj`. Extend the
   existing `#Preview` blocks to cover the new components in both colour
   schemes.
3. **Reference screens.** `LoginView`, `OnboardingView`, `RegistrationView`.
   Screenshot against `artifacts/116/iOS.jpg`.
4. **Extrapolated screens.** Conversation, list, verification, error, home, per
   the §4 rules.
5. **Write back.** Update `DESIGN.md` §8 and the extrapolation record.
6. **Visual gate.** Before/after captures on both colour schemes.

Steps 1–2 are the whole risk surface: they change every accent in the app at
once. Steps 3–4 are mechanical once the tokens are right.

## Tests

**The mechanical gate is nearly blind here, and pretending otherwise is the main
hazard.** `nix develop -c bin/test` does not compile or run anything under
`ios-app/`; a green run says nothing whatsoever about this change. The iOS unit
suite is view-model and client tests only — there is not a single view or theme
test — so it can confirm this change broke no behaviour, and nothing more.

Evidence required, in order of authority:

1. **Visual gate (the real authority).** `bin/build-ios simulator`,
   `bin/rest-server-up` (else the capture is the offline screen), then
   `bin/screenshot-ios simulator` — before and after, light and dark, for login
   and onboarding at minimum. Review must cite **numbers** from `DESIGN.md` —
   64pt control height, 16pt radius, `#EE732F` radio fill — against
   `artifacts/116/iOS.jpg`. An adjective-only review does not count.
2. **Compilation.** `bin/build-ios simulator` clean, plus a grep confirming
   every new file appears in `project.pbxproj`.
3. **Regression.** The existing XCTest suite via system Xcode
   (`xcodebuild test -scheme UnicoachiOS`), unchanged and passing.
4. **Token discipline.** Grep for inline `.font(.system(` and literal
   `Color(red:`/`.blue` in `ios-app/UnicoachiOS/**` — expected count zero, which
   is what keeps the typeface decision reversible.
5. `nix develop -c bin/test` for the repo gate, understood as covering the
   `rfc/README.md` edit and nothing else.
