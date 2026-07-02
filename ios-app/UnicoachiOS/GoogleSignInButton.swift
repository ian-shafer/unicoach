import GoogleSignIn
import SwiftUI

/// SwiftUI wrapper around Google's `GIDSignInButton`. Using Google's own button
/// keeps brand-guideline compliance and needs no bundled logo asset. The tap
/// closure runs the view model's `signInWithGoogle()`; the button itself carries
/// no sign-in logic.
struct GoogleSignInButton: UIViewRepresentable {
    let action: () -> Void

    func makeUIView(context: Context) -> GIDSignInButton {
        let button = GIDSignInButton()
        button.style = .wide
        button.addTarget(
            context.coordinator,
            action: #selector(Coordinator.tapped),
            for: .touchUpInside
        )
        return button
    }

    func updateUIView(_ uiView: GIDSignInButton, context: Context) {
        context.coordinator.action = action
    }

    // `GIDSignInButton` sizes itself through Auto Layout constraints rather than
    // an intrinsic content size, so SwiftUI's default representable sizing
    // reserves ~zero height and any layout below it (e.g. the Register button on
    // the login screen) overlaps it. Report the button's own fitted size instead:
    // it fixes the height (48pt) and takes the proposed width so the button still
    // stretches full-width when its container proposes one.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: GIDSignInButton, context: Context) -> CGSize? {
        let fitted = uiView.sizeThatFits(UIView.layoutFittingExpandedSize)
        let proposedWidth = proposal.width.flatMap { $0.isFinite ? $0 : nil }
        return CGSize(width: proposedWidth ?? fitted.width, height: fitted.height)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(action: action)
    }

    final class Coordinator: NSObject {
        var action: () -> Void

        init(action: @escaping () -> Void) {
            self.action = action
        }

        @objc func tapped() {
            action()
        }
    }
}
