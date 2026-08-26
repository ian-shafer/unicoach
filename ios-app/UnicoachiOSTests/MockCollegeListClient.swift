import Foundation
@testable import UnicoachiOS

/// Protocol mock in the `MockStudentClient` shape: configure per-call results,
/// read back what was captured. Also seeds snapshot scenes.
final class MockCollegeListClient: CollegeListClientProtocol, @unchecked Sendable {
    var listEntriesResult: Result<[CollegeListEntry], Error> = .success([])
    var addEntryResult: Result<CollegeListEntry, Error>?
    var updateEntryResult: Result<CollegeListEntry, Error>?
    var removeEntryResult: Result<Void, Error> = .success(())
    var searchCollegesResult: Result<[CollegeSummary], Error> = .success([])

    private(set) var listEntriesCallCount = 0
    private(set) var addedCollegeIds: [UUID] = []
    private(set) var updateCalls: [(id: UUID, version: Int, status: CollegeListStatus, reasons: String?)] = []
    private(set) var removeCalls: [(id: UUID, version: Int)] = []
    private(set) var searchQueries: [String] = []

    func listEntries() async throws -> [CollegeListEntry] {
        listEntriesCallCount += 1
        return try listEntriesResult.get()
    }

    func addEntry(collegeId: UUID) async throws -> CollegeListEntry {
        addedCollegeIds.append(collegeId)
        guard let result = addEntryResult else { fatalError("No addEntryResult configured") }
        return try result.get()
    }

    func updateEntry(id: UUID, version: Int, status: CollegeListStatus, reasons: String?) async throws -> CollegeListEntry {
        updateCalls.append((id: id, version: version, status: status, reasons: reasons))
        guard let result = updateEntryResult else { fatalError("No updateEntryResult configured") }
        return try result.get()
    }

    func removeEntry(id: UUID, version: Int) async throws {
        removeCalls.append((id: id, version: version))
        try removeEntryResult.get()
    }

    func searchColleges(query: String) async throws -> [CollegeSummary] {
        searchQueries.append(query)
        return try searchCollegesResult.get()
    }
}

// MARK: - Fixtures

extension CollegeListEntry {
    /// A deterministic fixture; every field is overridable per test.
    static func fixture(
        id: UUID = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000001")!,
        collegeId: UUID = UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000001")!,
        collegeName: String = "Test College",
        status: CollegeListStatus = .considering,
        reasons: String? = nil,
        version: Int = 1,
        supportingObservations: [SupportingObservation] = []
    ) -> CollegeListEntry {
        CollegeListEntry(
            id: id,
            collegeId: collegeId,
            collegeName: collegeName,
            status: status,
            reasons: reasons,
            version: version,
            supportingObservations: supportingObservations
        )
    }
}

extension CollegeSummary {
    static func fixture(
        id: UUID = UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000001")!,
        name: String = "Columbia University",
        city: String = "New York",
        state: String = "NY"
    ) -> CollegeSummary {
        CollegeSummary(id: id, name: name, city: city, state: state)
    }
}
