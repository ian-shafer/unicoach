import Foundation

enum UserAuthState: Equatable {
    case loading
    case unauthenticated
    case onboarding(PublicUser)
    case authenticated(PublicUser)
    /// Verified, and holding NEITHER a `students` row nor a `supporters` row
    /// (RFC 241): the account exists and nothing yet says what it is for. The
    /// screen asks once, and both answers are ordinary.
    ///
    /// This is the state that used to be `.onboarding` — "no student row" was
    /// read as "student who has not finished onboarding", which put a parent on
    /// a form asking for their own date of birth.
    case roleChoice(PublicUser)
    /// Verified, holding a `supporters` row and no `students` row (RFC 241).
    ///
    /// It carries the home the server answered with, rather than only the user,
    /// so the scene renders the roster it was resolved from — one read, not a
    /// second one the new screen would have to make on appear.
    case supporterHome(PublicUser, SupporterHome)
    /// Authenticated but the user's email is unverified. The server gates
    /// `students/me` until verification, so this state owns the verification
    /// lifecycle and blocks all other navigation. Carries the user whose email
    /// is unverified so the blocked screen can display the address.
    case verificationRequired(PublicUser)
    /// The server reported a genuine 5xx — its problem, retry may help.
    case serverError
    /// Any other unhandled failure (an unrecognized 4xx, or a client-side error
    /// with no HTTP status). Not attributable to the server; usually a missing
    /// handler on our side.
    case unexpectedError
    case noConnectivity

    static func == (lhs: UserAuthState, rhs: UserAuthState) -> Bool {
        switch (lhs, rhs) {
        case (.loading, .loading),
             (.unauthenticated, .unauthenticated),
             (.serverError, .serverError),
             (.unexpectedError, .unexpectedError),
             (.noConnectivity, .noConnectivity):
            return true
        case (.onboarding(let lhsUser), .onboarding(let rhsUser)):
            return lhsUser.id == rhsUser.id
        case (.authenticated(let lhsUser), .authenticated(let rhsUser)):
            return lhsUser.id == rhsUser.id
        case (.verificationRequired(let lhsUser), .verificationRequired(let rhsUser)):
            return lhsUser.id == rhsUser.id
        case (.roleChoice(let lhsUser), .roleChoice(let rhsUser)):
            return lhsUser.id == rhsUser.id
        case (.supporterHome(let lhsUser, let lhsHome), .supporterHome(let rhsUser, let rhsHome)):
            // The HOME is compared too, unlike every case above, because it is
            // state and not just an identity: a supporter whose student
            // accepted an invite is the same user with a different roster, and
            // a `==` that ignored the roster would call the before and after
            // states equal.
            return lhsUser.id == rhsUser.id && lhsHome == rhsHome
        // Every case is listed as a mismatch arm rather than covered by a
        // `default`, so a case ADDED to this enum fails to compile here — the
        // one place a new state can otherwise be forgotten silently, because
        // `default` answers "not equal" for a pair that is equal instead of
        // refusing to build. The root switch that renders these states has no
        // `default` and would have caught it; this one would not have.
        case (.loading, _),
             (.unauthenticated, _),
             (.onboarding, _),
             (.authenticated, _),
             (.roleChoice, _),
             (.supporterHome, _),
             (.verificationRequired, _),
             (.serverError, _),
             (.unexpectedError, _),
             (.noConnectivity, _):
            return false
        }
    }
}
