import UIKit
import XCTest
@testable import UnicoachiOS

/// `GIDSignIn.sharedInstance` presents real Google UI, so the provider's
/// credential path cannot be driven from a test — nor can its re-entry guard,
/// which only reports `.alreadyPresenting` while a first call is suspended
/// inside that SDK call (unlike `AppleSignInProvider`, whose continuation can
/// be held open with no SDK involved). What is reachable is the presentation
/// guard that fires before the SDK is ever touched, reached by injecting a
/// resolver that yields no presenting view controller.
@MainActor
class GoogleSignInProviderTests: XCTestCase {
    func testSignInWithoutKeyWindowThrowsPresentationUnavailable() async {
        await assertPresentationUnavailable(GoogleSignInProvider(windowResolver: { nil }))
    }

    func testSignInWithoutRootViewControllerThrowsPresentationUnavailable() async {
        // A bare, off-screen UIWindow has no rootViewController: the second way
        // the guard's chained lookup yields nothing, and the one Apple's
        // anchor-only twin has no equivalent of.
        await assertPresentationUnavailable(GoogleSignInProvider(windowResolver: { UIWindow() }))
    }

    private func assertPresentationUnavailable(_ provider: GoogleSignInProvider) async {
        do {
            _ = try await provider.signIn()
            XCTFail("Expected presentationUnavailable to be thrown")
        } catch GoogleSignInError.presentationUnavailable {
            // expected: no presenting view controller means nothing to present from
        } catch {
            XCTFail("Expected presentationUnavailable, got [\(error)]")
        }
    }
}
