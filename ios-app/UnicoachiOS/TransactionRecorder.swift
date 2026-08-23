import Foundation
import os

/// What the server had to say about a transaction, split by the only question
/// that matters at this layer: may StoreKit be told the transaction is done?
///
/// Splitting the outcome three ways rather than returning the raw error is the
/// point — `deferred` vs `rejected` *is* the finish decision, named. A caller
/// cannot re-derive it wrongly because it never sees the status code.
enum RecordOutcome: Sendable {
    /// The server holds it; the transaction was finished.
    case recorded(PublicSubscription)
    /// Permanently un-recordable; the transaction was finished.
    case rejected(RecordFailure)
    /// Transient; the transaction was **not** finished and Apple will redeliver it.
    case deferred(RecordFailure)
}

/// Why a transaction was not recorded, kept in the shape the failure actually
/// had. A request that never reached the server has no `code` and no server
/// `message`; synthesizing an `ErrorResponse` for it would invent a wire code
/// no server sent **and** throw away the real cause, leaving the log saying
/// `NETWORK_ERROR` and nothing about what failed.
enum RecordFailure: Sendable {
    /// The server answered, in its own vocabulary.
    case server(ErrorResponse)
    /// The exchange failed below the protocol — the thrown error, untouched.
    case transport(Error)
}

/// The seam the view model and the transaction listener see. `record` is the
/// whole surface: the finish decision is made inside, never by a caller.
protocol TransactionRecording: Sendable {
    func record(_ transaction: StoreTransaction) async -> RecordOutcome
}

/// The finish policy, as one type with three callers: a fresh purchase, Restore
/// Purchases, and the background transaction listener.
///
/// An unfinished StoreKit transaction is redelivered on every launch. Finishing
/// one the server never recorded loses a paid purchase. So the transaction is
/// finished only when the server reached a **terminal** answer:
///
/// | `/verify`                                        | arm        | why                                             |
/// | ------------------------------------------------ | ---------- | ----------------------------------------------- |
/// | 200                                              | `recorded` | recorded; the row is Apple truth                |
/// | 200 whose body will not decode                   | `rejected` | recorded server-side; re-posting cannot help    |
/// | 400 `validation_failed`                          | `rejected` | malformed JWS — will never succeed              |
/// | 409 `subscription_owned_by_other_account`        | `rejected` | permanent, first-writer-wins                    |
/// | 503 `service_unavailable`                        | `deferred` | Apple unreachable / credentials unset           |
/// | 404 `subscription_not_found`                     | `deferred` | environment mismatch — config, not the purchase |
/// | 402 `coaching_budget_exhausted`                  | `deferred` | not a `/verify` answer at all — unclassifiable  |
/// | 401 / 403 / 409 `student_profile_required` / 500 | `deferred` | session or server problem                       |
/// | transport failure                                | `deferred` | never reached the server                        |
///
/// Not finishing costs a re-post on the next launch, which is idempotent and
/// cheap. Finishing wrongly costs a customer's money.
///
/// Finishing is safe on the `rejected` arm specifically because StoreKit 2's
/// `Transaction.currentEntitlements` keeps returning an active entitlement
/// after `finish()` — so if the student later signs into the account that owns
/// it, Restore Purchases still finds it.
///
/// An `actor` because renewals arrive on the listener while a purchase or
/// Restore is in flight: serialising `record` means the same transaction cannot
/// be posted and finished twice concurrently.
///
/// **This is the only thing in the app that calls `store.finish`.**
actor TransactionRecorder: TransactionRecording {
    private let client: SubscriptionClientProtocol
    /// Narrowed to the finishing half of the seam on purpose: this is the only
    /// type in the app that holds a `TransactionFinishing`, which is what makes
    /// "only the recorder finishes" a compile-time fact rather than a comment.
    private let store: TransactionFinishing
    /// Transactions this recorder has already finished. The actor serialises
    /// `record`, but a purchase and the listener can still both carry the same
    /// transaction through it in turn; this makes the second pass finish it at
    /// most once. (Re-posting is safe either way — `/verify` is idempotent.)
    private var finished: Set<UInt64> = []
    private let logger = Logger.unicoach(category: "TransactionRecorder")

    init(client: SubscriptionClientProtocol, store: TransactionFinishing) {
        self.client = client
        self.store = store
    }

    func record(_ transaction: StoreTransaction) async -> RecordOutcome {
        do {
            let subscription = try await client.verify(signedTransaction: transaction.jws)
            await finish(transaction)
            return .recorded(subscription)
        } catch let error as ErrorResponse {
            if isPermanent(error) {
                logger.error("Transaction [\(transaction.id, privacy: .public)] permanently rejected: [\(error.code, privacy: .public)] [\(error.message, privacy: .public)]")
                await finish(transaction)
                return .rejected(.server(error))
            }
            logger.error("Transaction [\(transaction.id, privacy: .public)] deferred: [\(error.code, privacy: .public)] [\(error.message, privacy: .public)]")
            return .deferred(.server(error))
        } catch {
            // Transport and anything else unclassified: it may never have
            // reached the server, so it is always deferred — carrying the
            // thrown error itself, which is the only record of what failed.
            logger.error("Transaction [\(transaction.id, privacy: .public)] deferred on a transport failure: [\(error, privacy: .public)]")
            return .deferred(.transport(error))
        }
    }

    /// The single call site of `store.finish` in the whole app.
    private func finish(_ transaction: StoreTransaction) async {
        guard !finished.contains(transaction.id) else { return }
        finished.insert(transaction.id)
        await store.finish(transaction)
    }

    /// The terminal refusals. Everything else is transient, because the
    /// asymmetry between a wasted re-post and a lost purchase decides the tie —
    /// but **every** code says so for itself: there is no `default:`, so a new
    /// `ServerErrorCode` fails to compile here until someone decides whether it
    /// finishes a paid transaction or defers it.
    private func isPermanent(_ error: ErrorResponse) -> Bool {
        switch error.knownCode {
        case .validationFailed, .subscriptionOwnedByOtherAccount:
            return true
        case .decodeError:
            // Only ever synthesized *after* a 200 (`APIClient.decode`), so the
            // server already holds the transaction: re-posting would fail the
            // same way on every launch, forever.
            return true
        case .unauthorized, .emailNotVerified, .accountEmailNotVerified,
             .accountDisabled, .serviceUnavailable, .studentAlreadyExists,
             .studentProfileRequired, .subscriptionNotFound, .payloadTooLarge,
             .timeout, .networkError, .serverError:
            return false
        case .coachingBudgetExhausted:
            // `/verify` cannot answer 402 — the coaching gate guards turn
            // endpoints, not this one. If one ever arrived it would say nothing
            // about whether Apple's transaction was recorded, and the asymmetry
            // above decides an answer we do not understand: do not finish.
            logger.error("Unexpected 402 from /verify: [\(error.code, privacy: .public)] [\(error.message, privacy: .public)]; deferring")
            return false
        case nil:
            // A code this client has no case for — a newer server code, or a
            // client-synthesized one. Unclassifiable, therefore transient.
            logger.error("Unrecognized /verify code [\(error.code, privacy: .public)]; deferring")
            return false
        }
    }
}

/// The recorder's canvas double. A preview posts nothing, so every transaction
/// it is handed is deferred — the arm that changes no state.
struct PreviewTransactionRecorder: TransactionRecording {
    func record(_ transaction: StoreTransaction) async -> RecordOutcome {
        .deferred(.server(ErrorResponse(
            code: ServerErrorCode.serviceUnavailable.rawValue,
            message: "unavailable",
            fieldErrors: nil
        )))
    }
}
