import AuthenticationServices
import SwiftUI

/// Wraps Apple's own `ASAuthorizationAppleIDButton` in a `UIViewRepresentable`.
/// Using Apple's own control makes HIG compliance structural, and it is the
/// same wrapping technique RFC 90 used for Google's button before it was
/// restyled. Apple's own `SignInWithAppleButton` (the SwiftUI-native control)
/// is not used: it owns the request and hands a `Result<ASAuthorization, Error>`
/// to a view closure, which would put the credential mapping in an untestable
/// view outside the `SsoSignInProviding` seam.
///
/// Purely presentational: the tap closure runs the view model's
/// `signInWithApple()`; the button carries no sign-in logic.
struct AppleSignInButton: View {
    let action: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        // ASAuthorizationAppleIDButton's style is fixed at init, so a
        // colour-scheme change is handled by re-creating the representable
        // rather than mutating an existing instance. The identity lives here,
        // inside the button, so no call site has to know the rule.
        AppleSignInButtonRepresentable(colorScheme: colorScheme, action: action)
            .id(colorScheme)
    }
}

private struct AppleSignInButtonRepresentable: UIViewRepresentable {
    let colorScheme: ColorScheme
    let action: () -> Void

    @Environment(\.isEnabled) private var isEnabled

    // ASAuthorizationAppleIDButton has no font-driven intrinsic size the way an
    // SF Symbol/text button does, so its box is rebuilt here from the same
    // token the design system's own controls use: DSControl.height, scaled
    // relative to .headline exactly as PrimaryButtonStyle and
    // GoogleSignInButtonStyle scale theirs. All three therefore share one
    // control rhythm by construction rather than by coincidence.
    @ScaledMetric(relativeTo: .headline) private var height: CGFloat = DSControl.height

    func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
        let style: ASAuthorizationAppleIDButton.Style = colorScheme == .dark ? .white : .black
        let button = ASAuthorizationAppleIDButton(authorizationButtonType: .signIn, authorizationButtonStyle: style)
        button.cornerRadius = DSRadius.control
        button.accessibilityIdentifier = "appleSignInButton"
        button.addTarget(context.coordinator, action: #selector(Coordinator.handleTap), for: .touchUpInside)
        return button
    }

    func updateUIView(_ uiView: ASAuthorizationAppleIDButton, context: Context) {
        // SwiftUI rebuilds this struct — and its `action` closure — on every
        // render while reusing the coordinator, so the target's closure is
        // rebound here; without this the button keeps calling the very first
        // closure it was built with, however stale what that closure captured.
        context.coordinator.action = action

        // SwiftUI's \.isEnabled is not bridged into a wrapped UIView, so the
        // representable applies it here explicitly; without this the button
        // stays live during `.ssoLoading` and the provider's re-entry guard
        // (`.alreadyPresenting`) becomes the only double-tap guard.
        uiView.isUserInteractionEnabled = isEnabled
        uiView.alpha = isEnabled ? DSOpacity.enabled : DSOpacity.disabled
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ASAuthorizationAppleIDButton, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? uiView.intrinsicContentSize.width, height: height)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(action: action)
    }

    /// `@MainActor`-isolated: UIKit target-action delivers `handleTap()` on the
    /// main thread and `action` reaches `@MainActor` view-model state, so the
    /// requirement is declared here rather than assumed by each caller — the
    /// same isolation `AppleSignInProvider`'s delegate bridge makes explicit.
    @MainActor
    final class Coordinator {
        /// Rebound from `updateUIView` on every render, so a tap always runs
        /// the current closure rather than the one captured at `makeCoordinator`.
        var action: () -> Void

        init(action: @escaping () -> Void) {
            self.action = action
        }

        @objc func handleTap() {
            action()
        }
    }
}

#Preview("Apple Sign-In - Light") {
    AppleSignInButton {}
        .frame(maxWidth: .infinity)
        .padding(DSSpacing.lg)
        .frame(maxHeight: .infinity)
        .background(Color.dsBackground)
        .preferredColorScheme(.light)
}

#Preview("Apple Sign-In - Dark") {
    AppleSignInButton {}
        .frame(maxWidth: .infinity)
        .padding(DSSpacing.lg)
        .frame(maxHeight: .infinity)
        .background(Color.dsBackground)
        .preferredColorScheme(.dark)
}
