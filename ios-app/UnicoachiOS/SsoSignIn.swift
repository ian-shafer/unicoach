import UIKit

/// Which SSO identity provider produced a credential. Carries the per-provider
/// surface strings so a new provider is one case here rather than a new branch
/// in every switch that words a failure.
///
/// The raw value is this provider's diagnostic label — the token the log lines
/// that name a provider emit — declared here rather than reflected, so it
/// cannot change under a later edit to the enum's shape.
enum SsoProvider: String {
    case google
    case apple

    /// Client-synthesized code for a provider-side (SDK/controller) failure:
    /// UPPERCASE, disjoint from backend wire codes, only displayed.
    var signInFailureCode: String {
        switch self {
        case .google: return "GOOGLE_SIGN_IN_FAILED"
        case .apple: return "APPLE_SIGN_IN_FAILED"
        }
    }

    /// The inline-banner copy shown when this provider's own sign-in fails.
    var signInFailureMessage: String {
        switch self {
        case .google: return String(localized: "Google sign-in failed. Please try again.")
        case .apple: return String(localized: "Apple sign-in failed. Please try again.")
        }
    }

    /// The accessibility identifier of this provider's in-flight spinner.
    /// Derived here so a slot cannot be handed the other provider's id.
    var loadingIndicatorAccessibilityIdentifier: String {
        switch self {
        case .google: return "googleLoadingIndicator"
        case .apple: return "appleLoadingIndicator"
        }
    }
}

/// What the app POSTs for an authorization: the credential material stamped
/// with the provider that produced it, by `SsoProvider.credential(from:)`.
/// Provider-tagged so that "Google never carries a name" is unrepresentable
/// rather than a convention.
enum SsoCredential {
    case google(idToken: String)
    case apple(idToken: String, name: String?)
}

/// The raw material a successful authorization produced. Deliberately
/// untagged: the tag is the conformer's `provider`, and
/// `SsoProvider.credential(from:)` is the one place the two are combined, so a
/// conformer cannot hand back another provider's credential.
struct SsoAuthorization {
    let idToken: String
    /// Only Apple discloses a name; Google's conformer always passes `nil`.
    let name: String?
}

enum SsoSignInOutcome {
    case signedIn(SsoAuthorization)
    case cancelled // user dismissed the sheet — an ordinary outcome
    /// A sign-in for this provider is already presenting. The second request
    /// is an ordinary no-op — the first one still owns the screen and will
    /// deliver the only outcome — not a failure to report.
    case alreadyPresenting
}

extension SsoProvider {
    /// This provider's credential for an authorization it produced. The
    /// `.google` case drops `name`, keeping "Google never carries a name" a
    /// property of the type rather than of the conformer.
    func credential(from authorization: SsoAuthorization) -> SsoCredential {
        switch self {
        case .google:
            return .google(idToken: authorization.idToken)
        case .apple:
            return .apple(idToken: authorization.idToken, name: authorization.name)
        }
    }
}

/// Isolates a presentation-coupled SSO SDK/controller call behind a protocol
/// so `LoginViewModel`'s outcome logic stays unit-testable against a mock.
/// Replaces RFC 90's Google-only `GoogleSignInProviding`; `GoogleSignInProvider`
/// and `AppleSignInProvider` are its two conformers.
@MainActor
protocol SsoSignInProviding {
    /// The provider this conformer speaks to. Fixed per conformer, so the
    /// caller can set the loading phase before a credential exists and word a
    /// failure banner when `signIn()` throws; it is also the tag the caller
    /// stamps onto this conformer's untagged `SsoAuthorization`, so it is the
    /// sole source of the credential's provider rather than a claim the
    /// conformer's return value could contradict.
    var provider: SsoProvider { get }

    /// Presents the provider's UI; returns the outcome. Throws only for genuine
    /// failures — user cancellation and a request refused because one is
    /// already presenting are returned outcomes, not failures.
    func signIn() async throws -> SsoSignInOutcome
}

/// The active foreground scene's key window: Apple's `ASPresentationAnchor` and
/// the source of Google's presenting root view controller. One lookup, two
/// callers.
@MainActor
func foregroundKeyWindow() -> UIWindow? {
    UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .first(where: { $0.activationState == .foregroundActive })?
        .keyWindow
}

extension SsoCredential {
    /// The provider that produced this credential.
    var provider: SsoProvider {
        switch self {
        case .google: .google
        case .apple: .apple
        }
    }

    /// The backend route that accepts this credential.
    var path: String {
        switch self {
        case .google: "/api/v1/auth/google"
        case .apple: "/api/v1/auth/apple"
        }
    }

    /// The request body that route expects, already provider-shaped — Apple's
    /// carries the once-disclosed name, Google's has no name field at all.
    var requestBody: any Encodable {
        switch self {
        case .google(let idToken): GoogleLoginRequest(idToken: idToken)
        case .apple(let idToken, let name): AppleLoginRequest(idToken: idToken, name: name)
        }
    }
}
