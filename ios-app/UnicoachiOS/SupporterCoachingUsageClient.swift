import Foundation
import os

/// How the PARENT's own coaching is paid for:
/// `GET /api/v1/supporters/me/coaching-usage` (RFC 244).
///
/// Its own client, and not a second method on `CoachingUsageClient`: that one
/// reads `/api/v1/students/me/coaching-usage`, which is caller-scoped to a
/// student and answers `409 student_profile_required` for a supporter. A
/// supporter screen that held `CoachingUsageClientProtocol` could fire the
/// student route at all — and that refusal is swallowed into a blank meter
/// rather than surfaced, so the screen would look plausible and be permanently
/// wrong. Nor is it a method on `SupporterClient`: the meter is a projection of
/// `Entitlement`, which changes on every LLM call while the roster sits still
/// (the reason `CoachingUsageClient` is its own client).
protocol SupporterCoachingUsageClientProtocol: Sendable {
    /// `nil` when the caller holds no `supporters` row — the `404` is an ANSWER,
    /// exactly as it is for `GET /api/v1/supporters/me`, not a fault.
    func fetch() async throws -> SupporterCoachingUsage?
}

final class SupporterCoachingUsageClient: SupporterCoachingUsageClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger.unicoach(category: "SupporterCoachingUsageClient")

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    /// Reads through `getIfPresent`, the `SupporterClient.fetchHome` precedent:
    /// "you hold no supporters row" is an answer this route can give, and the
    /// transport owns which status says so.
    func fetch() async throws -> SupporterCoachingUsage? {
        logger.debug("Fetching supporter coaching usage")
        return try await apiClient.getIfPresent("/api/v1/supporters/me/coaching-usage")
    }
}

/// The parent meter's canvas double: one fixed reading, no network. Declared
/// next to the seam rather than per preview file — the
/// `PreviewCoachingUsageClient` precedent — so every canvas that reaches the
/// parent's home answers the same way.
struct PreviewSupporterCoachingUsageClient: SupporterCoachingUsageClientProtocol {
    var reading = SupporterCoachingUsage(
        usage: CoachingUsage(usedPercent: 18, exhausted: false, resetsAt: nil),
        coverage: CoachingCoverage(kind: .freeAllowance)
    )

    func fetch() async throws -> SupporterCoachingUsage? { reading }
}
