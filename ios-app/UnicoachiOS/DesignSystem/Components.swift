import SwiftUI

// MARK: - Button styles

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    init() {}

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.dsButton)
            .foregroundStyle(Color.brandOnAccent)
            .frame(maxWidth: .infinity)
            .padding(.vertical, DSSpacing.md)
            .background(Color.brandAccent)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.button, style: .continuous))
            .opacity(opacity(isPressed: configuration.isPressed))
    }

    private func opacity(isPressed: Bool) -> Double {
        if !isEnabled { return 0.5 }
        return isPressed ? 0.8 : 1.0
    }
}

struct DestructiveButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    init() {}

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.dsButton)
            .foregroundStyle(Color.dsError)
            .frame(maxWidth: .infinity)
            .padding(.vertical, DSSpacing.md)
            .background(
                ZStack {
                    Color.dsSurface
                    Color.dsError.opacity(0.12)
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.button, style: .continuous))
            .opacity(opacity(isPressed: configuration.isPressed))
    }

    private func opacity(isPressed: Bool) -> Double {
        if !isEnabled { return 0.5 }
        return isPressed ? 0.8 : 1.0
    }
}

// MARK: - LoadingButton

enum LoadingButtonRole {
    case primary
    case destructive
}

struct LoadingButton: View {
    private let title: String
    private let isLoading: Bool
    private let role: LoadingButtonRole
    private let accessibilityIdentifier: String?
    private let accessibilityLabelText: String?
    private let progressAccessibilityIdentifier: String?
    private let action: () -> Void

    init(
        _ title: String,
        isLoading: Bool,
        role: LoadingButtonRole = .primary,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        progressAccessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.isLoading = isLoading
        self.role = role
        self.accessibilityIdentifier = accessibilityIdentifier
        self.accessibilityLabelText = accessibilityLabel
        self.progressAccessibilityIdentifier = progressAccessibilityIdentifier
        self.action = action
    }

    var body: some View {
        styledButton
            .modifier(OptionalIdentifier(identifier: accessibilityIdentifier))
            .modifier(OptionalLabel(label: accessibilityLabelText))
    }

    @ViewBuilder
    private var styledButton: some View {
        let button = Button(action: action) {
            if isLoading {
                progressView
            } else {
                Text(title)
            }
        }
        .disabled(isLoading)

        switch role {
        case .primary:
            button.buttonStyle(PrimaryButtonStyle())
        case .destructive:
            button.buttonStyle(DestructiveButtonStyle())
        }
    }

    @ViewBuilder
    private var progressView: some View {
        if let progressAccessibilityIdentifier {
            ProgressView()
                .progressViewStyle(.circular)
                .accessibilityIdentifier(progressAccessibilityIdentifier)
        } else {
            ProgressView()
                .progressViewStyle(.circular)
        }
    }
}

private struct OptionalIdentifier: ViewModifier {
    let identifier: String?

    func body(content: Content) -> some View {
        if let identifier {
            content.accessibilityIdentifier(identifier)
        } else {
            content
        }
    }
}

private struct OptionalLabel: ViewModifier {
    let label: String?

    func body(content: Content) -> some View {
        if let label {
            content.accessibilityLabel(label)
        } else {
            content
        }
    }
}

// MARK: - LabeledField

struct LabeledField<Value: Hashable>: View {
    private let label: String
    private let text: Binding<String>
    private let isSecure: Bool
    private let error: String?
    private let focus: FocusState<Value?>.Binding
    private let equals: Value
    private let keyboardType: UIKeyboardType
    private let autocapitalization: TextInputAutocapitalization
    private let disableAutocorrection: Bool
    private let submitLabel: SubmitLabel
    private let accessibilityIdentifier: String?
    private let accessibilityLabelText: String?
    private let onSubmit: () -> Void

    init(
        _ label: String,
        text: Binding<String>,
        isSecure: Bool = false,
        error: String? = nil,
        focus: FocusState<Value?>.Binding,
        equals: Value,
        keyboardType: UIKeyboardType = .default,
        autocapitalization: TextInputAutocapitalization = .never,
        disableAutocorrection: Bool = true,
        submitLabel: SubmitLabel = .next,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        onSubmit: @escaping () -> Void = {}
    ) {
        self.label = label
        self.text = text
        self.isSecure = isSecure
        self.error = error
        self.focus = focus
        self.equals = equals
        self.keyboardType = keyboardType
        self.autocapitalization = autocapitalization
        self.disableAutocorrection = disableAutocorrection
        self.submitLabel = submitLabel
        self.accessibilityIdentifier = accessibilityIdentifier
        self.accessibilityLabelText = accessibilityLabel
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text(label)
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextSecondary)

            inputField
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .keyboardType(keyboardType)
                .textInputAutocapitalization(autocapitalization)
                .disableAutocorrection(disableAutocorrection)
                .submitLabel(submitLabel)
                .focused(focus, equals: equals)
                .onSubmit(onSubmit)
                .padding(DSSpacing.md)
                .background(Color.dsSurface)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.field, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: DSRadius.field, style: .continuous)
                        .stroke(error == nil ? Color.dsFieldBorder : Color.dsError, lineWidth: 1)
                )
                .modifier(OptionalIdentifier(identifier: accessibilityIdentifier))
                .modifier(OptionalLabel(label: accessibilityLabelText))

            if let error {
                FieldErrorText(error)
            }
        }
    }

    @ViewBuilder
    private var inputField: some View {
        if isSecure {
            SecureField(label, text: text)
        } else {
            TextField(label, text: text)
        }
    }
}

// MARK: - FieldErrorText

struct FieldErrorText: View {
    private let message: String

    init(_ message: String) {
        self.message = message
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: DSSpacing.xs) {
            Image(systemName: "exclamationmark.circle")
            Text(message)
        }
        .font(.dsCaption)
        .foregroundStyle(Color.dsError)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(message)
    }
}

// MARK: - FormErrorBanner

struct FormErrorBanner: View {
    private let message: String

    init(_ message: String) {
        self.message = message
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: DSSpacing.sm) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(message)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.dsCaption)
        .foregroundStyle(Color.dsError)
        .padding(DSSpacing.md)
        .background(
            ZStack {
                Color.dsSurface
                Color.dsError.opacity(0.12)
            }
        )
        .clipShape(RoundedRectangle(cornerRadius: DSRadius.field, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(message)
    }
}

// MARK: - Previews

#Preview("Buttons - Light") {
    buttonPreview
        .preferredColorScheme(.light)
}

#Preview("Buttons - Dark") {
    buttonPreview
        .preferredColorScheme(.dark)
}

private var buttonPreview: some View {
    VStack(spacing: DSSpacing.md) {
        LoadingButton("Log In", isLoading: false, role: .primary) {}
        LoadingButton("Log In", isLoading: true, role: .primary) {}
        LoadingButton("Log In", isLoading: false, role: .primary) {}
            .disabled(true)
        LoadingButton("Log Out", isLoading: false, role: .destructive) {}
        LoadingButton("Log Out", isLoading: true, role: .destructive) {}
    }
    .padding(DSSpacing.lg)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.dsBackground)
}

private struct LabeledFieldPreviewHost: View {
    @FocusState private var focus: PreviewField?
    @State private var email = "user@example.com"
    @State private var password = ""

    enum PreviewField {
        case email, password
    }

    var body: some View {
        VStack(spacing: DSSpacing.md) {
            LabeledField(
                "Email",
                text: $email,
                focus: $focus,
                equals: .email,
                keyboardType: .emailAddress
            )
            LabeledField(
                "Password",
                text: $password,
                isSecure: true,
                error: "Password must be at least 8 characters",
                focus: $focus,
                equals: .password,
                submitLabel: .go
            )
            FieldErrorText("Email is already taken")
            FormErrorBanner("Invalid email or password")
        }
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }
}

#Preview("Fields - Light") {
    LabeledFieldPreviewHost()
        .preferredColorScheme(.light)
}

#Preview("Fields - Dark") {
    LabeledFieldPreviewHost()
        .preferredColorScheme(.dark)
}
