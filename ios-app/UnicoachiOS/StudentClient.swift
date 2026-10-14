import Foundation
import os

protocol StudentClientProtocol: Sendable {
    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent
    func fetchProfile() async throws -> PublicStudent?
}

class StudentClient: StudentClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "StudentClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent {
        logger.debug("Creating student profile")
        let (data, response) = try await apiClient.post("/api/v1/students", body: request)
        let studentResponse: StudentResponse = try apiClient.decode(data: data, response: response, expectedStatus: 201)
        return studentResponse.student
    }

    /// Reads the student profile, mapping `404` to `nil`: an account that has
    /// not created a student row yet is a legitimate answer on this read, not a
    /// failure. The mapping is opt-in per call site — `createStudent` keeps
    /// throwing on `404`, where it would mean a genuinely missing owner.
    func fetchProfile() async throws -> PublicStudent? {
        logger.debug("Fetching student profile")
        let studentResponse: StudentResponse? = try await apiClient.getIfPresent("/api/v1/students/me")
        return studentResponse?.student
    }
}

protocol SupporterInviteClientProtocol: Sendable {
    /// Mints a supporter invite link for the signed-in caller (RFC 232).
    ///
    /// `nil` sends no word at all: the server owns the product default, and the
    /// response reports the word it used.
    func createInvite(relationship: SupporterRelationship?) async throws -> SupporterInviteResponse

    /// `GET /api/v1/students/me/supporters` (RFC 249): the adults connected to
    /// the signed-in student, the ceiling in the student's voice, and whether
    /// the Family Cost Report link is live. Student-scoped: only the student's
    /// Settings calls it.
    func fetchSupporters() async throws -> StudentSupporters

    /// `DELETE /api/v1/students/me/supporters/{linkId}` (RFC 249). Every
    /// status but `204` and `404 supporter_link_not_found` throws.
    func disconnectSupporter(linkId: UUID) async throws -> DisconnectOutcome

    /// `POST /api/v1/students/me/supporter-requests/{requestId}/approve`
    /// (RFC 254). Every status but `200` and `404 supporter_request_not_found`
    /// throws.
    func approveSupporterRequest(requestId: UUID) async throws -> ApproveOutcome

    /// `POST /api/v1/students/me/supporter-requests/{requestId}/decline`
    /// (RFC 254). Every status but `204` and `404 supporter_request_not_found`
    /// throws.
    func declineSupporterRequest(requestId: UUID) async throws -> DeclineOutcome
}

class SupporterInviteClient: SupporterInviteClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "SupporterInviteClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func createInvite(relationship: SupporterRelationship?) async throws -> SupporterInviteResponse {
        logger.debug("Minting supporter invite")
        do {
            let (data, response) = try await apiClient.post(
                "/api/v1/supporter-invites",
                body: CreateSupporterInviteRequest(relationship: relationship)
            )
            return try apiClient.decode(data: data, response: response, expectedStatus: 201)
        } catch {
            // The view flattens every failure into one sentence for the student,
            // so the structured cause is recorded HERE or nowhere at all.
            logger.error("Supporter invite mint failed: [\(String(describing: error), privacy: .public)]")
            throw error
        }
    }

    func fetchSupporters() async throws -> StudentSupporters {
        logger.debug("Fetching the student's supporters")
        let (data, response) = try await apiClient.get("/api/v1/students/me/supporters")
        return try apiClient.decode(data: data, response: response, expectedStatus: 200)
    }

    func disconnectSupporter(linkId: UUID) async throws -> DisconnectOutcome {
        logger.debug("Disconnecting supporter link [\(linkId, privacy: .public)]")
        let (data, response) = try await apiClient.delete("/api/v1/students/me/supporters/\(linkId.uuidString)")
        return try apiClient.disconnectOutcome(data: data, response: response)
    }

    func approveSupporterRequest(requestId: UUID) async throws -> ApproveOutcome {
        logger.debug("Approving supporter request [\(requestId, privacy: .public)]")
        let (data, response) = try await apiClient.post("/api/v1/students/me/supporter-requests/\(requestId.uuidString)/approve")
        return try apiClient.approveOutcome(data: data, response: response)
    }

    func declineSupporterRequest(requestId: UUID) async throws -> DeclineOutcome {
        logger.debug("Declining supporter request [\(requestId, privacy: .public)]")
        let (data, response) = try await apiClient.post("/api/v1/students/me/supporter-requests/\(requestId.uuidString)/decline")
        return try apiClient.declineOutcome(data: data, response: response)
    }
}

/// The invite door's canvas double: one fixed minted link, no network. Declared
/// next to the seam rather than per preview file — the
/// `PreviewCoachingUsageClient` precedent — so the two canvases that show the
/// invite control cannot answer differently.
struct PreviewSupporterInviteClient: SupporterInviteClientProtocol {
    let inviteUrl = "https://app.unicoach.test/invite?token=preview"

    /// What the canvas SHOWS when the caller names no relationship. A stored
    /// property rather than a literal inside the body: the product default is
    /// the server's, and a second copy of it in a function body is the client
    /// asserting a vocabulary it does not own.
    var mintedRelationship: SupporterRelationship = .supporter
    var inviterRole: InviterRole = .student
    var expiresAt = Date(timeIntervalSince1970: 4_102_444_800)

    /// What the student's list shows on the canvas (RFC 249). Empty by
    /// default; a canvas that wants people names them.
    var supporters = StudentSupporters(
        supporters: [],
        ceiling: SupporterCeiling(canSee: [], staysPrivate: []),
        costReportLinkLive: false,
        requests: []
    )

    func createInvite(relationship: SupporterRelationship?) async throws -> SupporterInviteResponse {
        SupporterInviteResponse(
            id: UUID(),
            inviteUrl: inviteUrl,
            inviterRole: inviterRole,
            relationship: relationship ?? mintedRelationship,
            expiresAt: expiresAt
        )
    }

    func fetchSupporters() async throws -> StudentSupporters { supporters }

    func disconnectSupporter(linkId: UUID) async throws -> DisconnectOutcome { .disconnected }

    /// The canvas's approve connects the person who asked, under their name.
    func approveSupporterRequest(requestId: UUID) async throws -> ApproveOutcome {
        guard let request = supporters.requests.first(where: { $0.requestId == requestId }) else {
            return .noLongerPending
        }
        return .connected(LinkedSupporter(linkId: UUID(), name: request.name))
    }

    func declineSupporterRequest(requestId: UUID) async throws -> DeclineOutcome { .declined }
}
