import SwiftUI

/// The Settings "Subscription" section: the coaching meter, the bound
/// subscription's status line, Subscribe, and Restore Purchases.
///
/// It drops between `appearanceSection` and the button stack with no
/// restructuring — `SettingsView` was already composed as a stack of
/// `dsOverlineStyle()` sections awaiting exactly this. The slide-over menu gets
/// no new row: it must not scroll, and already degrades its recents under large
/// Dynamic Type, so Settings is the home.
///
/// Layout only. What to say and whether to offer a purchase are the view
/// model's, where the rest of this rail's tests can reach them.
struct SubscriptionSection: View {
    @ObservedObject var viewModel: SubscriptionViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            Text("Subscription")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextSecondary)

            if let usage = viewModel.usage {
                UsageMeter(usedPercent: usage.usedPercent, exhausted: usage.exhausted, resetsAt: usage.resetsAt)
            } else if viewModel.phase == .loading {
                ProgressView()
                    .progressViewStyle(.circular)
                    .accessibilityIdentifier("subscriptionLoading")
            } else if viewModel.usageUnavailable {
                // A finished load with no meter says so: a header with nothing
                // under it reads as a rendering bug, not as a failed fetch.
                Text("Coaching usage is unavailable right now.")
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextSecondary)
                    .accessibilityIdentifier("coachingUsageUnavailable")
            }

            if let statusLine = viewModel.statusLine {
                Text(statusLine)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextSecondary)
                    .accessibilityIdentifier("subscriptionStatus")
            }

            // The banner is for failures only; an approval-pending purchase or
            // an empty restore is ordinary news, said in ordinary type.
            switch viewModel.notice {
            case .informational(let text):
                Text(text)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextSecondary)
                    .accessibilityIdentifier("subscriptionNotice")
            case .failure(let text):
                FormErrorBanner(text)
            case nil:
                EmptyView()
            }

            VStack(spacing: DSControl.stackGap) {
                if viewModel.offersSubscribe, let product = viewModel.product {
                    LoadingButton(
                        "Subscribe \(product.displayPrice)/month",
                        isLoading: viewModel.phase == .purchasing,
                        role: .primary,
                        accessibilityIdentifier: "subscribeButton",
                        accessibilityLabel: "Subscribe",
                        action: { Task { await viewModel.subscribe() } }
                    )
                }

                Button(action: { Task { await viewModel.restore() } }) {
                    Text(viewModel.phase == .restoring ? "Restoring…" : "Restore Purchases")
                        .font(.dsLabel)
                        .frame(maxWidth: .infinity)
                }
                // Not brandAccent: `#EE732F` on white is 2.95:1 (DESIGN.md §6).
                .foregroundStyle(Color.dsTextPrimary)
                .disabled(viewModel.phase == .restoring)
                .accessibilityIdentifier("restorePurchasesButton")
                .accessibilityLabel("Restore Purchases")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task { await viewModel.load() }
    }
}
