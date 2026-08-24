import SwiftUI

/// The offer itself — Subscribe, Restore Purchases, and the notice either of
/// them may leave behind — shared by the two surfaces that make it: the
/// Settings section (RFC 119) and the paywall (RFC 121).
///
/// Extracted rather than copied, because a second copy of these two buttons is
/// a second place for the price, the loading state, the identifiers and the
/// notice/error split to drift. What differs between the surfaces is the
/// chrome around the offer — Settings keeps its heading, meter and status line;
/// the paywall keeps its explanation — so that is what stays outside.
///
/// Layout only, like `SubscriptionSection`: what the offer block has to show is
/// `viewModel.offer` — a button, a spinner, a spoken unavailability, or
/// nothing — and is never re-derived here.
struct SubscriptionOffer: View {
    @ObservedObject var viewModel: SubscriptionViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
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
                // Every state of the offer, named by the view model and
                // switched here with no residual branch: a price still being
                // fetched looks like one, and a price that never arrived says
                // so and offers a way to try again. On the paywall this is the
                // blocked student's only exit, so its silent absence would be
                // both the failure and the explanation (RFC 121).
                switch viewModel.offer {
                case .subscribe(let product):
                    LoadingButton(
                        "Subscribe \(product.displayPrice)/month",
                        isLoading: viewModel.phase == .purchasing,
                        role: .primary,
                        accessibilityIdentifier: "subscribeButton",
                        accessibilityLabel: "Subscribe",
                        action: { Task { await viewModel.subscribe() } }
                    )
                case .loading:
                    ProgressView()
                        .progressViewStyle(.circular)
                        .accessibilityIdentifier("subscribeLoading")
                case .unavailable:
                    VStack(alignment: .leading, spacing: DSSpacing.sm) {
                        Text("Subscribing is unavailable right now.")
                            .font(.dsBody)
                            .foregroundStyle(Color.dsTextSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("subscribeUnavailable")

                        // The price, and only the price: this button exists
                        // because *that* fetch failed, and `load()` would also
                        // re-read the meter and re-post the newest entitlement
                        // to `/verify`.
                        Button("Try again") { Task { await viewModel.refreshProduct() } }
                            .font(.dsButton)
                            .foregroundStyle(Color.dsTextPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("subscribeRetryButton")
                            .accessibilityLabel("Try again")
                    }
                case .bound:
                    // A subscription a purchase would not help: `active`, and
                    // since RFC 128 also a card failing to bill, where what is
                    // wrong is the payment method and `ManageSubscriptionLink`
                    // is what fixes it. Which states those are is
                    // `offersSubscribe`'s rule, read via `offer` rather than
                    // restated here. Restore stays below.
                    EmptyView()
                }

                // `DSTextButton`, not a hand-rolled `Button`: the colour rule
                // (never `brandAccent`) and the 44pt tap target are the
                // primitive's, stated once there.
                DSTextButton(
                    String(localized: "Restore Purchases"),
                    isLoading: viewModel.phase == .restoring,
                    loadingTitle: String(localized: "Restoring…"),
                    accessibilityIdentifier: "restorePurchasesButton",
                    accessibilityLabel: "Restore Purchases",
                    action: { Task { await viewModel.restore() } }
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
