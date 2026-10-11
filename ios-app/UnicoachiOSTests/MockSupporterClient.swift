import Foundation
@testable import UnicoachiOS

/// The supporter doors' double (RFC 241). Call counts as well as results,
/// because the state machine's ORDER is part of the contract: a student must
/// reach the app without the supporter read being made at all.
class MockSupporterClient: SupporterClientProtocol, @unchecked Sendable {
    var fetchHomeResult: Result<SupporterHome?, Error>? = .success(nil)
    private(set) var fetchHomeCallCount = 0

    /// Held open while the read is "in flight", so a test can run a transition
    /// — a logout — WHILE `fetchHome()` has not answered yet, rather than
    /// guessing at a sleep. The `MockMoneyProfileClient.updateGate` precedent.
    var fetchHomeGate: (@Sendable () async -> Void)?

    var becomeSupporterResult: Result<SupporterHome, Error>?
    private(set) var becomeSupporterCallCount = 0

    var disconnectResult: Result<DisconnectOutcome, Error>?
    private(set) var disconnectedLinkIds: [UUID] = []

    func fetchHome() async throws -> SupporterHome? {
        fetchHomeCallCount += 1
        await fetchHomeGate?()
        if let result = fetchHomeResult {
            switch result {
            case .success(let home):
                return home
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }

    func becomeSupporter() async throws -> SupporterHome {
        becomeSupporterCallCount += 1
        if let result = becomeSupporterResult {
            switch result {
            case .success(let home):
                return home
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }

    func disconnect(linkId: UUID) async throws -> DisconnectOutcome {
        disconnectedLinkIds.append(linkId)
        guard let disconnectResult else { fatalError("No result configured") }
        return try disconnectResult.get()
    }
}

/// The student's family client double (RFC 249). Until this slice no test
/// drove `SupporterInviteClientProtocol` — snapshot scenes used the app
/// target's `PreviewSupporterInviteClient` — so this is its first mock.
class MockSupporterInviteClient: SupporterInviteClientProtocol, @unchecked Sendable {
    var fetchSupportersResult: Result<StudentSupporters, Error>?
    private(set) var fetchSupportersCallCount = 0

    var disconnectSupporterResult: Result<DisconnectOutcome, Error>?
    private(set) var disconnectedLinkIds: [UUID] = []

    func createInvite(relationship: SupporterRelationship?) async throws -> SupporterInviteResponse {
        fatalError("Not used by these tests")
    }

    func fetchSupporters() async throws -> StudentSupporters {
        fetchSupportersCallCount += 1
        guard let fetchSupportersResult else { fatalError("No result configured") }
        return try fetchSupportersResult.get()
    }

    func disconnectSupporter(linkId: UUID) async throws -> DisconnectOutcome {
        disconnectedLinkIds.append(linkId)
        guard let disconnectSupporterResult else { fatalError("No result configured") }
        return try disconnectSupporterResult.get()
    }
}

/// An in-memory `SupporterSubjectStore`. Tests substitute the STORAGE by
/// conforming, never by swapping the `UserDefaults` suite — the rule
/// `UserDefaultsAppleNameStore` states and `MockAppleNameStore` follows.
final class MockSupporterSubjectStore: SupporterSubjectStore, @unchecked Sendable {
    private var selections: [UUID: UUID] = [:]

    init(selections: [UUID: UUID] = [:]) {
        self.selections = selections
    }

    func selectedStudentId(forUserId userId: UUID) -> UUID? { selections[userId] }

    func select(studentId: UUID, forUserId userId: UUID) {
        selections[userId] = studentId
    }
}
