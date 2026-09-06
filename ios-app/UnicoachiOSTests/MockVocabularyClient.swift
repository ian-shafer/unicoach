import Foundation
@testable import UnicoachiOS

/// Protocol double for view-model tests, in the `MockCollegeListClient` shape:
/// configure the result, read back the call count. The counter matters here
/// because RFC 171's rule is partly about degradation — the screen must render
/// its pickers from the fallback lists when this call fails, and only a counter
/// distinguishes "fell back" from "never asked".
final class MockVocabularyClient: VocabularyClientProtocol, @unchecked Sendable {
    var fetchResult: Result<VocabulariesResponse, Error> = .success(.fixture())

    private(set) var fetchCallCount = 0

    func fetch() async throws -> VocabulariesResponse {
        fetchCallCount += 1
        return try fetchResult.get()
    }
}

// MARK: - Fixtures

extension VocabulariesResponse {
    /// The document as the server serves it, in the server's own order: the
    /// five bands lowest-first, then jurisdictions by name — including a
    /// territory, because offering more than `ResidencyStates.offered` is the
    /// point of serving the list at all (RFC 171 §2).
    static func fixture(
        version: String = "b3f1c2d4e5a60718",
        bands: [VocabularyEntry] = VocabularyEntry.bandFixtures,
        states: [VocabularyEntry] = VocabularyEntry.stateFixtures
    ) -> VocabulariesResponse {
        VocabulariesResponse(
            version: version,
            vocabularies: [
                VocabulariesResponse.Name.incomeBands.rawValue: PublicVocabulary(entries: bands),
                VocabulariesResponse.Name.residencyStates.rawValue: PublicVocabulary(entries: states),
            ]
        )
    }
}

extension VocabularyEntry {
    static let bandFixtures: [VocabularyEntry] = IncomeBand.allCases.map {
        VocabularyEntry(value: $0.rawValue, label: $0.bracket)
    }

    static let stateFixtures: [VocabularyEntry] = [
        VocabularyEntry(value: "AL", label: "Alabama"),
        VocabularyEntry(value: "AS", label: "American Samoa"),
        VocabularyEntry(value: "CA", label: "California"),
        VocabularyEntry(value: "DC", label: "District of Columbia"),
        VocabularyEntry(value: "GU", label: "Guam"),
        VocabularyEntry(value: "NY", label: "New York"),
    ]
}
