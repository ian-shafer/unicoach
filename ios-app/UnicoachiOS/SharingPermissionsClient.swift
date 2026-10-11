import Foundation
import os

protocol SharingPermissionsClientProtocol: Sendable {
    /// Reads every permission's answer (RFC 248). Like the intended-subjects
    /// read there is no `nil`: the route always answers `200`, and a permission
    /// the student was never asked is listed as `not_asked`.
    ///
    /// Throws `ErrorResponse` on every failure, as `update` does.
    func fetch() async throws -> [PublicSharingPermission]

    /// Writes one answer and returns the stored one. The caller adopts what
    /// comes back, never what it sent.
    func update(_ permission: SharingPermission, to choice: SharingChoice) async throws -> PublicSharingPermission
}

/// `GET /api/v1/students/me/sharing-permissions` and
/// `PUT /api/v1/students/me/sharing-permissions/{permission}` (RFC 248): who the
/// student lets people they share with see their data. A sibling of the
/// intended-subjects route and not part of it — a permission is its own answer
/// about that data.
///
/// Surfaces `409 student_profile_required` rather than absorbing it, as every
/// student-scoped client here does.
final class SharingPermissionsClient: SharingPermissionsClientProtocol, @unchecked Sendable {
    private static let path = "/api/v1/students/me/sharing-permissions"

    private let apiClient: APIClient
    private let logger = Logger.unicoach(category: "SharingPermissionsClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func fetch() async throws -> [PublicSharingPermission] {
        logger.debug("Fetching sharing permissions")
        let (data, response) = try await apiClient.get(Self.path)
        let answer: SharingPermissionsResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return answer.permissions
    }

    func update(_ permission: SharingPermission, to choice: SharingChoice) async throws -> PublicSharingPermission {
        logger.debug("""
            Updating sharing permission: permission=[\(permission.rawValue, privacy: .public)] \
            status=[\(choice.rawValue, privacy: .public)]
            """)
        let (data, response) = try await apiClient.put(
            "\(Self.path)/\(permission.rawValue)",
            body: UpdateSharingPermissionRequest(choice)
        )
        return try apiClient.decode(data: data, response: response, expectedStatus: 200)
    }
}

/// The sharing seam's canvas double: every permission never asked — what a
/// fresh student sees — and a write answers the choice it was sent. Declared
/// next to the seam rather than per preview file (the
/// `PreviewCoachingUsageClient` precedent), so every canvas that reaches "Your
/// details" answers the same way.
struct PreviewSharingPermissionsClient: SharingPermissionsClientProtocol {
    func fetch() async throws -> [PublicSharingPermission] {
        SharingPermission.allCases.map {
            PublicSharingPermission(permission: $0.rawValue, status: SharingPermissionStatus.notAsked.rawValue)
        }
    }

    func update(_ permission: SharingPermission, to choice: SharingChoice) async throws -> PublicSharingPermission {
        PublicSharingPermission(permission: permission.rawValue, status: choice.rawValue)
    }
}
