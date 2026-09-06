import Foundation
@testable import UnicoachiOS

/// Protocol double for view-model tests (TESTING.md: a view model is never
/// driven through the real client). Counts calls as well as capturing the last
/// request, because RFC 163's rule is partly about a call that must NOT happen —
/// no optional answer means no PUT at all, and only a counter can assert that.
class MockMoneyProfileClient: MoneyProfileClientProtocol, @unchecked Sendable {
    /// Unconfigured, the read answers `nil` — the server's `404`, i.e. a
    /// student who has never written a profile. That is the state a fresh
    /// account is really in, so a test has to opt IN to having answers rather
    /// than opt out of them.
    var fetchResult: Result<PublicMoneyProfile?, Error> = .success(nil)
    var updateResult: Result<PublicMoneyProfile, Error>?
    /// Optional hook a test awaits inside `update`, so a write can be held
    /// open and the view model observed while it is genuinely in flight. Nil
    /// for every test that does not care, which is nearly all of them.
    var updateGate: (@Sendable () async -> Void)?
    private(set) var fetchCallCount = 0
    private(set) var updateCallCount = 0
    private(set) var lastUpdateRequest: UpdateMoneyProfileRequest?

    func fetch() async throws -> PublicMoneyProfile? {
        fetchCallCount += 1
        return try fetchResult.get()
    }

    func update(_ request: UpdateMoneyProfileRequest) async throws -> PublicMoneyProfile {
        updateCallCount += 1
        lastUpdateRequest = request
        await updateGate?()
        // Unconfigured, the double answers what the server would: every field
        // the request set, answered. `PublicMoneyProfile.answering` owns that
        // projection, so this fake cannot disagree with the real rule.
        let answered = PublicMoneyProfile.answering(
            request,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0)
        )
        switch updateResult ?? .success(answered) {
        case .success(let profile):
            return profile
        case .failure(let error):
            throw error
        }
    }
}
