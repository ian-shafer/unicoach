import SwiftUI

struct LoginView: View {
    @StateObject private var viewModel: LoginViewModel
    let onSwitchToRegister: () -> Void
    @FocusState private var focusedField: Field?
    
    enum Field {
        case email, password
    }
    
    init(authClient: AuthClientProtocol, onLoginSuccess: @escaping (PublicUser) -> Void, onSwitchToRegister: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: LoginViewModel(authClient: authClient, onLoginSuccess: onLoginSuccess))
        self.onSwitchToRegister = onSwitchToRegister
    }
    
    var body: some View {
        Form {
            Section {
                TextField("Email", text: $viewModel.email)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .focused($focusedField, equals: .email)
                    .onSubmit { focusedField = .password }
                    .accessibilityIdentifier("loginEmailField")
                    .accessibilityLabel("Email")
                
                SecureField("Password", text: $viewModel.password)
                    .focused($focusedField, equals: .password)
                    .onSubmit {
                        Task { await viewModel.login() }
                    }
                    .accessibilityIdentifier("loginPasswordField")
                    .accessibilityLabel("Password")
            }
            
            if let errorResponse = viewModel.errorResponse {
                Text(errorResponse.message)
                    .foregroundColor(.red)
                    .font(.caption)
            }
            
            Section {
                Button {
                    Task { await viewModel.login() }
                } label: {
                    if viewModel.isLoading {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle())
                            .frame(maxWidth: .infinity)
                    } else {
                        Text("Log In")
                            .frame(maxWidth: .infinity)
                    }
                }
                .disabled(viewModel.isLoading)
                .accessibilityIdentifier("loginButton")
                .accessibilityLabel("Log In")
            }
            
            Section {
                Button(action: onSwitchToRegister) {
                    Text("Don't have an account? Register")
                        .frame(maxWidth: .infinity)
                }
                .accessibilityIdentifier("switchToRegisterButton")
                .accessibilityLabel("Register")
            }
        }
        .fullScreenCover(item: $viewModel.infrastructureError) { error in
            ErrorView(
                title: error.title,
                description: error.description,
                systemImage: error.systemImage,
                retryAction: {
                    viewModel.infrastructureError = nil
                    Task { await viewModel.login() }
                }
            )
        }
    }
}
