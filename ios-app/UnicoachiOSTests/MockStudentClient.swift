import Foundation
@testable import UnicoachiOS

class MockStudentClient: StudentClientProtocol, @unchecked Sendable {
    var createStudentResult: Result<PublicStudent, Error>?
    /// The last body this double was handed. A test that asserts a request was
    /// NOT made needs the count below; one that asserts what was IN it needs
    /// this.
    private(set) var lastCreateStudentRequest: CreateStudentRequest?
    private(set) var createStudentCallCount = 0
    var fetchProfileResult: Result<PublicStudent?, Error>?
    private(set) var fetchProfileCallCount = 0

    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent {
        createStudentCallCount += 1
        lastCreateStudentRequest = request
        if let result = createStudentResult {
            switch result {
            case .success(let student):
                return student
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }

    func fetchProfile() async throws -> PublicStudent? {
        fetchProfileCallCount += 1
        if let result = fetchProfileResult {
            switch result {
            case .success(let student):
                return student
            case .failure(let error):
                throw error
            }
        }
        fatalError("No result configured")
    }
}
