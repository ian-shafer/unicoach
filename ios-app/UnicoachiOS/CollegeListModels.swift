import Foundation

// MARK: - College-list domain models (RFC 137)
//
// Kept out of Models.swift, which stays auth/conversation focused.

/// The closed status vocabulary, mirrored from the server's DB CHECK. An
/// unknown wire status fails decoding loudly — both ends own the same four
/// values, so a fifth is a contract break, not a display concern.
enum CollegeListStatus: String, Codable, CaseIterable, Sendable {
    case considering
    case applying
    case admitted
    case rejected

    var displayName: String {
        switch self {
        case .considering: return String(localized: "Considering")
        case .applying: return String(localized: "Applying")
        case .admitted: return String(localized: "Admitted")
        case .rejected: return String(localized: "Rejected")
        }
    }
}

/// A conversational citation backing a list entry (RFC 91). Read-only on iOS:
/// the screen displays provenance and never writes it.
struct SupportingObservation: Codable, Hashable, Sendable {
    let id: Int64
    let quote: String
    let utteredAt: Date
}

/// One entry of the student's college list, as the server enriches it
/// (RFC 137): the college's display name rides every success response.
struct CollegeListEntry: Codable, Identifiable, Hashable, Sendable {
    let id: UUID
    let collegeId: UUID
    let collegeName: String
    let status: CollegeListStatus
    let reasons: String?
    let version: Int
    let supportingObservations: [SupportingObservation]
}

/// One row of the college-search picker: `GET /api/v1/colleges?q=…`.
struct CollegeSummary: Codable, Identifiable, Hashable, Sendable {
    let id: UUID
    let name: String
    let city: String
    let state: String
}

// MARK: - Wire DTOs

struct CollegeListResponse: Codable, Sendable {
    let entries: [CollegeListEntry]
}

struct CollegeListEntryResponse: Codable, Sendable {
    let entry: CollegeListEntry
}

struct CollegeSearchResponse: Codable, Sendable {
    let colleges: [CollegeSummary]
}

/// Body of `POST …/college-list`. iOS never sends `observationIds` — citations
/// are conversational provenance (RFC 91/136) — and `status` is omitted too:
/// the server defaults a new entry to `considering`, which is exactly what the
/// add flow means.
struct CreateCollegeListEntryRequest: Codable, Sendable {
    let collegeId: UUID
}

/// The three states `PATCH …/college-list/{id}` accepts for the per-college
/// living-plan override (RFC 164). `keep` emits NEITHER key: on the wire,
/// silence means "leave the stored value alone", and that is the whole reason
/// this type exists rather than a bare `LivingPlan?`. `set` and `clear` are
/// separate cases because the wire admits a state the domain does not —
/// `livingPlan` together with `livingPlanClear: true` is a 400, and here it
/// cannot be written down.
///
/// **Not unified with `MoneyProfileFieldUpdate<LivingPlan>` in Models.swift.**
/// The money wire has a FOURTH state, `declined`, carried by a
/// `livingPlanDeclined` key that the college-list endpoint does not declare —
/// sending it here is a 400. Two endpoints that spell the same field with
/// different key sets are two types, not one type with a footgun.
///
/// **`set` and `clear` deliberately have no production caller yet.** The
/// per-college living-plan picker is a later slice: brief 0007 D3,
/// `product/0007-explicit-profile-view/spec.md:153-157`. They are built and
/// tested now so the wire contract is stated once, at the point the encoder
/// exists. Do not delete them as "unused".
enum LivingPlanUpdate: Equatable, Sendable {
    case keep
    case set(LivingPlan)
    case clear
}

/// Body of `PATCH …/college-list/{id}`. `addObservationIds` stays absent for
/// the same reason as above (the server defaults it to []).
///
/// **`encode(to:)` is hand-written, and the two absences below are the point.**
/// A synthesized encoder emits the same bytes today by accident; a later tidy-up
/// that added `let livingPlan: LivingPlan?` and hand-wrote `encodeNil` for it
/// would re-arm the RFC 164 defect. The rules, stated:
///
/// - `version` and `status` are always encoded: `version` is the OCC token the
///   server rejects the write without, and `status` is what the Save means.
/// - `reasons` uses `encodeIfPresent`: a `nil` OMITS the key, and an omitted
///   `reasons` CLEARS the stored note. That asymmetry with `livingPlan` is
///   deliberate (RFC 164 D4) — it is how the detail screen's Clear button works.
/// - `livingPlan` is silent in `.keep`, so a Save that does not manage the
///   living plan cannot destroy one the coach set.
///
/// There is no hand-written memberwise init: the `.keep` default lives in ONE
/// place, the `CollegeListClientProtocol` extension in CollegeListClient.swift,
/// and Swift's synthesized memberwise init serves the single construction site,
/// which passes `livingPlan` explicitly.
///
/// `Encodable`, not `Codable` like the sibling `CreateCollegeListEntryRequest`
/// above: nothing decodes a request body, and `LivingPlanUpdate` is
/// deliberately not `Decodable`, so the write-only vocabulary cannot leak into
/// a read model.
struct UpdateCollegeListEntryRequest: Encodable, Sendable {
    let version: Int
    let status: CollegeListStatus
    let reasons: String?
    let livingPlan: LivingPlanUpdate

    /// These five strings are the server's, not ours. The published contract is
    /// `api-specs/openapi.yaml`, schema `UpdateCollegeListEntryRequest`, which
    /// owns these key names and the `on_campus`/`off_campus`/`with_family`
    /// value vocabulary. `OpenApiCollegeListUpdateTest.kt` pins exactly two
    /// facets of that schema to the server DTO — that `required` is exactly
    /// `[version, status]`, and that `livingPlanClear` publishes a boolean
    /// default equal to the DTO's own. It pins NEITHER the key names NOR the
    /// three living-plan strings.
    ///
    /// NOTHING mechanical holds these Swift literals to that contract. The
    /// Swift tests pin the Swift bytes, and `CollegeListRoutingTest.kt` pins
    /// the server to hand-copied twins of the same strings; the two copies are
    /// kept equal by REVIEW, not by a compiler. Rename a key in the spec and
    /// this list has to be moved by hand.
    private enum CodingKeys: String, CodingKey {
        case version, status, reasons, livingPlan, livingPlanClear
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encode(status, forKey: .status)
        try container.encodeIfPresent(reasons, forKey: .reasons)
        // The exhaustive `switch` lives HERE, at the serialization boundary,
        // for the reason `UpdateMoneyProfileRequest.encode(to:)` keeps its own
        // encoding there (Models.swift:220-225): it is the one place where
        // "never both keys" can be broken, so it is the one place a 4th case
        // must not compile. That request's private per-field helper DERIVES its
        // flags (`update == .clear`), which is safe there because the money wire
        // ALWAYS emits both booleans — and is exactly what is NOT safe here,
        // where the keys must be ABSENT.
        // Derived `String?`/`Bool` properties on the enum would be lossy —
        // `nil` would mean both `.keep` and `.clear`, `false` both `.keep` and
        // `.set` — leaving the invariant in the encoder anyway and letting a
        // 4th case answer nil/false and silently emit `.keep`.
        switch livingPlan {
        case .keep: break
        case .set(let plan): try container.encode(plan.rawValue, forKey: .livingPlan)
        case .clear: try container.encode(true, forKey: .livingPlanClear)
        }
    }
}
