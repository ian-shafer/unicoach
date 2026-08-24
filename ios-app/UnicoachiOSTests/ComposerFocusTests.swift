import SwiftUI
import UIKit
import XCTest
@testable import UnicoachiOS

@MainActor
final class ComposerFocusTests: XCTestCase {
    /// Launch state: nothing has asked for a keyboard, so there is no request
    /// for `ConversationView` to mirror. A non-nil initial value would fire the
    /// view's `onChange` on first evaluation and lower a keyboard the student
    /// may have just raised.
    func testStartsWithNoCloseRequest() {
        XCTAssertNil(ComposerFocus().closeRequest)
    }

    func testRequestClosePublishesAValue() {
        let focus = ComposerFocus()

        focus.requestClose()

        XCTAssertNotNil(focus.closeRequest)
    }

    /// The regression guard for a drawer opened twice. `ConversationView`
    /// reacts to `closeRequest` *changing*, so a second request that republished
    /// the same value would be swallowed as a no-change — and the second drawer
    /// open would leave Settings behind the keyboard again, which is the whole
    /// defect RFC 127 exists to fix.
    func testTwoConsecutiveRequestsPublishDifferentValues() {
        let focus = ComposerFocus()

        focus.requestClose()
        let first = focus.closeRequest
        focus.requestClose()
        let second = focus.closeRequest

        XCTAssertNotNil(first)
        XCTAssertNotNil(second)
        XCTAssertNotEqual(first, second)
    }
}


// MARK: - The wiring, end to end

/// `ComposerFocus` on its own is three lines of `UUID`, and the reported defects
/// are not about `UUID`s: they are about whether the keyboard is up. What can
/// break is the WIRING — whether a request published by the root reaches the
/// `@FocusState` inside `ConversationView`, and whether an unrequested blank
/// page leaves it alone.
///
/// `ConversationView` holds the object as a plain `let` (an `@ObservedObject`
/// cannot be optional, and the pushed case has none), so the propagation runs
/// through the owner's `@StateObject` re-render rather than through a
/// subscription in the view itself. That is a real assumption about SwiftUI, and
/// a silent one: with only the unit tests above, inverting the `.onAppear` guard
/// or deleting the `.onChange` keeps the whole suite green and hands the bug
/// straight back.
///
/// So these host the real view in a real `UIWindow` — the same recipe
/// `SnapshotHost` documents, and for the same reason: a detached window has no
/// live scene, and a SwiftUI `@FocusState` then never becomes a real first
/// responder — and assert on the first responder, which IS the keyboard.
///
/// A snapshot scene cannot take this job: an offscreen render host never raises
/// a keyboard, so "launch has no keyboard" would be green whatever the code did.
@MainActor
final class ComposerFocusWiringTests: XCTestCase {
    /// Drives the pushes the real root drives — Settings, All conversations, a
    /// pushed conversation — so a test can pop back onto the root the way a
    /// student does.
    private final class Navigator: ObservableObject {
        @Published var path: [String] = []
    }

    /// Stands in for `AuthenticatedRootView`: it owns the `ComposerFocus` as a
    /// `@StateObject`, hands it to the root conversation, and puts that root in
    /// a `NavigationStack` — which is the ownership AND the shape the focus
    /// behaviour actually depends on.
    private struct Harness: View {
        @StateObject private var focus: ComposerFocus
        @ObservedObject private var navigator: Navigator
        private let gate: PaywallGate
        private let client: ConversationClientProtocol
        private let focusesComposerOnAppear: Bool

        init(
            focus: ComposerFocus,
            navigator: Navigator,
            gate: PaywallGate,
            client: ConversationClientProtocol,
            focusesComposerOnAppear: Bool
        ) {
            // Sound only because a harness is mounted ONCE per test: a
            // `@StateObject`'s autoclosure is evaluated on first construction
            // and never again, so a re-created `Harness` would keep this first
            // instance and the test would go on driving a detached object.
            _focus = StateObject(wrappedValue: focus)
            _navigator = ObservedObject(wrappedValue: navigator)
            self.gate = gate
            self.client = client
            self.focusesComposerOnAppear = focusesComposerOnAppear
        }

        var body: some View {
            NavigationStack(path: $navigator.path) {
                ConversationView(
                    conversationClient: client,
                    paywallGate: gate,
                    focusesComposerOnAppear: focusesComposerOnAppear,
                    focus: focus,
                    onProfileRequired: {}
                )
                .navigationDestination(for: String.self) { name in
                    Text(name)
                }
            }
        }
    }


    /// Launch, and every rebuild that is not a request: the blank page appears
    /// with nothing in the composer and no keyboard over it. **This is the
    /// reported defect's regression guard** — the old code focused whenever the
    /// conversation was fresh, and the root chat at launch is fresh.
    func testAnUnrequestedBlankPageDoesNotFocusTheComposer() {
        let window = host(focusesComposerOnAppear: false)

        XCTAssertNil(firstResponder(window), "launch raised the keyboard")
    }

    /// The other half of the same rule: an explicitly requested blank page —
    /// **New conversation** in the drawer — does take the composer, or the
    /// gesture stops working.
    func testARequestedBlankPageFocusesTheComposer() {
        let window = host(focusesComposerOnAppear: true)

        XCTAssertNotNil(firstResponder(window), "a requested blank page did not focus the composer")
    }

    func testACloseRequestResignsTheHostedComposer() {
        let focus = ComposerFocus()
        let window = host(focusesComposerOnAppear: true, focus: focus)
        XCTAssertNotNil(firstResponder(window), "composer never took focus on appear")

        // What `setMenu(open: true)` does.
        focus.requestClose()
        SnapshotHost.settle(1.0)

        XCTAssertNil(firstResponder(window), "close request never reached the composer")
    }

    /// The composer turning off under a raised keyboard, driven the way it
    /// really happens: the shared meter refreshes and reports the coaching
    /// budget spent, which is what `isComposerDisabled` reads. Nothing in the
    /// view is reached into — the lever is the same `refreshUsage()` the root's
    /// `scenePhase` re-check calls.
    ///
    /// It asserts the BEHAVIOUR, not one modifier, and honestly so: measured on
    /// this build, deleting `ConversationView`'s
    /// `.onChange(of: isComposerDisabled)` leaves this green, because SwiftUI's
    /// own `.disabled` resigns the field's first responder anyway. Deleting both
    /// fails it. So the `onChange` is belt-and-braces over a platform behaviour
    /// rather than the sole mechanism — which is exactly why the guard is
    /// written against the keyboard rather than against the modifier.
    func testTheKeyboardGoesDownWhenTheComposerBecomesBlocked() {
        let usageClient = MockCoachingUsageClient()
        let rail = SubscriptionViewModel(
            usageClient: usageClient,
            store: PreviewSubscriptionStore(),
            recorder: PreviewTransactionRecorder()
        )
        let window = host(focusesComposerOnAppear: true, rail: rail)
        XCTAssertNotNil(firstResponder(window), "composer never took focus on appear")

        usageClient.results = [.success(CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: nil))]
        Task { await rail.refreshUsage() }
        SnapshotHost.settle(1.0)

        XCTAssertEqual(rail.budget, .spent, "the lever itself did not fire; the assertion below would be vacuous")
        XCTAssertNil(firstResponder(window), "a blocked composer kept the keyboard")
    }

    /// The intent is spent once. `AuthenticatedRootView` does not clear its
    /// "this page was requested" value — nothing up there knows when the request
    /// was honoured — so it is still `true` when the student comes back from
    /// Settings or a pushed conversation, and `.onAppear` fires again on that
    /// return. Without `hasConsumedInitialFocus`, the keyboard springs up on a
    /// page they asked for minutes ago: the "appearance is not intent" rule this
    /// RFC exists to enforce, broken by the fix for it.
    ///
    /// The sequence is the real one, in order: the blank page is requested and
    /// focused, the drawer opens (which lowers the keyboard), Settings is
    /// pushed, and the student comes back. The keyboard must still be down.
    func testAReappearingBlankPageDoesNotFocusTheComposerAgain() {
        let focus = ComposerFocus()
        let navigator = Navigator()
        let window = host(focusesComposerOnAppear: true, focus: focus, navigator: navigator)
        XCTAssertNotNil(firstResponder(window), "a requested blank page did not focus the composer")

        // Opening the drawer is how Settings is reached at all, and it lowers
        // the keyboard on the way (`setMenu(open:)`).
        focus.requestClose()
        SnapshotHost.settle(1.0)
        XCTAssertNil(firstResponder(window), "the close request did not land")

        pushAndPopBack(navigator)

        XCTAssertNil(firstResponder(window), "coming back to the root raised the keyboard again")
    }

    // MARK: - Hosting

    /// Mounts through `SnapshotHost` rather than re-typing its recipe: the live
    /// scene, the hand-driven appearance cycle and the flushing settle are all
    /// findings with defects behind them, and a second copy of them here would
    /// drift from the one the snapshot gate keeps honest.
    @discardableResult
    private func host(
        focusesComposerOnAppear: Bool,
        focus: ComposerFocus = ComposerFocus(),
        navigator: Navigator = Navigator(),
        rail: SubscriptionViewModel? = nil
    ) -> UIWindow {
        let rail = rail ?? SubscriptionViewModel(
            usageClient: PreviewCoachingUsageClient(
                usage: CoachingUsage(usedPercent: 10, exhausted: false, resetsAt: nil)
            ),
            store: PreviewSubscriptionStore(),
            recorder: PreviewTransactionRecorder()
        )
        let harness = Harness(
            focus: focus,
            navigator: navigator,
            gate: PaywallGate(subscriptions: rail, presentedSheet: .constant(nil)),
            client: MockConversationClient(),
            focusesComposerOnAppear: focusesComposerOnAppear
        )

        let window = SnapshotHost.mount(AnyView(harness))
        addTeardownBlock { @MainActor in SnapshotHost.dismiss(window) }
        SnapshotHost.settle(1.0)
        return window
    }

    /// A real push and pop through the hosted `NavigationStack` — what the
    /// student does when they open Settings and come back. Driving the
    /// hosting controller's appearance transitions by hand instead LOOKS
    /// equivalent and is not: SwiftUI did not re-fire `onAppear` for it, and the
    /// test built that way passed with the consume-once guard deleted.
    private func pushAndPopBack(_ navigator: Navigator) {
        navigator.path = ["Settings"]
        SnapshotHost.settle(1.0)
        navigator.path = []
        SnapshotHost.settle(1.0)
    }

    private func firstResponder(_ view: UIView) -> UIView? {
        if view.isFirstResponder { return view }
        for subview in view.subviews {
            if let found = firstResponder(subview) { return found }
        }
        return nil
    }
}
