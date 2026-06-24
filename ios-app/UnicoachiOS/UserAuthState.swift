import Foundation

enum UserAuthState: Equatable {
    case loading
    case unauthenticated
    case onboarding(PublicUser)
    case authenticated(PublicUser)
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
        default:
            return false
        }
    }
}
