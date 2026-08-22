import Foundation

struct RegisterRequest: Codable {
    let email: String
    let password: String
    let name: String
}

struct PublicUser: Codable {
    let id: UUID
    let email: String
    let name: String
    let emailVerified: Bool
}

struct RegisterResponse: Codable {
    let user: PublicUser
}

struct FieldError: Codable, Equatable {
    let field: String
    let message: String
}

struct ErrorResponse: Codable, Error, Identifiable, Equatable {
    var id: String { code }
    let code: String
    let message: String
    let fieldErrors: [FieldError]?
    /// HTTP status of the originating response, when there was one. Stamped in by
    /// `APIClient.decodeError`; stays `nil` for client-synthesized errors
    /// (transport, decode, non-HTTP) that never had a status. Excluded from
    /// `Codable` — the server error body carries no `status` field.
    var status: Int? = nil

    private enum CodingKeys: String, CodingKey {
        case code, message, fieldErrors
    }
}

extension ErrorResponse {
    func fieldError(for field: String) -> String? {
        fieldErrors?.first(where: { $0.field == field })?.message
    }
}

/// The wire `code` vocabulary the client branches on: the server's `ErrorCode`
/// forms (lowercase, owned by
/// rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt) plus the
/// UPPERCASE codes `APIClient` synthesizes for transport failures. Nothing spans
/// the Swift/Kotlin boundary to derive or check the server forms, so a changed
/// wire string is corrected by hand — here, and until they are migrated onto
/// this enum, in `AppViewModel` and `OnboardingViewModel`, which still compare
/// `error.code` against raw literals.
enum ServerErrorCode: String {
    case unauthorized
    case emailNotVerified = "email_not_verified"
    case accountEmailNotVerified = "account_email_not_verified"
    case accountDisabled = "account_disabled"
    case serviceUnavailable = "service_unavailable"
    case studentAlreadyExists = "student_already_exists"
    case timeout = "TIMEOUT"
    case networkError = "NETWORK_ERROR"
    case serverError = "SERVER_ERROR"
}

extension ErrorResponse {
    /// The recognized code this response carries, or `nil` for one this client
    /// has no case for — a newer server code, or a client-synthesized one such
    /// as `VALIDATION` that is only ever displayed.
    var knownCode: ServerErrorCode? { ServerErrorCode(rawValue: code) }
}

struct LoginRequest: Codable {
    let email: String
    let password: String
}

struct LoginResponse: Codable {
    let user: PublicUser
}

struct GoogleLoginRequest: Codable {
    let idToken: String
}

/// Body of `POST /api/v1/auth/apple`. `name` is the name Apple disclosed on the
/// *first* authorization only — `AppleNameStore` persists it and resends it on
/// every later sign-in — and the server uses it solely when provisioning a new
/// user, never to rename an existing one. Swift's synthesized `Encodable` omits
/// a `nil` optional via `encodeIfPresent`, and that omission is how this body
/// says "no name": the server then derives one from the email local-part
/// (`AuthService.deriveName`). Send `nil`, never `""` — `PersonName.create`
/// rejects a blank, so an empty string is never a name, only a rejected
/// candidate the server logs before falling through to that same local-part.
struct AppleLoginRequest: Encodable {
    let idToken: String
    let name: String?
}

struct MeResponse: Codable {
    let user: PublicUser
}

struct ChangeEmailRequest: Codable {
    let email: String
}

struct ChangeEmailResponse: Codable {
    let user: PublicUser
}

struct CreateStudentRequest: Codable {
    let expectedHighSchoolGraduationDate: String
}

struct PublicStudent: Codable, Equatable {
    let id: UUID
    let expectedHighSchoolGraduationDate: String
    let version: Int
    let createdAt: Date
    let updatedAt: Date
}

struct StudentResponse: Codable {
    let student: PublicStudent
}

// MARK: - Conversation domain models

enum MessageRole: String, Codable, Sendable {
    case user
    case coach
}

struct Message: Codable, Sendable, Identifiable, Equatable {
    let id: String        // opaque; never parsed
    let role: MessageRole
    let content: String
    let createdAt: Date
}

struct Conversation: Codable, Sendable, Identifiable, Equatable {
    let id: UUID         // contract: uuid-format string; decoded as UUID
    let name: String
    let createdAt: Date
    let updatedAt: Date
    let lastActivityAt: Date?
    let archivedAt: Date?
}

struct ConversationListResponse: Codable, Sendable {
    let conversations: [Conversation]
}

struct MessageListResponse: Codable, Sendable {
    let messages: [Message]
}

struct CreateConversationRequest: Codable, Sendable {
    let message: String
    let name: String?     // always nil this iteration; server derives the name
}

struct PostMessageRequest: Codable, Sendable {
    let message: String
}

/// One-field-at-a-time conversation update mirroring the server DTO
/// (`UpdateConversationRequest(name: String? = null, archived: Boolean? = null)`).
/// Both fields are optional; Swift's synthesized `Encodable` omits a `nil`
/// optional via `encodeIfPresent`, so a PATCH carrying only `archived` leaves
/// the server-side `name` untouched.
struct UpdateConversationRequest: Codable, Sendable {
    let name: String?
    let archived: Bool?
}

// MARK: - Stream domain event

enum ConversationStreamEvent: Sendable {
    case conversation(Conversation, userMessage: Message)
    case userMessage(Message)
    case delta(String)
    case completed(Message)
}

// MARK: - Wire DTOs (SSE frame decoding)

struct ConversationCreatedFrame: Codable {
    let type: String
    let conversation: Conversation
    let userMessage: Message
}

struct UserMessageFrame: Codable {
    let type: String
    let userMessage: Message
}

struct MessageDeltaFrame: Codable {
    let type: String
    let text: String
}

struct MessageCompletedFrame: Codable {
    let type: String
    let message: Message
}

struct StreamErrorFrame: Codable {
    let type: String
    let error: ErrorResponse
}
