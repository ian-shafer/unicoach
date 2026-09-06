import Foundation
import os

protocol MoneyProfileClientProtocol: Sendable {
    /// Reads the whole money profile, or `nil` when the student has never
    /// written one. Absence is a legitimate answer, not a failure: the server
    /// creates the row on first write (RFC 134), so a family that has never
    /// answered has no row and the screen renders all-unanswered.
    ///
    /// Throws `ErrorResponse` on every other failure, as `update` does.
    func fetch() async throws -> PublicMoneyProfile?

    /// Writes the supplied subset of money-profile fields, returning the whole
    /// updated profile. Idempotent create-or-update (RFC 134).
    ///
    /// Throws `ErrorResponse` on every failure, transport included — the
    /// convention every client in this app follows, so a caller has one error
    /// type to branch on rather than one per client.
    func update(_ request: UpdateMoneyProfileRequest) async throws -> PublicMoneyProfile
}

/// The RFC 134 money-profile surface, in the `StudentClient`
/// shape: a thin endpoint binding over the injected `APIClient`, which owns
/// transport, status handling, and error decoding.
///
/// The read verb landed with the first screen that needs it: "Your details"
/// (RFC 171). RFC 163 §6 had deferred it because a `fetch` with no reader is a
/// method kept alive by its own test.
///
/// The `PUT` answers `409 student_profile_required` when the account has no
/// student row, so every caller must create the student profile first (RFC 163
/// §3). That error is surfaced, not absorbed here: which failures a screen may
/// swallow is the screen's decision, not the transport's.
final class MoneyProfileClient: MoneyProfileClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "MoneyProfileClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    /// Reads the profile, mapping `404` to `nil`.
    ///
    /// `getIfPresent` is chosen **per call site**, and deliberately not applied
    /// to `update`: `404` is overloaded on this resource. On `GET` it is the
    /// benign "no profile yet" state that precedes the first write; on `PUT` it
    /// is a real fault ("Owning student not found"). Both carry
    /// `code: "not_found"`, so only the verb distinguishes them — a mapping
    /// shared across verbs would silently turn a lost student row into an empty
    /// screen (`MoneyProfileRoutes.kt:64, 88`).
    ///
    /// Absence is read off the status rather than the code string — the
    /// `StudentClient.fetchProfile` precedent, now the same helper — because
    /// `APIClient.decodeError` treats 400/401/404/409 identically and
    /// `ServerErrorCode` has no `not_found` member to branch on.
    func fetch() async throws -> PublicMoneyProfile? {
        logger.debug("Fetching money profile")
        let profileResponse: MoneyProfileResponse? = try await apiClient.getIfPresent("/api/v1/students/me/money-profile")
        return profileResponse?.profile
    }

    func update(_ request: UpdateMoneyProfileRequest) async throws -> PublicMoneyProfile {
        // Which fields, not their values: the caller logs the values when it
        // drops a failure, and this line is only here to say what the write
        // touched.
        logger.debug(
            "Updating money profile: income=[\(request.income != nil, privacy: .public)] residency=[\(request.residency != nil, privacy: .public)] living=[\(request.living != nil, privacy: .public)]"
        )
        let (data, response) = try await apiClient.put("/api/v1/students/me/money-profile", body: request)
        let profileResponse: MoneyProfileResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return profileResponse.profile
    }
}
