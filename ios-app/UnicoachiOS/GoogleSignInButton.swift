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
