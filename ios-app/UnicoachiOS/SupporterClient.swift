import Foundation
import os

/// The two supporter doors (RFC 241): become one, and read your own home.
///
/// Its own client rather than a pair of methods on `StudentClient`, for the
/// reason `CoachingUsageClient` is its own: these routes are about the
/// SUPPORTER side of a family link, and a `StudentClientProtocol` a supporter
/// has to conform to in order to say "I have no student" is a seam named after
/// the wrong resource.
protocol SupporterClientProtocol: Sendable {
    /// `GET /api/v1/supporters/me`. `nil` when the caller holds no `supporters`
    /// row — the ordinary answer for every student, not a failure.
    func fetchHome() async throws -> SupporterHome?

    /// `POST /api/v1/supporters`. Idempotent: it makes the caller's account
    /// parent-shaped the first time and answers the same home on every later
    /// call, so a retry after a dropped response is safe.
    func becomeSupporter() async throws -> SupporterHome
}

final class SupporterClient: SupporterClientProtocol, @unchecked Sendable {
    /// The two statuses `POST /api/v1/supporters` can succeed with: `201` when
    /// the row was written, `200` when it already existed. Named because the
    /// idempotence is the contract — a client that accepted only one of them
    /// would refuse every call after the first.
    private static let successStatuses: Set<Int> = [200, 201]

    private let apiClient: APIClient
    private let logger = Logger.unicoach(category: "SupporterClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    /// `404` is an ANSWER here, not a fault, so this reads through
    /// `getIfPresent` exactly as `StudentClient.fetchProfile` does: "you hold no
    /// supporters row" is what the root state machine asks this method, and the
    /// absence is the reply.
    func fetchHome() async throws -> SupporterHome? {
        logger.debug("Fetching supporter home")
        return try await apiClient.getIfPresent("/api/v1/supporters/me")
    }

    func becomeSupporter() async throws -> SupporterHome {
        logger.debug("Making this account supporter-shaped")
        do {
            let (data, response) = try await apiClient.post("/api/v1/supporters")
            // Two successes, stated as data: `201` when this call minted the
            // row and `200` when it was already there. Every other status — a
            // 403 from the email gate, a 500 — comes back as the server's own
            // error, which is what `decode` does with a mismatch.
            return try apiClient.decode(data: data, response: response, expectedStatuses: Self.successStatuses)
        } catch {
            // The role-choice screen holds the `ErrorResponse` but renders
            // only its message, and a thrown non-`ErrorResponse` becomes
            // `.unexpected` there — so the structured cause is recorded HERE or
            // nowhere at all, the `SupporterInviteClient` precedent.
            logger.error("Becoming a supporter failed: [\(String(describing: error), privacy: .public)]")
            throw error
        }
    }
}

/// The supporter doors' canvas double: one fixed home, no network. Declared
/// next to the seam rather than per preview file — the
/// `PreviewSupporterInviteClient` precedent — so every canvas that can reach a
/// supporter screen answers the same way.
struct PreviewSupporterClient: SupporterClientProtocol {
    var home = SupporterHome(
        supporter: PublicSupporter(id: UUID(uuidString: "44440000-0000-0000-0000-000000000001") ?? UUID()),
        students: []
    )

    func fetchHome() async throws -> SupporterHome? { home }

    func becomeSupporter() async throws -> SupporterHome { home }
}
