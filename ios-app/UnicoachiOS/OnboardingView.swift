import SwiftUI

struct OnboardingView: View {
    @StateObject private var viewModel: OnboardingViewModel

    init(studentClient: StudentClientProtocol, onComplete: @escaping () -> Void, year: Int = Calendar.current.component(.year, from: Date())) {
        _viewModel = StateObject(wrappedValue: OnboardingViewModel(studentClient: studentClient, onComplete: onComplete, year: year))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                Text("When do you graduate?")
                    .font(.dsTitleXL)
                    .foregroundStyle(Color.dsTextPrimary)

                Text("Tell us your expected high school graduation date so we can tailor your coaching.")
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextSecondary)

                precisionPicker

                pickers

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
        .background(Color.dsBackground)
    }

    private var precisionPicker: some View {
        Picker("Precision", selection: $viewModel.precision) {
            Text("Year").tag(OnboardingViewModel.Precision.year)
            Text("Year & Month").tag(OnboardingViewModel.Precision.yearMonth)
            Text("Full Date").tag(OnboardingViewModel.Precision.full)
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("precisionPicker")
    }

    @ViewBuilder
    private var pickers: some View {
        VStack(spacing: DSSpacing.md) {
            Picker("Year", selection: $viewModel.year) {
                ForEach(Array(viewModel.yearRange), id: \.self) { year in
                    Text(String(format: "%04d", year)).tag(year)
                }
            }
            .pickerStyle(.wheel)
            .accessibilityIdentifier("yearPicker")
            .onChange(of: viewModel.year) { _, newValue in
                viewModel.setYear(newValue)
            }

            if viewModel.precision != .year {
                Picker("Month", selection: $viewModel.month) {
                    ForEach(1...12, id: \.self) { month in
                        Text(monthName(month)).tag(month)
                    }
                }
                .pickerStyle(.wheel)
                .accessibilityIdentifier("monthPicker")
                .onChange(of: viewModel.month) { _, newValue in
                    viewModel.setMonth(newValue)
                }
            }

            if viewModel.precision == .full {
                Picker("Day", selection: $viewModel.day) {
                    ForEach(Array(viewModel.dayRange), id: \.self) { day in
                        Text("\(day)").tag(day)
                    }
                }
                .pickerStyle(.wheel)
                .accessibilityIdentifier("dayPicker")
            }
        }
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

#Preview {
    OnboardingView(
        studentClient: OnboardingPreviewStudentClient(),
        onComplete: {},
        year: 2028
    )
}
