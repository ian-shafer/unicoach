import SwiftUI

/// The paywall gate's inseparable pieces: the shared subscription rail the block
/// is read from, the 402's landing point, and the sheet's opener (RFC 121).
///
/// **One value rather than three parameters**, because they are correct only
/// together — a screen handed a meter that its own callbacks do not refresh
/// would render an unblocked composer for a blocked student, and nothing in
/// three separate arguments says that cannot happen. `AuthenticatedRootView`
/// owns the rail and the sheet, so it is the only thing that can build one, and
/// a screen merely on the path between the root and a composer (the conversation
/// list) forwards one opaque value instead of keeping three in step.
///
/// The sheet's flag is reached through a `Binding` and mutated **here only**, by
/// the two methods below — so "present the paywall" is written once rather than
/// assigned at each call site.
@MainActor
struct PaywallGate {
    /// The one rail: read for the meter, refreshed by the 402, and rendered by
    /// the sheet.
    let subscriptions: SubscriptionViewModel

    private let isPresented: Binding<Bool>

    init(subscriptions: SubscriptionViewModel, isPresented: Binding<Bool>) {
        self.subscriptions = subscriptions
        self.isPresented = isPresented
    }

    /// The 402's landing point. It **refreshes usage from the server** rather
    /// than setting a blocked flag of its own: the block and the meter then come
    /// from one answer and cannot disagree, and every `ConversationViewModel` in
    /// the stack observes the same object. Awaited before presenting so the
    /// sheet opens on the fresh reading.
    func handleBudgetExhausted() async {
        await subscriptions.refreshUsage()
        present()
    }

    /// A "See options" tap — the same sheet the 402 opens, from the same flag.
    func present() {
        isPresented.wrappedValue = true
    }
}
