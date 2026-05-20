import SwiftUI

struct ErrorView: View {
    let title: String
    let description: String
    let systemImage: String
    let retryAction: (() -> Void)?
    
    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(description)
        } actions: {
            if let retryAction {
                Button("Try Again", action: retryAction)
            }
        }
    }
}
