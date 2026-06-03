import SwiftUI

struct HomeView: View {
    let user: PublicUser
    let onLogout: () async -> Void
    @State private var isLoggingOut = false

    var body: some View {
        VStack(spacing: DSSpacing.md) {
            Text("Welcome, \(user.name)!")
                .font(.dsTitleXL)
                .foregroundStyle(Color.dsTextPrimary)
                .padding(.top, DSSpacing.xl)

            Text(user.email)
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextSecondary)

            Spacer()

            LoadingButton(
                "Log Out",
                isLoading: isLoggingOut,
                role: .destructive,
                action: {
                    isLoggingOut = true
                    Task {
                        await onLogout()
                        isLoggingOut = false
                    }
                }
            )
            .padding(.bottom, DSSpacing.xl)
        }
        .padding(.horizontal, DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }
}

#Preview {
    HomeView(
        user: PublicUser(id: UUID(), email: "preview@example.com", name: "Preview User"),
        onLogout: {}
    )
}
