import Foundation

/// Shared, range-spanning, *boundary-faithful* test fixtures.
///
/// Two rules from the `test` skill drive this helper:
///   1. **Boundary fidelity** — fixtures must match what the real peer emits. The
///      REST server serializes `Instant` via Jackson's `JavaTimeModule`, so
///      `createdAt`/`updatedAt` go out as ISO-8601 strings with fractional
///      seconds and a trailing `Z` — NOT the numeric timestamps Swift's default
///      `Date` coding produces. `serverTimestamp` reproduces the real shape.
///   2. **Randomized across the valid range** — graduation dates are emitted at a
///      random valid precision (`YYYY` | `YYYY-MM` | `YYYY-MM-DD`) so the whole
///      domain is exercised over runs, not one hand-picked value.
///
/// Every builder is driven by an explicit `seed` and prints it, so a failure
/// produced by a random draw can be replayed exactly by pinning the seed.
enum RandomFixtures {
    /// Deterministic SplitMix64 generator — same seed ⇒ same sequence ⇒ replayable.
    struct SeededGenerator: RandomNumberGenerator {
        private var state: UInt64
        init(seed: UInt64) { state = seed }
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    /// A fresh seed for a run, suitable to pass into the builders below.
    static func freshSeed() -> UInt64 { UInt64.random(in: .min ... .max) }

    /// Random valid `expectedHighSchoolGraduationDate` at a random precision,
    /// matching the wire regex `^\d{4}(-\d{2}(-\d{2})?)?$`.
    static func graduationDate(using gen: inout SeededGenerator) -> String {
        let year = Int.random(in: 2024...2035, using: &gen)
        switch Int.random(in: 0...2, using: &gen) {
        case 0:
            return String(format: "%04d", year)
        case 1:
            return String(format: "%04d-%02d", year, Int.random(in: 1...12, using: &gen))
        default:
            let month = Int.random(in: 1...12, using: &gen)
            let day = Int.random(in: 1...daysInMonth(year: year, month: month), using: &gen)
            return String(format: "%04d-%02d-%02d", year, month, day)
        }
    }

    /// A server-realistic UTC timestamp string as Jackson emits for `Instant`:
    /// microsecond fractional seconds, trailing `Z`. This is the exact shape that
    /// a strategy-less `JSONDecoder` (Swift's `.deferredToDate` default) cannot
    /// parse — i.e. the real boundary the bug lived at.
    static func serverTimestamp(using gen: inout SeededGenerator) -> String {
        let year = Int.random(in: 2024...2026, using: &gen)
        let month = Int.random(in: 1...12, using: &gen)
        let day = Int.random(in: 1...daysInMonth(year: year, month: month), using: &gen)
        let hour = Int.random(in: 0...23, using: &gen)
        let minute = Int.random(in: 0...59, using: &gen)
        let second = Int.random(in: 0...59, using: &gen)
        let micros = Int.random(in: 0...999_999, using: &gen)
        return String(
            format: "%04d-%02d-%02dT%02d:%02d:%02d.%06dZ",
            year, month, day, hour, minute, second, micros
        )
    }

    /// Raw JSON body for a `StudentResponse`, built from the REAL wire formats
    /// (ISO-8601 string timestamps + variable-precision graduation date) rather
    /// than Swift's default `Date` encoding. Prints the seed and values so a
    /// failing draw is replayable.
    static func studentResponseJSON(
        seed: UInt64
    ) -> (data: Data, id: UUID, gradDate: String, createdAt: String, updatedAt: String) {
        var gen = SeededGenerator(seed: seed)
        let id = UUID()
        let grad = graduationDate(using: &gen)
        let created = serverTimestamp(using: &gen)
        let updated = serverTimestamp(using: &gen)
        let json = """
        {"student":{"id":"\(id.uuidString)","expectedHighSchoolGraduationDate":"\(grad)",\
        "version":1,"createdAt":"\(created)","updatedAt":"\(updated)"}}
        """
        print("[RandomFixtures] studentResponseJSON seed=\(seed) grad=\(grad) createdAt=\(created) updatedAt=\(updated)")
        return (Data(json.utf8), id, grad, created, updated)
    }

    private static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        case 2: return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0) ? 29 : 28
        default: return 31
        }
    }
}
