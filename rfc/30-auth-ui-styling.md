# RFC 30: Auth UI Design System (iOS)

## Executive Summary

The iOS app (`ios-app/`) is the only client that renders the register, login,
and logout flows; the Kotlin backend is a JSON-only API with no web UI. Every
form element in the three auth screens is currently constructed inline with
hard-coded colors, spacing, and fonts. There is no shared component or token
layer, and error presentation is inconsistent across screens (inline caption
text, `.alert`, and full-screen `ErrorView`).

This RFC introduces a reusable SwiftUI design-system layer — semantic tokens
(colors, typography, spacing, radius) plus a small set of components
(`PrimaryButtonStyle`, `DestructiveButtonStyle`, `LoadingButton`,
`LabeledField`, `FieldErrorText`, `FormErrorBanner`) — and refactors the three
auth screens to consume it. The objective is a coherent, consistent, and
accessible visual language built on standard iOS conventions (asset-catalog
Color Sets with light/dark appearances, Dynamic Type, idiomatic `ButtonStyle`
extension points), not bespoke branding. ViewModels and the network layer
(`AuthClient`) are not modified, which preserves the existing
ViewModel/`AuthClient` test suites as a regression guarantee.

Form-level error presentation is unified (inline field errors via
`LabeledField`, general errors via `FormErrorBanner`); the full-screen
infrastructure `ErrorView` retains its behavior and is re-skinned with the new
tokens. Dark mode and Dynamic Type are in scope. Project-format modernization
and unique branding are explicit non-goals.

## Detailed Design

### Scope and non-goals

In scope:

- A design-token layer and component set for the iOS app.
- Refactor of `LoginView`, `RegistrationView`, `HomeView`, and `ErrorView` to
  consume the new layer.
- Unification of form-level error presentation.
- Dark mode (via asset-catalog appearances) and Dynamic Type support.

Non-goals (explicitly deferred):

- Xcode project-format modernization (migration to file-system-synchronized
  groups) — separate RFC.
- Unique / bespoke branding beyond a coherent baseline visual language.
- UI snapshot / `XCUITest` coverage — see Tests.
- Any change to `LoginViewModel`, `RegistrationViewModel`, `AppViewModel`, or
  `AuthClient`.
- Any change to the backend (`rest-server`) or its JSON contracts.

### Module / file layout

New design-system source lives under a `DesignSystem/` group in the app target.
To minimize hand-editing of the classic-format `project.pbxproj` (see
Dependencies), the layer is restricted to two new app-target Swift files:

- `ios-app/UnicoachiOS/DesignSystem/Theme.swift` — all tokens.
- `ios-app/UnicoachiOS/DesignSystem/Components.swift` — all components and their
  `#Preview` providers.

The `ErrorResponse.fieldError(for:)` helper is added to the existing
`Models.swift` (already tracked by the project) to avoid an additional new file.

### Data Models

#### Color tokens (asset-catalog Color Sets)

Eight semantic Color Sets are added inside the existing
`ios-app/UnicoachiOS/Assets.xcassets`. Each defines an `Any` (light) and a
`Dark` appearance. Adding color sets inside an already-referenced `.xcassets`
folder does not require a `project.pbxproj` edit.

| Token (Color Set name) | Role                              | Light (sRGB hex) | Dark (sRGB hex) |
| ---------------------- | --------------------------------- | ---------------- | --------------- |
| `BrandAccent`          | Primary action background         | `#0A66C2`        | `#4F9DE0`       |
| `BrandOnAccent`        | Foreground on `BrandAccent`       | `#FFFFFF`        | `#0B1722`       |
| `Background`           | Screen background                 | `#FFFFFF`        | `#000000`       |
| `Surface`              | Field / banner surface            | `#F2F2F7`        | `#1C1C1E`       |
| `TextPrimary`          | Primary text                      | `#1C1C1E`        | `#FFFFFF`       |
| `TextSecondary`        | Secondary text                    | `#6C6C70`        | `#AEAEB2`       |
| `Error`                | Error text / border / banner tint | `#D70015`        | `#FF453A`       |
| `FieldBorder`          | Default field border              | `#C7C7CC`        | `#3A3A3C`       |

The error-state field border reuses the `Error` token; no separate Color Set is
defined for it. All foreground/background pairings above must meet WCAG AA
contrast (≥ 4.5:1 for body text) in both appearances; the listed hex values are
selected to meet AA and are normative — they are verified during implementation
via the contrast step (see Tests) and may be adjusted only if a re-check shows a
pairing fails, in which case the adjustment must be re-verified.

Each `<Token>.colorset/Contents.json` follows this normative structure: an
`info` block (`{ "author": "xcode", "version": 1 }`) and a `colors` array with
exactly two entries — the first with no `appearances` key (the `Any`/light
value) and the second with
`"appearances": [{ "appearance": "luminosity", "value": "dark" }]` (the dark
value). Each entry carries
`"color": { "color-space": "srgb",
"components": { "red", "green", "blue", "alpha" } }`,
with `red`/`green`/`blue` expressed as the `0x..` hex byte form (e.g. `"0xFF"`)
of the table value and `alpha` `"1.000"`. The absence of an `appearances` key on
the first entry is what makes it the universal/light value.

Each `Color` token resolves its Color Set via
`Color("<ColorSetName>", bundle:
.main)`, where `<ColorSetName>` MUST be the
exact `.colorset` directory name from the table above. A name mismatch resolves
to a SwiftUI default color at runtime without a build error; the component
`#Preview` providers (rendered in step 3) are the visual gate that confirms
tokens resolve to the specified colors rather than defaults.

Tokens are surfaced as a typed extension on SwiftUI `Color` declared in
`Theme.swift`:

```
extension Color {
    static let brandAccent: Color
    static let brandOnAccent: Color
    static let dsBackground: Color
    static let dsSurface: Color
    static let dsTextPrimary: Color
    static let dsTextSecondary: Color
    static let dsError: Color
    static let dsFieldBorder: Color
}
```

#### Typography tokens (Dynamic Type)

Declared in `Theme.swift`. Each token maps to a system text style so it scales
with the user's Dynamic Type setting; no fixed point sizes are used.

| Token     | Relative text style | Weight      | Usage                  |
| --------- | ------------------- | ----------- | ---------------------- |
| `titleXL` | `.largeTitle`       | `.bold`     | Screen / welcome title |
| `title`   | `.title2`           | `.semibold` | Section heading        |
| `body`    | `.body`             | `.regular`  | Field input text       |
| `label`   | `.subheadline`      | `.medium`   | Field labels           |
| `caption` | `.caption`          | `.regular`  | Errors / hints         |
| `button`  | `.headline`         | `.semibold` | Button titles          |

Surfaced as:

```
extension Font {
    static let dsTitleXL: Font
    static let dsTitle: Font
    static let dsBody: Font
    static let dsLabel: Font
    static let dsCaption: Font
    static let dsButton: Font
}
```

#### Spacing and radius tokens

Declared in `Theme.swift` as a namespaced set of `CGFloat` constants:

```
enum DSSpacing {
    static let xs: CGFloat   // 4
    static let sm: CGFloat   // 8
    static let md: CGFloat   // 16
    static let lg: CGFloat   // 24
    static let xl: CGFloat   // 40
}

enum DSRadius {
    static let field: CGFloat   // 10
    static let button: CGFloat  // 12
}
```

#### `ErrorResponse.fieldError(for:)`

Pure helper added to `Models.swift`, replacing the inline
`fieldErrors?.first(where:)` lookups in `RegistrationView`:

```
extension ErrorResponse {
    func fieldError(for field: String) -> String?
}
```

Returns the `message` of the first `FieldError` whose `field` equals the
argument, or `nil` when `fieldErrors` is `nil` or contains no match. The
existing `ErrorResponse`/`FieldError` shapes in `Models.swift` are unchanged.

### API Contracts (components)

All components are declared in `Components.swift`. Signatures are normative;
bodies are implementation detail.

#### Button styles

```
struct PrimaryButtonStyle: ButtonStyle {
    init()
}

struct DestructiveButtonStyle: ButtonStyle {
    init()
}
```

`PrimaryButtonStyle`: full-width, `BrandAccent` background, `BrandOnAccent`
label, `DSRadius.button` corner radius, pressed/disabled states. Used for the
primary action on each screen. `DestructiveButtonStyle`: full-width, tonal
treatment — `Error`-tinted `Surface` background with an `Error` foreground label
(mirroring the existing tonal logout look, which uses a low-opacity red fill
with red text), `DSRadius.button` corner radius, pressed/disabled states. The
`Error`-on-`Surface` text pairing must meet WCAG AA (≥ 4.5:1) in both
appearances, on the same footing as the foreground/background pairings in the
color-token table.

#### `LoadingButton`

Encapsulates the loading/disabled pattern currently duplicated across all three
screens (label is swapped for a `ProgressView`; the button is disabled while
busy).

```
enum LoadingButtonRole {
    case primary
    case destructive
}

struct LoadingButton: View {
    init(
        _ title: String,
        isLoading: Bool,
        role: LoadingButtonRole = .primary,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        progressAccessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    )
}
```

`role` selects `PrimaryButtonStyle` or `DestructiveButtonStyle`.
`progressAccessibilityIdentifier` sets the identifier on the in-button
`ProgressView` so the existing `loadingIndicator` identifier on the registration
screen is preserved. When `accessibilityIdentifier`, `accessibilityLabel`, or
`progressAccessibilityIdentifier` is `nil`, no corresponding identifier/label is
applied (SwiftUI default); this preserves today's `HomeView` logout button,
which carries no accessibility identifier.

#### `LabeledField`

A label + input + optional inline error, generic over the screen's focus enum so
focus management is retained.

```
struct LabeledField<Value: Hashable>: View {
    init(
        _ label: String,
        text: Binding<String>,
        isSecure: Bool = false,
        error: String? = nil,
        focus: FocusState<Value?>.Binding,
        equals: Value,
        keyboardType: UIKeyboardType = .default,
        autocapitalization: TextInputAutocapitalization = .never,
        disableAutocorrection: Bool = true,
        submitLabel: SubmitLabel = .next,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        onSubmit: @escaping () -> Void = {}
    )
}
```

Renders the label (`label` typography, `TextSecondary`), a `TextField` or
`SecureField` (`isSecure`) styled with `Surface` background, `FieldBorder`
border, `DSRadius.field` radius, `body` typography. When `error` is non-`nil`,
the border switches to the `Error` token and a `FieldErrorText` is rendered
below the field. The provided accessibility identifier/label are applied to the
input control. The `submitLabel` default of `.next` applies to non-terminal
fields only; each screen passes an explicit terminal `submitLabel` (`.go` or
`.done`) for its last field (the password field, whose `onSubmit` triggers
login/registration) so the on-screen return-key affordance matches today's
behavior.

#### `FieldErrorText`

```
struct FieldErrorText: View {
    init(_ message: String)
}
```

Inline error: `Error` color, `caption` typography, an `exclamationmark.circle`
SF Symbol, and an accessibility configuration that causes VoiceOver to announce
the message.

#### `FormErrorBanner`

```
struct FormErrorBanner: View {
    init(_ message: String)
}
```

General (non-field) error presentation: an `Error`-tinted `Surface` background,
leading SF Symbol, and `message` text in `caption` typography (consistent with
field-level error text). Replaces the inline general-error caption in
`LoginView` and the `.alert` in `RegistrationView`.

### Screen refactors

Behavior (state transitions, validation triggers, navigation callbacks,
infrastructure-error handling) is unchanged; only presentation changes.

- **`LoginView`**: Replace `Form`/`Section` with a `ScrollView` + `VStack`
  (token spacing) on a `Background` fill. Add a `titleXL` heading. Replace the
  two inline `TextField`/`SecureField` blocks with `LabeledField` (email,
  password) bound to the existing `Field` focus enum. Replace the inline general
  error caption with `FormErrorBanner` shown when `viewModel.errorResponse` is
  non-`nil`. Replace the inline primary `Button` with
  `LoadingButton("Log In", isLoading: viewModel.isLoading, role: .primary, …)`.
  Keep the secondary "Register" button as a text button. Retain the
  `fullScreenCover` presenting `ErrorView`. Preserve accessibility identifiers
  `loginEmailField`, `loginPasswordField`, `loginButton`,
  `switchToRegisterButton`.

- **`RegistrationView`**: Remove the `NavigationStack` + `navigationTitle`;
  replace with a custom `titleXL` heading consistent with `LoginView`. Replace
  the three inline fields with `LabeledField` (email, name, password) bound to
  the existing `FocusField` enum, each fed its inline error via
  `viewModel.errorResponse?.fieldError(for:)`. The prior `#if os(iOS)` compile
  guards around `.textInputAutocapitalization` are removed: the design-system
  layer is iOS-only (`LabeledField`'s `keyboardType: UIKeyboardType` parameter
  is iOS-specific), consistent with the iOS-only deployment target. Remove the
  `.alert` modifier; present the general error message via `FormErrorBanner`
  when `viewModel.errorResponse` is non-`nil`. Replace the inline primary
  `Button` with
  `LoadingButton("Register", isLoading: viewModel.isLoading, role: .primary,
  progressAccessibilityIdentifier: "loadingIndicator", …)`.
  Retain the `fullScreenCover` presenting `ErrorView`. Preserve accessibility
  identifiers `emailField`, `nameField`, `passwordField`, `registerButton`,
  `switchToLoginButton`, `loadingIndicator`.

- **`HomeView`**: Apply token spacing/typography and a `Background` fill.
  Replace the inline logout `Button` (which uses `.borderedProminent` and
  `.red`) with
  `LoadingButton("Log Out", isLoading: isLoggingOut, role: .destructive, …)`.
  Behavior (the `onLogout` async call and `isLoggingOut` toggling) is unchanged.

- **`ErrorView`**: Retain the `ContentUnavailableView` structure and the
  `init(title:description:systemImage:retryAction:)` signature. Apply token
  colors/typography to the symbol, title, description, and the "Try Again"
  button (which adopts `PrimaryButtonStyle`). Behavior is unchanged.

`AuthFlowView` and `UnicoachiOSApp` are intentionally not modified; each screen
applies its own `Background` fill.

### Error Handling / Edge Cases

- **Field vs general errors (registration)**: `viewModel.errorResponse` may
  carry both a general `message` and a `fieldErrors` array. After the refactor,
  per-field messages render inline via `LabeledField.error`, and the general
  `message` renders in `FormErrorBanner`. When `errorResponse` is present, the
  banner always shows `message` (matching the prior `.alert`, which always
  surfaced `message`); field-specific messages additionally render against their
  fields. No deduplication of `message` against field messages is performed.
- **Login validation/auth errors**: `errorResponse` for login has no
  `fieldErrors`; the banner shows `message`. Behavior matches today minus the
  presentation change from inline caption to banner.
- **Infrastructure errors**: Unchanged. `viewModel.infrastructureError`
  continues to drive the full-screen `ErrorView` via `fullScreenCover`; only
  `ErrorView`'s skin changes.
- **Dynamic Type extremes**: The `ScrollView` container ensures content remains
  reachable at the largest accessibility text sizes; no fixed-height controls
  are introduced.
- **Dark mode**: Driven entirely by the Color Sets' `Dark` appearance; no
  per-view conditional color logic.
- **Focus management**: `LabeledField` receives the screen's `FocusState`
  binding and `equals` value, preserving the existing tab-order/`onSubmit`
  chaining.

### Dependencies

- **No new third-party dependencies.** The design system uses only SwiftUI and
  Foundation. No SPM/CocoaPods packages are added.
- **Toolchain**: iOS auth UI is built and tested with **system Xcode**
  (`/usr/bin/xcodebuild`, Xcode 26.2), which is **not** part of the project's
  Nix dev shell. Consequently, iOS verification commands in this RFC are **not**
  wrapped in `nix develop -c`; they invoke `xcodebuild` directly. The Nix-shell
  rule in `CLAUDE.md` applies to the Kotlin/Postgres toolchain only.
- **Xcode project format**: `UnicoachiOS.xcodeproj` is classic format
  (`objectVersion = 46`); it does not use file-system-synchronized groups. Each
  new Swift file must be registered in `project.pbxproj` with four coordinated
  entries: a `PBXBuildFile`, a `PBXFileReference`, membership in the owning
  `PBXGroup`, and an entry in the owning target's `PBXSourcesBuildPhase` (app
  target for `Theme.swift`/`Components.swift`; test target for the new test
  file). Color Sets (inside the existing `.xcassets`) and the shared scheme file
  do not require `project.pbxproj` edits.
- **Shared scheme**: The `UnicoachiOS` scheme currently exists only in
  `xcuserdata` (auto-created). A shared scheme is added under
  `xcshareddata/xcschemes/` so build/test commands are reproducible on clean
  checkouts and CI. The scheme references the `UnicoachiOS` and
  `UnicoachiOSTests` target blueprint identifiers, which must be read from
  `project.pbxproj`. The pre-existing auto-created user scheme under
  `xcuserdata/<user>.xcuserdatad/xcschemes/` is left untouched: it is per-user
  and not committed, and Xcode resolves the shared scheme of the same name
  without conflict. No `xcuserdata` edit is part of this RFC.
- **Deployment target**: iOS 17 / Swift 6 (unchanged). `ContentUnavailableView`
  (iOS 17+) and the `Color`/`Font` token APIs used are within target.

## Tests

The existing test suites are unit tests against ViewModels and the network layer
(`UnicoachiOSTests/`); there are no UI tests. Because this RFC does not modify
ViewModels or `AuthClient`, those suites act as the regression guarantee that
the presentation refactor did not alter behavior.

### Existing suites (must remain green)

- `AuthClientTests`, `LoginViewModelTests`, `RegistrationViewModelTests`,
  `AppViewModelTests` — unchanged and must pass after the refactor.

### New unit tests — `UnicoachiOSTests/ErrorResponseTests.swift`

Tests for `ErrorResponse.fieldError(for:)`:

1. `testReturnsMessageWhenFieldPresent` — `fieldErrors` contains an entry for
   the queried field → returns that entry's `message`.
2. `testReturnsNilWhenFieldErrorsNil` — `fieldErrors == nil` → returns `nil`.
3. `testReturnsNilWhenFieldAbsent` — `fieldErrors` non-empty but no entry
   matches the queried field → returns `nil`.
4. `testReturnsFirstMatchWhenDuplicated` — multiple entries match the queried
   field → returns the first entry's `message`.
5. `testReturnsNilForEmptyFieldErrorsArray` — `fieldErrors == []` → returns
   `nil`.

### Component previews (manual visual verification)

`Components.swift` includes `#Preview` providers for `PrimaryButtonStyle`/
`DestructiveButtonStyle` (via `LoadingButton`), `LabeledField` (normal and error
states), `FieldErrorText`, and `FormErrorBanner`, each rendered in light and
dark (`.preferredColorScheme`). Each refactored screen file includes a
`#Preview` using an inline preview-only mock conforming to `AuthClientProtocol`.
The mock implements all four `AuthClientProtocol` methods (`register`, `login`,
`logout`, `me`) returning canned values or throwing, and is defined inline in
each screen file (the existing `UnicoachiOSTests/MockAuthClient.swift` lives in
the test target and is not visible to app-target previews, so it cannot be
reused here). Previews are the standard SwiftUI tool for visual verification;
they are compiled by the build but not asserted automatically.

### Contrast (manual verification)

The WCAG AA contrast requirement (≥ 4.5:1) is checked once during
implementation: the hex values in the color-token table are run through a
contrast checker for each specified foreground/background pairing
(`TextPrimary`/`TextSecondary` on `Background`/`Surface`; `BrandOnAccent` on
`BrandAccent`; `Error` on `Surface`) in both light and dark appearances. The
implementer preserves the listed values verbatim and re-runs a contrast checker
only if any value is changed. No code change alters contrast, so there is no
automated gate.

### Build / test gates

- `xcodebuild … build` succeeds for the `UnicoachiOS` scheme on the simulator.
- `xcodebuild … test` succeeds (existing suites + new `ErrorResponseTests`).

### Out of scope

Automated UI / snapshot testing is deferred. It would require either a new
`XCUITest` target (substantial `project.pbxproj` surgery, against the
minimize-new-files and defer-project-modernization decisions) or a third-party
snapshot dependency (no new dependencies). A dedicated RFC may add UI-snapshot
coverage later.

## Implementation Plan

All `xcodebuild` commands run from the repository root and target a simulator
present in this environment; adjust `name=iPhone 17` if that simulator is
unavailable (`xcrun simctl list devices available`). Commands are **not**
wrapped in `nix develop -c` (system Xcode).

Common verification command (referred to below as **BUILD**):

```
xcodebuild -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Common test command (referred to below as **TEST**):

```
xcodebuild -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

1. **Add Color Sets.** Create the eight `*.colorset/Contents.json` directories
   under `ios-app/UnicoachiOS/Assets.xcassets` per the color-token table, each
   with `Any` and `Dark` appearances.
   - Verify: `ls ios-app/UnicoachiOS/Assets.xcassets` lists the eight new
     `.colorset` directories; each `Contents.json` contains an
     `"appearance": "luminosity", "value": "dark"` entry (confirming the dark
     variant is present, not just the light default); **BUILD** succeeds.

2. **Create `Theme.swift`.** Add `ios-app/UnicoachiOS/DesignSystem/Theme.swift`
   declaring the `Color` token extension (mapping to the Color Sets), the `Font`
   token extension, and `DSSpacing`/`DSRadius`. Register the file in
   `project.pbxproj` (app target: `PBXBuildFile`, `PBXFileReference`, group
   membership, `PBXSourcesBuildPhase`).
   - Verify: **BUILD** succeeds.

3. **Create `Components.swift`.** Add
   `ios-app/UnicoachiOS/DesignSystem/Components.swift` declaring
   `PrimaryButtonStyle`, `DestructiveButtonStyle`, `LoadingButtonRole`,
   `LoadingButton`, `LabeledField`, `FieldErrorText`, `FormErrorBanner`, and
   their `#Preview` providers. Register the file in `project.pbxproj` (app
   target).
   - Verify: **BUILD** succeeds.

4. **Add `ErrorResponse.fieldError(for:)`.** Add the extension to
   `ios-app/UnicoachiOS/Models.swift`.
   - Verify: **BUILD** succeeds.

5. **Add `ErrorResponseTests.swift`.** Create
   `ios-app/UnicoachiOSTests/ErrorResponseTests.swift` with the five tests in
   Tests. Register the file in `project.pbxproj` (test target: `PBXBuildFile`,
   `PBXFileReference`, group membership, `PBXSourcesBuildPhase` of
   `UnicoachiOSTests`).
   - Verify: **TEST** succeeds and the five `ErrorResponse` tests execute and
     pass.

6. **Refactor `LoginView`.** Replace `Form` with the token `ScrollView`/`VStack`
   layout, `LabeledField` for email/password, `FormErrorBanner` for the general
   error, and `LoadingButton` for the primary action. Preserve the accessibility
   identifiers listed in Detailed Design and the `fullScreenCover`/`ErrorView`
   wiring.
   - Verify: **BUILD** succeeds; **TEST** succeeds (`LoginViewModelTests`
     unchanged and green).

7. **Refactor `RegistrationView`.** Remove `NavigationStack`/`navigationTitle`
   and `.alert`; add the custom heading, `LabeledField` for the three fields
   (inline errors via `fieldError(for:)`), `FormErrorBanner` for the general
   error, and `LoadingButton` (with
   `progressAccessibilityIdentifier:
   "loadingIndicator"`). Preserve listed
   accessibility identifiers and the `fullScreenCover`/`ErrorView` wiring.
   - Verify: **BUILD** succeeds; **TEST** succeeds (`RegistrationViewModelTests`
     unchanged and green).

8. **Refactor `HomeView`.** Apply token spacing/typography/background and
   replace the logout button with `LoadingButton(role: .destructive)`. Behavior
   unchanged.
   - Verify: **BUILD** succeeds.

9. **Re-skin `ErrorView`.** Apply token colors/typography and
   `PrimaryButtonStyle` to the "Try Again" action, keeping the
   `ContentUnavailableView` structure and the public initializer signature.
   - Verify: **BUILD** succeeds.

10. **Add shared scheme.** Create
    `ios-app/UnicoachiOS.xcodeproj/xcshareddata/xcschemes/UnicoachiOS.xcscheme`
    referencing the `UnicoachiOS` (build/run) and `UnicoachiOSTests` (test)
    target blueprint identifiers read from `project.pbxproj`.
    - Verify: `xcodebuild -list -project ios-app/UnicoachiOS.xcodeproj` lists
      the `UnicoachiOS` scheme as a `Shared` scheme; the file exists under
      `xcshareddata/xcschemes/`. The authoritative proof that the scheme's test
      action references `UnicoachiOSTests` is the **TEST** gate in step 11,
      which executes `xcodebuild ... test -scheme UnicoachiOS`; a scheme that
      lists but has no wired test action fails **TEST**.

11. **Full verification.** Run **BUILD** then **TEST** from a clean state.
    - Verify: both succeed; all existing suites and `ErrorResponseTests` pass.

## Files Modified

The preview-only `AuthClientProtocol` mocks are declared inline in the existing
screen files (see Tests); they introduce no new files.

Created:

- `ios-app/UnicoachiOS/DesignSystem/Theme.swift`
- `ios-app/UnicoachiOS/DesignSystem/Components.swift`
- `ios-app/UnicoachiOSTests/ErrorResponseTests.swift`
- `ios-app/UnicoachiOS.xcodeproj/xcshareddata/xcschemes/UnicoachiOS.xcscheme`
- `ios-app/UnicoachiOS/Assets.xcassets/BrandAccent.colorset/Contents.json`
- `ios-app/UnicoachiOS/Assets.xcassets/BrandOnAccent.colorset/Contents.json`
- `ios-app/UnicoachiOS/Assets.xcassets/Background.colorset/Contents.json`
- `ios-app/UnicoachiOS/Assets.xcassets/Surface.colorset/Contents.json`
- `ios-app/UnicoachiOS/Assets.xcassets/TextPrimary.colorset/Contents.json`
- `ios-app/UnicoachiOS/Assets.xcassets/TextSecondary.colorset/Contents.json`
- `ios-app/UnicoachiOS/Assets.xcassets/Error.colorset/Contents.json`
- `ios-app/UnicoachiOS/Assets.xcassets/FieldBorder.colorset/Contents.json`

Modified:

- `ios-app/UnicoachiOS/Models.swift` — add `ErrorResponse.fieldError(for:)`.
- `ios-app/UnicoachiOS/LoginView.swift` — refactor to components/tokens.
- `ios-app/UnicoachiOS/RegistrationView.swift` — refactor to components/tokens;
  remove `NavigationStack`/`.alert`.
- `ios-app/UnicoachiOS/HomeView.swift` — refactor logout button to
  `LoadingButton`; apply tokens.
- `ios-app/UnicoachiOS/ErrorView.swift` — re-skin with tokens; signature
  unchanged.
- `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` — register `Theme.swift`,
  `Components.swift` (app target) and `ErrorResponseTests.swift` (test target).
