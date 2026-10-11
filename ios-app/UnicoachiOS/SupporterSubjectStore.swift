import Foundation
import os

/// The working-student selection: which of a supporter's linked students the
/// app is currently about (RFC 241).
///
/// CLIENT-SIDE, deliberately. Brief 0014 D14 forbids server-side "current
/// student" state, and no server route takes a subject until
/// `family/05/the-subject-seam` — so a selection held anywhere else would be a
/// fact the server had no use for.
///
/// Keyed by the SIGNED-IN USER'S id, for the reason `AppleNameStore` is keyed
/// by Apple's `sub`: two accounts used on one device must not inherit each
/// other's selection, and a bare key would hand the second account the first
/// one's student — a student it may not even be linked to.
protocol SupporterSubjectStore {
    func selectedStudentId(forUserId userId: UUID) -> UUID?
    func select(studentId: UUID, forUserId userId: UUID)
    /// Forgets this user's selection — on account deletion (RFC 252).
    func clear(forUserId userId: UUID)
}

/// Production `SupporterSubjectStore`, on the `UserDefaultsAppleNameStore`
/// precedent: a namespaced key prefix with the user id appended verbatim.
final class UserDefaultsSupporterSubjectStore: SupporterSubjectStore {
    /// Namespaced so the key cannot collide with an unrelated `UserDefaults`
    /// entry.
    private static let keyPrefix = "SupporterSubject.studentId."

    /// The suite this store reads and writes. A constructor parameter with the
    /// production default rather than `UserDefaults.standard` reached for
    /// inside: the app names nothing and gets the one suite it has, while THIS
    /// class's own tests — the only tests of the real key and the parse guard —
    /// run against an isolated suite instead of mutating the device-wide one.
    /// Every other caller still substitutes by conforming to
    /// `SupporterSubjectStore`, which is the seam this parameter does not
    /// replace.
    private let defaults: UserDefaults

    private let logger = Logger.unicoach(category: "SupporterSubjectStore")

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func selectedStudentId(forUserId userId: UUID) -> UUID? {
        guard let raw = defaults.string(forKey: Self.key(userId)) else { return nil }
        guard let studentId = UUID(uuidString: raw) else {
            // An unparseable value is read as NO selection rather than trusted
            // or crashed on: `SupporterSubject.resolve` already has an answer
            // for that, and a string that is not a UUID can only have come from
            // a corrupted or older write. REPORTED rather than discarded — the
            // fallback happens either way, and this string is the only evidence
            // of which write produced it, so without the line the next "it
            // keeps picking the wrong student" is unanswerable. Both values are
            // opaque identifiers, not PII.
            logger.error(
                "Stored supporter subject is not a UUID; falling back: raw=[\(raw, privacy: .public)] userId=[\(userId.uuidString, privacy: .public)]"
            )
            return nil
        }
        return studentId
    }

    func select(studentId: UUID, forUserId userId: UUID) {
        defaults.set(studentId.uuidString, forKey: Self.key(userId))
    }

    func clear(forUserId userId: UUID) {
        defaults.removeObject(forKey: Self.key(userId))
    }

    /// Internal rather than private so the parse guard above can be tested at
    /// the REAL key. A test that rebuilt the key from its own copy of the
    /// prefix would still pass if the prefix changed — it would simply read an
    /// entry nobody had written and find the `nil` it was asserting.
    static func key(_ userId: UUID) -> String { keyPrefix + userId.uuidString }
}

/// The resolution RULE, separate from the STORAGE.
///
/// A free function on a caseless enum rather than a method on the protocol,
/// because it is the half worth testing and it has nothing to do with
/// `UserDefaults`: given a roster and whatever was stored, which student is the
/// app about?
enum SupporterSubject {
    /// The working student, or `nil` when the supporter is linked to nobody.
    ///
    /// The stored selection wins only while it is STILL ON THE ROSTER. A link
    /// that was removed, a selection written before this device was signed into
    /// this account, an id that never parsed — all of them fall back to the
    /// first student, which is the oldest link (the server orders the roster by
    /// the link's creation). Falling back rather than showing nothing matters:
    /// the header names the subject, and a header that named nothing while the
    /// roster had two students would be the switcher's one unreadable state.
    static func resolve(roster: [LinkedStudent], stored: UUID?) -> LinkedStudent? {
        guard let first = roster.first else { return nil }
        guard let stored else { return first }
        return roster.first { $0.studentId == stored } ?? first
    }
}
