import XCTest
@testable import UnicoachiOS

/// The `.verified`-only rule, executed directly — the whole reason the decision
/// was lifted out of `StoreKitSubscriptionStore`'s StoreKit-typed method, where
/// no test could reach its refusing arm. Invert the two arms of
/// `StoreKitVerdict.escaping` and these two tests must go red.
final class StoreKitVerdictTests: XCTestCase {
    func testVerifiedVerdictEscapesCarryingIdAndJwsUnmodified() {
        let jws = "eyJhbGciOiJFUzI1NiJ9.signed-payload.signature"
        let verdict = StoreKitVerdict.verified(id: 42, productID: SubscriptionProduct.monthlyIdentifier, jws: jws)

        let transaction = verdict.escaping

        XCTAssertEqual(transaction, StoreTransaction(id: 42, productID: SubscriptionProduct.monthlyIdentifier, jws: jws))
        XCTAssertEqual(transaction?.jws, jws, "the JWS is passed through untouched")
    }

    /// The refused case carries no JWS at all — an unverified blob has no
    /// representation that could reach `/verify`.
    func testUnverifiedVerdictDoesNotEscape() {
        let verdict = StoreKitVerdict.unverified(id: 42)

        XCTAssertNil(verdict.escaping, "an unverified transaction must never reach /verify")
    }
}
