import Foundation
import os

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

/// The relationship a supporter holds to a student (brief 0014 D22 vocabulary).
///
/// A closed set, named once: a `String` seam accepted any word at all, so a typo
/// compiled, shipped, and came back as something the caller never asked for.
///
/// The words are OWNED by `ed.unicoach.db.models.SupporterRelationship` and the
/// `supporter_student_links.relationship` CHECK, and nothing in either build
/// derives this copy — so the server may publish a fifth word before this app
/// is rebuilt. Decoding is therefore LENIENT (see `init(from:)`): a word this
/// client does not know decodes as [unrecognised], which carries it verbatim.
enum SupporterRelationship: Codable, Equatable, Sendable {
    case parent
    case guardian
    case grandparent
    case supporter
    /// A word the server published after this build shipped, KEPT VERBATIM.
    ///
    /// Not folded onto `supporter`: that is a word the server can genuinely
    /// send, so folding makes "the server said supporter" and "the server said
    /// something we have never heard of" the same value, and no caller can
    /// separate them again. This type is also ENCODED
    /// (`CreateSupporterInviteRequest`), so holding the raw word is what stops
    /// the client putting a word on the wire that the server never used.
    case unrecognised(String)

    private static let logger = Logger.unicoach(category: "SupporterRelationship")

    /// The word as the server states it — the value this decodes from and
    /// encodes back to, for every case including [unrecognised].
    var wireValue: String {
        switch self {
        case .parent: return "parent"
        case .guardian: return "guardian"
        case .grandparent: return "grandparent"
        case .supporter: return "supporter"
        case .unrecognised(let raw): return raw
        }
    }

    /// An unknown word decodes as [unrecognised] rather than throwing.
    ///
    /// A `DecodingError` here is not a field-level failure: it fails the whole
    /// `GET /api/v1/supporters/me` body, which is not an `ErrorResponse`, so
    /// the root state machine falls into its catch-all and the parent has NO
    /// HOME AT ALL — over a word no screen renders. The leniency is therefore
    /// kept; what is not kept is the silence. The word is LOGGED, because a
    /// lenient read nobody can see is a fifth relationship word that never gets
    /// noticed — it is a wire vocabulary member, not PII, so `.public`.
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        switch raw {
        case "parent": self = .parent
        case "guardian": self = .guardian
        case "grandparent": self = .grandparent
        case "supporter": self = .supporter
        default:
            Self.logger.error(
                "Unknown supporter relationship word, kept verbatim as unrecognised: raw=[\(raw, privacy: .public)]"
            )
            self = .unrecognised(raw)
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(wireValue)
    }
}

/// Which side minted the invite (RFC 232).
enum InviterRole: String, Codable, Sendable {
    case student
    case supporter
}

/// `POST /api/v1/supporter-invites` body (RFC 232). `relationship` is optional
/// and defaults to `supporter` on the server (RFC 240): this app names none, and
/// a word nobody stated must not read to a recipient as a claim about a family.
struct CreateSupporterInviteRequest: Codable {
    let relationship: SupporterRelationship?
}

/// The minted supporter invite (RFC 232). The raw token rides only in `inviteUrl`.
///
/// `expiresAt` is a `Date`, like every other timestamp in this file:
/// `APIClient.jsonDecoder` already parses the server's ISO-8601 instants, and a
/// `String` here would leave the first consumer that must say "expires in 3
/// days" hand-parsing it at a call site instead of at the decode.
struct SupporterInviteResponse: Codable {
    let id: UUID
    let inviteUrl: String
    let inviterRole: InviterRole
    let relationship: SupporterRelationship
    let expiresAt: Date
}

// MARK: - The supporter home (RFC 241)

/// Which side of a family link this app is in, as the ROOT STATE MACHINE
/// resolved it — a student who holds a `students` row, or a supporter who holds
/// a `supporters` row (RFC 241).
///
/// Not [InviterRole], which is the wire vocabulary for "who minted THIS invite"
/// — a fact about one invite row that the server answers. This is a fact about
/// the screen the app is currently showing, and it is what decides which
/// SENTENCE a shared invite carries. The two cannot disagree: the client's role
/// here is "does `GET /students/me` answer", and the server derives the
/// inviter's role from the same row.
enum ClientRole {
    case student
    case supporter
}

/// The caller's supporter identity, as both supporter doors return it
/// (RFC 241). One field today; it is a struct rather than a bare `UUID`
/// because the wire nests it and `family/04` has more to say about a supporter.
struct PublicSupporter: Codable, Equatable, Sendable {
    let id: UUID
}

/// One student on a supporter's roster (RFC 241).
///
/// FOUR fields, and the omission is the slice's boundary: no date of birth and
/// no graduation date, because reading a linked student's profile is
/// `family/05/the-subject-seam` and nothing here authorises it. The server's
/// projection cannot supply either, so this type cannot grow them by accident.
///
/// `Identifiable` by the LINK, not by the student: the link is what a later
/// slice unlinks and what a later read authorises against, and two links to one
/// student would otherwise collide in a `ForEach`.
struct LinkedStudent: Codable, Equatable, Identifiable, Sendable {
    let linkId: UUID
    let studentId: UUID
    let name: String
    let relationship: SupporterRelationship
    /// The OTHER adults linked to this student, by name only (RFC 249) — never
    /// the caller, never an email. Always emitted, empty when the caller is the
    /// only one, so it is required rather than optional.
    let otherSupporterNames: [String]

    var id: UUID { linkId }
}

/// The supporter home: who the caller is on the supporter side, and the
/// students they hold an active link to (RFC 241).
///
/// ONE shape for both doors — `POST /api/v1/supporters` answers it with `201`
/// the first time and `200` afterwards, `GET /api/v1/supporters/me` with `200`
/// — so a client that has just made an account holds exactly what a client that
/// re-read it holds.
///
/// `students` is EMPTY for a supporter who has linked nobody. That is the
/// ordinary first state of a parent-first signup, not an error.
///
/// `pendingRequests` counts the caller's requests to connect that a student
/// has not answered yet (RFC 254) — a count and nothing more, because the
/// request names a student the caller is not connected to.
struct SupporterHome: Codable, Equatable, Sendable {
    let supporter: PublicSupporter
    let students: [LinkedStudent]
    let pendingRequests: Int
}

// MARK: - The student's supporters (RFC 249)

/// `GET /api/v1/students/me/supporters`: every adult connected to the student,
/// what any of them can see, and whether the student's Family Cost Report link
/// is live — the one thing a disconnect does NOT turn off.
///
/// `Decodable` only: this body is read and never sent.
struct StudentSupporters: Decodable, Equatable, Sendable {
    let supporters: [LinkedSupporter]
    let ceiling: SupporterCeiling
    let costReportLinkLive: Bool
    /// The adults asking to connect (RFC 254). The server lists only a request
    /// whose claimant has a verified email and a live account.
    let requests: [SupporterRequest]
}

/// One adult asking to connect with the student (RFC 254): someone who opened
/// the student's Family Cost Report link and made an account. The email is
/// here, unlike on `LinkedSupporter`, because it is how the student tells
/// whether they know this person before approving.
///
/// The wire also carries `requestedAt`; nothing shows it, so it is not read.
struct SupporterRequest: Decodable, Equatable, Identifiable, Sendable {
    let requestId: UUID
    let name: String
    let email: String

    var id: UUID { requestId }
}

/// What an approve did (RFC 254). `noLongerPending` is
/// `404 supporter_request_not_found` — its cases are listed where
/// `ServerErrorCode.supporterRequestNotFound` points. Unlike a disconnect's
/// `alreadyGone`, the answer the student wanted may not be the one that holds.
enum ApproveOutcome: Equatable, Sendable {
    case connected(LinkedSupporter)
    case noLongerPending
}

/// What a decline did (RFC 254), with the same `noLongerPending` as
/// `ApproveOutcome`.
enum DeclineOutcome: Equatable, Sendable {
    case declined
    case noLongerPending
}

/// One adult on the student's list. Two fields, and the server's projection
/// selects no more — in particular no email.
struct LinkedSupporter: Decodable, Equatable, Identifiable, Sendable {
    let linkId: UUID
    let name: String

    var id: UUID { linkId }
}

/// What a disconnect did (RFC 249) — the server's two answers. `alreadyGone`
/// is `404 supporter_link_not_found`: not live for this caller, which from a
/// list that showed the link means another device got there first.
enum DisconnectOutcome: Equatable, Sendable {
    case disconnected
    case alreadyGone
}

/// What a connected adult can see, and what stays private, already worded for
/// the student. The words are the server's (`SupporterCeiling` in `:service`,
/// the same home the invite page renders from), so the app carries none.
struct SupporterCeiling: Decodable, Equatable, Sendable {
    let canSee: [String]
    let staysPrivate: [String]
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
    /// The 403 `POST /api/v1/students` answers when the date of birth is under
    /// the age floor (RFC 222). The screen shows the server's message verbatim
    /// in its banner and keeps the student on the form, so nothing BRANCHES on
    /// this code — it is named here because a refusal this client cannot name
    /// is one its tests cannot tell apart from any other 4xx, and because the
    /// enum is this app's record of the wire vocabulary.
    case ageRequirementNotMet = "age_requirement_not_met"
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
    /// The 404 both disconnect doors answer for a link that is not the
    /// caller's, never existed, or is already severed (RFC 249). From a list
    /// that showed the link, it means another device got there first.
    case supporterLinkNotFound = "supporter_link_not_found"
    /// The 404 both request doors answer for a request the student cannot act
    /// on (RFC 254). The cases are listed once, in the description of
    /// `approveStudentSupporterRequest` in `api-specs/openapi.yaml`. From a
    /// list that showed the request, it means the list is out of date.
    case supporterRequestNotFound = "supporter_request_not_found"
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
    /// The student's date of birth as a FULL ISO date, `YYYY-MM-DD`, always
    /// sent (RFC 222). Not the variable-precision string the graduation date
    /// carries: "year only" cannot decide the age floor in the year a student
    /// turns 13, and the server refuses the request without it.
    let dateOfBirth: String
}

struct PublicStudent: Codable, Equatable {
    let id: UUID
    let expectedHighSchoolGraduationDate: String
    /// `YYYY-MM-DD`, non-optional: the column is `NOT NULL` and every row has
    /// one (RFC 222), so no reader here branches on a missing age.
    let dateOfBirth: String
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
enum MoneyProfileFieldUpdate<Value: MoneyProfileFieldValue>: Equatable, Sendable {
    case set(Value)
    case declined
    case clear
}

/// A value that can be the payload of a [MoneyProfileFieldUpdate], i.e. one that
/// knows its own wire string. The three closed vocabularies conform; nothing
/// else can, so a free-text state code cannot be sent by accident.
/// `Sendable` because a request built on one actor is handed to a client that
/// awaits on another — the concurrency checker has to be told these are values,
/// or every screen has to build its request inside the call it makes.
protocol MoneyProfileFieldValue: Equatable, Sendable {
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
struct UpdateMoneyProfileRequest: Encodable, Equatable, Sendable {
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
    /// "I don't know yet" (RFC 205): a real answer at 16, and not silence. It
    /// is legal on the intended-subjects answer and **illegal on every money
    /// field** — `money_profiles` keeps its three-value CHECKs, and the server
    /// enum minus this case is what the three money OpenAPI enums declare. So
    /// this vocabulary is wider than the money one on purpose, and
    /// `MoneyProfileVocabularyTests` asserts both halves of that split rather
    /// than one `entries == spec` equality that could only be satisfied by a
    /// spec that lies.
    case undecided
}

/// The money half of `AnswerStatus`: the three values a money field may hold.
/// Named once, here, because two readers need it — the drift guard against the
/// three money OpenAPI enums, and any money-side code that must not accept
/// `undecided`.
extension AnswerStatus {
    static let moneyCases: [AnswerStatus] = allCases.filter { $0 != .undecided }
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

// MARK: - Served vocabulary models (RFC 165 surface, RFC 171 client)

/// `GET /api/v1/vocabularies` (RFC 165): the closed lists this app renders,
/// keyed by name (`income_bands`, `residency_states`).
///
/// `version` is a content hash of the served document — a plain field, not an
/// ETag, and unrelated to `PublicMoneyProfile.version`, which is a row's OCC
/// counter. Same key name, different resource, different meaning.
///
/// A vocabulary this client has no screen for still decodes: the map is keyed
/// by `String` rather than by a Swift enum, so a name added server-side is
/// carried, not rejected.
struct VocabulariesResponse: Codable, Equatable, Sendable {
    let version: String
    let vocabularies: [String: PublicVocabulary]
}

struct PublicVocabulary: Codable, Equatable, Sendable {
    let entries: [VocabularyEntry]
}

/// One entry of a served vocabulary.
///
/// The wire shape is **flat**: the server's `@JsonAnyGetter` flattens its
/// extras beside `value` and `label`, so an extra is a sibling key and there is
/// never an `extras` object (`VocabulariesResponse.kt:23-31`).
///
/// Only what the screen renders is decoded. Extras are an open set by design,
/// so an unknown one is ignored rather than failing the whole document — a
/// picker that refuses to render because the server started serving a new
/// attribute is the failure this shape prevents. Swift's synthesized
/// `Decodable` already ignores unknown keys, and
/// `VocabularyClientTests.testAnUnknownExtraKeyIsIgnoredRatherThanFatal` is
/// what keeps that true; the point is that nothing here may be made required
/// later without re-reading this comment.
///
/// `jurisdictionKind` is deliberately **not** decoded: this screen's answer to
/// "do not call Guam a state" is its own field label ("State or territory of
/// residence"), so the key had no reader and a decoded field with no reader is
/// kept alive only by its own tests.
struct VocabularyEntry: Codable, Equatable, Sendable {
    let value: String
    let label: String
}

extension VocabulariesResponse {
    /// The names this app renders. The decoded map stays `String`-keyed — a
    /// name added server-side is data, not a decode failure — but a name this
    /// app can ask for is closed, so a misspelled lookup does not compile.
    ///
    /// The raw values are the server's own
    /// (`VocabularyService.INCOME_BANDS` / `RESIDENCY_STATES`), and
    /// `VocabularyNameTests` reads them out of that file so a rename fails a
    /// test instead of silently degrading the screen to the fallback lists.
    enum Name: String {
        case incomeBands = "income_bands"
        case residencyStates = "residency_states"
    }

    /// The entries served under `name`, or `nil` when the server did not serve
    /// that vocabulary — which the screen treats exactly as a failed fetch:
    /// fall back to the shipped list and say the list may be incomplete.
    func entries(_ name: Name) -> [VocabularyEntry]? {
        vocabularies[name.rawValue]?.entries
    }
}

// MARK: - Intended-subject domain models (RFC 205)

/// One subject of OUR taxonomy (`db/data/subjects.json` -> the `subjects`
/// table), as both `GET /api/v1/subjects` and the intended-subjects answer
/// serve it: `slug` is identity and is what the client sends back, `name` is
/// the label and may be renamed server-side without touching a stored answer
/// (RFC 205 D38/D39).
///
/// No federal code appears here and none may be added: a 4-digit code cannot
/// represent a real answer (`neuroscience` spans three prefixes at two
/// grains), which is why the student's answer references the slug alone.
struct PublicSubject: Codable, Equatable, Sendable, Identifiable {
    let slug: String
    let name: String

    /// `slug` is the primary key server-side, so it is the identity here too.
    var id: String { slug }
}

/// `GET /api/v1/subjects?q=` (RFC 205 §5). `q` is optional — the taxonomy is a
/// closed authored list small enough to browse, and a picker that shows nothing
/// until the student types is a worse picker — so this response is also what an
/// unqueried list looks like.
struct SubjectSearchResponse: Codable, Equatable, Sendable {
    let subjects: [PublicSubject]
}

/// The student's whole answer to "what do you want to study?" (RFC 205 §6).
///
/// `status` arrives as a raw `String` with a `knownStatus` accessor beside it,
/// on the `PublicSubscription.status` / `PublicMoneyProfile` precedent: a bare
/// `enum: String, Codable` property would *throw on decode* the day the server
/// adds a fifth state.
///
/// `subjects` is **always emitted** by the server, empty when the status is not
/// `answered`, so it is not optional here. The "values iff answered" rule is
/// the schema's own deferred constraint trigger; this type does not re-derive
/// it, it just carries what the server said.
struct PublicIntendedSubjects: Codable, Equatable, Sendable {
    /// The cap the server enforces in the service AND as a deferred constraint
    /// trigger (RFC 205 §2). Named once here because the screen needs it to
    /// know when to stop offering "Add a subject"; it is a mirror of the
    /// server's rule, never the authority for it — a fourth subject sent anyway
    /// is a 400 the screen surfaces.
    static let maxSubjects = 3

    let status: String
    let subjects: [PublicSubject]

    var knownStatus: AnswerStatus? { AnswerStatus(rawValue: status) }
}

struct IntendedSubjectsResponse: Codable, Equatable, Sendable {
    let intendedSubjects: PublicIntendedSubjects
}

/// The `PUT /api/v1/students/me/intended-subjects` body (RFC 205 §3/§6): the
/// student's WHOLE answer, in one of three shapes. There is no add-one, no
/// remove-one and no clear — a caller can never write `unanswered`, which is
/// only ever the state of a student who was never asked.
///
/// An enum rather than three optional properties because the wire admits states
/// the domain does not: `{"subjects":["nursing"],"declined":true}` is
/// representable JSON and is a pooled 400. Here it cannot be written down.
///
/// **Exactly one key is emitted.** That is the server's "at most one of these
/// keys" rule taken literally rather than the money profile's always-emit-the-
/// booleans convention: this route counts the keys that are PRESENT, and a
/// `"declined": false` beside a subject list would be a second key present.
struct UpdateIntendedSubjectsRequest: Encodable, Equatable, Sendable {
    /// Removing the last subject is `.subjects([])`, and the server answers
    /// `undecided` — never `unanswered` (RFC 205 D17). The client does not
    /// decide that; it just submits the empty set.
    enum Answer: Equatable, Sendable {
        case subjects([String])
        case declined
        case undecided
    }

    let answer: Answer

    init(_ answer: Answer) {
        self.answer = answer
    }

    private enum CodingKeys: String, CodingKey {
        case subjects, declined, undecided
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch answer {
        case .subjects(let slugs):
            try container.encode(slugs, forKey: .subjects)
        case .declined:
            try container.encode(true, forKey: .declined)
        case .undecided:
            try container.encode(true, forKey: .undecided)
        }
    }
}

// MARK: - Sharing-permission domain models (RFC 248)

/// A piece of the student's data that people they share with may be allowed to
/// see. The raw values are the server's own (`SharingPermission.kt`), and the
/// value is the path segment a write names.
enum SharingPermission: String, Sendable, CaseIterable {
    case intendedSubjects = "intended_subjects"
}

/// The two answers a student can GIVE. "Never asked" is not one of them: it is
/// the absence of an answer, so it has no case here and cannot be written
/// (the server refuses it with a 400 as well).
enum SharingChoice: String, Sendable {
    case granted
    case refused
}

/// The status vocabulary the server READS back: the two answers plus
/// `not_asked`, the wire word for "no answer stored".
enum SharingPermissionStatus: String, Sendable {
    case notAsked = "not_asked"
    case granted
    case refused
}

/// One permission and the student's answer to it. Both fields are raw strings
/// with `known…` accessors, on the `PublicIntendedSubjects` precedent: a bare
/// `enum: String, Codable` would throw on decode the day the server adds a
/// permission or a status.
struct PublicSharingPermission: Codable, Equatable, Sendable {
    let permission: String
    let status: String

    var knownPermission: SharingPermission? { SharingPermission(rawValue: permission) }
    var knownStatus: SharingPermissionStatus? { SharingPermissionStatus(rawValue: status) }
}

/// `GET /api/v1/students/me/sharing-permissions`: one entry per permission the
/// server knows, a never-asked one included as `not_asked`.
struct SharingPermissionsResponse: Codable, Equatable, Sendable {
    let permissions: [PublicSharingPermission]
}

/// The `PUT /api/v1/students/me/sharing-permissions/{permission}` body.
struct UpdateSharingPermissionRequest: Encodable, Equatable, Sendable {
    let status: String

    init(_ choice: SharingChoice) {
        status = choice.rawValue
    }
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

// MARK: - The parent's coaching coverage (RFC 244)

/// `GET /api/v1/supporters/me/coaching-usage` 200 body (RFC 244): the same
/// abstract meter the student route publishes, plus the one thing a parent's
/// home has to say that a student's does not — WHICH allowance is being
/// metered.
///
/// No envelope type beside it, unlike `CoachingUsageResponse`: that one exists
/// because the student body nests a single field, and here the body IS this
/// shape.
///
/// `Decodable` rather than `Codable`: this body is read and never sent, and an
/// encode path nothing calls is one more thing that has to stay true.
struct SupporterCoachingUsage: Decodable, Equatable, Sendable {
    let usage: CoachingUsage
    let coverage: CoachingCoverage
}

/// WHO is paying, as the server states it (RFC 244) — a struct rather than a
/// bare field on the body, because the wire nests it.
struct CoachingCoverage: Decodable, Equatable, Sendable {
    let kind: CoachingCoverageKind
}

/// The closed wire vocabulary of `CoachingCoverage.kind`, owned by
/// `ed.unicoach.rest.models.CoachingCoverageKind`: the account holds its own
/// subscription, or it is on the one-time free allowance every account gets.
/// There is deliberately no "covered by somebody else" member — no account
/// rides another's subscription (RFC 244).
///
/// Decoding is LENIENT, the `SupporterRelationship` precedent: a word this
/// build has never heard of decodes as [unrecognised] and is LOGGED, rather
/// than throwing and failing a body whose meter is perfectly readable.
///
/// **What [unrecognised] must not do is pick a default.** It is not folded onto
/// `freeAllowance`, the commoner answer: that would put "You have a free
/// allowance to start with." on screen for an account the server said something
/// else about. `SupporterCoachingViewModel` renders it as the unavailable line
/// instead — absence and unreadability are decided separately and happen to be
/// said the same way, rather than being the same value.
enum CoachingCoverageKind: Decodable, Equatable, Sendable {
    case ownSubscription
    case freeAllowance
    /// A kind the server published after this build shipped, kept verbatim so
    /// the log line can name it.
    case unrecognised(String)

    private static let logger = Logger.unicoach(category: "CoachingCoverageKind")

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        switch raw {
        case "own_subscription": self = .ownSubscription
        case "free_allowance": self = .freeAllowance
        default:
            Self.logger.error(
                "Unknown coaching coverage kind, kept verbatim as unrecognised: raw=[\(raw, privacy: .public)]"
            )
            self = .unrecognised(raw)
        }
    }
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
