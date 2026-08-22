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
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }
}

private var errorPreview: some View {
    ErrorView(
        title: "Something went wrong",
        description: "We couldn't reach the server. Please try again.",
        systemImage: "wifi.exclamationmark",
        retryAction: {}
    )
}

#Preview("Error - Light") {
    errorPreview
        .preferredColorScheme(.light)
}

#Preview("Error - Dark") {
    errorPreview
        .preferredColorScheme(.dark)
}
