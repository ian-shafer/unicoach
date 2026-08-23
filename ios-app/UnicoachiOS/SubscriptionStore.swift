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

#if DEBUG
/// The simulator-Debug StoreKit switch, and the two pieces it is made of: the
/// launch argument that names it, and the pure predicate that reads an argument
/// list. A predicate over `[String]` rather than an inline `ProcessInfo` read at
/// the composition root because `bin/test` never compiles ios-app — XCTest is
/// the only mechanical authority this switch has, and a function that reaches
/// into the process's own environment cannot be given one.
///
/// **The switch is opt-IN, and that is the whole design.** The defect: a
/// StoreKit *configuration* is bound to the LAUNCH, not to the artifact.
/// `UnicoachiOS.storekit` is referenced by the scheme and injected by the
/// scheme's action; it is NOT inside the built `.app`. So on a simulator, "this
/// process was started by a scheme action" and "this process has a StoreKit
/// configuration" are the SAME condition — and only a scheme action can pass
/// this argument. Defaulting to disabled therefore means no launch can fall
/// through to the REAL App Store by forgetting anything: a hand-typed `xcrun
/// simctl launch`, `bin/screenshot-ios`, a test host, a launcher nobody has
/// written yet. A default-on switch would have made safety a flag every future
/// call site has to remember, and the first one that forgets gets the burst of
/// "Sign in to your Apple Account" system alerts over the UI
/// (`Product.products(for:)` and `Transaction.currentEntitlements` from
/// `SubscriptionViewModel.load()`, the session-long `Transaction.updates`
/// listener, and StoreKit's own retries).
///
/// **`#if DEBUG` on purpose.** A shipping binary that can be told on the command
/// line what to do about StoreKit is a binary that can be told to skip paying.
/// Release must not carry the switch at all — not "ignore the flag", not "log
/// and continue" — so it is compiled out rather than checked at runtime.
enum StoreKitLaunchOverride {
    /// The launch argument, spelled once. It lives in the scheme's LaunchAction
    /// next to the `StoreKitConfigurationFileReference` it depends on, so the
    /// thing that enables StoreKit and the configuration that makes it safe
    /// cannot drift apart. `ProcessInfo.arguments` carries it verbatim.
    static let enableArgument = "-UnicoachEnableStoreKit"

    /// Exact-match containment, deliberately: a prefix or case-insensitive match
    /// would let an unrelated argument switch real billing on by accident.
    static func isStoreKitEnabled(launchArguments: [String]) -> Bool {
        launchArguments.contains(enableArgument)
    }
}

/// The store a simulator Debug launch gets unless it opted in: genuinely inert,
/// so StoreKit is never called at all and nothing can demand an Apple Account
/// regardless of how the process was launched.
///
/// Distinct from `PreviewSubscriptionStore`, which answers `product(id:)` with a
/// FAKE "$10.00" plan. A canvas wants a populated row; a screenshot capture must
/// not invent a price that no storefront quoted — a fabricated price in a
/// captured image is exactly the kind of thing that ends up in a store listing.
/// Answering `nil` renders the honest "no purchase path offered" state: the
/// usage meter still draws, the Subscribe button simply is not there.
///
/// Every member is the emptiest truthful answer, never a fatal: this store is
/// live behind a real UI, and trapping would turn a capture into a crash.
struct DisabledSubscriptionStore: SubscriptionStoreProtocol, TransactionFinishing {
    func product(id: String) async throws -> StoreProduct? { nil }
    /// `.unavailable` and not `.userCancelled`: nothing was cancelled, the plan
    /// is genuinely not on offer in this process.
    func purchase(productID: String) async throws -> PurchaseResult { .unavailable }
    func currentEntitlements() async -> [StoreTransaction] { [] }
    func sync() async -> RestoreResult { .synced }
    func finish(_ transaction: StoreTransaction) async {}
    /// Finished immediately, so `AuthenticatedRootView`'s listener task ends at
    /// once instead of awaiting a stream that will never yield.
    func updates() -> AsyncStream<StoreTransaction> { AsyncStream { $0.finish() } }
}
#endif

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
