import XCTest
@testable import UnicoachiOS

/// The only endpoint binding RFC 171 adds, asserted over a real `URLSession`
/// round trip: the path, the verb, and the **flat** entry shape the server
/// actually serves. A mock client cannot disagree with the client it doubles,
/// so a mistyped path would otherwise ship green.
class VocabularyClientTests: XCTestCase {
    var vocabularyClient: VocabularyClient!
    var session: URLSession!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        vocabularyClient = VocabularyClient(apiClient: apiClient)
    }

    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    /// The server flattens its extras with `@JsonAnyGetter`
    /// (`VocabulariesResponse.kt:23-31`), so an extra such as
    /// `jurisdictionKind` is a **sibling** of `value`/`label` and never a
    /// member of an `extras` object. This client decodes only `value` and
    /// `label`; the extras in this body are here to prove that a served
    /// document carrying them still decodes to exactly the two keys the screen
    /// renders.
    func testFetchSendsGetToTheVocabulariesPathAndDecodesTheFlatEntries() async throws {
        let body = Data(#"""
        {
          "version": "v1-abc",
          "vocabularies": {
            "income_bands": {
              "entries": [
                {"value": "under_30k", "label": "Under $30,000"}
              ]
            },
            "residency_states": {
              "entries": [
                {"value": "GU", "label": "Guam", "jurisdictionKind": "territory"},
                {"value": "AL", "label": "Alabama", "jurisdictionKind": "state"}
              ]
            }
          }
        }
        """#.utf8)

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/vocabularies")
            XCTAssertEqual(request.httpMethod, "GET")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, body)
        }

        let document = try await vocabularyClient.fetch()

        XCTAssertEqual(document.version, "v1-abc")
        XCTAssertEqual(
            document.entries(.incomeBands),
            [VocabularyEntry(value: "under_30k", label: "Under $30,000")]
        )
        // Served order, not alphabetical: the server decides the menu order and
        // the client sorts nothing (RFC 171 §2). Guam before Alabama is the
        // assertion that no sort crept in.
        XCTAssertEqual(
            document.entries(.residencyStates),
            [
                VocabularyEntry(value: "GU", label: "Guam"),
                VocabularyEntry(value: "AL", label: "Alabama"),
            ]
        )
    }

    /// Extras are an open set. An entry carrying an attribute this build has
    /// never heard of must decode, never throw — a picker that refuses to
    /// render because the server started serving one more key is the failure
    /// this shape exists to prevent.
    func testAnUnknownExtraKeyIsIgnoredRatherThanFatal() async throws {
        let body = Data(#"""
        {
          "version": "v2",
          "vocabularies": {
            "residency_states": {
              "entries": [
                {"value": "PW", "label": "Palau", "jurisdictionKind": "freely-associated-state", "region": "pacific"}
              ]
            }
          }
        }
        """#.utf8)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, body)
        }

        let document = try await vocabularyClient.fetch()

        XCTAssertEqual(
            document.entries(.residencyStates),
            [VocabularyEntry(value: "PW", label: "Palau")]
        )
    }
}


/// The names this client keys the served document by are the server's, and
/// nothing in the build derives them: a renamed vocabulary reads as a failed
/// fetch, so the screen silently drops to the shipped fallback lists while
/// every other test stays green. The expected values are READ OUT of
/// `VocabularyService.kt` rather than retyped, the `IncomeBandLabelTests` /
/// `ResidencyStateTableTests` pattern — otherwise this would only compare one
/// client copy to another.
final class VocabularyNameTests: XCTestCase {
    /// **Hand-rolled on purpose**: no Kotlin parser is reachable from an iOS
    /// test target. Paired with a not-empty check, so a scrape that breaks says
    /// so instead of passing vacuously.
    private func serverNames() throws -> [String: String] {
        let source = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // UnicoachiOSTests
            .deletingLastPathComponent()   // ios-app
            .deletingLastPathComponent()   // repo root
            .appendingPathComponent("service/src/main/kotlin/ed/unicoach/vocabulary/VocabularyService.kt")
        let text = try String(contentsOf: source, encoding: .utf8)

        let pattern = #"const val ([A-Z_]+): String = "([a-z0-9_]+)""#
        let regex = try NSRegularExpression(pattern: pattern)
        let matches = regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
        let names: [(String, String)] = matches.compactMap { match in
            guard let constant = Range(match.range(at: 1), in: text),
                  let value = Range(match.range(at: 2), in: text) else { return nil }
            return (String(text[constant]), String(text[value]))
        }
        guard !names.isEmpty else {
            // NOT an empty map: "the file's shape changed" and "the server
            // names nothing" are different facts.
            throw XCTSkip("could not read the vocabulary names out of VocabularyService.kt — the scrape needs updating")
        }
        return Dictionary(uniqueKeysWithValues: names)
    }

    func testTheKeysThisClientLooksUpAreTheOnesTheServerRegisters() throws {
        let names = try serverNames()
        XCTAssertEqual(
            names["INCOME_BANDS"], VocabulariesResponse.Name.incomeBands.rawValue,
            "the server renamed its band vocabulary; this client would read a 200 as a failed fetch"
        )
        XCTAssertEqual(
            names["RESIDENCY_STATES"], VocabulariesResponse.Name.residencyStates.rawValue,
            "the server renamed its residency vocabulary; this client would read a 200 as a failed fetch"
        )
    }
}
