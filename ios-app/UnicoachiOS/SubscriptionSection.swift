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

            CoachingUsageMeter(viewModel: viewModel)

            if let statusLine = viewModel.statusLine {
                Text(statusLine)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextSecondary)
                    .accessibilityIdentifier("subscriptionStatus")
            }

            SubscriptionOffer(viewModel: viewModel)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task { await viewModel.load() }
    }
}
