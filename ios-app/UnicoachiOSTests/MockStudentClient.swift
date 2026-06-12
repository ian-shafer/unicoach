import Foundation
@testable import UnicoachiOS

class MockStudentClient: StudentClientProtocol, @unchecked Sendable {
    var createStudentResult: Result<PublicStudent, Error>?
    var fetchProfileResult: Result<PublicStudent?, Error>?
    private(set) var fetchProfileCallCount = 0

    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent {
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
