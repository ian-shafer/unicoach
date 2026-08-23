import Foundation

/// The single subscription product this app sells, matching the one plan
/// configured server-side (`service.conf`'s `subscriptions.plans`). Declared
/// once so the string is never transcribed at a call site.
///
/// A second in-repo copy lives in `ios-app/UnicoachiOS.storekit`, the catalogue
/// the scheme runs the simulator against; Xcode owns that file's format, so it
/// cannot be derived from this constant and is pinned by
/// `StoreKitConfigurationTests` instead.
enum SubscriptionProduct {
    static let monthlyIdentifier = "coach.uni.UnicoachiOS.monthly10"
}

/// A product as this app renders it. `displayPrice` is StoreKit's **localized**
/// price string and is the only price ever shown: the server's `priceUsd` is a
/// budget input, not display copy, and rendering it would show the wrong
/// currency (and violate App Review guidelines).
struct StoreProduct: Sendable, Equatable {
    let id: String
    let displayName: String
    let displayPrice: String
}

/// A StoreKit transaction reduced to the two things this app needs: an identity
/// to finish it by, and the signed blob the server re-verifies. Only
/// `.verified` StoreKit results ever become one of these.
struct StoreTransaction: Sendable, Equatable {
    let id: UInt64
    /// The product this transaction entitles. Carried across the seam so a
    /// transaction for a plan this app does not sell can be refused **by name**
    /// before it is posted — `/verify` answers a 500 for an unknown product.
    let productID: String
    /// `VerificationResult.jwsRepresentation` — passed through untouched.
    let jws: String
}

/// Every way a purchase attempt ends. All six are expected domain states, so
/// none of them is thrown: `purchase` reserves `throws` for StoreKit's own
/// infrastructure failures.
enum PurchaseResult: Sendable, Equatable {
    case purchased(StoreTransaction)
    case userCancelled
    /// Ask to Buy and friends: Apple will deliver the transaction later, on the
    /// listener, if it is ever approved.
    case pending
    /// StoreKit signed a result this app refuses to trust; it is never posted.
    case unverified
    /// The plan is not offered on this storefront / account — the same absence
    /// `product(id:)` answers with `nil`, stated as a value on this path too.
    case unavailable
    /// A StoreKit result this app has no case for (`@unknown default`). Its own
    /// arm, and a **failure** one: folding it into `pending` would tell the
    /// student to wait for an approval that is never coming. The raw value is
    /// logged where it arrives, in `StoreKitSubscriptionStore`.
    case unrecognized
}

/// Every way Restore Purchases ends. A cancelled App Store sign-in is an
/// ordinary end of the flow, not a failure, so it is a value here rather than
/// `StoreKitError.userCancelled` thrown up to a layer with no vocabulary for
/// it — the same reason `purchase` answers with `PurchaseResult.userCancelled`.
enum RestoreResult: Sendable, Equatable {
    case synced
    case userCancelled
    case failed
}

/// The StoreKit seam. Everything above this protocol is testable without a
/// StoreKit environment, in the same spirit as RFC 113's `SsoSignInProviding`.
///
/// It deliberately has **no `finish`**: see `TransactionFinishing`.
protocol SubscriptionStoreProtocol: Sendable {
    func product(id: String) async throws -> StoreProduct?
    func purchase(productID: String) async throws -> PurchaseResult
    /// The JWSs worth posting to `/verify` — used to enumerate, never to unlock
    /// anything locally.
    func currentEntitlements() async -> [StoreTransaction]
    /// Non-throwing on purpose: its one failure mode a caller must distinguish
    /// — the student cancelling — is an arm of `RestoreResult`, not an error.
    func sync() async -> RestoreResult
    /// Apple's session-long push: renewals, Ask to Buy approvals, and
    /// redeliveries of anything never finished. Every transaction that reaches
    /// the app outside a purchase or a restore arrives here, so the listener
    /// consuming it is what keeps a renewal from being invisible until the next
    /// launch. The stream ends when the consuming task is cancelled.
    func updates() -> AsyncStream<StoreTransaction>
}

/// Telling StoreKit a transaction is done — its own protocol because it is the
/// one call that can lose a paid purchase. `TransactionRecorder` owns the
/// finish policy and is the only type given a dependency of this type; nothing
/// else can even *express* the call, which is stronger than the comment that
/// used to say so.
protocol TransactionFinishing: Sendable {
    func finish(_ transaction: StoreTransaction) async
}

/// The seam's inert canvas double. A `#Preview` must never construct a real
/// `StoreKitSubscriptionStore` — a static canvas has no business touching
/// StoreKit — and every preview showing the subscription surface needs the same
/// empty store, so it is declared once here rather than per view file.
struct PreviewSubscriptionStore: SubscriptionStoreProtocol, TransactionFinishing {
    var displayPrice: String = "$10.00"

    func product(id: String) async throws -> StoreProduct? {
        StoreProduct(id: id, displayName: "Unicoach Monthly", displayPrice: displayPrice)
    }
    func purchase(productID: String) async throws -> PurchaseResult { .userCancelled }
    func currentEntitlements() async -> [StoreTransaction] { [] }
    func sync() async -> RestoreResult { .synced }
    func finish(_ transaction: StoreTransaction) async {}
    func updates() -> AsyncStream<StoreTransaction> { AsyncStream { $0.finish() } }
}
