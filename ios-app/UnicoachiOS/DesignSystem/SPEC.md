# SPEC: DesignSystem

## I. Overview

**Domain:** The shared SwiftUI presentation layer for the iOS app — semantic
design **tokens** (color, typography, spacing, radius) and the reusable **view
components** that consume them. This is a leaf module: it renders styled,
accessible UI primitives and owns no application state, navigation, or I/O. Its
consumers live outside this directory and are out of scope for this spec.

- [Theme.swift](./Theme.swift) — token definitions.
- [Components.swift](./Components.swift) — components and their `#Preview`
  providers.

## II. Invariants (The "Guardrails")

- **Token-only styling.** Every component in this module MUST derive its colors,
  fonts, spacing, and corner radii **exclusively** from the tokens declared in
  `Theme.swift` (`Color` extension, `Font` extension, `DSSpacing`, `DSRadius`).
  A component NEVER hardcodes a color literal, a fixed font/point size, a raw
  spacing magnitude, or a raw corner radius. The tokens are the single source of
  truth for the visual language.

- **Dynamic Type, never fixed sizes.** Every typography token MUST map to a
  relative system text style (`Font.system(<TextStyle>, …)`) so it scales with
  the user's Dynamic Type setting. The module NEVER declares a fixed point size.

- **Color via asset-catalog resolution.** Every color token MUST resolve through
  a **named asset-catalog Color Set** (`Color("<Name>", bundle: .main)`), never
  a literal RGB/`Color(red:green:blue:)`. Dark-mode and light-mode values are
  supplied entirely by the named Color Set's appearances; the module NEVER
  branches on color scheme in code. A token's string name MUST be the exact
  `.colorset` name it binds to (see Behavioral Contracts) — a mismatch resolves
  to a SwiftUI default color silently, with no build error.

- **Presentation purity.** Components MUST communicate with callers only through
  their initializer parameters (`Binding`s, value inputs, and escaping
  closures), and MUST NOT reach for application state, services, or navigation.
  This keeps the module reusable and independently previewable.

- **Caller-owned focus.** `LabeledField` MUST NOT own focus state. It is generic
  over the caller's focus enum (`Value: Hashable`) and binds to a
  caller-supplied `FocusState<Value?>.Binding` plus an `equals` value, so the
  caller retains full control of tab order and submit chaining.

- **Opt-in accessibility identifiers/labels.** When an
  `accessibilityIdentifier`, `accessibilityLabel`, or
  `progressAccessibilityIdentifier` parameter is `nil`, the component MUST apply
  **no** corresponding modifier (SwiftUI default preserved): a `nil` argument is
  a true no-op, never a default substitution. It NEVER substitutes a fabricated
  default identifier/label.

- **Intrinsic button inset.** `PrimaryButtonStyle` and `DestructiveButtonStyle`
  MUST apply a horizontal inset within the style itself, drawn from the
  `DSSpacing` scale, in addition to expanding to the parent's proposed width. In
  a content-sized container (e.g. `ContentUnavailableView` actions) the rendered
  button MUST still have horizontal breathing room around its label; the styles
  NEVER rely on the parent to provide width.

- **Loading implies disabled.** While a loading-capable button's `isLoading` is
  `true` (`LoadingButton`, `CircularIconButton`), the button MUST be disabled and
  its title/glyph MUST be replaced by a `ProgressView`; it NEVER presents a
  tappable button in the loading state.

- **Error state drives field presentation.** When `LabeledField.error` is
  non-`nil`, the field border MUST switch from the default border token to the
  error token **and** an inline `FieldErrorText` MUST render below the field.
  When `error` is `nil`, neither occurs.

- **iOS-only.** The module targets iOS exclusively (it uses iOS-specific types
  such as `UIKeyboardType` and `TextInputAutocapitalization`). It NEVER
  introduces cross-platform `#if os(...)` compile guards around its styling; the
  iOS deployment target is assumed, so `LabeledField` applies its
  caller-supplied input traits unconditionally, with no platform branch.

- **VoiceOver announces error text.** `FieldErrorText` and `FormErrorBanner`
  MUST combine their children into a single accessibility element whose label is
  the error `message`, so VoiceOver announces the message as one utterance.

- **Visual gate.** `Components.swift` MUST ship `#Preview` providers that render
  the components in **both** light and dark (`.preferredColorScheme`). The
  previews are the visual confirmation that tokens resolve to real Color Sets
  rather than silent defaults.

## III. Behavioral Contracts

### Tokens (`Theme.swift`)

- **Color tokens** (`Color` extension) bind by exact name to asset-catalog Color
  Sets:

  | Token             | Color Set name  | Role                              |
  | ----------------- | --------------- | --------------------------------- |
  | `brandAccent`     | `BrandAccent`   | Primary action background         |
  | `brandOnAccent`   | `BrandOnAccent` | Foreground on `brandAccent`       |
  | `dsBackground`    | `Background`    | Screen background                 |
  | `dsSurface`       | `Surface`       | Field / banner surface            |
  | `dsTextPrimary`   | `TextPrimary`   | Primary text                      |
  | `dsTextSecondary` | `TextSecondary` | Secondary text                    |
  | `dsError`         | `Error`         | Error text / border / banner tint |
  | `dsFieldBorder`   | `FieldBorder`   | Default field border              |

  The error-state field border reuses `dsError`; there is no separate token for
  it.

- **Typography tokens** (`Font` extension) map to relative system text styles
  (per the Dynamic Type invariant). `Theme.swift` is the authoritative source of
  each token's text style and weight.

  | Token       | Role                   |
  | ----------- | ---------------------- |
  | `dsTitleXL` | Screen / welcome title |
  | `dsTitle`   | Section heading        |
  | `dsBody`    | Field input text       |
  | `dsLabel`   | Field labels           |
  | `dsCaption` | Errors / hints         |
  | `dsButton`  | Button titles          |

- **`DSSpacing`** — a fixed, monotonically increasing spacing scale (`CGFloat`),
  ordered `xs < sm < md < lg < xl`. `Theme.swift` is the authoritative source of
  the literal values.
- **`DSRadius`** — a fixed corner-radius scale (`CGFloat`) with `field` and
  `button` steps. `Theme.swift` is authoritative for the literal values.

These token sets are the canonical definition of their values; consumers
reference the named tokens, never the raw literals.

### Components (`Components.swift`)

> **Design constraint (hand-verified, not an automated gate):** the tonal
> `dsError`-foreground-on-`dsSurface` fill that `DestructiveButtonStyle` and
> `FormErrorBanner` compose is verified once to meet WCAG AA (≥ 4.5:1) in both
> appearances. Raw token contrast is owned by the asset catalog.

All signatures below are normative; bodies are implementation detail. Components
are pure SwiftUI `View`/`ButtonStyle` values with **no side effects** beyond
invoking caller-supplied closures — no network, persistence, or external I/O.

- **`PrimaryButtonStyle: ButtonStyle`** — stretches edge-to-edge in a full-width
  parent and renders with an intrinsic horizontal inset in a content-sized
  container (see the intrinsic-button-inset invariant); `brandAccent`
  background, `brandOnAccent` label, `dsButton` font, `DSRadius.button` radius.
  Renders distinct pressed and disabled states via reduced opacity; the disabled
  state takes visual precedence over pressed. Used for the primary action.
- **`DestructiveButtonStyle: ButtonStyle`** — stretches edge-to-edge in a
  full-width parent and renders with an intrinsic horizontal inset in a
  content-sized container (see the intrinsic-button-inset invariant); tonal
  `dsError` foreground on a `dsSurface` + low-opacity `dsError` fill, `dsButton`
  font, `DSRadius.button` radius. Renders pressed and disabled states via
  reduced opacity, with the disabled state taking visual precedence over
  pressed.
- **`CircularIconButtonStyle: ButtonStyle`** — an intrinsically-sized circular
  button: the label is inset by a symmetric `DSSpacing` pad and clipped to a
  `Circle()`, so the circle is sized from its glyph and NEVER from a fixed
  diameter. `brandAccent` fill, `brandOnAccent` label, `dsButton` font. Renders
  pressed and disabled states via reduced opacity, with the disabled state taking
  visual precedence over pressed.
- **`CircularIconButton: View`** —
  `init(systemImage:, isLoading:, accessibilityIdentifier: = nil,
  accessibilityLabel: = nil, progressAccessibilityIdentifier: = nil, action:)`.
  Pairs a `Button` with `CircularIconButtonStyle`. The view owns the loading
  behavior — while `isLoading` it replaces the glyph with a circular
  `ProgressView` and disables the button (the style owns only the pressed/disabled
  opacity). The in-circle spinner MUST be tinted `brandOnAccent` so it reads
  against the `brandAccent` fill — a `ProgressView` ignores the style's
  `foregroundStyle`, so the tint is the only thing that colors it.
  `progressAccessibilityIdentifier` sets the identifier on that spinner; the three
  accessibility parameters follow the opt-in invariant.
- **`LoadingButtonRole`** — `.primary` | `.destructive`; selects the button
  style for `LoadingButton`.
- **`LoadingButton: View`** —
  `init(_ title:, isLoading:, role: = .primary, accessibilityIdentifier: = nil,
  accessibilityLabel: = nil, progressAccessibilityIdentifier: = nil, action:)`.
  Encapsulates the loading/disabled pattern: swaps the title for a circular
  `ProgressView` and disables the button while `isLoading`.
  `progressAccessibilityIdentifier` sets the identifier on the in-button
  `ProgressView`. The three accessibility parameters follow the opt-in
  invariant.
- **`LabeledField<Value: Hashable>: View`** —
  `init(_ label:, text: Binding<String>, isSecure: = false, error: = nil,
  focus: FocusState<Value?>.Binding, equals: Value, keyboardType: = .default,
  autocapitalization: = .never, disableAutocorrection: = true,
  submitLabel: = .next, accessibilityIdentifier: = nil, accessibilityLabel: = nil,
  onSubmit: = {})`.
  Renders a `dsLabel`/`dsTextSecondary` label, a `TextField` or (`isSecure`)
  `SecureField` styled with `dsSurface` background, `DSRadius.field` radius,
  `dsBody`/`dsTextPrimary` input, and a border that is `dsFieldBorder` normally
  / `dsError` on error. Accessibility identifier/label are applied to the input
  control. Default `submitLabel` is `.next`; callers pass a terminal label
  (`.go`/`.done`) for their last field.
- **`FieldErrorText: View`** — `init(_ message:)`. Inline error: `dsError`
  color, `dsCaption` font, an `exclamationmark.circle` SF Symbol,
  VoiceOver-announced.
- **`FormErrorBanner: View`** — `init(_ message:)`. General (non-field) error: a
  tonal `dsError`-on-`dsSurface` banner with a leading
  `exclamationmark.triangle.fill` SF Symbol and `dsCaption` message text,
  `DSRadius.field` radius, VoiceOver-announced.

### Error handling

- This module performs **no error handling logic**. It only _renders_ error
  presentation that callers drive: `LabeledField.error`, `FieldErrorText`, and
  `FormErrorBanner` display caller-supplied strings. There are no contractual or
  system error types originating here.

### Idempotency / safety

- All components are pure functions of their inputs; rendering is side-effect
  free and idempotent. The only outward effect is invoking the caller's `action`
  / `onSubmit` closures in response to user interaction.

## IV. Infrastructure & Environment

- **Frameworks:** SwiftUI and Foundation/UIKit only. **No** third-party
  (SPM/CocoaPods) dependencies.
- **Toolchain:** Built and previewed with **system Xcode**
  (`/usr/bin/xcodebuild`, Xcode 26.2), **not** the project's Nix dev shell. The
  `nix develop -c` rule in `CLAUDE.md` applies to the Kotlin/Postgres toolchain
  only and does **not** wrap iOS build commands.
- **Deployment target:** iOS 17 / Swift 6.
- **Asset dependency:** Color tokens resolve named Color Sets that live in
  `ios-app/UnicoachiOS/Assets.xcassets` (outside this directory). The light/dark
  hex values and their WCAG AA verification are owned there; this module owns
  only the token-name → Color-Set-name binding.
- **Project registration:** `Theme.swift` and `Components.swift` are app-target
  files registered in the classic-format `project.pbxproj` (outside this
  directory).

## V. History (Traceability Matrix)

- [x] [RFC 30: Auth UI Design System (iOS)](../../../rfc/30-auth-ui-styling.md)
- [x] [RFC 49: iOS Chat UX Improvements](../../../rfc/49-ios-chat-ux-improvements.md)
