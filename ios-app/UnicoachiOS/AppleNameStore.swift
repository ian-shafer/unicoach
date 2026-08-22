import Foundation

/// Persists the name Apple discloses on first authorization only. Apple never
/// repeats a user's name on subsequent authorizations for the same Apple ID, so
/// `AppleSignInProvider` persists it the first time and resends it on every
/// following sign-in (the backend ignores the field once the account is
/// provisioned).
protocol AppleNameStore {
    func name(forUserId id: String) -> String?
    func store(name: String, forUserId id: String)
}

/// Production `AppleNameStore`. Keyed by Apple's own per-app-per-user `sub`
/// (`credential.user`), so a second Apple ID authorized on the same device
/// never inherits the first one's stored name.
final class UserDefaultsAppleNameStore: AppleNameStore {
    /// Namespaced so the key cannot collide with an unrelated `UserDefaults`
    /// entry; the Apple user id is appended verbatim.
    private static let keyPrefix = "AppleSignIn.name."

    /// The app has exactly one name suite. Substituting the storage in a test
    /// is done by conforming to `AppleNameStore`, not by swapping the suite.
    private let defaults = UserDefaults.standard

    func name(forUserId id: String) -> String? {
        defaults.string(forKey: Self.keyPrefix + id)
    }

    func store(name: String, forUserId id: String) {
        defaults.set(name, forKey: Self.keyPrefix + id)
    }
}
