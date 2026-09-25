import SwiftUI

/// The neutral screen an account with neither a `students` row nor a
/// `supporters` row sees (RFC 241).
///
/// It exists because "no student row" used to mean "student who has not
/// finished onboarding", and a parent who registered was therefore put on a
/// form asking for THEIR date of birth and THEIR graduation date. The question
/// is not asked at registration — registration stays role-agnostic — it is
/// asked once, here, after the account exists.
///
/// TWO EQUAL PATHS. Both are the same filled control at the same weight, in a
/// screen that recommends neither: there is no wrong answer, and a parent who
/// picks the student path can still be linked by accepting an invite later.
struct RoleChoiceView: View {
    let userName: String
    let supporterClient: SupporterClientProtocol
    /// "I'm the student" — the existing onboarding form, unchanged.
    let onStudent: () -> Void
    /// "I'm supporting a student" — the home the `POST` answered with.
    let onSupporter: (SupporterHome) -> Void

    /// What the supporting path is doing. One value for the same reason
    /// `SupporterInviteControl` has one: a spinner beside a failure is not a
    /// state this screen has.
    @State private var state: ChoiceState = .idle

    enum ChoiceState: Equatable {
        case idle
        case joining
        /// The whole `ErrorResponse`, not the sentence out of it. The one
        /// refusal this call has is `409 supporter_closed`, which RFC 241
        /// deliberately routes to THIS screen — flattening it at the catch
        /// would leave a later "your supporter profile was closed, here is what
        /// to do" arm re-parsing prose or re-issuing the call. `ErrorResponse`
        /// is `Equatable`, so this state still is.
        case failed(ErrorResponse)
    }

    var body: some View {
        VStack(spacing: 0) {
            BrandTopBar()

            DSSheetScroll {
                Text("Welcome, \(userName)")
                    .dsOverlineStyle()
                    .foregroundStyle(Color.dsTextSecondary)
                    .accessibilityIdentifier("roleChoiceWelcome")

                Text("Who are you here for?")
                    .font(.dsTitleXL)
                    .foregroundStyle(Color.dsTextPrimary)

                if case let .failed(error) = state {
                    FormErrorBanner(error.message)
                        .accessibilityIdentifier("roleChoiceError")
                }

                // Disabled while the supporting path is in flight: the screen
                // has one action at a time. Otherwise a tap here opens the
                // onboarding form and the late `onSupporter(home)` yanks the
                // user straight back out of it.
                path(
                    title: "I'm the student",
                    detail: "Answer a few questions about you, then start coaching.",
                    identifier: "roleChoiceStudentButton",
                    isLoading: false,
                    isEnabled: state != .joining,
                    action: onStudent
                )

                path(
                    title: "I'm supporting a student",
                    detail: "Invite the student you're supporting, and look around while you wait.",
                    identifier: "roleChoiceSupporterButton",
                    isLoading: state == .joining,
                    isEnabled: true,
                    action: { Task { await becomeSupporter() } }
                )
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }

    /// One path: the control, and the sentence that says what it leads to. A
    /// helper rather than two transcribed blocks, because "equal paths" is the
    /// whole claim of this screen and two copies is how one of them quietly
    /// grows an extra line.
    private func path(
        title: String,
        detail: String,
        identifier: String,
        isLoading: Bool,
        isEnabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            LoadingButton(
                isLoading ? "Setting you up…" : title,
                isLoading: isLoading,
                role: .primary,
                accessibilityIdentifier: identifier,
                accessibilityLabel: title,
                action: action
            )
            .disabled(!isEnabled)

            Text(detail)
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// `POST /api/v1/supporters`, then hand the home it answered with to the
    /// root state machine. The call is idempotent, so a retry after a failure —
    /// including one where the row WAS written and the response was lost — is
    /// safe and lands on the same home.
    private func becomeSupporter() async {
        state = .joining
        do {
            let home = try await supporterClient.becomeSupporter()
            // Deliberately left at `.joining`: the state machine replaces this
            // whole scene, and clearing the flag first would flash the idle
            // control back for one frame.
            onSupporter(home)
        } catch {
            state = .failed(error as? ErrorResponse ?? .unexpected)
        }
    }
}

@MainActor private var roleChoicePreview: some View {
    RoleChoiceView(
        userName: "Kendall",
        supporterClient: PreviewSupporterClient(),
        onStudent: {},
        onSupporter: { _ in }
    )
}

#Preview("roleChoice - Light") {
    roleChoicePreview
        .preferredColorScheme(.light)
}

#Preview("roleChoice - Dark") {
    roleChoicePreview
        .preferredColorScheme(.dark)
}
