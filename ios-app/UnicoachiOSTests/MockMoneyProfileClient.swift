import Foundation
@testable import UnicoachiOS

/// Protocol double for view-model tests (TESTING.md: a view model is never
/// driven through the real client). Counts calls as well as capturing the last
/// request, because RFC 163's rule is partly about a call that must NOT happen —
/// no optional answer means no PUT at all, and only a counter can assert that.
class MockMoneyProfileClient: MoneyProfileClientProtocol, @unchecked Sendable {
    var updateResult: Result<PublicMoneyProfile, Error>?
    private(set) var updateCallCount = 0
    private(set) var lastUpdateRequest: UpdateMoneyProfileRequest?

    func update(_ request: UpdateMoneyProfileRequest) async throws -> PublicMoneyProfile {
        updateCallCount += 1
        lastUpdateRequest = request
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
