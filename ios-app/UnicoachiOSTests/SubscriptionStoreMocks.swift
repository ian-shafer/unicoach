import Foundation
@testable import UnicoachiOS

/// The StoreKit seam's test double. It conforms to both halves of the split
/// seam — the app hands `TransactionFinishing` only to `TransactionRecorder`,
/// but a mock has to be able to record what was finished.
///
/// State lives in one `Locked` box (the app's own primitive) rather than a
/// hand-rolled `NSLock` + `withLock` pair per mock: the protocols are
/// `Sendable` and a recorder actor calls into them from its own executor, so
/// the lock is what makes the recorded calls safe to read back from the test.
final class MockSubscriptionStore: SubscriptionStoreProtocol, TransactionFinishing, @unchecked Sendable {
    /// The answers this double gives and the calls it recorded, in one box.
    private struct Recorded {
        var productResult: Result<StoreProduct?, Error> = .success(nil)
        var purchaseResult: Result<PurchaseResult, Error> = .success(.userCancelled)
        var entitlements: [StoreTransaction] = []
        var syncResult: RestoreResult = .synced
        var finished: [StoreTransaction] = []
        var syncCallCount = 0
        var purchasedProductIDs: [String] = []
        var queued: [StoreTransaction] = []
    }

    private let recorded = Locked(Recorded())

    var productResult: Result<StoreProduct?, Error> {
        get { recorded.withLock { $0.productResult } }
        set { recorded.withLock { $0.productResult = newValue } }
    }
    var purchaseResult: Result<PurchaseResult, Error> {
        get { recorded.withLock { $0.purchaseResult } }
        set { recorded.withLock { $0.purchaseResult = newValue } }
    }
    var entitlements: [StoreTransaction] {
        get { recorded.withLock { $0.entitlements } }
        set { recorded.withLock { $0.entitlements = newValue } }
    }
    var syncResult: RestoreResult {
        get { recorded.withLock { $0.syncResult } }
        set { recorded.withLock { $0.syncResult = newValue } }
    }
    var queued: [StoreTransaction] {
        get { recorded.withLock { $0.queued } }
        set { recorded.withLock { $0.queued = newValue } }
    }

    /// Every transaction `finish` was called on, in order. The finish policy's
    /// assertion surface.
    var finished: [StoreTransaction] { recorded.withLock { $0.finished } }
    var syncCallCount: Int { recorded.withLock { $0.syncCallCount } }
    var purchasedProductIDs: [String] { recorded.withLock { $0.purchasedProductIDs } }

    func product(id: String) async throws -> StoreProduct? {
        try productResult.get()
    }

    func purchase(productID: String) async throws -> PurchaseResult {
        try recorded.withLock { recorded -> Result<PurchaseResult, Error> in
            recorded.purchasedProductIDs.append(productID)
            return recorded.purchaseResult
        }.get()
    }

    func currentEntitlements() async -> [StoreTransaction] {
        entitlements
    }

    func sync() async -> RestoreResult {
        recorded.withLock { recorded in
            recorded.syncCallCount += 1
            return recorded.syncResult
        }
    }

    func finish(_ transaction: StoreTransaction) async {
        recorded.withLock { $0.finished.append(transaction) }
    }

    func updates() -> AsyncStream<StoreTransaction> {
        let queued = self.queued
        return AsyncStream { continuation in
            for transaction in queued { continuation.yield(transaction) }
            continuation.finish()
        }
    }
}

final class MockSubscriptionClient: SubscriptionClientProtocol, @unchecked Sendable {
    /// The answer this double gives and the JWSs it took, in one box.
    private struct Exchange {
        var result: Result<PublicSubscription, Error> = .failure(
            ErrorResponse(code: ServerErrorCode.networkError.rawValue, message: "not configured", fieldErrors: nil)
        )
        var posted: [String] = []
    }

    private let exchange = Locked(Exchange())

    var result: Result<PublicSubscription, Error> {
        get { exchange.withLock { $0.result } }
        set { exchange.withLock { $0.result = newValue } }
    }

    /// The JWSs handed to `/verify`, in order — the "posted unmodified" check.
    var posted: [String] { exchange.withLock { $0.posted } }

    func verify(signedTransaction: String) async throws -> PublicSubscription {
        try exchange.withLock { exchange -> Result<PublicSubscription, Error> in
            exchange.posted.append(signedTransaction)
            return exchange.result
        }.get()
    }
}

final class MockCoachingUsageClient: CoachingUsageClientProtocol, @unchecked Sendable {
    /// The readings this double hands out and the calls it took, in one box.
    private struct Readings {
        var results: [Result<CoachingUsage, Error>] = []
        var callCount = 0
    }

    private let readings = Locked(Readings())

    /// Consumed in order, the last one repeating — so a test can say "the first
    /// fetch returns 40%, every later one 60%".
    var results: [Result<CoachingUsage, Error>] {
        get { readings.withLock { $0.results } }
        set { readings.withLock { $0.results = newValue } }
    }
    var callCount: Int { readings.withLock { $0.callCount } }

    func fetch() async throws -> CoachingUsage {
        let next = readings.withLock { readings -> Result<CoachingUsage, Error>? in
            guard !readings.results.isEmpty else { return nil }
            let index = min(readings.callCount, readings.results.count - 1)
            readings.callCount += 1
            return readings.results[index]
        }
        guard let next else {
            throw ErrorResponse(code: ServerErrorCode.networkError.rawValue, message: "not configured", fieldErrors: nil)
        }
        return try next.get()
    }
}

/// The view model's recorder double. The view model is presentation only, so
/// what a test wants to know here is *which* transactions reached the recorder
/// — never whether anything was finished, which is the recorder's own contract.
final class MockTransactionRecorder: TransactionRecording, @unchecked Sendable {
    /// The outcomes this double hands out and the transactions it took, in one box.
    private struct Ledger {
        var outcome: RecordOutcome = .deferred(
            .server(ErrorResponse(code: ServerErrorCode.networkError.rawValue, message: "not configured", fieldErrors: nil))
        )
        /// Consumed in order, the last one repeating — so a restore over two
        /// entitlements can answer differently for each. Empty means "always
        /// `outcome`".
        var outcomes: [RecordOutcome] = []
        var recorded: [StoreTransaction] = []
    }

    private let ledger = Locked(Ledger())

    var outcome: RecordOutcome {
        get { ledger.withLock { $0.outcome } }
        set { ledger.withLock { $0.outcome = newValue } }
    }
    var outcomes: [RecordOutcome] {
        get { ledger.withLock { $0.outcomes } }
        set { ledger.withLock { $0.outcomes = newValue } }
    }
    var recorded: [StoreTransaction] { ledger.withLock { $0.recorded } }

    func record(_ transaction: StoreTransaction) async -> RecordOutcome {
        ledger.withLock { ledger in
            let index = ledger.recorded.count
            ledger.recorded.append(transaction)
            guard !ledger.outcomes.isEmpty else { return ledger.outcome }
            return ledger.outcomes[min(index, ledger.outcomes.count - 1)]
        }
    }
}
