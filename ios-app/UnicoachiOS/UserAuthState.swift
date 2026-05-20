import Foundation

enum UserAuthState: Equatable {
    case loading
    case unauthenticated
    case authenticated(PublicUser)
    case serverError
    case noConnectivity

    static func == (lhs: UserAuthState, rhs: UserAuthState) -> Bool {
        switch (lhs, rhs) {
        case (.loading, .loading),
             (.unauthenticated, .unauthenticated),
             (.serverError, .serverError),
             (.noConnectivity, .noConnectivity):
            return true
        case (.authenticated(let lhsUser), .authenticated(let rhsUser)):
            return lhsUser.id == rhsUser.id
        default:
            return false
        }
    }
}
