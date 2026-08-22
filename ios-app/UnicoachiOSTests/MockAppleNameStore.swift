import Foundation
@testable import UnicoachiOS

final class MockAppleNameStore: AppleNameStore {
    private var namesByUserId: [String: String] = [:]

    func name(forUserId id: String) -> String? {
        namesByUserId[id]
    }

    func store(name: String, forUserId id: String) {
        namesByUserId[id] = name
    }
}

/// A `@MainActor` box for observing whether a suspended call ever resumed.
/// Lives here, beside the Apple test doubles, because `AppleSignInProviderTests`
/// is its only user: proving a refused re-entrant `signIn()` left the first
/// call's continuation untouched needs somewhere outside that call to record
/// that it returned.
@MainActor
final class Completion {
    var didComplete = false
}
