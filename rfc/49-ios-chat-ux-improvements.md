# RFC 49: iOS Chat UX Improvements

## Executive Summary

`ConversationView` — the iOS coaching-conversation screen — has two UX
deficiencies; the first is dominated by an app-level rendering defect.

The app declares no launch screen: `Info.plist` has no `UILaunchScreen` key and
the Xcode project contains no launch storyboard. Without one, iOS runs the app
in legacy compatibility mode, letterboxing the whole window to ~75% height with
black bands at the top and bottom on every screen (Home, Login, chat). This is
the primary cause of the wasted vertical space. Separately, `ConversationView`'s
root `VStack` omits a `maxHeight: .infinity` frame, so even under native
full-screen rendering the thread does not claim all vertical slack the
navigation container offers (unlike `HomeView`, which stretches); a frame cannot
defeat an app-level letterbox, so this is an in-view complement, not the fix.
Second, the send control is a full-width `LoadingButton` beside the input in an
`HStack`, consuming horizontal space and diverging from the common in-field send
affordance.

This RFC makes three changes. (1) An empty `UILaunchScreen` dictionary is added
to `Info.plist`, opting the app into native full-screen rendering and reclaiming
the full window height app-wide. (2) The view's root `VStack` and the message
`thread` `ScrollView` are stretched to fill available space, so the thread owns
the vertical slack and the composer pins to the bottom edge. (3) The composer
becomes a single `TextField` with a circular send button overlaid in its
bottom-trailing corner. The button is a new reusable `DesignSystem` component
(`CircularIconButton`, paired with `CircularIconButtonStyle`) — a `brandAccent`
circle bearing a `brandOnAccent` `arrow.up` glyph. It mirrors the
`LoadingButton`/`PrimaryButtonStyle` pairing: the `View` wrapper disables itself
while loading (as `LoadingButton` does at `Components.swift`), and the style
applies the opacity rule. It is token-pure (intrinsically sized from the glyph
plus `DSSpacing` padding, no raw magnitudes).

Send enablement moves into the ViewModel as a published-derived `canSend`
property (`!isStreaming && trimmed message non-empty`), making the empty-input
disable behavior unit-testable; the existing `send()` empty-guard is retained as
defense in depth. The screen is multi-turn, so a completed turn does not gate
sending — only an in-flight stream or an empty message does. The input field
stays enabled when empty so the user can type.

## Detailed Design

### Data Models

No data-model or API changes. The work is confined to the iOS presentation layer
(`ios-app/UnicoachiOS`).

### `CircularIconButton` and `CircularIconButtonStyle` (`DesignSystem/Components.swift`)

A new reusable circular icon button, structured as a `ButtonStyle` plus a `View`
wrapper, mirroring the existing `PrimaryButtonStyle` / `LoadingButton` pairing.
It belongs in `DesignSystem` because the module owns all token-styled,
independently-previewable UI primitives; `ConversationView` remains a pure
consumer.

`CircularIconButtonStyle: ButtonStyle` — renders the label inside a circular
`brandAccent` fill with a `brandOnAccent` foreground, clipped with
`.clipShape(Circle())`. The circle is sized **intrinsically** from its content:
the glyph at the `dsButton` font plus symmetric `DSSpacing.sm` padding. No fixed
point size or raw diameter is declared, satisfying the module's token-only and
Dynamic-Type invariants. Pressed and disabled states render via reduced opacity,
with the disabled state taking visual precedence over pressed, identical to
`PrimaryButtonStyle`'s opacity rule (`0.5` disabled, `0.8` pressed, `1.0`
otherwise); the style reads `@Environment(\.isEnabled)`. The loading→disabled
behavior is not in the style; it lives in the `View` wrapper below, mirroring
`LoadingButton`'s `.disabled(isLoading)`.

`CircularIconButton: View` — signature:

```
init(
    systemImage: String,
    isLoading: Bool,
    accessibilityIdentifier: String? = nil,
    accessibilityLabel: String? = nil,
    progressAccessibilityIdentifier: String? = nil,
    action: @escaping () -> Void
)
```

It swaps the SF Symbol (`Image(systemName: systemImage)`) for a circular
`ProgressView` while `isLoading`, disables the button while `isLoading`, and
applies `CircularIconButtonStyle`. The spinner is tinted `brandOnAccent` via
`.tint` (which `ProgressView` honors, unlike the style's `foregroundStyle`), so
it reads against the `brandAccent` fill like the glyph. The three accessibility
parameters follow the module's opt-in invariant: a `nil` argument applies no
modifier (reusing the existing `OptionalIdentifier` / `OptionalLabel`
modifiers). `progressAccessibilityIdentifier`, when present, sets the identifier
on the in-button `ProgressView`.

The component is invoked with `systemImage: "arrow.up"`.

### `ConversationView` composer (`ConversationView.swift`)

The composer changes from an `HStack { TextField; LoadingButton }` to a single
`TextField` carrying the send button as a bottom-trailing overlay:

- The `TextField("Message", …, axis: .vertical)` retains all its existing
  modifiers — `dsSurface` background, `DSRadius.field` clip, `dsBody` font,
  `messageField` accessibility identifier, "Message" label,
  `.focused($isComposerFocused)` (so `send()`'s keyboard dismissal still fires),
  and `.disabled(isComposerDisabled)`. Only the trailing inset and overlay below
  are added.
- The field carries a trailing content inset so input text never underlaps the
  button. Invariant: because the button is intrinsically sized and scales with
  Dynamic Type, the inset MUST track the button's scaled width plus its
  `DSSpacing.sm` edge inset, not a fixed token — a hardcoded value clears the
  button only at default text size and is overrun at accessibility sizes.
- `.overlay(alignment: .bottomTrailing)` hosts the `CircularIconButton`
  (`systemImage: "arrow.up"`, `isLoading: viewModel.isStreaming`,
  `accessibilityIdentifier: "sendButton"`, `accessibilityLabel: "Send"`,
  `action: send`), inset from the field edges by `DSSpacing.sm` and
  `.disabled(!viewModel.canSend)`.

Bottom-trailing alignment is correct for the vertically-growing field: as the
field grows with multi-line input, the button stays anchored to the bottom-right
corner.

The field stays enabled while empty (only `isComposerDisabled` — `isStreaming` —
disables it), so the user can always type; only the button disables on empty
input.

### App launch screen (`Info.plist`)

`ios-app/UnicoachiOS/Info.plist` gains an empty `UILaunchScreen` dictionary:

```
<key>UILaunchScreen</key>
<dict/>
```

The key's presence — the empty dictionary is a valid, content-free launch screen
— signals that the app supports native full-screen rendering. Absent it, and
with no launch storyboard in the Xcode project, iOS falls back to legacy
compatibility mode and renders every screen letterboxed to ~75% of the window
height with black bands at the top and bottom. This is an app-global defect, not
specific to `ConversationView`; the key reclaims the full window on every screen
(Home, Login, chat) and is the primary mechanism for the full-vertical-space
goal. The project sets both `GENERATE_INFOPLIST_FILE = YES` and an explicit
`INFOPLIST_FILE = UnicoachiOS/Info.plist` for the Debug and Release
configurations, so the key added to the checked-in plist is merged into the
built `Info.plist` for both; no `project.pbxproj` change is required.

### `ConversationView` vertical fill (`ConversationView.swift`)

This complements the launch-screen fix: it addresses residual in-view slack
within `ConversationView`'s own layout, which the launch-screen key does not
touch. With native full-screen rendering restored app-wide, the root
`VStack(spacing: 0)` gains `.frame(maxWidth: .infinity, maxHeight: .infinity)`
(applied before `.background(Color.dsBackground)`), and the `thread`
`ScrollView` gains `.frame(maxWidth: .infinity, maxHeight: .infinity)`. The
`ScrollView` thereby claims all vertical slack between the navigation bar and
the composer; the `validationArea` and `composer` keep their intrinsic heights
and the composer pins to the bottom edge. Existing top-leading alignment of the
message `VStack` is preserved (messages accrue from the top).

### `canSend` (`ConversationViewModel.swift`)

A computed property consolidates send enablement and makes the empty-input rule
testable:

```
var canSend: Bool {
    !isStreaming
        && !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
}
```

The screen is multi-turn: a completed turn does not block the next message, so
`canSend` carries no completed-turn guard — its only blocks are an in-flight
stream and an empty message. `canSend` is a plain computed property and does not
itself publish; the view's `body` reads `viewModel.canSend`, and because its two
inputs (`isStreaming`, `messageText`) are `@Published`, each mutation re-renders
`body`, re-evaluating `canSend` and updating the button's `.disabled` state. The
existing `send()` empty/length guard (`ConversationViewModel.send()`) is
unchanged and remains the authoritative defense — `canSend` is the
presentational gate that prevents the empty-message `VALIDATION` error from ever
being triggered by a tap.

### Error Handling / Edge Cases

- No new error paths. `send()`'s empty-input and max-length guards are retained;
  `canSend` only narrows when the button is tappable.
- While streaming, `canSend` yields `false`, so the button is non-interactive
  (matching the field's `isComposerDisabled`), and `isLoading` additionally
  shows the in-button `ProgressView`. After a completed turn the composer
  re-enables for the next message — `canSend` is `true` once the field is
  non-empty again, matching the multi-turn flow.
- Dynamic Type: the button scales with the `dsButton` text style; the circle is
  intrinsically sized, so larger type yields a proportionally larger control
  with no clipping. Text clears the control at every size via the width-tracking
  trailing inset (composer section).

### Dependencies

SwiftUI / Foundation only. No new third-party dependencies. No new source files
are created (the component is added to the existing `Components.swift`), so the
Xcode project file requires no changes.

## Tests

Layout and visual changes have no automated assertion path (no XCUITest target
and no snapshot infrastructure exist in `ios-app`); they are verified by a
successful build, by the `DesignSystem` `#Preview` visual gate, and — for the
launch-screen letterbox fix — by launching the built app in a simulator and
confirming the absence of black letterbox bands. The behavioral change
(`canSend`) is unit-tested in the ViewModel.

### `ConversationViewModelTests` (new cases)

Added to `ios-app/UnicoachiOSTests/ConversationViewModelTests.swift`, following
the existing `@MainActor` test pattern:

- **`canSend` false on empty message** — fresh ViewModel (`messageText == ""`) →
  `canSend == false`.
- **`canSend` false on whitespace-only message** — `messageText = "   \n\t"` →
  `canSend == false`.
- **`canSend` true on non-empty message** — `messageText = "Hello"` →
  `canSend == true`.
- **`canSend` false while streaming** — drive `isStreaming = true` with a
  non-empty `messageText` → `canSend == false`.
- **`canSend` true after a completed turn** — after a stream that yields a
  `.completed` message (via `MockConversationClient`), setting a non-empty
  `messageText` → `canSend == true` (multi-turn: a completed turn must not
  disable the composer for the next message).

### `Components.swift` visual gate (`#Preview`)

`CircularIconButton` is added to the `buttonPreview` computed view (rendered by
the existing `"Buttons - Light"` and `"Buttons - Dark"` `#Preview`s in
`Components.swift`), rendering its default, loading, and disabled states in both
schemes per the `DesignSystem` visual-gate invariant. This is a manual visual
confirmation, not an automated assertion.

## Implementation Plan

1. **Add the `UILaunchScreen` key to `ios-app/UnicoachiOS/Info.plist`.** Insert
   an empty `UILaunchScreen` dictionary (`<key>UILaunchScreen</key><dict/>`)
   into the top-level plist `dict`.
   - Verify:
     `cd ios-app && xcodebuild -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16' build`
     succeeds; then install the built app to a booted simulator
     (`xcrun simctl install booted <built-app-path>`) and launch it, confirming
     no black letterbox bands appear at the top or bottom on the Home, Login,
     and chat screens (content renders edge-to-edge).

2. **Add `CircularIconButtonStyle` and `CircularIconButton` to
   `DesignSystem/Components.swift`.** Implement the `ButtonStyle` (token-only,
   intrinsically-sized `brandAccent` circle with `brandOnAccent` foreground,
   opacity-driven pressed/disabled states) and the `View` wrapper (loading →
   `ProgressView` + disabled, opt-in accessibility via the existing
   `OptionalIdentifier`/`OptionalLabel` modifiers). Add the new button (default,
   loading, and disabled states) to the `buttonPreview` view backing the
   `"Buttons - Light"`/`"Buttons - Dark"` `#Preview`s.
   - Verify:
     `cd ios-app && xcodebuild -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16' build`
     succeeds.

3. **Add `canSend` to `ConversationViewModel.swift`.** Add the computed property
   as specified. Leave `send()` unchanged.
   - Verify:
     `cd ios-app && xcodebuild -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16' build`
     succeeds.

4. **Restructure the composer and apply vertical fill in
   `ConversationView.swift`.** Replace the composer `HStack` with a single
   `TextField` plus a bottom-trailing `CircularIconButton` overlay
   (`systemImage: "arrow.up"`, `isLoading: viewModel.isStreaming`,
   `accessibilityIdentifier: "sendButton"`, `accessibilityLabel: "Send"`,
   `.disabled(!viewModel.canSend)`). Reserve the field's trailing inset by
   measuring the button's scaled width (e.g. a `GeometryReader`/`PreferenceKey`
   width probe on a mirror of the button) so the inset tracks Dynamic Type. Add
   `.frame(maxWidth: .infinity, maxHeight: .infinity)` to the root `VStack` and
   to the `thread` `ScrollView`. Preserve all existing field modifiers,
   including the `messageField` identifier, `.focused($isComposerFocused)`, and
   `.disabled(isComposerDisabled)`.
   - Verify:
     `cd ios-app && xcodebuild -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16' build`
     succeeds.

5. **Add `canSend` unit tests to
   `ios-app/UnicoachiOSTests/ConversationViewModelTests.swift`.** Implement the
   five cases in the Tests section using block-bodied `@MainActor` test
   functions (expression-bodied async tests returning non-`Unit` are silently
   dropped by JUnit-style runners; use block bodies).
   - Verify:
     `cd ios-app && xcodebuild test -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:UnicoachiOSTests/ConversationViewModelTests`
     passes, and the executed-test count includes the five new cases.

6. **Full iOS test + build pass.**
   - Verify:
     `cd ios-app && xcodebuild test -scheme UnicoachiOS -destination 'platform=iOS Simulator,name=iPhone 16'`
     passes.

## Files Modified

- `ios-app/UnicoachiOS/Info.plist` — add an empty `UILaunchScreen` dictionary to
  opt the app into native full-screen rendering (removes the app-wide
  letterbox).
- `ios-app/UnicoachiOS/DesignSystem/Components.swift` — add
  `CircularIconButtonStyle: ButtonStyle` and `CircularIconButton: View`; add the
  new button to the `buttonPreview` view (`"Buttons - Light"`/`"Buttons - Dark"`
  `#Preview`s).
- `ios-app/UnicoachiOS/ConversationView.swift` — composer restructure (single
  `TextField` + bottom-trailing `CircularIconButton` overlay + trailing inset,
  retaining `.focused($isComposerFocused)`), root `VStack` and `thread`
  `ScrollView` `maxHeight: .infinity` frames, button
  `.disabled(!viewModel.canSend)`.
- `ios-app/UnicoachiOS/ConversationViewModel.swift` — add the `canSend` computed
  property.
- `ios-app/UnicoachiOSTests/ConversationViewModelTests.swift` — add the five
  `canSend` unit-test cases.
