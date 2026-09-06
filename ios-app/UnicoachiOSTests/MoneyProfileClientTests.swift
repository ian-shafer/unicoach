import XCTest
@testable import UnicoachiOS

/// `UpdateMoneyProfileRequest` is asserted at the **wire**, not through a Swift
/// round-trip: the point of these tests is which keys leave the device, and a
/// decode into the same struct could never see an extra or a missing one. So the
/// body is read as raw JSON.
class MoneyProfileClientTests: XCTestCase {
    var moneyProfileClient: MoneyProfileClient!
    var session: URLSession!

    /// The one way a test names a state: the type has a `fileprivate` init, so
    /// an offered value is the only value there is.
    private let california = ResidencyStates.offered.first { $0.code == "CA" }!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        session = URLSession(configuration: config)
        let apiClient = APIClient(baseURL: URL(string: "http://localhost:8080")!, session: session)
        moneyProfileClient = MoneyProfileClient(apiClient: apiClient)
    }

    private func makeProfile(request: UpdateMoneyProfileRequest) -> PublicMoneyProfile {
        PublicMoneyProfile.answering(
            request,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            updatedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }

    private func encodedResponse(_ profile: PublicMoneyProfile) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(MoneyProfileResponse(profile: profile))
    }

    private func jsonObject(_ data: Data?) throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: data ?? Data())
        return try XCTUnwrap(object as? [String: Any])
    }

    func testFetchSendsGetToTheMoneyProfilePathAndDecodesTheProfile() async throws {
        let expected = makeProfile(request: UpdateMoneyProfileRequest(residency: .set(california)))
        let responseData = try encodedResponse(expected)

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students/me/money-profile")
            XCTAssertEqual(request.httpMethod, "GET")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let result = try await moneyProfileClient.fetch()
        XCTAssertEqual(result, expected)
    }

    /// The `404` the server answers before the first write is the benign
    /// "no profile yet" state, and only the READ verb may read it that way: the
    /// same status on the `PUT` means the owning student row is gone. Both
    /// carry `code: "not_found"`, so this mapping can only live on the verb.
    func testFetchMapsTheNotFoundBeforeTheFirstWriteToNil() async throws {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 404, httpVersion: nil, headerFields: nil)!
            return (response, Data(#"{"code":"not_found","message":"No money profile yet"}"#.utf8))
        }

        let result = try await moneyProfileClient.fetch()
        XCTAssertNil(result)
    }

    /// The other side of the same coin: a `404` on the WRITE is a real fault and
    /// must still throw, or a lost student row would read as a saved answer.
    func testUpdateNotFoundStillThrows() async {
        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 404, httpVersion: nil, headerFields: nil)!
            return (response, Data(#"{"code":"not_found","message":"Owning student not found"}"#.utf8))
        }

        do {
            _ = try await moneyProfileClient.update(UpdateMoneyProfileRequest(income: .declined))
            XCTFail("a 404 on the PUT is a fault, not an empty profile")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.status, 404)
            XCTAssertEqual(error.message, "Owning student not found")
        } catch {
            XCTFail("unexpected error type: [\(error)]")
        }
    }

    func testUpdateSendsPutToTheMoneyProfilePath() async throws {
        let request = UpdateMoneyProfileRequest(residency: .set(california))
        let expected = makeProfile(request: request)
        let responseData = try encodedResponse(expected)

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/students/me/money-profile")
            XCTAssertEqual(request.httpMethod, "PUT")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        // The WHOLE decoded value, not one field: a field-by-field assertion
        // passes while a neighbouring key silently decodes wrong.
        let result = try await moneyProfileClient.update(request)
        XCTAssertEqual(result, expected)
    }

    /// The test that catches an accidental `*Declined` / `*Clear`, and an extra
    /// key the server's `FAIL_ON_UNKNOWN_PROPERTIES` would reject: a state-only
    /// write encodes EXACTLY the six flags (all false) plus `residencyState`.
    ///
    /// The assertions are unchanged from when the request was three `String?`s
    /// and six `Bool`s. That is the point: the Swift type became an ADT, and the
    /// bytes did not move.
    func testResidencyOnlyBodyEncodesExactlyTheExpectedKeys() async throws {
        let request = UpdateMoneyProfileRequest(residency: .set(california))
        let responseData = try encodedResponse(makeProfile(request: request))

        MockURLProtocol.requestHandler = { request in
            let body = try self.jsonObject(request.resolvedBody)
            XCTAssertEqual(
                Set(body.keys),
                [
                    "residencyState",
                    "incomeBandDeclined", "incomeBandClear",
                    "residencyDeclined", "residencyClear",
                    "livingPlanDeclined", "livingPlanClear",
                ]
            )
            XCTAssertEqual(body["residencyState"] as? String, "CA")
            XCTAssertNil(body["incomeBand"], "an unset value must be OMITTED, not sent as null")
            XCTAssertNil(body["livingPlan"], "onboarding never writes a living plan")
            for flag in ["incomeBandDeclined", "incomeBandClear", "residencyDeclined",
                         "residencyClear", "livingPlanDeclined", "livingPlanClear"] {
                XCTAssertEqual(body[flag] as? Bool, false, "[\(flag)] must be sent, and must be false")
            }
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        // The awaited call returning is what proves the assertions above ran.
        let profile = try await moneyProfileClient.update(request)
        XCTAssertEqual(profile.residencyState, "CA")
    }

    func testIncomeOnlyBodyOmitsResidency() async throws {
        let request = UpdateMoneyProfileRequest(income: .set(.k48To75k))
        let responseData = try encodedResponse(makeProfile(request: request))

        MockURLProtocol.requestHandler = { request in
            let body = try self.jsonObject(request.resolvedBody)
            XCTAssertEqual(body["incomeBand"] as? String, "48k_to_75k")
            XCTAssertNil(body["residencyState"])
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let profile = try await moneyProfileClient.update(request)
        XCTAssertEqual(profile.incomeBand, "48k_to_75k")
    }

    /// The three cases of `MoneyProfileFieldUpdate` at the wire. `declined` and
    /// `clear` are not reachable from onboarding — that is the type's job — but
    /// they are reachable from the transport, and each must set exactly one flag
    /// and send no value.
    func testDeclineAndClearEachSetExactlyTheirOwnFlag() async throws {
        let request = UpdateMoneyProfileRequest(income: .declined, residency: .clear)
        let responseData = try encodedResponse(makeProfile(request: request))

        MockURLProtocol.requestHandler = { request in
            let body = try self.jsonObject(request.resolvedBody)
            XCTAssertNil(body["incomeBand"], "a declined field sends no value")
            XCTAssertNil(body["residencyState"], "a cleared field sends no value")
            XCTAssertEqual(body["incomeBandDeclined"] as? Bool, true)
            XCTAssertEqual(body["incomeBandClear"] as? Bool, false)
            XCTAssertEqual(body["residencyClear"] as? Bool, true)
            XCTAssertEqual(body["residencyDeclined"] as? Bool, false)
            XCTAssertEqual(body["livingPlanDeclined"] as? Bool, false)
            XCTAssertEqual(body["livingPlanClear"] as? Bool, false)
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, responseData)
        }

        let profile = try await moneyProfileClient.update(request)
        XCTAssertEqual(profile.knownIncomeBandStatus, .declined)
    }

    func testUpdateDecodesStatusesAsRawStringsWithKnownAccessors() async throws {
        let fixture = RandomFixtures.moneyProfileResponseJSON(seed: RandomFixtures.freshSeed())

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, fixture.data)
        }

        let profile = try await moneyProfileClient.update(
            UpdateMoneyProfileRequest(residency: .set(california))
        )

        XCTAssertEqual(profile.incomeBand, fixture.incomeBand)
        XCTAssertEqual(profile.residencyState, fixture.residencyState)
        XCTAssertEqual(profile.livingPlan, fixture.livingPlan)
        XCTAssertEqual(profile.version, fixture.version)
        // Every status the server sends today is one this client recognizes.
        XCTAssertNotNil(profile.knownIncomeBandStatus)
        XCTAssertNotNil(profile.knownResidencyStatus)
        XCTAssertNotNil(profile.knownLivingPlanStatus)
        XCTAssertEqual(profile.knownIncomeBand?.rawValue, fixture.incomeBand)
        XCTAssertEqual(profile.knownLivingPlan?.rawValue, fixture.livingPlan)
    }

    /// The reason the raw-string convention exists: a band this client has never
    /// heard of must decode, and simply not be recognized.
    func testUnknownIncomeBandDecodesAndYieldsNilFromKnownAccessor() async throws {
        let json = """
        {"profile":{"incomeBandStatus":"answered","incomeBand":"over_250k",\
        "residencyStatus":"unanswered","residencyState":null,\
        "livingPlanStatus":"unanswered","livingPlan":null,\
        "version":2,"createdAt":"2025-01-07T22:16:27.092942Z","updatedAt":"2025-01-07T22:16:27Z"}}
        """

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(json.utf8))
        }

        let profile = try await moneyProfileClient.update(
            UpdateMoneyProfileRequest(income: .set(.over110k))
        )
        XCTAssertEqual(profile.incomeBand, "over_250k")
        XCTAssertNil(profile.knownIncomeBand)
        XCTAssertEqual(profile.knownIncomeBandStatus, .answered)
    }

    func testUpdateValidationFailedSurfacesFieldErrors() async throws {
        let errorPayload = ErrorResponse(
            code: "validation_failed",
            message: "Validation failed",
            fieldErrors: [FieldError(field: "residencyState", message: "Must be a two-letter US state postal code, got: XX")]
        )
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 400, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await moneyProfileClient.update(
                UpdateMoneyProfileRequest(residency: .set(california))
            )
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.knownCode, .validationFailed)
            XCTAssertEqual(
                error.fieldError(for: "residencyState"),
                "Must be a two-letter US state postal code, got: XX"
            )
        }
    }

    /// The sequencing constraint, at the boundary: a PUT before the student row
    /// exists is answered `409 student_profile_required`.
    func testUpdateStudentProfileRequiredSurfacesTheCode() async throws {
        let errorPayload = ErrorResponse(code: "student_profile_required", message: "Student profile required", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 409, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await moneyProfileClient.update(
                UpdateMoneyProfileRequest(residency: .set(california))
            )
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.knownCode, .studentProfileRequired)
            XCTAssertEqual(error.status, 409)
        }
    }

    func testUpdateUnauthorizedSurfacesUnauthorized() async throws {
        let errorPayload = ErrorResponse(code: "unauthorized", message: "Unauthorized", fieldErrors: nil)
        let errorData = try JSONEncoder().encode(errorPayload)

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, errorData)
        }

        do {
            _ = try await moneyProfileClient.update(
                UpdateMoneyProfileRequest(residency: .set(california))
            )
            XCTFail("Should have thrown")
        } catch let error as ErrorResponse {
            XCTAssertEqual(error.knownCode, .unauthorized)
        }
    }
}

// MARK: - Vocabulary drift guards

/// The client transcribes three closed server vocabularies by hand, and nothing
/// in the build derives them. A renamed band is then a `400` the student cannot
/// act on, discovered in production.
///
/// `api-specs/openapi.yaml` is the seam: it enumerates all three, it is the
/// contract both sides are written against, and it is in this repo. Read from
/// source via `#filePath`, on the `StoreKitConfigurationTests` precedent — the
/// thing under test is the checked-in spec, not a copy of it.
final class MoneyProfileVocabularyTests: XCTestCase {
    private func specText() throws -> String {
        let spec = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // UnicoachiOSTests
            .deletingLastPathComponent()   // ios-app
            .deletingLastPathComponent()   // repo root
            .appendingPathComponent("api-specs/openapi.yaml")
        return try String(contentsOf: spec, encoding: .utf8)
    }

    /// Every `enum: [...]` list in the spec, as an array of member arrays.
    ///
    /// **Hand-rolled on purpose**, like the Kotlin scrape below it: no YAML
    /// parser is reachable from this test target, and the flow-style
    /// `enum: [a, b, c]` the spec uses is a shape a line scan reads correctly.
    /// The vacuity guard is that the scrape must find SOME enum at all. A
    /// vocabulary is checked by asking whether the spec declares it ANYWHERE,
    /// which is robust to the schema being renamed or reordered — the failure
    /// this guards is a changed VALUE, not a moved section.
    private func declaredVocabularies(in text: String) -> [[String]] {
        text
            .split(separator: "\n")
            .compactMap { line -> [String]? in
                guard let open = line.firstIndex(of: "["), let close = line.lastIndex(of: "]"),
                      line.contains("enum:"), open < close else { return nil }
                return line[line.index(after: open)..<close]
                    .split(separator: ",")
                    .map { $0.trimmingCharacters(in: .whitespaces) }
            }
    }

    func testTheSpecDeclaresTheVocabulariesThisClientTranscribes() throws {
        let declared = declaredVocabularies(in: try specText()).map(Set.init)
        XCTAssertFalse(declared.isEmpty, "scraped no enum from openapi.yaml — the guard would pass vacuously")

        let cases: [(name: String, members: Set<String>)] = [
            ("IncomeBand", Set(IncomeBand.allCases.map(\.rawValue))),
            ("LivingPlan", Set(LivingPlan.allCases.map(\.rawValue))),
            ("AnswerStatus", Set(AnswerStatus.allCases.map(\.rawValue))),
        ]
        for testCase in cases {
            XCTAssertTrue(
                declared.contains(testCase.members),
                "[\(testCase.name)] is [\(testCase.members.sorted())], which api-specs/openapi.yaml declares nowhere"
            )
        }
    }
}

/// The state table is a client-side menu built from a vocabulary the SERVER
/// owns. Offering a code the server would reject is a 400 the user cannot fix.
///
/// The accepted set is READ OUT of `MoneyProfileService.kt` rather than retyped
/// here: a transcribed copy would only ever compare one client copy to another,
/// and could not fail on the change that matters — the server narrowing its set.
final class ResidencyStateTableTests: XCTestCase {
    /// The eight codes the server accepts and this app does not offer: the
    /// territories and freely associated states. Named here so the expected
    /// server set can be stated exactly, rather than as a floor.
    private let territories: Set<String> = ["AS", "FM", "GU", "MH", "MP", "PR", "PW", "VI"]

    /// `USPS_STATE_CODES` as the service declares it: a concatenation of
    /// space-separated two-letter codes across quoted string fragments.
    ///
    /// **Hand-rolled on purpose.** No Kotlin parser is reachable from an iOS
    /// test target, and the alternative — retyping the list here — is the very
    /// thing this test exists to avoid: a transcribed copy compares one client
    /// copy to another and cannot fail on the change that matters, the server
    /// narrowing its set. The scrape is therefore deliberately paired with an
    /// exact-count assertion below, so a scrape that breaks says so instead of
    /// passing vacuously.
    private func serverAcceptedCodes() throws -> Set<String> {
        let source = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // UnicoachiOSTests
            .deletingLastPathComponent()   // ios-app
            .deletingLastPathComponent()   // repo root
            .appendingPathComponent("service/src/main/kotlin/ed/unicoach/coaching/moneyprofile/MoneyProfileService.kt")
        let text = try String(contentsOf: source, encoding: .utf8)

        guard let declaration = text.range(of: "USPS_STATE_CODES"),
              let terminator = text.range(of: ".split(", range: declaration.upperBound..<text.endIndex) else {
            // NOT an empty set: "the file's shape changed" and "the server
            // accepts nothing" are different facts, and a sentinel would let
            // the first masquerade as the second and pass every membership
            // assertion below.
            throw XCTSkip("could not locate USPS_STATE_CODES in MoneyProfileService.kt — the scrape needs updating")
        }
        let body = text[declaration.upperBound..<terminator.lowerBound]
        return Set(
            body
                .split(whereSeparator: { $0 == " " || $0 == "\"" || $0 == "\n" || $0 == "+" })
                .map(String.init)
                .filter { $0.count == 2 && $0.allSatisfy(\.isUppercase) }
        )
    }

    /// The scrape reads a foreign file's syntax, so it can match PART of it and
    /// make every assertion below vacuous. A floor (`> 50`) would pass on a
    /// partial read, so the expected set is stated exactly: the 51 codes this
    /// app offers plus the eight it does not.
    func testTheServerSetIsExactlyTheOfferedCodesPlusTheTerritories() throws {
        let accepted = try serverAcceptedCodes()
        let expected = Set(ResidencyStates.offered.map(\.code)).union(territories)
        XCTAssertEqual(
            accepted, expected,
            "MoneyProfileService.kt's USPS_STATE_CODES is not what this app expects — either the server "
                + "changed its set, or the scrape did"
        )
        XCTAssertEqual(accepted.count, 59, "50 states + DC + 8 territories")
    }

    func testEveryOfferedCodeIsAcceptedByTheServer() throws {
        let accepted = try serverAcceptedCodes()
        for state in ResidencyStates.offered {
            XCTAssertTrue(
                accepted.contains(state.code),
                "[\(state.code)] (\(state.name)) is offered but the server would reject it"
            )
        }
    }

    func testOfferedCodesAreUniqueAndCoverTheFiftyStatesPlusDC() {
        let codes = ResidencyStates.offered.map(\.code)
        XCTAssertEqual(Set(codes).count, codes.count, "the state table has a duplicate code")
        XCTAssertEqual(codes.count, 51, "50 states plus DC")
        XCTAssertTrue(codes.contains("DC"))
    }
}
