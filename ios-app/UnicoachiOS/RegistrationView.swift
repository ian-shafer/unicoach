import SwiftUI

struct RegistrationView: View {
    @StateObject private var viewModel: RegistrationViewModel
    let onSwitchToLogin: () -> Void
    
    enum FocusField {
        case email
        case name
        case password
    }
    
    @FocusState private var focusedField: FocusField?
    
    init(authClient: AuthClientProtocol, onRegisterSuccess: @escaping (PublicUser) -> Void, onSwitchToLogin: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: RegistrationViewModel(authClient: authClient, onRegisterSuccess: onRegisterSuccess))
        self.onSwitchToLogin = onSwitchToLogin
    }
    
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Email", text: $viewModel.email)
                        .keyboardType(.emailAddress)
#if os(iOS)
                        .textInputAutocapitalization(.never)
#endif
                        .disableAutocorrection(true)
                        .focused($focusedField, equals: .email)
                        .accessibilityIdentifier("emailField")
                        .accessibilityLabel("Email")
                        .onSubmit { focusedField = .name }
                    
                    if let fieldError = viewModel.errorResponse?.fieldErrors?.first(where: { $0.field == "email" }) {
                        Text(fieldError.message)
                            .foregroundStyle(.red)
                            .font(.caption)
                    }
                    
                    TextField("Name", text: $viewModel.name)
#if os(iOS)
                        .textInputAutocapitalization(.words)
#endif
                        .disableAutocorrection(true)
                        .focused($focusedField, equals: .name)
                        .accessibilityIdentifier("nameField")
                        .accessibilityLabel("Name")
                        .onSubmit { focusedField = .password }
                    
                    if let fieldError = viewModel.errorResponse?.fieldErrors?.first(where: { $0.field == "name" }) {
                        Text(fieldError.message)
                            .foregroundStyle(.red)
                            .font(.caption)
                    }
                    
                    SecureField("Password", text: $viewModel.password)
                        .focused($focusedField, equals: .password)
                        .accessibilityIdentifier("passwordField")
                        .accessibilityLabel("Password")
                        .onSubmit {
                            focusedField = nil
                            register()
                        }
                    
                    if let fieldError = viewModel.errorResponse?.fieldErrors?.first(where: { $0.field == "password" }) {
                        Text(fieldError.message)
                            .foregroundStyle(.red)
                            .font(.caption)
                    }
                }
                
                Section {
                    Button(action: register) {
                        if viewModel.isLoading {
                            ProgressView()
                                .accessibilityIdentifier("loadingIndicator")
                        } else {
                            Text("Register")
                        }
                    }
                    .disabled(viewModel.isLoading)
                    .accessibilityIdentifier("registerButton")
                    .accessibilityLabel("Register")
                }
                
                Section {
                    Button(action: onSwitchToLogin) {
                        Text("Already have an account? Log in")
                            .frame(maxWidth: .infinity)
                    }
                    .accessibilityIdentifier("switchToLoginButton")
                    .accessibilityLabel("Log In")
                }
            }
            .navigationTitle("Register")
            .alert(item: $viewModel.errorResponse, content: { error in
                Alert(title: Text("Error"), message: Text(error.message), dismissButton: .default(Text("OK")))
            })
            .fullScreenCover(item: $viewModel.infrastructureError) { error in
                ErrorView(
                    title: error.title,
                    description: error.description,
                    systemImage: error.systemImage,
                    retryAction: {
                        viewModel.infrastructureError = nil
                        register()
                    }
                )
            }
        }
    }
    
    private func register() {
        Task {
            await viewModel.register()
        }
    }
}
