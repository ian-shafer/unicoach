import Foundation
import StoreKit
import os

/// The `.verified`-only rule, lifted out of StoreKit's types so it can be
/// executed by a test.
///
/// A union rather than a flag beside a payload: the refused case carries no
/// JWS, so an unverified blob has no representation that could reach `/verify`
/// even by a mistyped argument. `StoreKitSubscriptionStore.map` does
/// nothing but translate a `VerificationResult` into one of these; the decision
/// — which transactions are allowed to reach `/verify` — lives here.
enum StoreKitVerdict: Sendable, Equatable {
    case verified(id: UInt64, productID: String, jws: String)
    case unverified(id: UInt64)

    /// The transaction that escapes into the app, or `nil` for an unverified
    /// result — which is dropped rather than posted, keeping a forged payload
    /// out of the request path entirely.
    var escaping: StoreTransaction? {
        guard case .verified(let id, let productID, let jws) = self else { return nil }
        return StoreTransaction(id: id, productID: productID, jws: jws)
    }
}

/// The concrete StoreKit 2 rail behind `SubscriptionStoreProtocol` — and, for
/// `TransactionRecorder` alone, behind `TransactionFinishing`.
///
/// Two rules live here and nowhere else:
///
/// - **Only `.verified` results escape** — the decision itself is
///   `StoreKitVerdict` above, so the refusal arm is unit-tested rather than
///   sealed inside a StoreKit-typed method. A `VerificationResult.unverified`
///   is dropped with a log and never becomes a `StoreTransaction`.
/// - **`jws` is `VerificationResult.jwsRepresentation`** — the exact signed
///   blob the server re-verifies. The client parses none of it.
///
/// StoreKit's `Transaction` is needed to finish one, but the app above this
/// seam only ever holds a `StoreTransaction`, so every transaction that escapes
/// is registered here by id and looked up again on `finish`. A miss can only
/// mean it was already finished, so it is logged and ignored.
final class StoreKitSubscriptionStore: SubscriptionStoreProtocol, TransactionFinishing, @unchecked Sendable {
    private let logger = Logger.unicoach(category: "SubscriptionStore")
    private let pending = Locked<[UInt64: Transaction]>([:])

    init() {}

    func product(id: String) async throws -> StoreProduct? {
        let products = try await Product.products(for: [id])
        guard let product = products.first else {
            logger.error("No App Store product for id [\(id, privacy: .public)]")
            return nil
        }
        return StoreProduct(id: product.id, displayName: product.displayName, displayPrice: product.displayPrice)
    }

    /// Every outcome below is a `PurchaseResult` arm, never a thrown error: a
    /// storefront that does not carry the plan, and a payload that failed
    /// verification, are both expected states of this rail. Neither carries
    /// user-facing copy — the words are the view model's job.
    func purchase(productID: String) async throws -> PurchaseResult {
        guard let product = try await Product.products(for: [productID]).first else {
            logger.error("Cannot purchase unknown product [\(productID, privacy: .public)]")
            return .unavailable
        }

        let result = try await product.purchase()
        switch result {
        case .success(let verification):
            guard let transaction = registerAndMap(verification) else {
                return .unverified
            }
            return .purchased(transaction)
        case .userCancelled:
            return .userCancelled
        case .pending:
            return .pending
        @unknown default:
            // Never `.pending`: an outcome this app cannot name is not an
            // approval to wait for. The raw value is logged here, the one place
            // that still holds it.
            logger.error("Unrecognized StoreKit purchase result: [\(String(describing: result), privacy: .public)]")
            return .unrecognized
        }
    }

    func currentEntitlements() async -> [StoreTransaction] {
        var transactions: [StoreTransaction] = []
        for await verification in Transaction.currentEntitlements {
            if let transaction = registerAndMap(verification) {
                transactions.append(transaction)
            }
        }
        return transactions
    }

    /// StoreKit's `userCancelled` stops here: the student dismissing the App
    /// Store sign-in sheet is an ordinary end of Restore, and translating it
    /// into a value is what keeps a `StoreKitError` — and an error banner for
    /// doing nothing wrong — out of the layer above.
    func sync() async -> RestoreResult {
        do {
            try await AppStore.sync()
            return .synced
        } catch StoreKitError.userCancelled {
            logger.debug("Restore cancelled by the student")
            return .userCancelled
        } catch {
            logger.error("AppStore.sync failed: [\(error, privacy: .public)]")
            return .failed
        }
    }

    /// A registry miss can only mean the transaction was already finished —
    /// every `StoreTransaction` in the app came out of `registerAndMap`, which
    /// registered it, and `removeRegistered` removes it exactly once. So there is nothing
    /// to recover here; log it and return.
    func finish(_ transaction: StoreTransaction) async {
        guard let known = removeRegistered(transaction.id) else {
            logger.debug("Already finished, or never registered: id [\(transaction.id, privacy: .public)]")
            return
        }
        await known.finish()
    }

    /// Each result goes through `registerAndMap`, so an unverified one is
    /// dropped and a verified one is registered before it is yielded — without
    /// that registration the recorder's later `finish` would find nothing.
    /// `[weak self]` so the task never keeps the store alive; a deallocated
    /// store means the app is tearing down, so yielding nothing is fine.
    /// `onTermination` cancels the task, which is how ending the consuming
    /// `.task` (logout) also ends the `for await` on `Transaction.updates`.
    func updates() -> AsyncStream<StoreTransaction> {
        AsyncStream { continuation in
            let task = Task { [weak self] in
                for await verification in Transaction.updates {
                    guard let transaction = self?.registerAndMap(verification) else { continue }
                    continuation.yield(transaction)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Translation only: `VerificationResult` → verdict, no side effects. The
    /// decision is a pure mapping stated once and unit-tested directly, because
    /// a refusal arm no test can execute can be inverted without turning the
    /// suite red — and this one gates forged payloads.
    private func map(_ verification: VerificationResult<Transaction>) -> StoreKitVerdict {
        switch verification {
        case .verified(let transaction):
            return .verified(id: transaction.id, productID: transaction.productID, jws: verification.jwsRepresentation)
        case .unverified(let transaction, let error):
            logger.error("Dropping unverified transaction [\(transaction.id, privacy: .public)]: [\(error, privacy: .public)]")
            return .unverified(id: transaction.id)
        }
    }

    /// Registration only: remembers the StoreKit value `finish` will need.
    private func register(_ transaction: Transaction) {
        pending.withLock { $0[transaction.id] = transaction }
    }

    /// The one gate every StoreKit result passes through on its way out: map it
    /// to a verdict, then — only for a transaction that reaches the app —
    /// register it, so it can be finished later. The name says both jobs;
    /// neither is hidden.
    private func registerAndMap(_ verification: VerificationResult<Transaction>) -> StoreTransaction? {
        guard let escaping = map(verification).escaping else { return nil }
        if case .verified(let transaction) = verification { register(transaction) }
        return escaping
    }

    private func removeRegistered(_ id: UInt64) -> Transaction? {
        pending.withLock { $0.removeValue(forKey: id) }
    }
}
