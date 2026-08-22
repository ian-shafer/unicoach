# Unicoach iOS — Design Specification

Derived from the style reference at
[`rfc/artifacts/116/iOS.jpg`](../rfc/artifacts/116/iOS.jpg) (login + onboarding
mockups), measured against a 393pt-wide device (iPhone 16 class). This is the
durable source of truth for the app's visual language: prefer editing this file
and the token layer (`DesignSystem/Theme.swift`, `Assets.xcassets/*.colorset`)
over restyling individual views.

Unlike an RFC, this document is **living** — it is updated in place as the
design evolves, not superseded by a higher-numbered file.

## 0. How to read this spec

**The tokens are the contract; the numbers are only their definitions.** A view
must read `DSControl.height`, never re-type `64`. Once it does, the rendered
dimension is correct by construction — so a screenshot measured against this
table confirms only what the token already guaranteed. Exactness is enforced in
the diff, by the absence of magic numbers, not in the pixels.

**The mockup is a reference, not a contract.** It is one render at one width.
Values here were measured off it and are _indicative_: they set the rhythm, not
a tolerance. A control that reads as the motif is correct even if it is a few
points from the render.

**Prefer proportional and semantic layout to transcribed constants.** "62% of
the container width" is better than a pixel figure taken off the mockup, because
it survives the device sizes the mockup never showed. Where a relationship can
be expressed structurally — a fraction, an intrinsic size, a `@ScaledMetric`
minimum — express it that way and let this file describe the intent rather than
the arithmetic.

**Clean view code outranks fidelity to the render.** If matching the mockup
exactly would require awkward, brittle, or unreadable SwiftUI, write the clear
version and update this file to describe what was built. The code is the source
of truth (see `rfc/README.md`); this document explains it and must not force it
into contortions.

## 1. Brand

The brand mark is a warm **orange-to-pink linear gradient**, used for the logo
lockup, the app's top chrome, and selection accents. It is the only saturated
colour in the palette; everything else is white, near-black, or grey.

| Token                | Value     | Use                                                                       |
| -------------------- | --------- | ------------------------------------------------------------------------- |
| `brandGradientStart` | `#EE7330` | Gradient origin (leading / top-leading)                                   |
| `brandGradientEnd`   | `#E94577` | Gradient terminus (trailing / bottom-trailing)                            |
| `brandAccentSolid`   | `#EE732F` | Solid accent where a gradient cannot apply (radio fill, small indicators) |

Gradient direction: **leading → trailing** for horizontal chrome (the top bar);
**topLeading → bottomTrailing** for the circular logo mark.

## 2. Colour

Measured from the reference. The reference is **light-mode only**; the dark
palette in §2.1 is derived (Ian's call: keep dark mode).

| Token           | Light     | Colorset                                                                                      |
| --------------- | --------- | --------------------------------------------------------------------------------------------- |
| `Background`    | `#FFFFFF` | `Background`                                                                                  |
| `Surface`       | `#FFFFFF` | `Surface` — flat white; the reference has no grey fills, separation is by border, not by tint |
| `TextPrimary`   | `#000000` | `TextPrimary`                                                                                 |
| `TextSecondary` | `#6E6E6E` | `TextSecondary` — **not** the `#787878` measured off the reference; see §6                    |
| `FieldBorder`   | `#B0B0B0` | `FieldBorder` — borders carry the layout, so they must be visible                             |
| `BrandAccent`   | `#EE732F` | `BrandAccent` — replaced the pre-spec LinkedIn blue `#0A66C2` entirely                        |
| `BrandOnAccent` | `#FFFFFF` | `BrandOnAccent`                                                                               |
| `ControlFill`   | `#030303` | `ControlFill` — near-black fill for primary / SSO buttons                                     |
| `ControlOnFill` | `#FFFFFF` | `ControlOnFill` — label on that fill                                                          |
| gradient stops  | see §1    | `BrandGradientStart` / `BrandGradientEnd`, read through `DSGradient`                          |

`TextSecondary` ships at `#6E6E6E` rather than the `#787878` measured off the
reference: §6 records that the measured value lands at 4.42:1 on white, just
under AA. The reference is a mockup, not an accessibility audit.

These values landed in RFC 116. Before it every one of them still held its
pre-spec value — most consequentially `BrandAccent`, which was LinkedIn blue
`#0A66C2`.

### 2.1 Dark mode (derived)

Dark mode is **kept**. The reference's visual logic — flat surfaces, no
elevation, separation by hairline border — inverts cleanly; only the near-black
control fill cannot survive inversion.

| Token           | Dark      | Contrast on background | Note                                                                                       |
| --------------- | --------- | ---------------------- | ------------------------------------------------------------------------------------------ |
| `Background`    | `#0E0E10` | —                      | Near-black, not pure black: keeps hairline borders visible and avoids OLED smear on scroll |
| `BrandOnAccent` | `#FFFFFF` | —                      | Unchanged: the gradient it sits on is not recoloured for dark                              |
| `Surface`       | `#0E0E10` | —                      | Same as background, mirroring light mode's flatness                                        |
| `TextPrimary`   | `#FFFFFF` | 19.3 ✓                 |                                                                                            |
| `TextSecondary` | `#A8A8AD` | 8.1 ✓                  |                                                                                            |
| `FieldBorder`   | `#5A5A5F` | 2.8                    | Decorative only — never text                                                               |
| `BrandAccent`   | `#EE7330` | 6.6 ✓                  | Gradient unchanged; it reads _better_ on dark than on white                                |
| `ControlFill`   | `#FFFFFF` | 19.3 ✓                 | **Inverts.** A `#030303` button on a near-black background is invisible                    |
| `ControlOnFill` | `#000000` | 21.0 ✓                 | Black label on the white fill                                                              |

The one asymmetry worth stating: in light mode the primary/SSO button is a
**near-black fill with a white label**; in dark mode it becomes a **white fill
with a black label**. This is the same inversion Apple's own Sign-in-with-Apple
button performs, so it will look native rather than accidental.

The brand gradient is deliberately _not_ recoloured for dark. It sits at 6.6:1
against `#0E0E10` — stronger than its 2.95:1 against white — so the same two
stops serve both modes.

## 3. Geometry

Measured directly off the reference, normalised to points.

| Token              | Value     | Notes                                                                       |
| ------------------ | --------- | --------------------------------------------------------------------------- |
| `DSRadius.control` | **16**    | Buttons, fields, and option cards share one radius (currently 12/10, split) |
| `DSControl.height` | **64**    | Buttons and option cards are the same height — one control rhythm           |
| `DSSpacing.md`     | 16        | unchanged                                                                   |
| `DSSpacing.lg`     | 24        | screen horizontal margin (measured 24–28)                                   |
| Control stack gap  | **10–12** | vertical gap between stacked buttons / cards                                |
| Top bar height     | **~63**   | gradient chrome, extending under the status bar                             |

The token layer carries these as `DSRadius.control`, `DSControl.height`,
`DSControl.stackGap` (12), `DSControl.borderWidth` (1), `DSControl.textInset`
(20), `DSControl.radioDiameter` (22), and `DSControl.topBarHeight` (44 — the
content height; the gradient itself extends under the status bar to the ~63 the
reference measures). Every control height is applied as a `@ScaledMetric`
minimum rather than a fixed frame, so Dynamic Type grows the box instead of
clipping the label.

Controls are notably **chunky**: 64pt tall against iOS's 44–50pt norm, with a
16pt radius that is emphatically _not_ a capsule (a capsule at this height would
be 32pt). Borders are 1pt hairlines, not fills. There are **no shadows and no
elevation** anywhere in the reference — depth is communicated by outline alone.

## 4. Typography

The reference uses a **bold geometric sans**, not SF. Characteristics: double-
storey `a`, single-storey `g`, geometric numerals, and very heavy weights for
headings — near-black on titles, with tight leading on two-line headings.

**Decision (Ian, this session): SF Pro at heavier weights.** Zero dependency,
ships today, keeps Dynamic Type and every accessibility affordance for free, and
captures roughly 70% of the reference's feel. The face was judged easy to change
later, which it is _provided_ every view reads its type from the tokens below
and never calls `.font(.system(...))` inline — **that discipline is what keeps
the decision reversible**, so treat an inline font as a defect.

Swapping to a bundled geometric sans (Poppins / Montserrat / Archivo class)
later means editing this table and adding `UIFontMetrics` scaling, nothing more.

Scale (weights firmer than the current tokens throughout):

| Token        | Style                                                                      |
| ------------ | -------------------------------------------------------------------------- |
| `dsDisplay`  | `.largeTitle` / `.heavy` — screen headings ("When will you graduate?")     |
| `dsTitleXL`  | `.largeTitle` / `.bold`                                                    |
| `dsTitle`    | `.title2` / `.bold` (was `.semibold`)                                      |
| `dsBody`     | `.body` / `.regular`                                                       |
| `dsLabel`    | `.subheadline` / `.medium`                                                 |
| `dsOverline` | `.caption` / `.semibold`, uppercase, ~0.08em tracking — "WELCOME, KENDALL" |
| `dsButton`   | `.headline` / `.semibold`                                                  |
| `dsCaption`  | `.caption` / `.regular`                                                    |

`dsOverline` is applied through `Text.dsOverlineStyle()`, which carries the
uppercasing and the tracking with the font so the three cannot drift apart.
`Font.dsLogoGlyph(diameter:)` is the one size-taking token: the logo mark's `U`
is artwork, sized from its circle rather than from the type scale. | `dsOption`
| `.title3` / `.bold` — option-card labels, which are far larger than list text
normally is |

## 5. Components

### Primary / SSO button

64pt tall, 16pt radius, `#030303` fill, white label, leading icon inset with the
icon+label pair centred as a group. Stacked with a 10–12pt gap.

### Text field

64pt tall, 16pt radius, white fill, 1pt `#B0B0B0` border, `#B0B0B0` placeholder,
20pt leading text inset.

### Option card

64pt tall, 16pt radius, white fill, 1pt border. Leading radio circle (~22pt),
hollow grey ring unselected, solid `#EE732F` fill selected; the selected card's
border darkens. Trailing label in `dsOption`. This is the reference's signature
control; it is `OptionCard` in `DesignSystem/Components.swift`.

### Top bar

Full-bleed brand gradient extending under the status bar, `uni.COACH` wordmark
in white, leading-aligned, ~44pt of content height. This replaces the stock
`.navigationTitle` chrome on branded screens (`BrandTopBar`); a screen that
adopts it hides the stock navigation bar rather than showing both.

### Segmented selector

One outlined `DSRadius.control` container holding N segments; the selected
segment carries a `BrandAccent` fill with a **black** (`OnBrandAccent`) label.
`SegmentedSelector` exists because SwiftUI's `.pickerStyle(.segmented)` renders
a stock grey capsule, which contradicts both §2 (no grey fills) and §3 (not a
capsule).

### Step indicator

Horizontal rail of N circles joined by a 1pt line; current step filled, the rest
hollow (`StepIndicator`). Used for multi-step onboarding — on the
graduation-date screen it shows account → profile → coaching, with profile
current.

### Logo mark

Circular gradient (topLeading → bottomTrailing) with a heavy white `U`, ~62% of
the screen width on the login screen (`LogoMark`, `DSLogo.widthFraction`).

## 6. Accessibility constraints

Measured contrast ratios against the reference palette:

| Pair               | Ratio    | Verdict                                  |
| ------------------ | -------- | ---------------------------------------- |
| White on `#EE7330` | **2.95** | ✗ fails WCAG AA (4.5) and AA-large (3.0) |
| White on `#E94577` | **3.76** | ✗ fails AA; passes AA-large only         |
| Black on `#EE7330` | 7.13     | ✓ passes AA                              |
| `#787878` on white | 4.42     | ~ marginal, just under AA                |
| `#B0B0B0` on white | 2.17     | ✗ decorative borders only — never text   |

**The white `uni.COACH` wordmark on the gradient bar fails contrast.** It is a
logotype rather than body copy, so it is defensible, but nothing else may follow
it onto that gradient. Any text placed on the brand gradient should be black, or
the gradient must be darkened for that surface. Secondary text ships at
`#6E6E6E` rather than the measured `#787878` to clear AA cleanly.

Two consequences bind the whole app, not just the gradient:

- **`BrandAccent` never carries text.** `#EE732F` on white is 2.95:1, so the
  tertiary links that used to be brand-coloured ("Register", "Retry", "Change
  Email") are `TextPrimary`. Brand colour appears as chrome and as the option
  card's selection fill, nowhere else.
- **`BrandAccent` is never a large tappable surface** either, for the same
  reason. Primary and SSO controls are `ControlFill`.

## 7. Navigation

**Decision (Ian, this session): chat-first with a slide-over menu.**
Conversation is the app's primary interface; everything else is secondary and
lives behind a menu rather than competing with it in a tab bar.

Structure:

- The root of the authenticated state is the **conversation view**, not a hub.
  `HomeView`'s current three-button menu screen disappears entirely.
- A **leading toolbar button** on the gradient top bar opens a slide-over menu
  (drawer) from the leading edge, over a dimmed scrim.
- The menu holds: **New conversation**, the **conversation list** (recent
  first), and a footer entry into **Settings/Profile**.
- **Settings/Profile** is a new destination absorbing Log Out, Change Email,
  subscription status, and coaching usage — none of which have a home today.
  `ChangeEmailViewModel` already exists and is currently reachable only from the
  pre-verification blocking screen, so a verified user cannot change their
  email; this destination fixes that.
- One `NavigationStack` at the root. The four independent `NavigationStack`s in
  `HomeView`, `ConversationView`, `ConversationListView`, and
  `VerificationRequiredView` collapse into it. The auth-state `switch` in
  `UnicoachiOSApp` stays where it is — it routes _between_ states, above
  navigation.

The menu is a custom overlay, not `NavigationSplitView`: the reference's
full-bleed gradient chrome does not survive stock split-view presentation on
iPhone.

## 8. What the reference does not cover

The mockups show **only the login screen and one onboarding step.** Everything
else is extrapolated from the tokens above. The rule for extrapolation is:
**extrapolate from tokens; invent no new visual language.**

Concretely, and binding:

- Flat surfaces; separation by a 1pt `FieldBorder` hairline.
- **No shadows and no elevation anywhere.** The reference communicates depth by
  outline alone, and a single shadow would read as a different design.
- 16pt radius on any container that reads as a control.
- The brand gradient appears only as top chrome and as a selection accent.

### 8.1 Extrapolations already made (RFC 116)

| Surface                     | Treatment                                                                                                                                                                                                                   |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Message bubbles             | Outlined, never filled. Both turns are `Surface` at `DSRadius.control`; the **user's** turn is distinguished by a darker `TextPrimary` hairline, the coach's by `FieldBorder`. No saturated bubble.                         |
| Composer                    | A `LabeledField` in everything but name: `DSRadius.control`, 1pt `FieldBorder`, 20pt leading inset, `DSControl.height` minimum. The send control is a `ControlFill` circle.                                                 |
| Conversation list rows      | Outlined cards on the background, one per row, with the stock separator and row background removed so the card's own hairline is the only separation.                                                                       |
| Empty state                 | `dsDisplay` headline, `dsBody` supporting line, one filled `ControlFill` action.                                                                                                                                            |
| Loading state               | Stock `ProgressView` on the flat background, with a `dsCaption` `TextSecondary` line. Spinners inside a `ControlFill` control are tinted `ControlOnFill`, which the untinted system spinner would not survive.              |
| Error presentation          | Existing `dsError` semantics kept, but **outlined**: the tinted wash is gone, replaced by a `dsError` hairline at `DSRadius.control`. Same for the destructive button.                                                      |
| Stock chrome                | One app-wide `.tint(TextPrimary)` at the root scene: without it the navigation back button, toolbar glyphs, alert actions and selection handles all render in the system blue this design removed.                          |
| Secondary / paired controls | Same 64pt/16pt box as the primary, outlined instead of filled. At most one filled control per screen.                                                                                                                       |
| Home (interim)              | `BrandTopBar` chrome, overline + `dsDisplay` greeting, one filled and one outlined control, destructive log-out. RFC 117 deletes this screen; it is styled anyway so the app does not disagree with itself in the meantime. |

### 8.2 Still undesigned

- The slide-over menu's own visual treatment (the menu itself arrives in RFC
  117).
- Loading **skeletons** — today's loading states are spinners, not skeletons.
- System alert and confirmation-dialog presentation, which is stock UIKit chrome
  and cannot take these tokens without being replaced outright.
