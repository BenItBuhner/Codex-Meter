import Charts
import CodexMeterCore
import SwiftUI

struct UsageHistoryDashboardCard: View {
    @Environment(AppModel.self) private var model

    private var hasCharts: Bool {
        model.usage?.fiveHour != nil || model.usage?.weekly != nil || model.usage?.monthly != nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Usage history")
                .font(.headline.bold())
            Text("On-device burn trends improve pace estimates as samples accumulate.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if let usage = model.usage {
                if let fiveHour = usage.fiveHour {
                    UsageBurnChart(
                        title: "5-hour",
                        kind: .fiveHour,
                        window: fiveHour,
                        history: model.fiveHourHistory,
                        observedAt: usage.fetchedAt,
                        compact: true
                    )
                }
                if let weekly = usage.weekly {
                    UsageBurnChart(
                        title: "Weekly",
                        kind: .weekly,
                        window: weekly,
                        history: model.weeklyHistory,
                        observedAt: usage.fetchedAt,
                        compact: true
                    )
                }
                if let monthly = usage.monthly {
                    UsageBurnChart(
                        title: "Monthly",
                        kind: .monthly,
                        window: monthly,
                        history: model.monthlyHistory,
                        observedAt: usage.fetchedAt,
                        compact: true
                    )
                }
            }

            if !hasCharts {
                Label(
                    "Charts appear once OpenAI reports your 5-hour, weekly, or monthly usage windows.",
                    systemImage: "chart.xyaxis.line"
                )
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.vertical, 8)
            }

            NavigationLink {
                UsageHistoryView()
            } label: {
                Text("View history")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
        .padding(AppChrome.cardPadding)
        .cardSurface()
    }
}

struct UsageHistoryView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingClear = false
    @State private var showingCustomize = false

    private var hasCharts: Bool {
        model.usage?.fiveHour != nil || model.usage?.weekly != nil || model.usage?.monthly != nil
    }

    private var hasSamples: Bool {
        !model.fiveHourHistory.samples.isEmpty
            || !model.weeklyHistory.samples.isEmpty
            || !model.monthlyHistory.samples.isEmpty
    }

    private var pricing: PlanPricing? {
        guard model.settings.isHistorySectionVisible(HistorySections.valueEstimates) else {
            return nil
        }
        return PlanPricing.forPlan(model.usage?.planType)
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: AppChrome.sectionSpacing) {
                if model.settings.isHistorySectionVisible(HistorySections.guide) {
                    VStack(alignment: .leading, spacing: 9) {
                        Text("How to read the charts")
                            .font(.title3.bold())
                        Text("The solid line is this window's usage, faint lines are previous windows, the dotted diagonal is a sustainable pace, and the dashed line is the projection. Drag a chart to inspect any moment. Samples are recorded after each successful refresh and stay on this device.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(AppChrome.cardPadding)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cardSurface()
                }

                if let usage = model.usage {
                    if let fiveHour = usage.fiveHour {
                        windowSection(
                            title: "5-hour",
                            kind: .fiveHour,
                            window: fiveHour,
                            history: model.fiveHourHistory,
                            observedAt: usage.fetchedAt
                        )
                    }
                    if let weekly = usage.weekly {
                        windowSection(
                            title: "Weekly",
                            kind: .weekly,
                            window: weekly,
                            history: model.weeklyHistory,
                            observedAt: usage.fetchedAt
                        )
                    }
                    if let monthly = usage.monthly {
                        windowSection(
                            title: "Monthly",
                            kind: .monthly,
                            window: monthly,
                            history: model.monthlyHistory,
                            observedAt: usage.fetchedAt
                        )
                    }
                }

                if !hasCharts {
                    Label(
                        "Charts appear once OpenAI reports your 5-hour, weekly, or monthly usage windows. Refresh usage from the dashboard to check again.",
                        systemImage: "clock.badge.questionmark"
                    )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(AppChrome.cardPadding)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .cardSurface()
                }

                if let pricing, hasCharts {
                    ValueEstimatesCard(usage: model.usage, pricing: pricing)
                }

                Button("Clear local history", role: .destructive) {
                    confirmingClear = true
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .disabled(!hasSamples)
            }
            .frame(maxWidth: AppChrome.contentMaxWidth)
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Usage history")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Customize") {
                    showingCustomize = true
                }
            }
        }
        .sheet(isPresented: $showingCustomize) {
            HistoryCustomizeView()
        }
        .confirmationDialog(
            "Clear usage history?",
            isPresented: $confirmingClear,
            titleVisibility: .visible
        ) {
            Button("Clear", role: .destructive) {
                Task { await model.clearUsageHistory() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes every locally stored usage sample. Your latest allowance and account sign-in stay intact.")
        }
    }

    @ViewBuilder
    private func windowSection(
        title: String,
        kind: UsageHistoryKind,
        window: UsageWindow,
        history: UsageHistory,
        observedAt: Date
    ) -> some View {
        HistoryDetailCard(
            title: title,
            kind: kind,
            window: window,
            history: history,
            observedAt: observedAt,
            pricing: pricing,
            showWindowList: model.settings.isHistorySectionVisible(HistorySections.windowList)
        )
        if let insights = HistoryInsightsCard.model(
            window: window,
            history: history,
            observedAt: observedAt,
            pricing: pricing,
            settings: model.settings
        ) {
            HistoryInsightsCard(model: insights)
        }
    }
}

private struct HistoryCustomizeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(HistorySections.all, id: \.self) { key in
                        Toggle(HistorySections.label(key), isOn: binding(for: key))
                    }
                } header: {
                    Text("Highlights to show")
                } footer: {
                    Text("The charts and clear-history action always stay available. Turning a highlight off declutters this page without deleting samples.")
                }
            }
            .navigationTitle("Customize")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func binding(for key: String) -> Binding<Bool> {
        Binding(
            get: { model.settings.isHistorySectionVisible(key) },
            set: { visible in
                var settings = model.settings
                settings.setHistorySectionVisible(key, visible: visible)
                model.save(settings: settings)
            }
        )
    }
}

private struct HistoryDetailCard: View {
    let title: String
    let kind: UsageHistoryKind
    let window: UsageWindow
    let history: UsageHistory
    let observedAt: Date
    let pricing: PlanPricing?
    let showWindowList: Bool

    @State private var selectedWindowIndex: Int?
    @State private var scrubText: String?

    private var breakdown: [UsageStats.WindowStats] {
        UsageStats.windowBreakdown(history, maximumWindows: 5)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            UsageBurnChart(
                title: title,
                kind: kind,
                window: window,
                history: history,
                observedAt: observedAt,
                compact: false,
                selectedWindowIndex: selectedWindowIndex,
                onScrub: handleScrub,
                onScrubEnd: { scrubText = nil }
            )

            Text(scrubText ?? defaultDetail)
                .font(.caption)
                .foregroundStyle(scrubText == nil ? .secondary : .primary)

            Text(
                "\(history.samples.count) samples · \(history.completedWindowCount) completed \(history.completedWindowCount == 1 ? "window" : "windows")"
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            if showWindowList, breakdown.count > 1 {
                Divider()
                ForEach(Array(breakdown.enumerated().reversed()), id: \.offset) { index, stats in
                    Button {
                        selectedWindowIndex = stats.complete ? index : nil
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(stats.complete ? windowRangeLabel(stats) : "Current window")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(
                                    selectedWindowIndex == index || (!stats.complete && selectedWindowIndex == nil)
                                        ? Color.accentColor : .primary
                                )
                            Text(windowSubtitle(stats))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(AppChrome.cardPadding)
        .cardSurface()
    }

    private var defaultDetail: String {
        showWindowList && breakdown.count > 1
            ? "Drag to inspect · tap a window to compare"
            : "Drag to inspect"
    }

    private func handleScrub(date: Date, usedPercent: Double, historical: Bool) {
        var text = "\(UsageFormat.absolute(date, relativeTo: .now)) — \(Int(usedPercent.rounded()))% used"
        if let pricing {
            text += " · ≈ \(PlanPricing.formatUsd(pricing.estimatedValueUsd(for: kind, usedPercent: usedPercent)))"
        }
        if historical {
            text += " · previous window"
        }
        scrubText = text
    }

    private func windowSubtitle(_ stats: UsageStats.WindowStats) -> String {
        var parts = ["\(stats.finalPercent)% used"]
        if stats.averageBurnPercentPerHour > 0 {
            parts.append("avg \(UsageStats.formatRate(stats.averageBurnPercentPerHour))")
        }
        if let pricing {
            parts.append("≈ \(PlanPricing.formatUsd(pricing.estimatedValueUsd(for: kind, usedPercent: Double(stats.finalPercent))))")
        }
        if stats.exhausted {
            parts.append("hit limit")
        }
        return parts.joined(separator: " · ")
    }

    private func windowRangeLabel(_ stats: UsageStats.WindowStats) -> String {
        let day = DateFormatter()
        day.dateFormat = "MMM d"
        if kind == .weekly || kind == .monthly {
            return "\(day.string(from: stats.windowStart)) – \(day.string(from: stats.resetAt))"
        }
        let time = DateFormatter()
        time.dateFormat = DateFormatter.dateFormat(
            fromTemplate: "jm",
            options: 0,
            locale: .current
        )
        return "\(day.string(from: stats.windowStart)) · \(time.string(from: stats.windowStart)) – \(time.string(from: stats.resetAt))"
    }
}

private struct HistoryInsightsModel {
    var rows: [(label: String, value: String)]
}

private struct HistoryInsightsCard: View {
    let model: HistoryInsightsModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Insights")
                .font(.headline.bold())
            ForEach(Array(model.rows.enumerated()), id: \.offset) { _, row in
                HStack {
                    Text(row.label)
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 12)
                    Text(row.value)
                        .multilineTextAlignment(.trailing)
                }
                .font(.subheadline)
            }
        }
        .padding(AppChrome.cardPadding)
        .cardSurface()
    }

    static func model(
        window: UsageWindow,
        history: UsageHistory,
        observedAt: Date,
        pricing: PlanPricing?,
        settings: AppSettings
    ) -> HistoryInsightsModel? {
        var rows: [(label: String, value: String)] = []
        let now = Date()

        if settings.isHistorySectionVisible(HistorySections.insightPace),
           let resetAt = window.effectiveResetDate(relativeTo: observedAt),
           window.windowSeconds > 0 {
            let remaining = max(0, resetAt.timeIntervalSince(now))
            let elapsedFraction = 1 - min(1, remaining / TimeInterval(window.windowSeconds))
            if let typical = UsageStats.typicalUsedPercent(in: history, atElapsedFraction: elapsedFraction) {
                let delta = Int((Double(window.usedPercent) - typical).rounded())
                let value: String
                if delta >= 2 {
                    value = "\(delta) pts ahead of typical"
                } else if delta <= -2 {
                    value = "\(-delta) pts behind typical"
                } else {
                    value = "On par with typical"
                }
                rows.append(("Pace vs. previous windows", value))
            }
        }

        if settings.isHistorySectionVisible(HistorySections.insightExhaustion) {
            let pace = UsagePace.assess(
                window: window,
                history: history,
                observedAt: observedAt,
                now: now,
                sensitivity: .balanced
            )
            if pace.isAvailable, let exhaustion = pace.estimatedExhaustionAt {
                rows.append(("Projected exhaustion", UsageFormat.relative(until: exhaustion, from: now)))
            }
        }

        if settings.isHistorySectionVisible(HistorySections.insightAverage),
           let average = UsageStats.averageFinalPercent(history) {
            rows.append(("Avg. completed window", "\(Int(average.rounded()))% used"))
        }

        if settings.isHistorySectionVisible(HistorySections.insightPeak) {
            let peak = UsageStats.peakBurnPercentPerHour(history)
            if peak > 0 {
                var value = UsageStats.formatRate(peak)
                if let pricing {
                    value += " · ≈ \(PlanPricing.formatUsd(pricing.windowValueUsd(for: history.kind) * peak / 100))/h"
                }
                rows.append(("Peak burn observed", value))
            }
        }

        if let pricing {
            rows.append((
                "Est. value used this window",
                "≈ \(PlanPricing.formatUsd(pricing.estimatedValueUsd(for: history.kind, usedPercent: Double(window.usedPercent)))) of \(PlanPricing.formatUsd(pricing.windowValueUsd(for: history.kind)))"
            ))
        }

        return rows.isEmpty ? nil : HistoryInsightsModel(rows: rows)
    }
}

private struct ValueEstimatesCard: View {
    let usage: UsageSnapshot?
    let pricing: PlanPricing

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(pricing.planLabel) · \(PlanPricing.formatUsd(pricing.monthlyPriceUsd))/month")
                .font(.headline.bold())
            estimateRow("Est. included usage", PlanPricing.formatUsd(pricing.monthlyValueUsd) + "/month")
            estimateRow("Weekly allowance", PlanPricing.formatUsd(pricing.weeklyValueUsd))
            estimateRow("5-hour allowance", PlanPricing.formatUsd(pricing.fiveHourValueUsd))
            estimateRow("Vs. subscription price", "≈ \(Int(pricing.valueMultiplier.rounded()))x the monthly cost")
            if let weekly = usage?.weekly {
                estimateRow(
                    "Weekly value remaining",
                    PlanPricing.formatUsd(
                        pricing.estimatedValueUsd(for: .weekly, usedPercent: Double(weekly.remainingPercent))
                    )
                )
            }
            if let monthly = usage?.monthly, usage?.weekly == nil {
                estimateRow(
                    "Monthly value remaining",
                    PlanPricing.formatUsd(
                        pricing.estimatedValueUsd(for: .monthly, usedPercent: Double(monthly.remainingPercent))
                    )
                )
            }
            Text("Rough community estimates comparing plan allowances with API pricing; not a billing statement.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
        }
        .padding(AppChrome.cardPadding)
        .cardSurface()
    }

    private func estimateRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text("≈ \(value)")
        }
        .font(.subheadline)
    }
}

private struct UsageBurnChart: View {
    let title: String
    let kind: UsageHistoryKind
    let window: UsageWindow
    let history: UsageHistory
    let observedAt: Date
    let compact: Bool
    var selectedWindowIndex: Int?
    var onScrub: ((Date, Double, Bool) -> Void)?
    var onScrubEnd: (() -> Void)?

    private var windows: [[UsageSample]] {
        history.recentWindows(maximum: 5)
    }

    private var currentPoints: [UsageChartPoint] {
        Self.points(for: history.currentWindowSamples, series: "Current", prefix: "current")
    }

    private var historicalPoints: [UsageChartPoint] {
        guard windows.count > 1 else { return [] }
        return windows.dropLast().enumerated().flatMap { index, samples in
            Self.points(
                for: samples,
                series: "History \(index)",
                prefix: "history-\(index)"
            )
        }
    }

    private var pace: UsagePaceAssessment {
        UsagePace.assess(
            window: window,
            history: history,
            observedAt: observedAt,
            now: .now,
            sensitivity: .balanced
        )
    }

    private var projectionPoints: [UsageChartPoint] {
        guard pace.isAvailable,
              let latest = history.currentWindowSamples.last,
              let resetAt = window.effectiveResetDate(relativeTo: observedAt),
              let exhaustionAt = pace.estimatedExhaustionAt
        else {
            return []
        }
        let startAt = resetAt.addingTimeInterval(-TimeInterval(window.windowSeconds))
        guard resetAt > startAt else { return [] }
        let from = Self.progress(for: latest.observedAt, start: startAt, reset: resetAt)
        let projectedDate = min(resetAt, exhaustionAt)
        let to = Self.progress(for: projectedDate, start: startAt, reset: resetAt)
        let projectedUsed = exhaustionAt <= resetAt
            ? 100 : latest.usedPercent
        return [
            UsageChartPoint(
                id: "projection-start",
                progress: from,
                usedPercent: Double(latest.usedPercent),
                series: "Projection"
            ),
            UsageChartPoint(
                id: "projection-end",
                progress: to,
                usedPercent: Double(projectedUsed),
                series: "Projection"
            )
        ]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Text(title)
                    .font(.subheadline.bold())
                Spacer(minLength: 8)
                Text(currentPoints.count < 2 ? "Building history" : "\(currentPoints.count) samples")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Chart {
                LineMark(
                    x: .value("Window", 0.0),
                    y: .value("Used", 0.0),
                    series: .value("Series", "Budget")
                )
                .foregroundStyle(.secondary.opacity(0.5))
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 5]))
                LineMark(
                    x: .value("Window", 1.0),
                    y: .value("Used", 100.0),
                    series: .value("Series", "Budget")
                )
                .foregroundStyle(.secondary.opacity(0.5))
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 5]))

                ForEach(historicalPoints) { point in
                    LineMark(
                        x: .value("Window", point.progress),
                        y: .value("Used", point.usedPercent),
                        series: .value("Series", point.series)
                    )
                    .foregroundStyle(.secondary.opacity(selectedOpacity(for: point.series)))
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
                }

                ForEach(currentPoints) { point in
                    LineMark(
                        x: .value("Window", point.progress),
                        y: .value("Used", point.usedPercent),
                        series: .value("Series", point.series)
                    )
                    .foregroundStyle(Color.accentColor)
                    .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                }

                ForEach(projectionPoints) { point in
                    LineMark(
                        x: .value("Window", point.progress),
                        y: .value("Used", point.usedPercent),
                        series: .value("Series", point.series)
                    )
                    .foregroundStyle(pace.isAccelerated ? Color.orange : Color.accentColor.opacity(0.7))
                    .lineStyle(StrokeStyle(lineWidth: 2, dash: [7, 5]))
                }
            }
            .chartXScale(domain: 0.0 ... 1.0)
            .chartYScale(domain: 0.0 ... 100.0)
            .chartXAxis(.hidden)
            .chartYAxis {
                AxisMarks(values: [0, 50, 100]) {
                    AxisGridLine()
                    AxisValueLabel()
                }
            }
            .chartLegend(.hidden)
            .frame(height: compact ? 112 : 190)
            .chartOverlay { proxy in
                GeometryReader { geometry in
                    Rectangle()
                        .fill(.clear)
                        .contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { value in
                                    handleDrag(at: value.location, proxy: proxy, geometry: geometry)
                                }
                                .onEnded { _ in
                                    onScrubEnd?()
                                }
                        )
                }
            }
            .accessibilityLabel("\(title) usage burn chart")
            .accessibilityValue(chartAccessibilityValue)

            HStack {
                Text("0%")
                Spacer()
                Text("reset")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)

            if pace.isAvailable, let exhaustionAt = pace.estimatedExhaustionAt {
                Label {
                    if pace.isAccelerated {
                        Text("Current pace may exhaust allowance \(exhaustionAt, style: .relative).")
                    } else {
                        Text("Estimated exhaustion \(exhaustionAt, style: .relative).")
                    }
                } icon: {
                    Image(systemName: pace.isAccelerated
                        ? "exclamationmark.triangle.fill" : "speedometer")
                }
                .font(.caption)
                .foregroundStyle(pace.isAccelerated ? .orange : .secondary)
            }
        }
    }

    private var chartAccessibilityValue: String {
        var value = currentPoints.count < 2
            ? "Building local history"
            : "\(currentPoints.count) local samples"
        if pace.isAvailable {
            value += pace.isAccelerated
                ? ", accelerated pace detected"
                : ", pace estimate available"
        }
        return value
    }

    private func selectedOpacity(for series: String) -> Double {
        guard let selectedWindowIndex else { return 0.25 }
        return series == "History \(selectedWindowIndex)" ? 0.7 : 0.12
    }

    private func handleDrag(at location: CGPoint, proxy: ChartProxy, geometry: GeometryProxy) {
        guard let onScrub,
              let plotFrame = proxy.plotFrame else { return }
        let frame = geometry[plotFrame]
        let x = location.x - frame.origin.x
        guard let progress: Double = proxy.value(atX: x) else { return }
        let clamped = min(1, max(0, progress))

        let samples: [UsageSample]
        let historical: Bool
        if let selectedWindowIndex, windows.indices.contains(selectedWindowIndex) {
            samples = windows[selectedWindowIndex]
            historical = selectedWindowIndex < windows.count - 1
        } else {
            samples = history.currentWindowSamples
            historical = false
        }
        guard let first = samples.first ?? history.currentWindowSamples.first else { return }
        let start = first.resetAt.addingTimeInterval(-TimeInterval(first.windowSeconds))
        let date = start.addingTimeInterval(clamped * TimeInterval(first.windowSeconds))
        let used = UsageStats.usedPercent(at: date, in: samples)
            ?? interpolateOutside(date: date, samples: samples, start: start)
        onScrub(date, used, historical)
    }

    private func interpolateOutside(date: Date, samples: [UsageSample], start: Date) -> Double {
        guard let first = samples.first, let last = samples.last else {
            return Double(window.usedPercent)
        }
        if date <= first.observedAt { return Double(first.usedPercent) }
        if date >= last.observedAt { return Double(last.usedPercent) }
        return Double(window.usedPercent)
    }

    private static func points(
        for samples: [UsageSample],
        series: String,
        prefix: String
    ) -> [UsageChartPoint] {
        samples.enumerated().compactMap { index, sample in
            let startAt = sample.resetAt.addingTimeInterval(-TimeInterval(sample.windowSeconds))
            guard sample.resetAt > startAt else { return nil }
            return UsageChartPoint(
                id: "\(prefix)-\(index)-\(sample.observedAt.timeIntervalSince1970)",
                progress: progress(
                    for: sample.observedAt,
                    start: startAt,
                    reset: sample.resetAt
                ),
                usedPercent: Double(sample.usedPercent),
                series: series
            )
        }
    }

    private static func progress(for date: Date, start: Date, reset: Date) -> Double {
        guard reset > start else { return 0 }
        return min(1, max(0, date.timeIntervalSince(start) / reset.timeIntervalSince(start)))
    }
}

private struct UsageChartPoint: Identifiable {
    let id: String
    let progress: Double
    let usedPercent: Double
    let series: String
}
