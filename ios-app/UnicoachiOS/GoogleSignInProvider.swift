import Foundation
import GoogleSignIn
import UIKit

/// Isolates the presentation-coupled `GIDSignIn` SDK call behind a protocol so
/// `LoginViewModel`'s outcome logic stays unit-testable against a mock. The
/// production conformer drives real UI (an account chooser) and cannot run in a
/// unit test; the protocol returns only the resulting ID token string.
@MainActor
protocol GoogleSignInProviding {
    /// Presents Google's account chooser; returns the outcome. Throws only for
    /// genuine failures — user cancellation is a returned outcome, not one.
    func signIn() async throws -> GoogleSignInOutcome
}

enum GoogleSignInOutcome {
    case signedIn(String) // the resulting ID token string
    case cancelled        // user dismissed the sheet — an ordinary outcome
}

enum GoogleSignInError: Error {
    case presentationUnavailable // no key-window root view controller found
    case missingIdToken          // GIDSignInResult carried no ID token
    case sdkError(Error)         // any other GIDSignIn failure
}

/// Production `GoogleSignInProviding`. Resolves the presenting view controller
/// from the active foreground scene's key window, invokes the Google SDK, and
/// returns the outcome. Takes no client ID — the SDK self-configures from
/// `Info.plist`'s `GIDClientID`.
@MainActor
final class GoogleSignInProvider: GoogleSignInProviding {
    func signIn() async throws -> GoogleSignInOutcome {
        guard let presenter = Self.keyWindowRootViewController() else {
            throw GoogleSignInError.presentationUnavailable
        }

        let result: GIDSignInResult
        do {
            result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
        } catch let error as NSError where error.domain == kGIDSignInErrorDomain
            && error.code == GIDSignInError.canceled.rawValue {
            // Google's own cancellation is the SDK enum `GIDSignInError.canceled`,
            // which the SDK surfaces as an `NSError` carrying `kGIDSignInErrorDomain`
            // and that raw code — hence the NSError bridge here (it is not a Swift
            // `catch GIDSignInError.canceled`). Note `GIDSignInError` (the SDK's
            // type) is distinct from this file's own `GoogleSignInError`; user
            // cancellation is not a failure, so it maps to the returned
            // `.cancelled` outcome rather than a thrown error.
            return .cancelled
        } catch {
            throw GoogleSignInError.sdkError(error)
        }

        guard let idToken = result.user.idToken?.tokenString else {
            throw GoogleSignInError.missingIdToken
        }
        return .signedIn(idToken)
    }

    /// The root view controller of the active foreground scene's key window, or
    /// `nil` when no such window exists (e.g. mid-transition).
    private static func keyWindowRootViewController() -> UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first(where: { $0.activationState == .foregroundActive })?
            .keyWindow?
            .rootViewController
    }
}
