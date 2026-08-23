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
    case subscriptionNotFound = "subscription_not_found"
    case subscriptionOwnedByOtherAccount = "subscription_owned_by_other_account"
    case validationFailed = "validation_failed"
    case payloadTooLarge = "payload_too_large"
    /// A 409 from a turn endpoint: the account has no student profile, so the
    /// root state machine routes back to onboarding. Named here because
    /// `ConversationViewModel.handle` branches on `knownCode`, and a code it
    /// must act on cannot be one this enum has never heard of.
    case studentProfileRequired = "student_profile_required"
    /// The 402 both streaming turn endpoints answer once the coaching budget is
    /// spent (RFC 109). Action-scoped: reads stay open, so this is never an auth
    /// state — see RFC 121.
    case coachingBudgetExhausted = "coaching_budget_exhausted"
    case timeout = "TIMEOUT"
    case networkError = "NETWORK_ERROR"
    case serverError = "SERVER_ERROR"
    /// `APIClient.decode` synthesizes this **only after** the response carried
    /// the expected status — i.e. the request succeeded and the body could not
    /// be read. A code, not a `nil`, because that distinction decides whether a
    /// StoreKit transaction may be finished (`TransactionRecorder.isPermanent`).
    case decodeError = "DECODE_ERROR"
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

// MARK: - Subscription and coaching-usage domain models

/// Body of `POST /api/v1/subscriptions/verify`: the StoreKit 2 JWS exactly as
/// Apple signed it. The client parses none of it.
struct SubscriptionVerifyRequest: Codable {
    let signedTransaction: String
}

struct SubscriptionVerifyResponse: Codable {
    let subscription: PublicSubscription
}

/// The server's record of the student's subscription, as returned by `/verify`
/// — which is also the only read path: there is no GET for subscription state,
/// so re-posting the current entitlement's JWS is how this is refreshed.
///
/// Display only. Nothing in this app derives entitlement from `status` or
/// `currentPeriodEnd`; the entitlement truth is `CoachingUsage`, straight from
/// the server.
struct PublicSubscription: Codable, Equatable, Sendable {
    /// The raw wire string rather than an enum, mirroring `ErrorResponse.code`.
    /// A bare `enum: String, Codable` property would *throw on decode* the day
    /// the server adds a status, turning a display concern into a hard failure
    /// of the whole response. Read the closed vocabulary through `knownStatus`.
    let status: String
    let productId: String
    let currentPeriodEnd: Date
}

/// The status vocabulary as the server spells it today
/// (`db/.../models/Subscription.kt`). Display-only; it gates nothing.
enum SubscriptionStatus: String, Sendable {
    case active
    case expired
    case grace
    case revoked
    case billingRetry = "billing_retry"
}

extension PublicSubscription {
    /// The recognized status this response carries, or `nil` for one this
    /// client has no case for — a newer server status, never a decode failure.
    var knownStatus: SubscriptionStatus? { SubscriptionStatus(rawValue: status) }
}

struct CoachingUsageResponse: Codable {
    let usage: CoachingUsage
}

/// The abstract coaching meter — a percentage and a reset date, never dollars,
/// tokens, or the budget ratio (the brief's abstraction principle; the server
/// sends nothing else). Read from the same `Entitlement` the four turn gates
/// read, so the bar and the block can never disagree.
struct CoachingUsage: Codable, Equatable, Sendable {
    /// The percentage contract, **named once**. `usedPercent` is floored and
    /// capped server-side to this range, and so is everything derived from it
    /// (`SubscriptionViewModel.remainingPercent`, `CoachingBudgetGlance`'s
    /// label). Those had all re-typed the bounds as bare literals — one of them
    /// only in prose, over an expression that could not honour it — in a change
    /// where every geometric constant got a named token. The guarantee lives
    /// here, on the type the server's own value arrives as, rather than in
    /// three comments that can drift apart.
    ///
    /// `DesignSystem/DSFraction` deliberately does **not** use this: it clamps
    /// a fraction to 0...1 in its own terms, and the design system must not
    /// take a dependency on the API layer to do it.
    static let percentRange = 0...100

    /// The one clamp, expressed in terms of the range. A client-side guard on a
    /// server-side guarantee: if the cap were ever broken, the ring (which
    /// clamps its own sweep) and the label beside it would otherwise
    /// contradict each other — an empty ring next to "-5% left".
    static func clamped(percent: Int) -> Int {
        min(max(percent, percentRange.lowerBound), percentRange.upperBound)
    }

    /// `percentRange`, floored and capped server-side. `usedPercent == 100` iff
    /// `exhausted`.
    let usedPercent: Int
    let exhausted: Bool
    /// When the meter resets: the subscription period's end, or `nil` on the
    /// free tier, whose allowance is a lifetime credit that never resets. The
    /// server emits the key explicitly as `null`, so this stays optional rather
    /// than being defaulted client-side.
    let resetsAt: Date?
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

// Hashable so a conversation can be a `NavigationPath` destination value:
// opening an existing conversation always pushes (DESIGN.md §7).
struct Conversation: Codable, Sendable, Identifiable, Equatable, Hashable {
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
