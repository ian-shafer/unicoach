import XCTest
@testable import UnicoachiOS

final class ErrorResponseTests: XCTestCase {
    func testReturnsMessageWhenFieldPresent() {
        let response = ErrorResponse(
            code: "VALIDATION",
            message: "Validation failed",
            fieldErrors: [
                FieldError(field: "email", message: "Email is invalid"),
                FieldError(field: "password", message: "Password too short")
            ]
        )

        XCTAssertEqual(response.fieldError(for: "email"), "Email is invalid")
    }

    func testReturnsNilWhenFieldErrorsNil() {
        let response = ErrorResponse(code: "VALIDATION", message: "Validation failed", fieldErrors: nil)

        XCTAssertNil(response.fieldError(for: "email"))
    }

    func testReturnsNilWhenFieldAbsent() {
        let response = ErrorResponse(
            code: "VALIDATION",
            message: "Validation failed",
            fieldErrors: [FieldError(field: "password", message: "Password too short")]
        )

        XCTAssertNil(response.fieldError(for: "email"))
    }

    func testReturnsFirstMatchWhenDuplicated() {
        let response = ErrorResponse(
            code: "VALIDATION",
            message: "Validation failed",
            fieldErrors: [
                FieldError(field: "email", message: "First email error"),
                FieldError(field: "email", message: "Second email error")
            ]
        )

        XCTAssertEqual(response.fieldError(for: "email"), "First email error")
    }

    func testReturnsNilForEmptyFieldErrorsArray() {
        let response = ErrorResponse(code: "VALIDATION", message: "Validation failed", fieldErrors: [])

        XCTAssertNil(response.fieldError(for: "email"))
    }
}
