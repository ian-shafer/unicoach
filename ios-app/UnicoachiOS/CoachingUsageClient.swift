import Foundation
import os

/// The coaching meter. Deliberately its own client rather than a method on
/// `StudentClient`: the `/students/me/` prefix is *caller scoping*, not
/// ownership — server-side the handler takes `BudgetService` and touches
/// `StudentService` only to resolve the caller. The meter is a projection of
/// `Entitlement`, which changes on every LLM call while the profile sits still.
///
/// Nor does it belong on `SubscriptionClient`: it is defined for a student who
/// has never subscribed and never will (the `resetsAt == nil` free-tier
/// branch), which is its most common reader.
protocol CoachingUsageClientProtocol: Sendable {
    func fetch() async throws -> CoachingUsage
}

class CoachingUsageClient: CoachingUsageClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger.unicoach(category: "CoachingUsageClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func fetch() async throws -> CoachingUsage {
        logger.debug("Fetching coaching usage")
        let (data, response) = try await apiClient.get("/api/v1/students/me/coaching-usage")
        let usageResponse: CoachingUsageResponse = try apiClient.decode(
            data: data,
            response: response,
            expectedStatus: 200
        )
        return usageResponse.usage
    }
}

/// The meter's canvas double: one fixed reading, no network. Declared next to
/// the seam rather than per preview file, so every canvas showing the
/// subscription surface shares it.
struct PreviewCoachingUsageClient: CoachingUsageClientProtocol {
    var usage = CoachingUsage(usedPercent: 42, exhausted: false, resetsAt: nil)

    func fetch() async throws -> CoachingUsage { usage }
}
