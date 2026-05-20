import Foundation

enum InfrastructureError: Identifiable {
    case serverError
    case noConnectivity
    case timeout

    var id: String {
        switch self {
        case .serverError: return "serverError"
        case .noConnectivity: return "noConnectivity"
        case .timeout: return "timeout"
        }
    }

    var title: String {
        switch self {
        case .serverError: return "Something Went Wrong"
        case .noConnectivity: return "No Connection"
        case .timeout: return "Request Timed Out"
        }
    }

    var description: String {
        switch self {
        case .serverError: return "We couldn't reach the server. Please try again later."
        case .noConnectivity: return "Check your internet connection and try again."
        case .timeout: return "The server took too long to respond. Please try again."
        }
    }

    var systemImage: String {
        switch self {
        case .serverError: return "exclamationmark.triangle"
        case .noConnectivity: return "wifi.slash"
        case .timeout: return "clock.badge.exclamationmark"
        }
    }
}
