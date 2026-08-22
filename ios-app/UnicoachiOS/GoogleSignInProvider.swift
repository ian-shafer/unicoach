import Foundation
import GoogleSignIn
import UIKit

enum GoogleSignInError: Error {
    case presentationUnavailable // no key-window root view controller found
    case missingIdToken          // GIDSignInResult carried no ID token
    case sdkError(Error)         // any other GIDSignIn failure
}

/// Production `SsoSignInProviding` conformer for Google. Resolves the
/// presenting view controller from the shared `foregroundKeyWindow()` helper,
/// invokes the Google SDK, and returns the outcome. Takes no client ID — the
/// SDK self-configures from `Info.plist`'s `GIDClientID`.
@MainActor
final class GoogleSignInProvider: SsoSignInProviding {
    let provider: SsoProvider = .google

    /// True while a `signIn()` call is awaiting the SDK. A second request made
    /// in that window is refused as the ordinary `.alreadyPresenting` outcome
    /// rather than reaching `GIDSignIn` twice — the guard
    /// `AppleSignInProvider` owns, held here for the same reason: the provider
    /// cannot depend on its caller having disabled the button.
    private var isPresenting = false

    // Defaults to the real shared helper; overridable only so the presentation
    // guard below is reachable from a unit test without the test host's scene
    // having to reach `.foregroundActive` — mirroring `AppleSignInProvider`'s
    // `windowResolver`.
    private let windowResolver: @MainActor () -> UIWindow?

    init(windowResolver: @escaping @MainActor () -> UIWindow? = foregroundKeyWindow) {
        self.windowResolver = windowResolver
    }

    func signIn() async throws -> SsoSignInOutcome {
        guard !isPresenting else {
            return .alreadyPresenting
        }
        guard let presenter = windowResolver()?.rootViewController else {
            throw GoogleSignInError.presentationUnavailable
        }
        isPresenting = true
        defer { isPresenting = false }

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
        // Google discloses no name; the `.google` credential carries none.
        return .signedIn(SsoAuthorization(idToken: idToken, name: nil))
    }
}
