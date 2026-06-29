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

    func fetchProfile() async throws -> PublicStudent? {
        logger.debug("Fetching student profile")
        let (data, response) = try await apiClient.get("/api/v1/students/me")
        if response.statusCode == 404 {
            return nil
        }
        let studentResponse: StudentResponse = try apiClient.decode(data: data, response: response, expectedStatus: 200)
        return studentResponse.student
    }
}
