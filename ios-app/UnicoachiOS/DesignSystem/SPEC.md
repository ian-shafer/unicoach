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

Components style themselves exclusively from the tokens declared in
`Theme.swift` (the `Color` extension, the `Font` extension, `DSSpacing`, and
`DSRadius`): a component takes its colors, fonts, spacing, and corner radii from
those tokens rather than from a color literal, a fixed font/point size, a raw
spacing magnitude, or a raw corner radius. The tokens are the single source of
truth for the visual language. Components communicate with callers only through
their initializer parameters (`Binding`s, value inputs, and escaping closures)
and reach for no application state, services, or navigation, which keeps the
module reusable and independently previewable.

## II. Behavioral Contracts

### Tokens (`Theme.swift`)

- **Color tokens** (`Color` extension) bind by exact name to asset-catalog Color
  Sets. Each token resolves through a **named asset-catalog Color Set**
  (`Color("<Name>", bundle: .main)`) rather than a literal RGB /
  `Color(red:green:blue:)`. Dark-mode and light-mode values are supplied
  entirely by the named Color Set's appearances; no token branches on color
  scheme in code. A token's string name is the exact `.colorset` name it binds
  to (see the table); a name that does not match a Color Set resolves to a
  SwiftUI default color silently, with no build error.

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

- **Typography tokens** (`Font` extension) each map to a relative system text
  style (`Font.system(<TextStyle>, …)`), so the text scales with the user's
  Dynamic Type setting and no token declares a fixed point size. `Theme.swift`
  is the authoritative source of each token's text style and weight.

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

- **`PrimaryButtonStyle: ButtonStyle`** — applies a horizontal inset within the
  style itself (drawn from the `DSSpacing` scale) in addition to expanding to
  the parent's proposed width, so the button keeps horizontal breathing room
  around its label even in a content-sized container (e.g.
  `ContentUnavailableView` actions) and does not rely on the parent to provide
  width. Renders with a `brandAccent` background, `brandOnAccent` label,
  `dsButton` font, and `DSRadius.button` radius. Renders distinct pressed and
  disabled states via reduced opacity; the disabled state takes visual
  precedence over pressed. Used for the primary action.
- **`DestructiveButtonStyle: ButtonStyle`** — applies the same intrinsic
  horizontal inset and full-width expansion as `PrimaryButtonStyle`, with a
  tonal `dsError` foreground on a `dsSurface` + low-opacity `dsError` fill,
  `dsButton` font, and `DSRadius.button` radius. Renders pressed and disabled
  states via reduced opacity, with the disabled state taking visual precedence
  over pressed.
- **`CircularIconButtonStyle: ButtonStyle`** — an intrinsically-sized circular
  button: the label is inset by a symmetric `DSSpacing` pad and clipped to a
  `Circle()`, so the circle is sized from its glyph rather than from a fixed
  diameter. `brandAccent` fill, `brandOnAccent` label, `dsButton` font. Renders
  pressed and disabled states via reduced opacity, with the disabled state
  taking visual precedence over pressed.
- **`CircularIconButton: View`** —
  `init(systemImage:, isLoading:, accessibilityIdentifier: = nil,
  accessibilityLabel: = nil, progressAccessibilityIdentifier: = nil, action:)`.
  Pairs a `Button` with `CircularIconButtonStyle`. The view owns the loading
  behavior: while `isLoading` is `true` it replaces the glyph with a circular
  `ProgressView` and disables the button, so it presents no tappable button in
  the loading state (the style owns only the pressed/disabled opacity). The
  in-circle spinner is tinted `brandOnAccent` so it reads against the
  `brandAccent` fill — a `ProgressView` ignores the style's `foregroundStyle`,
  so the tint is the only thing that colors it.
  `progressAccessibilityIdentifier` sets the identifier on that spinner. Each of
  the three accessibility parameters is opt-in: when `nil`, the component
  applies no corresponding modifier (the SwiftUI default is preserved) rather
  than substituting a fabricated default identifier/label.
- **`LoadingButtonRole`** — `.primary` | `.destructive`; selects the button
  style for `LoadingButton`.
- **`LoadingButton: View`** —
  `init(_ title:, isLoading:, role: = .primary, accessibilityIdentifier: = nil,
  accessibilityLabel: = nil, progressAccessibilityIdentifier: = nil, action:)`.
  Encapsulates the loading/disabled pattern: while `isLoading` is `true` it
  swaps the title for a circular `ProgressView` and disables the button, so it
  presents no tappable button in the loading state.
  `progressAccessibilityIdentifier` sets the identifier on the in-button
  `ProgressView`. The three accessibility parameters are opt-in (a `nil`
  argument applies no modifier).
- **`LabeledField<Value: Hashable>: View`** —
  `init(_ label:, text: Binding<String>, isSecure: = false, error: = nil,
  focus: FocusState<Value?>.Binding, equals: Value, keyboardType: = .default,
  autocapitalization: = .never, disableAutocorrection: = true,
  submitLabel: = .next, accessibilityIdentifier: = nil, accessibilityLabel: = nil,
  onSubmit: = {})`.
  Renders a `dsLabel`/`dsTextSecondary` label, a `TextField` or (`isSecure`)
  `SecureField` styled with `dsSurface` background, `DSRadius.field` radius, and
  `dsBody`/`dsTextPrimary` input. The field does not own focus state: it is
  generic over the caller's focus enum (`Value: Hashable`) and binds to a
  caller-supplied `FocusState<Value?>.Binding` plus an `equals` value, so the
  caller retains full control of tab order and submit chaining. The `error`
  parameter drives the field presentation: when `error` is non-`nil`, the border
  switches from the `dsFieldBorder` token to the `dsError` token **and** an
  inline `FieldErrorText` renders below the field; when `error` is `nil`,
  neither occurs. Accessibility identifier/label are applied to the input
  control (opt-in: a `nil` argument applies no modifier). Default `submitLabel`
  is `.next`; callers pass a terminal label (`.go`/`.done`) for their last
  field.
- **`FieldErrorText: View`** — `init(_ message:)`. Inline error: `dsError`
  color, `dsCaption` font, an `exclamationmark.circle` SF Symbol. Combines its
  children into a single accessibility element whose label is the error
  `message`, so VoiceOver announces the message as one utterance.
- **`FormErrorBanner: View`** — `init(_ message:)`. General (non-field) error: a
  tonal `dsError`-on-`dsSurface` banner with a leading
  `exclamationmark.triangle.fill` SF Symbol and `dsCaption` message text,
  `DSRadius.field` radius. Combines its children into a single accessibility
  element whose label is the error `message`, so VoiceOver announces it as one
  utterance.

### Platform assumptions

- The module targets iOS exclusively, using iOS-specific types such as
  `UIKeyboardType` and `TextInputAutocapitalization`. It carries no
  cross-platform `#if os(...)` compile guards around its styling; the iOS
  deployment target is assumed, so `LabeledField` applies its caller-supplied
  input traits unconditionally, with no platform branch.

### Previews

- `Components.swift` ships `#Preview` providers that render the components in
  **both** light and dark (`.preferredColorScheme`). The previews are the visual
  confirmation that tokens resolve to real Color Sets rather than silent
  defaults.

### Error handling

- This module performs **no error handling logic**. It only _renders_ error
  presentation that callers drive: `LabeledField.error`, `FieldErrorText`, and
  `FormErrorBanner` display caller-supplied strings. There are no contractual or
  system error types originating here.

### Idempotency / safety

- All components are pure functions of their inputs; rendering is side-effect
  free and idempotent. The only outward effect is invoking the caller's `action`
  / `onSubmit` closures in response to user interaction.

## III. Infrastructure & Environment

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

## IV. History

- [x] [RFC 30: Auth UI Design System (iOS)](../../../rfc/30-auth-ui-styling.md)
- [x] [RFC 49: iOS Chat UX Improvements](../../../rfc/49-ios-chat-ux-improvements.md)
