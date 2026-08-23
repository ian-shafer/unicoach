import XCTest
@testable import UnicoachiOS

/// The finish policy's own test matrix: one test per row of the table in
/// `TransactionRecorder`, each asserting the arm returned **and** whether the
/// transaction was finished. Nothing else in the app may make this decision, so
/// nothing else asserts it.
final class TransactionRecorderTests: XCTestCase {
    private var store: MockSubscriptionStore!
    private var client: MockSubscriptionClient!
    private var recorder: TransactionRecorder!

    private let transaction = StoreTransaction(id: 42, productID: SubscriptionProduct.monthlyIdentifier, jws: "header.payload.signature")

    override func setUp() {
        super.setUp()
        store = MockSubscriptionStore()
        client = MockSubscriptionClient()
        recorder = TransactionRecorder(client: client, store: store)
    }

    private func serverAnswers(_ status: Int, _ code: String) {
        client.result = .failure(ErrorResponse(code: code, message: "m", fieldErrors: nil, status: status))
    }

    private let subscription = PublicSubscription(
        status: "active",
        productId: SubscriptionProduct.monthlyIdentifier,
        currentPeriodEnd: Date(timeIntervalSince1970: 1_773_000_000)
    )

    // MARK: - Recorded

    func testTwoHundredRecordsAndFinishes() async {
        client.result = .success(subscription)

        let outcome = await recorder.record(transaction)

        guard case .recorded(let recorded) = outcome else { return XCTFail("expected .recorded, got [\(outcome)]") }
        XCTAssertEqual(recorded, subscription)
        XCTAssertEqual(store.finished, [transaction])
    }

    func testTheJwsIsPostedUnmodified() async {
        client.result = .success(subscription)

        _ = await recorder.record(transaction)

        XCTAssertEqual(client.posted, ["header.payload.signature"])
    }

    // MARK: - Rejected (terminal — safe to finish)

    func testValidationFailedIsRejectedAndFinished() async {
        serverAnswers(400, "validation_failed")

        let outcome = await recorder.record(transaction)

        guard case .rejected(.server(let error)) = outcome else { return XCTFail("expected .rejected(.server), got [\(outcome)]") }
        XCTAssertEqual(error.knownCode, .validationFailed)
        XCTAssertEqual(store.finished, [transaction])
    }

    func testOwnedByOtherAccountIsRejectedAndFinished() async {
        serverAnswers(409, "subscription_owned_by_other_account")

        let outcome = await recorder.record(transaction)

        guard case .rejected(.server(let error)) = outcome else { return XCTFail("expected .rejected(.server), got [\(outcome)]") }
        XCTAssertEqual(error.knownCode, .subscriptionOwnedByOtherAccount)
        XCTAssertEqual(store.finished, [transaction])
    }

    // MARK: - Deferred (transient — must NOT finish)

    func testServiceUnavailableIsDeferredAndNotFinished() async {
        serverAnswers(503, "service_unavailable")

        let outcome = await recorder.record(transaction)

        guard case .deferred(.server(let error)) = outcome else { return XCTFail("expected .deferred(.server), got [\(outcome)]") }
        XCTAssertEqual(error.knownCode, .serviceUnavailable)
        XCTAssertTrue(store.finished.isEmpty)
    }

    func testSubscriptionNotFoundIsDeferredAndNotFinished() async {
        serverAnswers(404, "subscription_not_found")

        let outcome = await recorder.record(transaction)

        guard case .deferred(.server(let error)) = outcome else { return XCTFail("expected .deferred(.server), got [\(outcome)]") }
        XCTAssertEqual(error.knownCode, .subscriptionNotFound)
        XCTAssertTrue(store.finished.isEmpty)
    }

    func testSessionAndServerProblemsAreDeferredAndNotFinished() async {
        let cases: [(Int, String)] = [
            (401, "unauthorized"),
            (403, "email_not_verified"),
            (409, "student_profile_required"),
            (500, "internal_error"),
            (413, "payload_too_large"),
        ]

        for (status, code) in cases {
            store = MockSubscriptionStore()
            client = MockSubscriptionClient()
            recorder = TransactionRecorder(client: client, store: store)
            serverAnswers(status, code)

            let outcome = await recorder.record(transaction)

            guard case .deferred = outcome else {
                return XCTFail("expected .deferred for status=[\(status)] code=[\(code)], got [\(outcome)]")
            }
            XCTAssertTrue(store.finished.isEmpty, "must not finish on status=[\(status)] code=[\(code)]")
        }
    }

    /// `APIClient` maps a connection-phase failure onto its own `NETWORK_ERROR`
    /// response, so this arrives as a server-shaped failure and defers.
    func testASynthesizedNetworkErrorIsDeferredAndNotFinished() async {
        client.result = .failure(ErrorResponse(code: "NETWORK_ERROR", message: "offline", fieldErrors: nil))

        let outcome = await recorder.record(transaction)

        guard case .deferred(.server(let error)) = outcome else { return XCTFail("expected .deferred(.server), got [\(outcome)]") }
        XCTAssertEqual(error.knownCode, .networkError)
        XCTAssertTrue(store.finished.isEmpty)
    }

    /// An error that is not an `ErrorResponse` at all cannot be classified, so
    /// it defers: the asymmetry between a wasted re-post and a lost purchase
    /// decides every tie. It is carried as the **thrown error**, not as a
    /// fabricated `NETWORK_ERROR` body no server ever sent.
    func testATransportFailureIsDeferredCarryingTheRealCause() async {
        struct Boom: Error {}
        client.result = .failure(Boom())

        let outcome = await recorder.record(transaction)

        guard case .deferred(.transport(let cause)) = outcome else {
            return XCTFail("expected .deferred(.transport), got [\(outcome)]")
        }
        XCTAssertTrue(cause is Boom, "the thrown error itself, not a synthesized response")
        XCTAssertTrue(store.finished.isEmpty)
    }

    // MARK: - A 200 the client could not read

    /// `APIClient.decode` throws `DECODE_ERROR` only **after** the status was
    /// 200 — the server holds the transaction. Deferring would re-post the same
    /// unreadable response on every launch forever, so it is terminal.
    func testAnUndecodableTwoHundredIsRejectedAndFinished() async {
        client.result = .failure(ErrorResponse(code: "DECODE_ERROR", message: "Failed to parse response", fieldErrors: nil))

        let outcome = await recorder.record(transaction)

        guard case .rejected(.server(let error)) = outcome else {
            return XCTFail("expected .rejected(.server), got [\(outcome)]")
        }
        XCTAssertEqual(error.knownCode, .decodeError)
        XCTAssertEqual(store.finished, [transaction], "the server recorded it; finishing is safe")
    }

    /// The actor guarantee: two concurrent records of the same transaction
    /// serialise, and it is finished at most once.
    func testConcurrentRecordsSerialiseAndFinishAtMostOnce() async {
        client.result = .success(subscription)

        // Bound locally: the actor and the transaction are `Sendable`, the
        // XCTestCase is not.
        let recorder = recorder!
        let transaction = transaction
        async let first = recorder.record(transaction)
        async let second = recorder.record(transaction)
        let outcomes = await [first, second]

        for outcome in outcomes {
            guard case .recorded(let recorded) = outcome else { return XCTFail("expected .recorded, got [\(outcome)]") }
            XCTAssertEqual(recorded, subscription)
        }
        XCTAssertEqual(store.finished, [transaction], "finished at most once")
        // Both are posted: `/verify` is idempotent, and re-posting is its
        // documented refresh path.
        XCTAssertEqual(client.posted.count, 2)
    }
}
