import XCTest
@testable import UnicoachiOS

/// The working-student selection (RFC 241): what is STORED, and what is
/// RESOLVED from it. Two halves, two sets of tests — the storage is keyed by
/// user id, and the rule that reads it falls back when the stored student is no
/// longer on the roster.
final class SupporterSubjectStoreTests: XCTestCase {
    /// The REAL `UserDefaults` store, because "keyed by the signed-in user's
    /// id" is a claim about the key and a double could not break it — but in
    /// an ISOLATED suite, which the store now takes as a constructor
    /// parameter. The whole domain is removed afterwards, so no test leaves an
    /// entry in the device-wide suite (`AppearancePreferenceTests` precedent).
    private static let suiteName = "SupporterSubjectStoreTests"
    private var defaults: UserDefaults!
    private var store: UserDefaultsSupporterSubjectStore!

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: Self.suiteName)
        store = UserDefaultsSupporterSubjectStore(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: Self.suiteName)
        defaults = nil
        store = nil
        super.tearDown()
    }

    private func user() -> UUID {
        UUID()
    }

    private func student(_ name: String) -> LinkedStudent {
        LinkedStudent(linkId: UUID(), studentId: UUID(), name: name, relationship: .parent, otherSupporterNames: [])
    }

    // MARK: - Storage

    func testSelectionRoundTrips() {
        let userId = user()
        let studentId = UUID()

        store.select(studentId: studentId, forUserId: userId)

        XCTAssertEqual(store.selectedStudentId(forUserId: userId), studentId)
    }

    func testNoSelectionReadsAsNil() {
        XCTAssertNil(store.selectedStudentId(forUserId: user()))
    }

    /// The whole reason the key carries the user id: a second account signed in
    /// on the same device must not inherit the first one's student.
    func testSelectionIsKeyedByUserId() {
        let firstUser = user()
        let secondUser = user()
        let studentId = UUID()

        store.select(studentId: studentId, forUserId: firstUser)

        XCTAssertEqual(store.selectedStudentId(forUserId: firstUser), studentId)
        XCTAssertNil(store.selectedStudentId(forUserId: secondUser))
    }

    func testLaterSelectionReplacesTheEarlierOne() {
        let userId = user()
        let first = UUID()
        let second = UUID()

        store.select(studentId: first, forUserId: userId)
        store.select(studentId: second, forUserId: userId)

        XCTAssertEqual(store.selectedStudentId(forUserId: userId), second)
    }

    /// A stored value that is not a UUID reads as NO selection, so the
    /// resolution rule below falls back rather than the app trapping on it.
    func testUnparseableStoredValueReadsAsNoSelection() {
        let userId = user()
        defaults.set("not-a-uuid", forKey: UserDefaultsSupporterSubjectStore.key(userId))

        XCTAssertNil(store.selectedStudentId(forUserId: userId))
    }

    /// Account deletion forgets the selection (RFC 252) — this user's only.
    func testClearForgetsOnlyThisUsersSelection() {
        let closing = user()
        let other = user()
        let studentId = UUID()
        store.select(studentId: studentId, forUserId: closing)
        store.select(studentId: studentId, forUserId: other)

        store.clear(forUserId: closing)

        XCTAssertNil(defaults.string(forKey: UserDefaultsSupporterSubjectStore.key(closing)))
        XCTAssertEqual(store.selectedStudentId(forUserId: other), studentId)
    }

    // MARK: - Resolution

    func testResolveAnswersNilForAnEmptyRoster() {
        XCTAssertNil(SupporterSubject.resolve(roster: [], stored: UUID()))
    }

    func testResolveFallsBackToTheFirstStudentWhenNothingIsStored() {
        let roster = [student("Ava"), student("Noah")]

        XCTAssertEqual(SupporterSubject.resolve(roster: roster, stored: nil), roster[0])
    }

    func testResolveHonoursAStoredSelectionStillOnTheRoster() {
        let roster = [student("Ava"), student("Noah")]

        XCTAssertEqual(
            SupporterSubject.resolve(roster: roster, stored: roster[1].studentId),
            roster[1]
        )
    }

    /// The stale-link case the RFC names: the selection points at a student
    /// this supporter is no longer linked to.
    func testResolveFallsBackWhenTheStoredStudentIsNoLongerLinked() {
        let roster = [student("Ava"), student("Noah")]
        let unlinked = student("Removed")

        XCTAssertEqual(
            SupporterSubject.resolve(roster: roster, stored: unlinked.studentId),
            roster[0]
        )
    }

    /// End to end across both halves: a selection that was written, then
    /// unlinked, resolves to the first student on the roster it is read against.
    func testStoredSelectionForAnUnlinkedStudentResolvesToTheFirst() {
        let userId = user()
        let roster = [student("Ava"), student("Noah")]
        let unlinked = student("Removed")

        store.select(studentId: unlinked.studentId, forUserId: userId)

        XCTAssertEqual(
            SupporterSubject.resolve(roster: roster, stored: store.selectedStudentId(forUserId: userId)),
            roster[0]
        )
    }
}
