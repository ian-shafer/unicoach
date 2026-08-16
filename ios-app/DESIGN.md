# Unicoach iOS — Design Specification

Derived from the style reference at `iOS.jpg` (login + onboarding mockups),
measured against a 393pt-wide device (iPhone 16 class). This is the durable
source of truth for the app's visual language: prefer editing this file and the
token layer (`DesignSystem/Theme.swift`, `Assets.xcassets/*.colorset`) over
restyling individual views.

Unlike an RFC, this document is **living** — it is updated in place as the
design evolves, not superseded by a higher-numbered file.

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

| Token           | Light     | Current value | Change                                                                                       |
| --------------- | --------- | ------------- | -------------------------------------------------------------------------------------------- |
| `Background`    | `#FFFFFF` | `#FFFFFF`     | none                                                                                         |
| `Surface`       | `#FFFFFF` | `#F2F2F7`     | **flatten to white** — the reference has no grey fills; separation is by border, not by tint |
| `TextPrimary`   | `#000000` | `#1C1C1E`     | darken to pure black                                                                         |
| `TextSecondary` | `#787878` | `#6C6C70`     | slight lighten                                                                               |
| `FieldBorder`   | `#B0B0B0` | `#C7C7CC`     | darken (borders carry the layout, so they must be visible)                                   |
| `BrandAccent`   | `#EE732F` | `#0A66C2`     | **replaces the current blue entirely**                                                       |
| `BrandOnAccent` | `#FFFFFF` | `#FFFFFF`     | none                                                                                         |
| `ControlFill`   | `#030303` | _(new)_       | near-black fill for primary/SSO buttons                                                      |

The single most consequential change: **the current accent is LinkedIn blue
(`#0A66C2`); the reference is orange.** Every accent surface in the app moves.

### 2.1 Dark mode (derived)

Dark mode is **kept**. The reference's visual logic — flat surfaces, no
elevation, separation by hairline border — inverts cleanly; only the near-black
control fill cannot survive inversion.

| Token           | Dark      | Contrast on background | Note                                                                                       |
| --------------- | --------- | ---------------------- | ------------------------------------------------------------------------------------------ |
| `Background`    | `#0E0E10` | —                      | Near-black, not pure black: keeps hairline borders visible and avoids OLED smear on scroll |
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

| Token                | Style                                                                                     |
| -------------------- | ----------------------------------------------------------------------------------------- |
| `dsDisplay` _(new)_  | `.largeTitle` / `.heavy` — screen headings ("When will you graduate?")                    |
| `dsTitleXL`          | `.largeTitle` / `.bold`                                                                   |
| `dsTitle`            | `.title2` / `.bold` (was `.semibold`)                                                     |
| `dsBody`             | `.body` / `.regular`                                                                      |
| `dsLabel`            | `.subheadline` / `.medium`                                                                |
| `dsOverline` _(new)_ | `.caption` / `.semibold`, uppercase, ~0.08em tracking — "WELCOME, KENDALL"                |
| `dsButton`           | `.headline` / `.semibold`                                                                 |
| `dsCaption`          | `.caption` / `.regular`                                                                   |
| `dsOption` _(new)_   | `.title3` / `.bold` — option-card labels, which are far larger than list text normally is |

## 5. Components

### Primary / SSO button

64pt tall, 16pt radius, `#030303` fill, white label, leading icon inset with the
icon+label pair centred as a group. Stacked with a 10–12pt gap.

### Text field

64pt tall, 16pt radius, white fill, 1pt `#B0B0B0` border, `#B0B0B0` placeholder,
20pt leading text inset.

### Option card _(new component)_

64pt tall, 16pt radius, white fill, 1pt border. Leading radio circle (~22pt),
hollow grey ring unselected, solid `#EE732F` fill selected; the selected card's
border darkens. Trailing label in `dsOption`. This has no equivalent in the
codebase today and is the reference's signature control.

### Top bar _(new component)_

Full-bleed brand gradient extending under the status bar, `uni.COACH` wordmark
in white, leading-aligned, ~44pt of content height. This replaces the stock
`.navigationTitle` chrome on branded screens.

### Step indicator _(new component)_

Horizontal rail of N circles joined by a 1pt line; current step filled, the rest
hollow. Used for multi-step onboarding.

### Logo mark

Circular gradient (topLeading → bottomTrailing) with a heavy white `U`, ~62% of
the screen width on the login screen.

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
the gradient must be darkened for that surface. Secondary text at `#787878`
should shift to roughly `#6E6E6E` to clear AA cleanly.

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

The mockups show **only the login screen and one onboarding step**. They specify
nothing about, and the following remain undesigned:

- the conversation/chat surface, which is the product's primary screen — message
  bubbles, the streaming/typing state, the composer
- the slide-over menu's own visual treatment
- conversation list rows, empty states, loading skeletons
- error and alert presentation
