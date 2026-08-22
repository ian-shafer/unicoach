import UIKit
import XCTest
@testable import UnicoachiOS

/// `ASAuthorizationAppleIDCredential` has no public initializer, so the
/// provider's credential path cannot be driven from a test. What is reachable
/// is the name store and the provider's use of it — through
/// `resolveName(formattedName:userId:)`, which takes a formatted name and
/// an Apple user id, against `MockAppleNameStore` — plus the two guards that
/// fire before any credential exists: the re-entry guard and the
/// presentation-anchor guard, the latter reached by injecting a resolver that
/// yields no window.
@MainActor
class AppleSignInProviderTests: XCTestCase {
    var nameStore: MockAppleNameStore!
    var provider: AppleSignInProvider!

    override func setUp() async throws {
        try await super.setUp()
        nameStore = MockAppleNameStore()
        // A bare, off-screen UIWindow stands in for the real foreground key
        // window: forcing the re-entry guard below needs only a non-nil
        // anchor, not a genuinely foregrounded scene, and the test host's
        // scene activation timing is not something a unit test controls.
        provider = AppleSignInProvider(nameStore: nameStore, windowResolver: { UIWindow() })
    }

    func testFirstAuthorizationPersistsName() {
        let result = provider.resolveName(formattedName: "Ada Lovelace", userId: "apple-user-1")

        XCTAssertEqual(result, "Ada Lovelace")
        XCTAssertEqual(nameStore.name(forUserId: "apple-user-1"), "Ada Lovelace")
    }

    func testSubsequentAuthorizationReadsStoredName() {
        nameStore.store(name: "Ada Lovelace", forUserId: "apple-user-1")

        let result = provider.resolveName(formattedName: nil, userId: "apple-user-1")

        XCTAssertEqual(result, "Ada Lovelace")
    }

    func testStoredNameIsNotSharedAcrossAppleUserIds() {
        nameStore.store(name: "Ada Lovelace", forUserId: "apple-user-1")

        let result = provider.resolveName(formattedName: nil, userId: "apple-user-2")

        XCTAssertNil(result, "A second Apple ID on the same device must not inherit the first one's name")
    }

    func testBlankNameIsNotStoredAndYieldsNil() {
        let result = provider.resolveName(formattedName: "   ", userId: "apple-user-1")

        XCTAssertNil(result)
        XCTAssertNil(nameStore.name(forUserId: "apple-user-1"), "A whitespace-only name must not be persisted")
    }

    func testNameSurvivesRepeatedReads() {
        _ = provider.resolveName(formattedName: "Ada Lovelace", userId: "apple-user-1")

        let firstRead = provider.resolveName(formattedName: nil, userId: "apple-user-1")
        let secondRead = provider.resolveName(formattedName: nil, userId: "apple-user-1")

        XCTAssertEqual(firstRead, "Ada Lovelace")
        XCTAssertEqual(secondRead, "Ada Lovelace", "Reading a stored name must not clear it")
    }

    func testSecondConcurrentSignInReturnsAlreadyPresenting() async throws {
        // The first call is left suspended awaiting its (never-arriving, in a
        // test host) delegate callback, holding the continuation open.
        let firstSignIn = Completion()
        let firstTask = Task { @MainActor in
            _ = try? await self.provider.signIn()
            firstSignIn.didComplete = true
        }
        // The guard below can leave early on failure, so the suspended first
        // call is cancelled on every exit path rather than only the last one.
        defer { firstTask.cancel() }
        await Task.yield()
        await Task.yield()

        let outcome = try await provider.signIn()
        guard case .alreadyPresenting = outcome else {
            XCTFail("Expected alreadyPresenting, got [\(outcome)]")
            return
        }

        // Resuming a continuation only schedules its task; without a suspension
        // point here the first call could never record its completion and the
        // assertion below would hold vacuously. These yields give a wrongly
        // resumed first call a chance to run before it is checked.
        await Task.yield()
        await Task.yield()

        XCTAssertFalse(
            firstSignIn.didComplete,
            "The refused second call must not resume or overwrite the first continuation"
        )
    }

    func testSignInWithoutPresentationAnchorThrowsPresentationUnavailable() async {
        let anchorless = AppleSignInProvider(nameStore: nameStore, windowResolver: { nil })

        do {
            _ = try await anchorless.signIn()
            XCTFail("Expected presentationUnavailable to be thrown")
        } catch AppleSignInError.presentationUnavailable {
            // expected: no foreground key window means no anchor to present from
        } catch {
            XCTFail("Expected presentationUnavailable, got [\(error)]")
        }
    }
}
