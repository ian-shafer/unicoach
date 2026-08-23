import XCTest
@testable import UnicoachiOS

/// The `.storekit` configuration the scheme runs the simulator against is a
/// second in-repo copy of the product id. Xcode owns that file's format, so the
/// copy cannot be derived — this test is what makes it loud: edit either side
/// alone and it goes red.
///
/// Without it, divergence is silent by construction — `product(id:)` answers
/// `nil`, `load()` degrades, and the Subscribe button simply stops rendering.
///
/// The file is read from source via `#filePath` rather than a bundled resource:
/// the thing under test is the checked-in catalogue, not a copy of it.
final class StoreKitConfigurationTests: XCTestCase {
    func testTheStoreKitConfigurationSellsTheProductTheAppAsksFor() throws {
        let configuration = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // UnicoachiOSTests
            .deletingLastPathComponent()   // ios-app
            .appendingPathComponent("UnicoachiOS.storekit")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: configuration))

        let groups = (json as? [String: Any])?["subscriptionGroups"] as? [[String: Any]] ?? []
        let productIDs = groups
            .flatMap { $0["subscriptions"] as? [[String: Any]] ?? [] }
            .compactMap { $0["productID"] as? String }

        XCTAssertEqual(productIDs, [SubscriptionProduct.monthlyIdentifier])
    }
}
