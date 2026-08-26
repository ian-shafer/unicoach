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

/// Body of `PATCH …/college-list/{id}`. `addObservationIds` stays absent for
/// the same reason as above (the server defaults it to []).
struct UpdateCollegeListEntryRequest: Codable, Sendable {
    let version: Int
    let status: CollegeListStatus
    let reasons: String?
}
