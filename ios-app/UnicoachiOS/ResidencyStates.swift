import Foundation

/// One offered state of residence: a USPS code the server accepts, paired with
/// the name a student reads.
///
/// `init` is `fileprivate`, so the only values that exist are the ones
/// [ResidencyStates.offered] lists. That is the point of the type: the money
/// profile's residency answer used to be a `String?`, where `"XX"`, `"ca"` and
/// a ZIP code all compiled and only the server said no — a 400 the student
/// cannot act on. An unrepresentable wrong answer needs no validation.
struct ResidencyState: Hashable, Identifiable, MoneyProfileFieldValue {
    /// The two-letter USPS code, as College Scorecard's `STABBR` spells it.
    let code: String
    /// The state's name, as the menu reads it.
    let name: String

    var id: String { code }
    var wireValue: String { code }

    fileprivate init(_ code: String, _ name: String) {
        self.code = code
        self.name = name
    }

    /// A jurisdiction the **server** published (RFC 165), accepted only in the
    /// exact shape this type is: the two-letter USPS code, uppercase ASCII.
    ///
    /// Construction stays gated exactly as the `fileprivate` init gates it:
    /// there is still no initializer from a bare `String`, so a hand-typed
    /// `"XX"` does not compile. The shape check is a backstop on the decoded
    /// entry, not a second source of truth — the server's rule is membership of
    /// `MoneyProfileService.USPS_STATE_CODES`, which this app deliberately does
    /// not copy — and it refuses a malformed served code here rather than
    /// letting it become a menu row whose write the server answers with a 400
    /// the family cannot act on. This is how the details screen offers all 59
    /// jurisdictions while this file's menu offers 51 (RFC 171).
    init?(served entry: VocabularyEntry) {
        guard entry.value.count == 2,
              entry.value.allSatisfy({ $0.isASCII && $0.isUppercase }) else { return nil }
        self.init(entry.value, entry.label)
    }
}

/// The residency menu this app offers (RFC 163 §4).
///
/// It lives here rather than in `Models.swift` because it is not a wire
/// contract: the server's vocabulary is the wider College Scorecard `STABBR`
/// set, and this is a reading menu built from part of it. What the wire owns is
/// the two-letter code, which `ResidencyState.wireValue` carries.
///
/// The menu offers the 50 states plus DC and NOT the territories and freely
/// associated states the server also accepts (AS FM GU MH MP PR PW VI).
/// Offering fewer than the server accepts is safe — a territory holder can
/// still set the answer in chat — where offering more would be a 400 with no
/// recourse. `ResidencyStateTableTests` reads the server's set out of
/// `MoneyProfileService.kt` and asserts membership, so this list cannot drift
/// past it in silence.
enum ResidencyStates {
    /// The 50 states plus the District of Columbia, ordered by state name —
    /// the order a student reads the menu in. USPS-code order would be the
    /// server's convenience, not theirs.
    static let offered: [ResidencyState] = [
        ResidencyState("AL", "Alabama"),
        ResidencyState("AK", "Alaska"),
        ResidencyState("AZ", "Arizona"),
        ResidencyState("AR", "Arkansas"),
        ResidencyState("CA", "California"),
        ResidencyState("CO", "Colorado"),
        ResidencyState("CT", "Connecticut"),
        ResidencyState("DE", "Delaware"),
        ResidencyState("DC", "District of Columbia"),
        ResidencyState("FL", "Florida"),
        ResidencyState("GA", "Georgia"),
        ResidencyState("HI", "Hawaii"),
        ResidencyState("ID", "Idaho"),
        ResidencyState("IL", "Illinois"),
        ResidencyState("IN", "Indiana"),
        ResidencyState("IA", "Iowa"),
        ResidencyState("KS", "Kansas"),
        ResidencyState("KY", "Kentucky"),
        ResidencyState("LA", "Louisiana"),
        ResidencyState("ME", "Maine"),
        ResidencyState("MD", "Maryland"),
        ResidencyState("MA", "Massachusetts"),
        ResidencyState("MI", "Michigan"),
        ResidencyState("MN", "Minnesota"),
        ResidencyState("MS", "Mississippi"),
        ResidencyState("MO", "Missouri"),
        ResidencyState("MT", "Montana"),
        ResidencyState("NE", "Nebraska"),
        ResidencyState("NV", "Nevada"),
        ResidencyState("NH", "New Hampshire"),
        ResidencyState("NJ", "New Jersey"),
        ResidencyState("NM", "New Mexico"),
        ResidencyState("NY", "New York"),
        ResidencyState("NC", "North Carolina"),
        ResidencyState("ND", "North Dakota"),
        ResidencyState("OH", "Ohio"),
        ResidencyState("OK", "Oklahoma"),
        ResidencyState("OR", "Oregon"),
        ResidencyState("PA", "Pennsylvania"),
        ResidencyState("RI", "Rhode Island"),
        ResidencyState("SC", "South Carolina"),
        ResidencyState("SD", "South Dakota"),
        ResidencyState("TN", "Tennessee"),
        ResidencyState("TX", "Texas"),
        ResidencyState("UT", "Utah"),
        ResidencyState("VT", "Vermont"),
        ResidencyState("VA", "Virginia"),
        ResidencyState("WA", "Washington"),
        ResidencyState("WV", "West Virginia"),
        ResidencyState("WI", "Wisconsin"),
        ResidencyState("WY", "Wyoming"),
    ]
}
