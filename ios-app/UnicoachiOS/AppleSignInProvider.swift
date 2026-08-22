import AuthenticationServices
import os
import UIKit

/// Every case below carries the state a reader would otherwise have to
/// reproduce a real Apple authorization to obtain. The three credential cases
/// fire only when Apple hands back something unexpected, which is precisely
/// when the offending value *is* the diagnosis — as bare cases they logged as a
/// single word and the root cause was unrecoverable.
///
/// The payloads are logged at this app's uniform `.public` os.Logger privacy
/// because none of them is secret. `appleUserId` is Apple's per-app
/// pseudonymous `sub`: meaningless outside this app's Apple team, and already
/// persisted verbatim by `UserDefaultsAppleNameStore`. `base64Prefix` is a
/// bounded head of bytes that, by the definition of the case carrying it, are
/// not a valid identity token — a real one is all-ASCII base64url and would
/// have decoded.
enum AppleSignInError: Error {
    /// No foreground key window to anchor the authorization sheet to.
    case presentationUnavailable
    /// `ASAuthorization.credential` was some other credential class.
    case unexpectedCredentialType(received: String)
    /// The Apple ID credential carried no `identityToken`.
    case missingIdentityToken(appleUserId: String)
    /// The `identityToken` bytes were not UTF-8; `base64Prefix` is the head of
    /// the raw bytes, enough alongside `byteCount` to tell a truncated token
    /// from a corrupt one.
    case undecodableIdentityToken(appleUserId: String, byteCount: Int, base64Prefix: String)
    case authorizationFailed(Error)
}

/// Production `SsoSignInProviding` conformer for Apple. Drives
/// `ASAuthorizationController` and bridges its delegate callbacks to `async`
/// with a checked continuation, resumed exactly once.
///
/// The delegate methods below are `nonisolated` (as `ASAuthorizationControllerDelegate`
/// requires) and use `MainActor.assumeIsolated` to hop back onto this
/// `@MainActor` class's isolation rather than weakening it — sound because
/// Apple documents the callbacks as always arriving on the main thread.
@MainActor
final class AppleSignInProvider: NSObject, SsoSignInProviding {
    let provider: SsoProvider = .apple

    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "AppleSignInProvider")

    private let nameStore: AppleNameStore

    // Injected rather than shared statically: the formatter is locale- and
    // style-driven, so the exact name POSTed to the backend is only pinnable
    // if a caller can supply the instance that produces it.
    private let nameFormatter: PersonNameComponentsFormatter

    /// Exactly one in-flight authorization: the anchor it presents from and
    /// the continuation to resume. Set together before `performRequests()`
    /// and cleared together when the continuation resumes, so "awaiting a
    /// delegate callback with no anchor" cannot be represented.
    private struct PendingAuthorization {
        let anchor: ASPresentationAnchor
        let continuation: CheckedContinuation<SsoSignInOutcome, Error>
    }

    private var pending: PendingAuthorization?

    /// How many leading bytes of an undecodable identity token to carry into
    /// `AppleSignInError.undecodableIdentityToken`. Enough to distinguish a
    /// truncated token from a corrupt one, far short of usable token material.
    private static let undecodableTokenPreviewBytes = 16

    // Defaults to the real shared helper. Injected so the two guards that fire
    // before any credential exists are reachable from `AppleSignInProviderTests`
    // without the test host's scene reaching `.foregroundActive`: its `setUp`
    // injects `{ UIWindow() }`, which holds a `signIn()` open for the re-entry
    // guard (forcing that guard needs a non-nil window, not a genuine one), and
    // the anchor-guard test injects `{ nil }` to force `presentationUnavailable`.
    // Not test-only: `presentationAnchor(for:)`'s release fallback resolves
    // through it in production too.
    private let windowResolver: @MainActor () -> ASPresentationAnchor?

    init(
        nameStore: AppleNameStore = UserDefaultsAppleNameStore(),
        nameFormatter: PersonNameComponentsFormatter = PersonNameComponentsFormatter(),
        windowResolver: @escaping @MainActor () -> ASPresentationAnchor? = foregroundKeyWindow
    ) {
        self.nameStore = nameStore
        self.nameFormatter = nameFormatter
        self.windowResolver = windowResolver
    }

    func signIn() async throws -> SsoSignInOutcome {
        guard pending == nil else {
            return .alreadyPresenting
        }
        guard let anchor = windowResolver() else {
            throw AppleSignInError.presentationUnavailable
        }

        let controller = createAuthorizationController()
        return try await withCheckedThrowingContinuation { continuation in
            self.pending = PendingAuthorization(anchor: anchor, continuation: continuation)
            controller.performRequests()
        }
    }

    /// Apple's authorization controller, carrying a scoped request and wired to
    /// this provider's own delegate and presentation-anchor conformances.
    /// `.email` is mandatory: the backend's verifier rejects a token carrying
    /// no `email` claim, which the route reports as a generic `401
    /// unauthorized`.
    private func createAuthorizationController() -> ASAuthorizationController {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        return controller
    }

    /// The single blank-name rule for Apple's disclosed name: trimmed of
    /// surrounding whitespace, `nil` when nothing is left. Both the formatter
    /// output and `resolveName`'s own argument pass through it, so the rule has
    /// one owner.
    private static func nonBlankName(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    private func formattedName(from components: PersonNameComponents?) -> String? {
        guard let components else { return nil }
        return Self.nonBlankName(nameFormatter.string(from: components))
    }

    /// Removes the in-flight authorization, clearing the stored value so no
    /// later callback can resume it a second time. `nil` when there is nothing
    /// to resume.
    private func removePendingAuthorization() -> PendingAuthorization? {
        defer { pending = nil }
        return pending
    }

    private func handleCompletion(authorization: ASAuthorization) {
        // A callback with nothing stored carries a terminal authorization
        // outcome that can no longer be delivered — a continuation resumes
        // exactly once — so the only thing left to do with it is record it.
        guard let continuation = removePendingAuthorization()?.continuation else {
            logger.error(
                "Apple authorization completed with no pending continuation to resume [credential=\(String(describing: type(of: authorization.credential)), privacy: .public)]"
            )
            return
        }

        do {
            continuation.resume(returning: .signedIn(try credential(from: authorization)))
        } catch {
            continuation.resume(throwing: error)
        }
    }

    /// Maps Apple's authorization to the untagged `SsoAuthorization` the
    /// outcome carries — the credential material, with the provider tag left
    /// to `SsoProvider` — throwing the `AppleSignInError` naming whichever part
    /// the credential lacked. Apple's `email` is ignored: the backend reads the
    /// email from the token itself, and `AppleLoginRequest` carries no email
    /// field.
    private func credential(from authorization: ASAuthorization) throws -> SsoAuthorization {
        guard let appleCredential = authorization.credential as? ASAuthorizationAppleIDCredential else {
            throw AppleSignInError.unexpectedCredentialType(received: String(describing: type(of: authorization.credential)))
        }
        guard let tokenBytes = appleCredential.identityToken else {
            throw AppleSignInError.missingIdentityToken(appleUserId: appleCredential.user)
        }
        guard let idToken = String(data: tokenBytes, encoding: .utf8) else {
            throw AppleSignInError.undecodableIdentityToken(
                appleUserId: appleCredential.user,
                byteCount: tokenBytes.count,
                base64Prefix: tokenBytes.prefix(Self.undecodableTokenPreviewBytes).base64EncodedString()
            )
        }
        let name = resolveName(formattedName: formattedName(from: appleCredential.fullName), userId: appleCredential.user)
        return SsoAuthorization(idToken: idToken, name: name)
    }

    private func handleFailure(error: Error) {
        // The orphaned-callback case of `handleCompletion`, carrying Apple's
        // own error — the richest failure payload in this flow, so it is logged
        // whole rather than dropped.
        guard let continuation = removePendingAuthorization()?.continuation else {
            logger.error(
                "Apple authorization failed with no pending continuation to resume [error=\(error, privacy: .public)]"
            )
            return
        }

        if let authError = error as? ASAuthorizationError, authError.code == .canceled {
            continuation.resume(returning: .cancelled)
        } else {
            continuation.resume(throwing: AppleSignInError.authorizationFailed(error))
        }
    }
}

extension AppleSignInProvider {
    /// Persists `formattedName` when it survives the blank-name rule, then
    /// returns the name to send for `userId`: the just-stored one, or the
    /// previously stored one when Apple disclosed nothing. `nil` when neither
    /// exists. Stored and returned names are the trimmed form, so what is kept
    /// is exactly what is sent. The stored name is never cleared — resending it
    /// is free, while clearing it would need this provider to learn the
    /// backend's outcome through a `SsoSignInProviding` method Google has no
    /// use for.
    func resolveName(formattedName: String?, userId: String) -> String? {
        if let name = Self.nonBlankName(formattedName) {
            nameStore.store(name: name, forUserId: userId)
            return name
        }
        return nameStore.name(forUserId: userId)
    }
}

extension AppleSignInProvider: ASAuthorizationControllerDelegate {
    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        MainActor.assumeIsolated {
            handleCompletion(authorization: authorization)
        }
    }

    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        MainActor.assumeIsolated {
            handleFailure(error: error)
        }
    }
}

extension AppleSignInProvider: ASAuthorizationControllerPresentationContextProviding {
    nonisolated func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        // Identity taken here, outside the isolation hop: `ASAuthorizationController`
        // is not `Sendable`, so capturing it in the `@MainActor` closure is a
        // sending violation, while the `ObjectIdentifier` it yields is.
        let controllerId = ObjectIdentifier(controller)
        return MainActor.assumeIsolated {
            guard let anchor = pending?.anchor else {
                // `pending` carries its anchor from before `performRequests()`,
                // so a controller that is calling back always has one. No
                // pending authorization means this provider's single-in-flight
                // model is broken — recorded, then aborted in debug and test
                // runs where a developer can act on it.
                logger.error(
                    "Apple authorization asked for an anchor with no pending authorization [controller=\(String(describing: controllerId), privacy: .public)]"
                )
                assertionFailure("presentationAnchor requested with no pending authorization")
                // Release still has to return something: a freshly resolved
                // foreground key window presents somewhere real, whereas a bare
                // `ASPresentationAnchor()` presents into nothing, so no delegate
                // callback would arrive and the continuation would never resume.
                return windowResolver() ?? ASPresentationAnchor()
            }
            return anchor
        }
    }
}
