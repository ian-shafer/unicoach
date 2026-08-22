import SwiftUI

struct OnboardingView: View {
    @StateObject private var viewModel: OnboardingViewModel

    /// The signed-in user's name, for the reference's "WELCOME, KENDALL"
    /// overline. Non-optional because `UserAuthState.onboarding` always carries
    /// a `PublicUser`, whose `name` is itself non-optional.
    private let userName: String

    init(studentClient: StudentClientProtocol, userName: String, onComplete: @escaping () -> Void, year: Int = Calendar.current.component(.year, from: Date())) {
        _viewModel = StateObject(wrappedValue: OnboardingViewModel(studentClient: studentClient, onComplete: onComplete, year: year))
        self.userName = userName
    }

    var body: some View {
        VStack(spacing: 0) {
            BrandTopBar()

            ScrollView {
                VStack(alignment: .leading, spacing: DSSpacing.lg) {
                    Text("Welcome, \(userName)")
                        .dsOverlineStyle()
                        .foregroundStyle(Color.dsTextPrimary)

                    Text("I need some more info to help dial in how I can help you.")
                        .font(.dsBody)
                        .foregroundStyle(Color.dsTextSecondary)

                    // Account → profile → coaching. Onboarding is the middle
                    // step: the account exists, the coaching starts once this
                    // form submits.
                    StepIndicator(count: 3, current: 1)

                    Text("When do you graduate?")
                        .font(.dsDisplay)
                        .foregroundStyle(Color.dsTextPrimary)

                    precisionPicker

                    yearOptions

                    monthAndDay

                    if let errorResponse = viewModel.errorResponse {
                        FormErrorBanner(errorResponse.message)
                    }

                    LoadingButton(
                        "Create Profile",
                        isLoading: viewModel.isLoading,
                        role: .primary,
                        accessibilityIdentifier: "createProfileButton",
                        accessibilityLabel: "Create Profile",
                        progressAccessibilityIdentifier: "loadingIndicator",
                        action: { Task { await viewModel.submit() } }
                    )
                }
                .padding(DSSpacing.lg)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }

    private var precisionPicker: some View {
        SegmentedSelector(
            options: [
                (tag: OnboardingViewModel.Precision.year, title: "Year"),
                (tag: OnboardingViewModel.Precision.yearMonth, title: "Year & Month"),
                (tag: OnboardingViewModel.Precision.full, title: "Full Date"),
            ],
            selection: $viewModel.precision,
            accessibilityIdentifier: "precisionPicker"
        )
    }

    /// The graduation year as option cards — the reference's signature control,
    /// bound to exactly the range and setter the wheel picker used, so the
    /// choice this screen offers is unchanged.
    private var yearOptions: some View {
        VStack(spacing: DSControl.stackGap) {
            ForEach(Array(viewModel.yearRange), id: \.self) { year in
                OptionCard(
                    String(format: "%04d", year),
                    isSelected: viewModel.year == year,
                    accessibilityIdentifier: "yearOption"
                ) {
                    viewModel.setYear(year)
                }
            }
        }
        .accessibilityIdentifier("yearPicker")
    }

    /// Month and day, shown only at the precisions that use them. Menu pickers
    /// in a control-shaped row rather than wheels: a wheel is stock chrome with
    /// no place in this motif, and the row reads as the same object as a field
    /// or an option card.
    @ViewBuilder
    private var monthAndDay: some View {
        VStack(spacing: DSControl.stackGap) {
            if viewModel.precision != .year {
                pickerRow(label: "Month", accessibilityIdentifier: "monthPicker") {
                    Picker("Month", selection: $viewModel.month) {
                        ForEach(1 ... 12, id: \.self) { month in
                            Text(monthName(month)).tag(month)
                        }
                    }
                    .onChange(of: viewModel.month) { _, newValue in
                        viewModel.setMonth(newValue)
                    }
                }
            }

            if viewModel.precision == .full {
                pickerRow(label: "Day", accessibilityIdentifier: "dayPicker") {
                    Picker("Day", selection: $viewModel.day) {
                        ForEach(Array(viewModel.dayRange), id: \.self) { day in
                            Text("\(day)").tag(day)
                        }
                    }
                }
            }
        }
    }

    private func pickerRow<Content: View>(
        label: String,
        accessibilityIdentifier: String,
        @ViewBuilder picker: () -> Content
    ) -> some View {
        HStack(spacing: DSSpacing.md) {
            Text(label)
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextSecondary)
            Spacer(minLength: 0)
            picker()
                .pickerStyle(.menu)
                .labelsHidden()
                .font(.dsBody)
                .tint(Color.dsTextPrimary)
        }
        .padding(.horizontal, DSControl.textInset)
        .frame(maxWidth: .infinity, minHeight: DSControl.height, alignment: .leading)
        .background(Color.dsSurface)
        .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
        )
        .accessibilityIdentifier(accessibilityIdentifier)
    }

    private func monthName(_ month: Int) -> String {
        let symbols = Calendar.current.monthSymbols
        guard month >= 1 && month <= symbols.count else {
            return "\(month)"
        }
        return symbols[month - 1]
    }
}

private final class OnboardingPreviewStudentClient: StudentClientProtocol, @unchecked Sendable {
    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent {
        PublicStudent(id: UUID(), expectedHighSchoolGraduationDate: request.expectedHighSchoolGraduationDate, version: 1, createdAt: Date(), updatedAt: Date())
    }
    func fetchProfile() async throws -> PublicStudent? { nil }
}

@MainActor private var onboardingPreview: some View {
    OnboardingView(
        studentClient: OnboardingPreviewStudentClient(),
        userName: "Kendall",
        onComplete: {},
        year: 2028
    )
}

#Preview("onboarding - Light") {
    onboardingPreview
        .preferredColorScheme(.light)
}

#Preview("onboarding - Dark") {
    onboardingPreview
        .preferredColorScheme(.dark)
}
