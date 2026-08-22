import Foundation
@testable import UnicoachiOS

@MainActor
class MockSsoSignInProvider: SsoSignInProviding {
    let provider: SsoProvider
    var signInResult: Result<SsoSignInOutcome, Error>?
    private(set) var signInCallCount = 0

    init(provider: SsoProvider = .google) {
        self.provider = provider
    }

    func signIn() async throws -> SsoSignInOutcome {
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
