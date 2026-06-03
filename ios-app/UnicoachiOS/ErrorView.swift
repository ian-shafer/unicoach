import SwiftUI

struct ErrorView: View {
    let title: String
    let description: String
    let systemImage: String
    let retryAction: (() -> Void)?

    var body: some View {
        ContentUnavailableView {
            Label {
                Text(title)
                    .font(.dsTitle)
                    .foregroundStyle(Color.dsTextPrimary)
            } icon: {
                Image(systemName: systemImage)
                    .foregroundStyle(Color.dsError)
            }
        } description: {
            Text(description)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
        } actions: {
            if let retryAction {
                Button("Try Again", action: retryAction)
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.horizontal, DSSpacing.xl)
            }
        }
        .background(Color.dsBackground)
    }
}

#Preview {
    ErrorView(
        title: "Something went wrong",
        description: "We couldn't reach the server. Please try again.",
        systemImage: "wifi.exclamationmark",
        retryAction: {}
    )
}
