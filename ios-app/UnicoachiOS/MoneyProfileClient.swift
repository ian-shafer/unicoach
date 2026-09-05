import Foundation
import os

protocol MoneyProfileClientProtocol: Sendable {
    /// Writes the supplied subset of money-profile fields, returning the whole
    /// updated profile. Idempotent create-or-update (RFC 134).
    ///
    /// Throws `ErrorResponse` on every failure, transport included — the
    /// convention every client in this app follows, so a caller has one error
    /// type to branch on rather than one per client.
    func update(_ request: UpdateMoneyProfileRequest) async throws -> PublicMoneyProfile
}

/// The write half of the RFC 134 money-profile surface, in the `StudentClient`
/// shape: a thin endpoint binding over the injected `APIClient`, which owns
/// transport, status handling, and error decoding.
///
/// **There is no read binding**, though the server offers `GET`: RFC 163 §6
/// states this app grows no money-profile read or edit surface — chat is the
/// edit path — so a `fetchMoneyProfile` here would be a method with no reader,
/// kept alive by its own test. The read verb lands with the first screen that
/// needs it.
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
