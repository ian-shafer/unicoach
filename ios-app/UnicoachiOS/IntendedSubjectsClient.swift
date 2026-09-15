import Foundation
import os

protocol IntendedSubjectsClientProtocol: Sendable {
    /// Reads the student's whole answer (RFC 205 §6). There is **no `nil`
    /// here**, unlike `MoneyProfileClient.fetch`: this route always answers
    /// `200`, with `status: "unanswered"` and an empty list before any write.
    /// The status IS the payload, so an absent answer is a state this resource
    /// can state rather than a missing row.
    ///
    /// Throws `ErrorResponse` on every failure, as `update` does.
    func fetch() async throws -> PublicIntendedSubjects

    /// Writes the student's whole answer and returns the stored result. One
    /// operation, three shapes, no add-one and no remove-one (RFC 205 §3).
    ///
    /// The server may answer with a status the request did not name: an empty
    /// subject set from a student who HAD subjects stores `undecided`, never
    /// `unanswered` (D17). The caller adopts what comes back, never what it
    /// sent.
    func update(_ request: UpdateIntendedSubjectsRequest) async throws -> PublicIntendedSubjects
}

/// `GET`/`PUT /api/v1/students/me/intended-subjects` (RFC 205 §6), a sibling of
/// the money-profile route and not an extension of it: what a student wants to
/// study is not money (D37).
///
/// Like `MoneyProfileClient` it surfaces `409 student_profile_required` rather
/// than absorbing it — which failures a screen may swallow is the screen's
/// decision, not the transport's.
final class IntendedSubjectsClient: IntendedSubjectsClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger.unicoach(category: "IntendedSubjectsClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func fetch() async throws -> PublicIntendedSubjects {
        logger.debug("Fetching intended subjects")
        let (data, response) = try await apiClient.get("/api/v1/students/me/intended-subjects")
        let answer: IntendedSubjectsResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return answer.intendedSubjects
    }

    func update(_ request: UpdateIntendedSubjectsRequest) async throws -> PublicIntendedSubjects {
        // WHICH answer, not which subjects: the slugs are the student's answer
        // and are not logged. The count is, because a refused write is nearly
        // always a cap or a duplicate and the count is what makes that legible.
        logger.debug("Updating intended subjects: \(Self.mapToLogLabel(request.answer), privacy: .public)")
        let (data, response) = try await apiClient.put("/api/v1/students/me/intended-subjects", body: request)
        let answer: IntendedSubjectsResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return answer.intendedSubjects
    }

    private static func mapToLogLabel(_ answer: UpdateIntendedSubjectsRequest.Answer) -> String {
        switch answer {
        case .subjects(let slugs): return "subjects(count=\(slugs.count))"
        case .declined: return "declined"
        case .undecided: return "undecided"
        }
    }
}
