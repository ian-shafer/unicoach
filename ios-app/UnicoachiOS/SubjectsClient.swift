import Foundation
import os

protocol SubjectsClientProtocol: Sendable {
    /// The subjects the picker offers (RFC 205 §5). `query` is **optional**:
    /// absent, the server serves the whole list in name order, which is what
    /// the picker shows before the student types anything.
    ///
    /// Throws `ErrorResponse` on every failure, the convention every client in
    /// this app follows. There is deliberately **no shipped fallback list** —
    /// this is a search route, not a served vocabulary (RFC 165 excludes the
    /// taxonomy by rule), so an unreachable list is reported as unreachable and
    /// the screen still shows what is already stored.
    func search(query: String?) async throws -> [PublicSubject]
}

/// `GET /api/v1/subjects` (RFC 205 §5), in the `VocabularyClient` shape: a thin
/// endpoint binding over the injected `APIClient`, which owns transport, status
/// handling, and error decoding.
///
/// The endpoint needs a session but **no student profile** — the same reasoning
/// `GET /api/v1/colleges` already states — so this client never raises
/// `409 student_profile_required`.
///
/// Nothing is cached here and nothing is sorted here. The server serves the
/// list in name order, and a client-side re-sort would be a second opinion
/// about an order the server owns.
final class SubjectsClient: SubjectsClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger.unicoach(category: "SubjectsClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    /// A blank-but-present `q` is a 400 server-side, so an empty or whitespace
    /// query is sent as **no** `q` at all rather than as `q=`: the student who
    /// cleared the search field is asking for the whole list, not making a
    /// malformed request. `limit` is never sent — the list is closed and small,
    /// and the server's default is the whole of it.
    func search(query: String?) async throws -> [PublicSubject] {
        let trimmed = query?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        logger.debug("Searching subjects [queried=\(!trimmed.isEmpty, privacy: .public)]")
        let queryItems = trimmed.isEmpty ? [] : [URLQueryItem(name: "q", value: trimmed)]
        let (data, response) = try await apiClient.get("/api/v1/subjects", query: queryItems)
        let searchResponse: SubjectSearchResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return searchResponse.subjects
    }
}
