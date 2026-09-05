import Foundation
import os

protocol CollegeListClientProtocol: Sendable {
    func listEntries() async throws -> [CollegeListEntry]
    func addEntry(collegeId: UUID) async throws -> CollegeListEntry
    func updateEntry(
        id: UUID,
        version: Int,
        status: CollegeListStatus,
        reasons: String?,
        livingPlan: LivingPlanUpdate
    ) async throws -> CollegeListEntry
    func removeEntry(id: UUID, version: Int) async throws
    func searchColleges(query: String) async throws -> [CollegeSummary]
}

extension CollegeListClientProtocol {
    /// "Say nothing about a field you do not manage" is the default: a caller
    /// that omits `livingPlan` sends `.keep`, i.e. neither wire key (RFC 164).
    /// Swift forbids a default argument on a protocol requirement, so the
    /// default lives here rather than in the declaration above — every existing
    /// call site stays byte-identical on the wire.
    ///
    /// **A concrete type must never declare its own 4-argument overload.**
    /// Extension methods are STATICALLY dispatched: a `CollegeListClient` that
    /// declared this signature itself would be called by concrete-typed callers
    /// while existential/generic callers still got this one, and the two could
    /// drift apart silently. Both sides are pinned:
    /// `testUpdateEntryKeepSendsNeitherLivingPlanKey` calls the 4-argument form
    /// on the CONCRETE client and asserts the bytes it sends, and the restatus-
    /// Save assertion in `CollegeListViewModelTests` covers the EXISTENTIAL
    /// path the app actually takes — `CollegeListViewModel` holds a
    /// `CollegeListClientProtocol` — by asserting it passes `.keep`.
    func updateEntry(
        id: UUID,
        version: Int,
        status: CollegeListStatus,
        reasons: String?
    ) async throws -> CollegeListEntry {
        try await updateEntry(id: id, version: version, status: status, reasons: reasons, livingPlan: .keep)
    }
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
    ///
    /// `livingPlan` says one of three things about the per-college living-plan
    /// override (RFC 164): `.keep` sends neither key and leaves the stored value
    /// alone, `.set(plan)` writes it, `.clear` removes it. The two are never
    /// sent together, so the server's 400 for that pair is unreachable here.
    func updateEntry(
        id: UUID,
        version: Int,
        status: CollegeListStatus,
        reasons: String?,
        livingPlan: LivingPlanUpdate
    ) async throws -> CollegeListEntry {
        logger.debug("Updating college-list entry [\(id, privacy: .public)] at version [\(version, privacy: .public)]")
        let (data, response) = try await apiClient.patch(
            "/api/v1/students/me/college-list/\(id.uuidString)",
            body: UpdateCollegeListEntryRequest(version: version, status: status, reasons: reasons, livingPlan: livingPlan)
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
