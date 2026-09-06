import Foundation
import os

protocol VocabularyClientProtocol: Sendable {
    /// Reads every published vocabulary (RFC 165). Throws `ErrorResponse` on
    /// every failure, the convention every client in this app follows.
    func fetch() async throws -> VocabulariesResponse
}

/// The RFC 165 vocabulary surface, in the `StudentClient` shape: a thin
/// endpoint binding over the injected `APIClient`, which owns transport,
/// status handling, and error decoding.
///
/// The endpoint needs a session but deliberately **no student profile**, so
/// this client never raises `409 student_profile_required`: the screen that
/// needs the picker is exactly the screen the caller may have no profile on
/// yet (`VocabularyRoutes.kt:26-31`).
///
/// Nothing is cached here. The served `version` is a content hash a caller may
/// hold to decide whether anything changed, but the endpoint sets no cache
/// headers and offers no conditional request, so a fetch is always a full
/// body — and this app fetches once per screen appearance, which is cheap
/// enough that a cache would only be a second source of truth.
final class VocabularyClient: VocabularyClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger.unicoach(category: "VocabularyClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func fetch() async throws -> VocabulariesResponse {
        logger.debug("Fetching served vocabularies")
        let (data, response) = try await apiClient.get("/api/v1/vocabularies")
        return try apiClient.decode(data: data, response: response, expectedStatus: 200)
    }
}
