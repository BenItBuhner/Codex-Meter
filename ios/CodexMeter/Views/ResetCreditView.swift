import CodexMeterCore
import SwiftUI

struct ResetCreditView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirming = false

    private var count: Int {
        model.credits?.availableCount ?? model.usage?.resetCreditsAvailable ?? 0
    }

    private var nextExpiry: Date? {
        model.credits?.credits
            .filter(\.isAvailable)
            .compactMap(\.expiresAt)
            .filter { $0 > .now }
            .min()
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                VStack(spacing: 16) {
                    Image(systemName: "arrow.counterclockwise.circle.fill")
                        .font(.system(size: 52))
                        .foregroundStyle(.tint)
                        .symbolRenderingMode(.hierarchical)
                        .accessibilityHidden(true)

                    VStack(spacing: 6) {
                        Text("\(count) reset \(count == 1 ? "credit" : "credits") available")
                            .font(.title2.bold())
                        if let nextExpiry {
                            Text("Next credit expires \(nextExpiry, style: .relative) (\(nextExpiry, format: .dateTime.month().day().hour().minute())).")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        } else if count > 0 {
                            Text("Expiry details are unavailable; OpenAI will choose an eligible credit.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                    }

                    Text("Using a credit asks OpenAI to reset the currently used Codex rate-limit windows. The app reloads usage and the remaining credit inventory afterward.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(AppChrome.cardPadding)
                .frame(maxWidth: .infinity)
                .cardSurface()

                if let error = model.visibleError, model.resetResultMessage == nil {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .bannerSurface(tint: .orange)
                }

                if let result = model.resetResultMessage {
                    Label(
                        result,
                        systemImage: model.resetResultIsSuccess == true
                            ? "checkmark.circle.fill"
                            : "exclamationmark.circle.fill"
                    )
                    .foregroundStyle(model.resetResultIsSuccess == true ? .green : .orange)
                    .bannerSurface(
                        tint: model.resetResultIsSuccess == true ? .green : .orange
                    )
                }

                Button {
                    confirming = true
                } label: {
                    if model.isRedeemingReset {
                        HStack {
                            ProgressView()
                            Text("Applying…")
                        }
                    } else {
                        Text(count > 0 ? "Use reset" : "No reset available")
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(count == 0 || model.isRedeemingReset)

                if model.resetResultIsSuccess == true {
                    Button("Done") { dismiss() }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                }
            }
            .frame(maxWidth: 560)
            .padding(28)
            .frame(maxWidth: .infinity)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Codex reset")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Close") { dismiss() }
            }
        }
        .alert("Use one Codex reset?", isPresented: $confirming) {
            Button("Cancel", role: .cancel) {}
            Button("Use reset", role: .destructive) {
                Task { await model.consumeResetCredit() }
            }
        } message: {
            Text("This action consumes one available reset credit and cannot be undone.")
        }
    }
}
