import Foundation
@testable import UnicoachiOS

/// Protocol doubles for the two RFC 205 surfaces, in the
/// `MockMoneyProfileClient` shape: configure the result, read back the call
/// count and the captured request.
///
/// The counters matter for the same reason they do there — some of this
/// slice's rules are about a call that must NOT happen (a duplicate subject
/// sends nothing) and about a call that must happen even though nothing on
/// screen changed (the list probe that decides whether the row says the list
/// is unavailable).
final class MockSubjectsClient: SubjectsClientProtocol, @unchecked Sendable {
    /// Unconfigured, the search answers a small taxonomy. There is no shipped
    /// fallback list in the app, so a test that wants an unreachable list sets
    /// `searchResult` to a failure and asserts the row says so.
    var searchResult: Result<[PublicSubject], Error> = .success(PublicSubject.fixtures)

    private(set) var searchCallCount = 0
    private(set) var lastQuery: String??

    func search(query: String?) async throws -> [PublicSubject] {
        searchCallCount += 1
        lastQuery = query
        return try searchResult.get()
    }
}

final class MockIntendedSubjectsClient: IntendedSubjectsClientProtocol, @unchecked Sendable {
    /// Unconfigured, the read answers what the server answers before any
    /// write: `unanswered`, with an empty list — never a 404, because this
    /// route has none (the status IS the payload).
    var fetchResult: Result<PublicIntendedSubjects, Error> = .success(.unanswered)
    var updateResult: Result<PublicIntendedSubjects, Error>?

    private(set) var fetchCallCount = 0
    private(set) var updateCallCount = 0
    private(set) var lastUpdateRequest: UpdateIntendedSubjectsRequest?

    func fetch() async throws -> PublicIntendedSubjects {
        fetchCallCount += 1
        return try fetchResult.get()
    }

    func update(_ request: UpdateIntendedSubjectsRequest) async throws -> PublicIntendedSubjects {
        updateCallCount += 1
        lastUpdateRequest = request
        // Unconfigured, the double answers what the server would for the
        // shapes it can answer without a stored row: a set becomes `answered`
        // (or `undecided` when empty — D17 applied by the SERVER, which is
        // exactly why this double applies it too rather than echoing the
        // request), and the two named non-answers become themselves.
        switch updateResult ?? .success(Self.stored(request.answer)) {
        case .success(let answer):
            return answer
        case .failure(let error):
            throw error
        }
    }

    private static func stored(_ answer: UpdateIntendedSubjectsRequest.Answer) -> PublicIntendedSubjects {
        switch answer {
        case .subjects(let slugs) where slugs.isEmpty:
            return PublicIntendedSubjects(status: AnswerStatus.undecided.rawValue, subjects: [])
        case .subjects(let slugs):
            return PublicIntendedSubjects(
                status: AnswerStatus.answered.rawValue,
                subjects: slugs.map { slug in
                    PublicSubject.fixtures.first { $0.slug == slug } ?? PublicSubject(slug: slug, name: slug)
                }
            )
        case .declined:
            return PublicIntendedSubjects(status: AnswerStatus.declined.rawValue, subjects: [])
        case .undecided:
            return PublicIntendedSubjects(status: AnswerStatus.undecided.rawValue, subjects: [])
        }
    }
}

/// The RFC 248 permissions route, in the same shape. Unconfigured, the read
/// answers what the server answers before any write — every permission
/// `not_asked` — and a write answers the choice it was sent, as the server
/// stores it.
final class MockSharingPermissionsClient: SharingPermissionsClientProtocol, @unchecked Sendable {
    var fetchResult: Result<[PublicSharingPermission], Error> = .success(SharingPermission.allCases.map {
        PublicSharingPermission(permission: $0.rawValue, status: SharingPermissionStatus.notAsked.rawValue)
    })
    var updateResult: Result<PublicSharingPermission, Error>?
    /// Awaited inside `update`, so a test can hold a write open
    /// (`MockMoneyProfileClient.updateGate`).
    var updateGate: (@Sendable () async -> Void)?

    private(set) var fetchCallCount = 0
    private(set) var updateCallCount = 0
    private(set) var lastUpdate: (permission: SharingPermission, choice: SharingChoice)?

    func fetch() async throws -> [PublicSharingPermission] {
        fetchCallCount += 1
        return try fetchResult.get()
    }

    func update(_ permission: SharingPermission, to choice: SharingChoice) async throws -> PublicSharingPermission {
        updateCallCount += 1
        lastUpdate = (permission, choice)
        await updateGate?()
        return try (updateResult ?? .success(PublicSharingPermission(permission: permission.rawValue, status: choice.rawValue))).get()
    }
}

// MARK: - Fixtures

extension PublicSubject {
    /// Four of OUR subjects, spelled as the taxonomy file spells them —
    /// family-worded names over slug keys. **No count is asserted anywhere**
    /// (RFC 205 R1): the fixtures build their own taxonomy, and nothing in this
    /// target knows or cares how many subjects the real file holds.
    static let fixtures: [PublicSubject] = [
        PublicSubject(slug: "nursing", name: "Nursing"),
        PublicSubject(slug: "marine-biology", name: "Marine biology"),
        PublicSubject(slug: "neuroscience", name: "Neuroscience"),
        PublicSubject(slug: "computer-science", name: "Computer science"),
    ]
}

extension PublicSharingPermission {
    static func intendedSubjects(_ status: SharingPermissionStatus) -> PublicSharingPermission {
        PublicSharingPermission(permission: SharingPermission.intendedSubjects.rawValue, status: status.rawValue)
    }
}

extension PublicIntendedSubjects {
    static let unanswered = PublicIntendedSubjects(status: AnswerStatus.unanswered.rawValue, subjects: [])

    static func answered(_ slugs: [String]) -> PublicIntendedSubjects {
        PublicIntendedSubjects(
            status: AnswerStatus.answered.rawValue,
            subjects: slugs.map { slug in
                PublicSubject.fixtures.first { $0.slug == slug } ?? PublicSubject(slug: slug, name: slug)
            }
        )
    }

    static let declined = PublicIntendedSubjects(status: AnswerStatus.declined.rawValue, subjects: [])
    static let undecided = PublicIntendedSubjects(status: AnswerStatus.undecided.rawValue, subjects: [])
}
