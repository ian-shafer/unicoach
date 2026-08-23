import SwiftUI

/// The way out to Apple's own subscription-management screen — and the sentence
/// that says so before it is tapped (RFC 123).
///
/// **You cannot unsubscribe in-app.** Apple requires cancellation to go through
/// the App Store and offers no API that cancels on a student's behalf, so this
/// is a link out rather than a control, and the copy under it says as much
/// rather than letting the student discover it by tapping. The change reaches
/// us the way every other change does — as a transaction on
/// `Transaction.updates`, recorded and verified server-side (RFC 119).
///
/// **It owns its own condition.** The link renders only when a subscription is
/// bound, in *any* state — `grace` and `expired` included, which are precisely
/// the states where reaching the subscription matters most — and that rule is
/// here rather than at each call site, so the sheet and the paywall cannot
/// disagree about when it appears. Shown to a student with nothing bound it
/// would be a door onto an empty room.
///
/// **It brings its own rule with it.** The separator above the link is part of
/// the link, not of whichever sheet is rendering it. Without that, only the
/// surface that remembered to write the hairline got it: a subscriber reads
/// "Restore Purchases" and "Manage subscription" as twins — identical type,
/// colour, centring and width, ~30pt apart — and the control that actually
/// matters is second of two identical black words. Owning the rule here serves
/// both sheets from one place and removes the second site that would have to be
/// kept in step with `offersManage`. Its `DSSpacing.lg` column matches
/// `DSSheetScroll`'s, so the rule sits at a section boundary's rhythm on either
/// surface rather than at whatever the enclosing stack happens to use.
///
/// Layout only, like `SubscriptionOffer`: whether to show it is
/// `viewModel.offersManage`, and what a failed presentation says is
/// `showManagement()`'s notice — neither is re-derived here.
struct ManageSubscriptionLink: View {
    @ObservedObject var viewModel: SubscriptionViewModel

    var body: some View {
        if viewModel.offersManage {
            VStack(spacing: DSSpacing.lg) {
                // Rendered here and only here, so it cannot appear without the
                // link or the link without it (DESIGN.md §3, §8: the 1pt rule
                // is this design's only separator).
                DSHairline()

                content
            }
        }
    }

    /// The link itself and the sentence under it — the pair the rule above
    /// separates, named so the body's two children sit at the same altitude.
    private var content: some View {
        VStack(spacing: DSSpacing.xs) {
            // The action this block exists for, so `.primary` — the colour
            // rule and the 44pt target are `DSTextButton`'s.
            DSTextButton(
                String(localized: "Manage subscription"),
                accessibilityIdentifier: "manageSubscriptionButton",
                accessibilityLabel: "Manage subscription",
                accessibilityHint: "Opens the App Store",
                action: { Task { await viewModel.showManagement() } }
            )

            // Said once, next to the control, rather than left to be
            // discovered. `dsCaption`/`TextSecondary` and not an error
            // treatment: it is a fact about the platform, not a problem.
            Text("Cancelling is handled by the App Store.")
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("manageSubscriptionNote")
        }
    }
}

// MARK: - Previews

/// The link needs a **bound** subscription to render at all, and the only way
/// one reaches this rail is through the recorder — so the canvas hands it one
/// the same way the app does.
private struct ManageSubscriptionLinkPreviewContainer: View {
    @StateObject private var viewModel = SubscriptionViewModel(
        usageClient: PreviewCoachingUsageClient(),
        store: PreviewSubscriptionStore(),
        recorder: PreviewTransactionRecorder()
    )

    /// The closed vocabulary rather than a hand-spelled wire string: the canvas
    /// has `SubscriptionStatus` available and nothing here is decoding a
    /// server response, so a typo'd status should not compile. (The test files
    /// keep their hand-written wire strings on purpose — there the raw string
    /// *is* what is under test.)
    let status: SubscriptionStatus

    var body: some View {
        ManageSubscriptionLink(viewModel: viewModel)
            .task {
                await viewModel.apply(.recorded(PublicSubscription(
                    status: status.rawValue,
                    productId: SubscriptionProduct.monthlyIdentifier,
                    currentPeriodEnd: Date()
                )))
            }
    }
}

@MainActor private var manageSubscriptionLinkPreview: some View {
    VStack(spacing: DSSpacing.lg) {
        ManageSubscriptionLinkPreviewContainer(status: .active)
        ManageSubscriptionLinkPreviewContainer(status: .billingRetry)
    }
    .padding(DSSpacing.lg)
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    .background(Color.dsBackground)
}

#Preview("manageSubscriptionLink - Light") {
    manageSubscriptionLinkPreview
        .preferredColorScheme(.light)
}

#Preview("manageSubscriptionLink - Dark") {
    manageSubscriptionLinkPreview
        .preferredColorScheme(.dark)
}
