import SwiftUI

/// Design-system-styled "Sign in with Google" button.
///
/// Replaces Google's native `GIDSignInButton`, which carried its own fixed
/// chrome — hairline border, system text, a corner radius and height that
/// matched nothing else — and read as foreign beside the `PrimaryButtonStyle`
/// "Log In" button. This mirrors the design-system button metrics (same vertical
/// rhythm, `DSRadius.button` corners, `dsButton` font, full-width) as a neutral
/// secondary variant: `dsSurface` fill + `dsFieldBorder` stroke, matching the
/// form fields above it. Google-branding compliance is preserved — the official
/// multicolor "G" mark (`GoogleLogo` asset) and the "Sign in with Google" label,
/// both unaltered.
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

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.dsButton)
            .foregroundStyle(Color.dsTextPrimary)
            // Match PrimaryButtonStyle's box metrics so the two buttons stack as
            // equals: intrinsic horizontal inset, stretched full-width, tokenized
            // vertical padding, shared button corner radius.
            .padding(.horizontal, DSSpacing.lg)
            .frame(maxWidth: .infinity)
            .padding(.vertical, DSSpacing.md)
            .background(Color.dsSurface)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.button, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DSRadius.button, style: .continuous)
                    .stroke(Color.dsFieldBorder, lineWidth: 1)
            )
            .opacity(opacity(isPressed: configuration.isPressed))
    }

    private func opacity(isPressed: Bool) -> Double {
        if !isEnabled { return 0.5 }
        return isPressed ? 0.8 : 1.0
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
