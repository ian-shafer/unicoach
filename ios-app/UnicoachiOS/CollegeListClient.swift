import Foundation
import os

protocol CollegeListClientProtocol: Sendable {
    func listEntries() async throws -> [CollegeListEntry]
    func addEntry(collegeId: UUID) async throws -> CollegeListEntry
    func updateEntry(id: UUID, version: Int, status: CollegeListStatus, reasons: String?) async throws -> CollegeListEntry
    func removeEntry(id: UUID, version: Int) async throws
    func searchColleges(query: String) async throws -> [CollegeSummary]
}

/// The RFC 91 college-list REST surface plus the RFC 137 college search, in
/// the `StudentClient` shape: a thin endpoint binding over the injected
/// `APIClient`, which owns transport, status handling, and error decoding.
final class CollegeListClient: CollegeListClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "CollegeListClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func listEntries() async throws -> [CollegeListEntry] {
        logger.debug("Listing college-list entries")
        let (data, response) = try await apiClient.get("/api/v1/students/me/college-list")
        let listResponse: CollegeListResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return listResponse.entries
    }

    /// Adds a college with the server-side default status (`considering`).
    /// A duplicate add throws the server's 409 `conflict` with its message.
    func addEntry(collegeId: UUID) async throws -> CollegeListEntry {
        logger.debug("Adding college [\(collegeId, privacy: .public)] to the list")
        let (data, response) = try await apiClient.post(
            "/api/v1/students/me/college-list",
            body: CreateCollegeListEntryRequest(collegeId: collegeId)
        )
        let entryResponse: CollegeListEntryResponse = try apiClient.decode(data: data, response: response, expectedStatus: 201)
        return entryResponse.entry
    }

    /// Replaces the entry's status and reasons against `version` (OCC). A
    /// concurrent move throws the server's 409 `version_conflict`.
    func updateEntry(id: UUID, version: Int, status: CollegeListStatus, reasons: String?) async throws -> CollegeListEntry {
        logger.debug("Updating college-list entry [\(id, privacy: .public)] at version [\(version, privacy: .public)]")
        let (data, response) = try await apiClient.patch(
            "/api/v1/students/me/college-list/\(id.uuidString)",
            body: UpdateCollegeListEntryRequest(version: version, status: status, reasons: reasons)
        )
        let entryResponse: CollegeListEntryResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return entryResponse.entry
    }

    /// Soft-deletes the entry against `version` (OCC), expecting `204`.
    func removeEntry(id: UUID, version: Int) async throws {
        logger.debug("Removing college-list entry [\(id, privacy: .public)] at version [\(version, privacy: .public)]")
        let (data, response) = try await apiClient.delete(
            "/api/v1/students/me/college-list/\(id.uuidString)",
            query: [URLQueryItem(name: "version", value: String(version))]
        )
        try apiClient.expect(data: data, response: response, expectedStatus: 204)
    }

    /// Name search for the add picker. The free-text query rides as a query
    /// item — `APIClient` owns its percent-encoding.
    func searchColleges(query: String) async throws -> [CollegeSummary] {
        logger.debug("Searching colleges by name")
        let (data, response) = try await apiClient.get(
            "/api/v1/colleges",
            query: [URLQueryItem(name: "q", value: query)]
        )
        let searchResponse: CollegeSearchResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return searchResponse.colleges
    }
}
