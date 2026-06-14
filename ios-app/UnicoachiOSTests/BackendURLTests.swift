import XCTest
@testable import UnicoachiOS

/// Drives the pure `resolveBackendURL(_:)` directly (no bundle, no IO),
/// asserting the resolved `URL.absoluteString` for every documented input class.
final class BackendURLTests: XCTestCase {
    private let fallback = "http://localhost:8080"

    func testNilFallsBackToLocalhost() {
        XCTAssertEqual(resolveBackendURL(nil).absoluteString, fallback)
    }

    func testEmptyStringFallsBackToLocalhost() {
        XCTAssertEqual(resolveBackendURL("").absoluteString, fallback)
    }

    func testWhitespaceOnlyFallsBackToLocalhost() {
        XCTAssertEqual(resolveBackendURL("   ").absoluteString, fallback)
    }

    func testTailscaleHostIsReturnedVerbatim() {
        let value = "http://mymac.example.ts.net:8080"
        XCTAssertEqual(resolveBackendURL(value).absoluteString, value)
    }

    func testLANIPIsReturnedVerbatim() {
        let value = "http://192.168.1.42:8080"
        XCTAssertEqual(resolveBackendURL(value).absoluteString, value)
    }

    func testUnparseableValueFallsBackToLocalhost() {
        XCTAssertEqual(resolveBackendURL("not a url").absoluteString, fallback)
    }
}
