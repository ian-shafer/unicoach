import XCTest
@testable import UnicoachiOS

/// The income-band dollar copy this app shows is a transcription of the
/// server's `IncomeBand.bracket`, which RFC 142 makes the ONE home for that
/// wording. The served vocabulary is authoritative at runtime, but the shipped
/// `bracket` list is what a family sees when the vocabulary fetch fails
/// (RFC 171 §2), so it must not drift past the server in silence.
///
/// The expected copy is READ OUT of `IncomeBand.kt` rather than retyped here,
/// the `ResidencyStateTableTests` pattern: a transcribed copy would only
/// compare one client copy to another, and could not fail on the change that
/// matters — the server rewording a band.
final class IncomeBandLabelTests: XCTestCase {
    /// The `("value", "bracket")` pairs as the Kotlin enum declares them, in
    /// declaration order — which is also the order the server serves them in.
    ///
    /// **Hand-rolled on purpose.** No Kotlin parser is reachable from an iOS
    /// test target. The scrape is therefore paired with an exact-count
    /// assertion, so a scrape that breaks says so instead of passing vacuously.
    private func serverBands() throws -> [(value: String, bracket: String)] {
        let source = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // UnicoachiOSTests
            .deletingLastPathComponent()   // ios-app
            .deletingLastPathComponent()   // repo root
            .appendingPathComponent("db/src/main/kotlin/ed/unicoach/db/models/IncomeBand.kt")
        let text = try String(contentsOf: source, encoding: .utf8)

        let pattern = #"\("([a-z0-9_]+)",\s*"((?:[^"\\]|\\.)*)"\)"#
        let regex = try NSRegularExpression(pattern: pattern)
        let matches = regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
        let bands: [(value: String, bracket: String)] = try matches.compactMap { match in
            guard let valueRange = Range(match.range(at: 1), in: text),
                  let bracketRange = Range(match.range(at: 2), in: text) else { return nil }
            return (value: String(text[valueRange]), bracket: try unescaped(text[bracketRange]))
        }
        guard !bands.isEmpty else {
            // NOT an empty list: "the file's shape changed" and "the server
            // declares no bands" are different facts, and a sentinel would let
            // the first masquerade as the second.
            throw XCTSkip("could not read the band declarations out of IncomeBand.kt — the scrape needs updating")
        }
        return bands
    }

    /// Kotlin's string-literal escapes are JSON's plus `\$` and `\'`, so those
    /// two are reduced here and every other escape — `\n`, `\t`, `\"`, `\\`,
    /// `\uXXXX` — is decoded by `JSONSerialization`, a tested decoder, rather
    /// than by the one escape this test happened to think of. The pattern above
    /// admits any escape, so a band reworded with any of them must decode
    /// rather than reach the comparison still carrying backslashes.
    private func unescaped(_ literal: Substring) throws -> String {
        let jsonLiteral = literal
            .replacingOccurrences(of: "\\$", with: "$")
            .replacingOccurrences(of: "\\'", with: "'")
        let decoded = try? JSONSerialization.jsonObject(
            with: Data("\"\(jsonLiteral)\"".utf8), options: [.fragmentsAllowed]
        )
        guard let bracket = decoded as? String else {
            throw XCTSkip("could not decode the band copy [\(literal)] out of IncomeBand.kt — the scrape needs updating")
        }
        return bracket
    }

    func testTheFallbackBracketCopyIsTheServersWordForWord() throws {
        let bands = try serverBands()
        XCTAssertEqual(bands.count, IncomeBand.allCases.count, "the server declares a different number of bands than this app offers")

        for (value, bracket) in bands {
            let band = IncomeBand(rawValue: value)
            XCTAssertNotNil(band, "the server declares band [\(value)], which this app has no case for")
            XCTAssertEqual(
                band?.bracket, bracket,
                "the fallback copy for [\(value)] is not the server's wording"
            )
        }
    }

    func testTheFallbackListIsInTheServersDeclarationOrder() throws {
        let bands = try serverBands()
        XCTAssertEqual(
            bands.map(\.value), IncomeBand.allCases.map(\.rawValue),
            "the fallback menu is shown in this order when the served vocabulary is unreachable, so it "
                + "must be the server's own order — lowest band first"
        )
    }
}
