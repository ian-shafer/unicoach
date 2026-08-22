import SwiftUI

/// Design-system-styled "Sign in with Google" button.
///
/// Replaces Google's native `GIDSignInButton`, which carried its own fixed
/// chrome — hairline border, system text, a corner radius and height that
/// matched nothing else — and read as foreign beside the `PrimaryButtonStyle`
/// "Log In" button. This adopts the design system's control box exactly
/// (`ControlFill` at 64pt / `DSRadius.control`, `dsButton` label, full-width),
/// so it stacks as an equal with the Apple button beside it — which is what the
/// style reference shows. Google-branding compliance is preserved: the official
/// multicolor "G" mark (`GoogleLogo` asset) on a dark button and the unaltered
/// "Sign in with Google" label are both sanctioned Google button treatments.
///
/// Purely presentational: the tap closure runs the view model's
/// `signInWithGoogle()`; the button carries no sign-in logic.
struct GoogleSignInButton: View {
    let action: () -> Void

    // The "G" is an image, not a glyph, so it has no font-driven intrinsic size
    // the way an SF Symbol does. @ScaledMetric relative to the label's `.headline`
    // text style is the Dynamic-Type-correct substitute for a raw point size: the
    // mark keeps its proportion to the "Sign in with Google" text at every size.
    @ScaledMetric(relativeTo: .headline) private var logoSize: CGFloat = 20

    var body: some View {
        Button(action: action) {
            HStack(spacing: DSSpacing.sm) {
                Image("GoogleLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: logoSize, height: logoSize)
                    // Decorative mark: let VoiceOver read the button as just its
                    // "Sign in with Google" label, not the asset.
                    .accessibilityHidden(true)
                Text("Sign in with Google")
            }
        }
        .buttonStyle(GoogleSignInButtonStyle())
    }
}

/// Neutral secondary button style: `dsSurface` fill with a `dsFieldBorder`
/// stroke, tying the button to the form fields while matching the primary
/// button's size, padding, corner radius, and pressed/disabled feedback. Kept
/// private to its only consumer rather than promoted to the design system, since
/// no other screen needs a neutral button yet.
private struct GoogleSignInButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    @ScaledMetric(relativeTo: .headline) private var height: CGFloat = DSControl.height

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.dsButton)
            .foregroundStyle(Color.dsControlOnFill)
            // Match PrimaryButtonStyle's box exactly so the two stack as equals:
            // intrinsic horizontal inset, stretched full-width, the 64pt control
            // height, and the shared 16pt control radius.
            .padding(.horizontal, DSSpacing.lg)
            .frame(maxWidth: .infinity, minHeight: height)
            .background(Color.dsControlFill)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
            .opacity(opacity(isPressed: configuration.isPressed))
    }

    private func opacity(isPressed: Bool) -> Double {
        if !isEnabled { return DSOpacity.disabled }
        return isPressed ? DSOpacity.pressed : DSOpacity.enabled
    }
}

#Preview("Google Sign-In - Light") {
    GoogleSignInButton {}
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        .preferredColorScheme(.light)
}

#Preview("Google Sign-In - Dark") {
    GoogleSignInButton {}
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        .preferredColorScheme(.dark)
}
