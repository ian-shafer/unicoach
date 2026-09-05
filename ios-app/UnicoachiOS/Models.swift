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

    /// The app's uniform fallback for a failure with no decodable server
    /// error — a thrown non-`ErrorResponse`, or a body the client could not
    /// read. One value, one home: view models reference it instead of minting
    /// their own copy of the code/message pair.
    static let unexpected = ErrorResponse(
        code: "SERVER_ERROR",
        message: String(localized: "An unexpected error occurred."),
        fieldErrors: nil
    )
}

/// The wire `code` vocabulary the client branches on: the server's `ErrorCode`
/// forms (lowercase, owned by
/// rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt) plus the
/// UPPERCASE codes `APIClient` synthesizes for transport failures. Nothing spans
/// the Swift/Kotlin boundary to derive or check the server forms, so a changed
/// wire string is corrected by hand — here, and until it is migrated onto this
/// enum, in `AppViewModel`, which still compares `error.code` against raw
/// literals. `OnboardingViewModel` reads this enum (RFC 163).
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
    /// A 409 from a college-list mutation whose `version` lost the race
    /// (RFC 91): the chat tool or another device moved the entry. The list
    /// screen recovers with a fresh read (RFC 137).
    case versionConflict = "version_conflict"
    /// The create-path 409: the college is already on the list (RFC 91).
    case conflict = "conflict"
    /// The 404 from a college-list mutation whose entry another device or the
    /// chat tool already removed (RFC 91): the same lost race as
    /// `version_conflict`, resolved the same way — a fresh read (RFC 137).
    case notFound = "not_found"
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

// MARK: - Money-profile domain models (RFC 134 surface, RFC 163 client)

/// One field's write in a money-profile PUT: set a value, decline the field, or
/// clear it back to unanswered. A field with no update is simply `nil` on
/// [UpdateMoneyProfileRequest], which is how a partial write leaves the other
/// fields untouched. This mirrors the server's own `FieldUpdate` sealed
/// interface, and it is the SAME three-way choice — one vocabulary, two
/// languages.
///
/// It is an enum rather than a value plus two booleans because the wire's shape
/// admits states the domain does not: `incomeBand: "48k_to_75k"` together with
/// `incomeBandDeclined: true` is representable in JSON and is a 400. Here it
/// cannot be written down. Onboarding's rule — a sign-up screen may never spend
/// a permanent decision (RFC 163) — is then a fact about which cases that screen
/// constructs, not a pair of tests hoping nobody sets a flag.
enum MoneyProfileFieldUpdate<Value: MoneyProfileFieldValue>: Equatable {
    case set(Value)
    case declined
    case clear
}

/// A value that can be the payload of a [MoneyProfileFieldUpdate], i.e. one that
/// knows its own wire string. The three closed vocabularies conform; nothing
/// else can, so a free-text state code cannot be sent by accident.
protocol MoneyProfileFieldValue: Equatable {
    var wireValue: String { get }
}

/// The `PUT /api/v1/students/me/money-profile` body (RFC 134): an idempotent
/// create-or-update of any subset of fields.
///
/// **The Swift type is narrow; the JSON is exactly what the server declares.**
/// `encode(to:)` is hand-written for that reason: all six booleans are always
/// emitted (`false` unless the matching field says otherwise) and a value key is
/// omitted when there is no value. Jackson runs with
/// `FAIL_ON_UNKNOWN_PROPERTIES` **and** `FAIL_ON_MISSING_CREATOR_PROPERTIES`, so
/// the client may send no key the DTO does not declare and should not lean on a
/// Kotlin default for one it does. The wire is unchanged by the typing above it.
struct UpdateMoneyProfileRequest: Encodable, Equatable {
    var income: MoneyProfileFieldUpdate<IncomeBand>?
    var residency: MoneyProfileFieldUpdate<ResidencyState>?
    /// Where the student would live when they have the choice (RFC 152).
    /// Onboarding does not ask — it is the most situational of the three and the
    /// screen's point is brevity — but the transport carries it, so the surface
    /// is whole.
    var living: MoneyProfileFieldUpdate<LivingPlan>?

    private enum CodingKeys: String, CodingKey {
        case incomeBand, incomeBandDeclined, incomeBandClear
        case residencyState, residencyDeclined, residencyClear
        case livingPlan, livingPlanDeclined, livingPlanClear
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try Self.encode(income, into: &container, value: .incomeBand, declined: .incomeBandDeclined, clear: .incomeBandClear)
        try Self.encode(residency, into: &container, value: .residencyState, declined: .residencyDeclined, clear: .residencyClear)
        try Self.encode(living, into: &container, value: .livingPlan, declined: .livingPlanDeclined, clear: .livingPlanClear)
    }

    /// One field's three keys, written once for all three fields: the value key
    /// only when there is a value, both flags always. A per-field copy of this
    /// is where a missing `false` or a stray `true` would hide.
    private static func encode<Value: MoneyProfileFieldValue>(
        _ update: MoneyProfileFieldUpdate<Value>?,
        into container: inout KeyedEncodingContainer<CodingKeys>,
        value valueKey: CodingKeys,
        declined declinedKey: CodingKeys,
        clear clearKey: CodingKeys
    ) throws {
        switch update {
        case .some(.set(let payload)):
            try container.encode(payload.wireValue, forKey: valueKey)
        case .some(.declined), .some(.clear), .none:
            break
        }
        try container.encode(update == .declined, forKey: declinedKey)
        try container.encode(update == .clear, forKey: clearKey)
    }
}

/// The student-facing money-profile projection (RFC 134): per-field tri-state
/// status, with the value present exactly when the status is `answered`.
///
/// Every closed vocabulary arrives as a raw `String` with a `known…` accessor
/// beside it, on the `PublicSubscription.status` / `ErrorResponse.code`
/// precedent: a bare `enum: String, Codable` property would *throw on decode*
/// the day the server adds a band, a status or a living arrangement.
struct PublicMoneyProfile: Codable, Equatable, Sendable {
    let incomeBandStatus: String
    let incomeBand: String?
    let residencyStatus: String
    let residencyState: String?
    let livingPlanStatus: String
    let livingPlan: String?
    let version: Int
    let createdAt: Date
    let updatedAt: Date
}

extension PublicMoneyProfile {
    /// The profile the server answers with for `request`: every field it set is
    /// `answered` and carries its value, every field it left alone is
    /// `unanswered` and carries none.
    ///
    /// The "value present iff answered" rule is the schema's own CHECK, and it
    /// has one home here rather than one per double — a preview stub, a mock and
    /// a test fixture each spelling `"answered"` / `"unanswered"` for themselves.
    ///
    /// `createdAt`/`updatedAt` have **no defaults**: an epoch-zero placeholder
    /// in production code is a value that looks like data, and every caller
    /// already knows the timestamps it wants.
    static func answering(
        _ request: UpdateMoneyProfileRequest,
        version: Int = 1,
        createdAt: Date,
        updatedAt: Date
    ) -> PublicMoneyProfile {
        func projection<Value: MoneyProfileFieldValue>(_ update: MoneyProfileFieldUpdate<Value>?) -> (status: String, value: String?) {
            switch update {
            case .some(.set(let payload)): return (AnswerStatus.answered.rawValue, payload.wireValue)
            case .some(.declined): return (AnswerStatus.declined.rawValue, nil)
            case .some(.clear), .none: return (AnswerStatus.unanswered.rawValue, nil)
            }
        }
        let income = projection(request.income)
        let residency = projection(request.residency)
        let living = projection(request.living)
        return PublicMoneyProfile(
            incomeBandStatus: income.status,
            incomeBand: income.value,
            residencyStatus: residency.status,
            residencyState: residency.value,
            livingPlanStatus: living.status,
            livingPlan: living.value,
            version: version,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

struct MoneyProfileResponse: Codable, Sendable {
    let profile: PublicMoneyProfile
}

/// The tri-state answer status as the server spells it
/// (`db/.../models/AnswerStatus.kt`). `CaseIterable` so
/// `MoneyProfileVocabularyTests` can check the whole vocabulary against
/// `api-specs/openapi.yaml` rather than a hand-listed subset of it.
enum AnswerStatus: String, Sendable, CaseIterable {
    case unanswered
    case answered
    case declined
}

/// The household income bands as the server spells them
/// (`db/.../models/IncomeBand.kt`). The dollar-range copy a student reads lives
/// at the UI boundary (`OnboardingView`), not here: this type is the wire
/// vocabulary, and display copy on a DTO is how a wire key ends up read aloud.
enum IncomeBand: String, Sendable, CaseIterable, MoneyProfileFieldValue {
    case under30k = "under_30k"
    case k30To48k = "30k_to_48k"
    case k48To75k = "48k_to_75k"
    case k75To110k = "75k_to_110k"
    case over110k = "over_110k"

    var wireValue: String { rawValue }
}

/// The living arrangements as the server spells them
/// (`db/.../models/LivingArrangement.kt`). Onboarding neither asks nor writes it
/// (RFC 163 §5); `PublicMoneyProfile` still decodes it, because the PUT response
/// carries the whole profile. It has a second consumer: the per-college living
/// plan OVERRIDE on the college list carries the same vocabulary
/// (`LivingPlanUpdate` in CollegeListModels.swift, RFC 164/168).
enum LivingPlan: String, Sendable, CaseIterable, MoneyProfileFieldValue {
    case onCampus = "on_campus"
    case offCampus = "off_campus"
    case withFamily = "with_family"

    var wireValue: String { rawValue }
}

extension PublicMoneyProfile {
    var knownIncomeBandStatus: AnswerStatus? { AnswerStatus(rawValue: incomeBandStatus) }
    var knownIncomeBand: IncomeBand? { incomeBand.flatMap(IncomeBand.init(rawValue:)) }
    var knownResidencyStatus: AnswerStatus? { AnswerStatus(rawValue: residencyStatus) }
    var knownLivingPlanStatus: AnswerStatus? { AnswerStatus(rawValue: livingPlanStatus) }
    var knownLivingPlan: LivingPlan? { livingPlan.flatMap(LivingPlan.init(rawValue:)) }
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
/// `CaseIterable` so a rule that must hold for *every* status can be asserted
/// over the vocabulary itself rather than a hand-listed copy of it that a new
/// case would silently fall out of (`offersSubscribe`'s "some door is always
/// open").
enum SubscriptionStatus: String, Sendable, CaseIterable {
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
