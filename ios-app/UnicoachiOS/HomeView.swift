import SwiftUI

struct HomeView: View {
    let user: PublicUser
    let onLogout: () async -> Void
    @State private var isLoggingOut = false
    
    var body: some View {
        VStack(spacing: 20) {
            Text("Welcome, \(user.name)!")
                .font(.largeTitle)
                .padding(.top, 40)
            
            Text(user.email)
                .font(.subheadline)
                .foregroundColor(.secondary)
            
            Spacer()
            
            Button {
                isLoggingOut = true
                Task {
                    await onLogout()
                    isLoggingOut = false
                }
            } label: {
                if isLoggingOut {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle())
                        .frame(maxWidth: .infinity)
                } else {
                    Text("Log Out")
                        .foregroundColor(.red)
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(.red.opacity(0.1))
            .disabled(isLoggingOut)
            .padding(.bottom, 40)
            .padding(.horizontal)
        }
    }
}
