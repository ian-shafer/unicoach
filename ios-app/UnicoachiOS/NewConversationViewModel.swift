import Foundation

@MainActor
final class NewConversationViewModel: ObservableObject {
    /// Contract bounds for `CreateConversationRequest.message`.
    private static let messageMaxLength = 100_000

    @Published var messageText: String = ""
    @Published var isStreaming: Bool = false
    @Published private(set) var userMessage: Message?
    @Published private(set) var coachStreamingText: String = ""   // live buffer
    @Published private(set) var coachMessage: Message?            // canonical, on completion
    @Published private(set) var conversation: Conversation?
    @Published var errorResponse: ErrorResponse?
    @Published var infrastructureError: InfrastructureError?

    private let conversationClient: ConversationClientProtocol
    private let onProfileRequired: () -> Void

    init(conversationClient: ConversationClientProtocol, onProfileRequired: @escaping () -> Void) {
        self.conversationClient = conversationClient
        self.onProfileRequired = onProfileRequired
    }

    func send() async {
        // First-turn-only guard: a turn already succeeded.
        if coachMessage != nil {
            return
        }

        errorResponse = nil
        infrastructureError = nil

        let trimmed = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.count > Self.messageMaxLength {
            errorResponse = ErrorResponse(
                code: "VALIDATION",
                message: String(localized: "Validation error"),
                fieldErrors: [FieldError(field: "message", message: String(localized: "Enter a message to start coaching."))]
            )
            return
        }

        isStreaming = true
        defer { isStreaming = false }

        let request = CreateConversationRequest(message: trimmed, name: nil)

        do {
            for try await event in conversationClient.streamConversation(request: request) {
                switch event {
                case .conversation(let conversation, let userMessage):
                    self.conversation = conversation
                    self.userMessage = userMessage
                    messageText = ""
                case .delta(let text):
                    coachStreamingText += text
                case .completed(let message):
                    coachMessage = message
                }
            }
        } catch let error as ErrorResponse {
            map(error)
        } catch {
            infrastructureError = .serverError
        }
    }

    private func map(_ error: ErrorResponse) {
        // Codes are matched exactly/case-sensitively: client-synthesized codes are
        // UPPERCASE; this route family's server codes are lowercase (RFC 39).
        switch error.code {
        case "student_profile_required":
            // Abnormal edge: the profile was deleted server-side mid-session. The
            // root state machine replaces this screen with onboarding — publish
            // nothing (no errorResponse, no infrastructureError).
            onProfileRequired()
        case "TIMEOUT":
            infrastructureError = .timeout
        case "NETWORK_ERROR":
            infrastructureError = .noConnectivity
        case "SERVER_ERROR":
            infrastructureError = .serverError
        default:
            errorResponse = error
        }
    }
}
