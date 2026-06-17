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
}

extension ErrorResponse {
    func fieldError(for field: String) -> String? {
        fieldErrors?.first(where: { $0.field == field })?.message
    }
}

struct LoginRequest: Codable {
    let email: String
    let password: String
}

struct LoginResponse: Codable {
    let user: PublicUser
}

struct MeResponse: Codable {
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
