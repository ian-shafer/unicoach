import Foundation
import os

protocol StudentClientProtocol: Sendable {
    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent
    func fetchProfile() async throws -> PublicStudent?
}

class StudentClient: StudentClientProtocol, @unchecked Sendable {
    private let apiClient: APIClient
    private let logger = Logger(subsystem: "coach.uni.UnicoachiOS", category: "StudentClient")

    init(apiClient: APIClient = APIClient()) {
        self.apiClient = apiClient
    }

    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent {
        logger.debug("Creating student profile")
        let (data, response) = try await apiClient.post("/api/v1/students", body: request)
        let studentResponse: StudentResponse = try apiClient.decode(data: data, response: response, expectedStatus: 201)
        return studentResponse.student
    }

    /// Reads the student profile, mapping `404` to `nil`: an account that has
    /// not created a student row yet is a legitimate answer on this read, not a
    /// failure. The mapping is opt-in per call site — `createStudent` keeps
    /// throwing on `404`, where it would mean a genuinely missing owner.
    func fetchProfile() async throws -> PublicStudent? {
        logger.debug("Fetching student profile")
        let studentResponse: StudentResponse? = try await apiClient.getIfPresent("/api/v1/students/me")
        return studentResponse?.student
    }
}
