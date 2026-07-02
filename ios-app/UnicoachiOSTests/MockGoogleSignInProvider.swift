import Foundation
@testable import UnicoachiOS

@MainActor
class MockGoogleSignInProvider: GoogleSignInProviding {
    var signInResult: Result<GoogleSignInOutcome, Error>?
    private(set) var signInCallCount = 0

    func signIn() async throws -> GoogleSignInOutcome {
        signInCallCount += 1
        guard let result = signInResult else {
            fatalError("No result configured")
        }
        switch result {
        case .success(let outcome):
            return outcome
        case .failure(let error):
            throw error
        }
    }
}
