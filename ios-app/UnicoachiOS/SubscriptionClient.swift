import Foundation
import os

/// The purchase rail's server half: hand Apple's signed transaction to the
/// backend, which verifies it against the App Store Server API and binds it to
/// this student.
///
/// `/verify` is the **binding** call and is documented as idempotent, so it is
/// also the only read of subscription state this API offers — the server
/// exposes no GET. Re-posting the current entitlement's JWS is therefore the
/// refresh path, not a workaround.
protocol SubscriptionClientProtocol: Sendable {
    func verify(signedTransaction: String) async throws -> PublicSubscription
}

class SubscriptionClient: SubscriptionClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger.unicoach(category: "SubscriptionClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func verify(signedTransaction: String) async throws -> PublicSubscription {
        logger.debug("Verifying signed transaction")
        let (data, response) = try await apiClient.post(
            "/api/v1/subscriptions/verify",
            body: SubscriptionVerifyRequest(signedTransaction: signedTransaction)
        )
        let verifyResponse: SubscriptionVerifyResponse = try apiClient.decode(
            data: data,
            response: response,
            expectedStatus: 200
        )
        return verifyResponse.subscription
    }
}
