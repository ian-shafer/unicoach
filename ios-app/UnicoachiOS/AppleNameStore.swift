import Foundation

/// Persists the name Apple discloses on first authorization only. Apple never
/// repeats a user's name on subsequent authorizations for the same Apple ID, so
/// `AppleSignInProvider` persists it the first time and resends it on every
/// following sign-in (the backend ignores the field once the account is
/// provisioned).
protocol AppleNameStore {
    func name(forUserId id: String) -> String?
    func store(name: String, forUserId id: String)
    /// Forgets every stored name — on account deletion (RFC 252). All of them,
    /// not one: the stored key is Apple's `sub`, and nothing on the device
    /// records which `sub` the deleted account signed in with.
    func removeAll()
}

/// Production `AppleNameStore`. Keyed by Apple's own per-app-per-user `sub`
/// (`credential.user`), so a second Apple ID authorized on the same device
/// never inherits the first one's stored name.
final class UserDefaultsAppleNameStore: AppleNameStore {
    /// Namespaced so the key cannot collide with an unrelated `UserDefaults`
    /// entry; the Apple user id is appended verbatim.
    private static let keyPrefix = "AppleSignIn.name."

    /// The suite this store reads and writes, on the
    /// `UserDefaultsSupporterSubjectStore` precedent: the app names nothing and
    /// gets the one suite it has, while this class's own tests — the only tests
    /// of the real key prefix and the `removeAll` sweep — run against an
    /// isolated suite. Every other caller still substitutes by conforming to
    /// `AppleNameStore`.
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func name(forUserId id: String) -> String? {
        defaults.string(forKey: Self.keyPrefix + id)
    }

    func store(name: String, forUserId id: String) {
        defaults.set(name, forKey: Self.keyPrefix + id)
    }

    func removeAll() {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(Self.keyPrefix) {
            defaults.removeObject(forKey: key)
        }
    }
}
