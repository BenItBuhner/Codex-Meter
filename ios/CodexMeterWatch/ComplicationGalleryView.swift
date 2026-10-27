import CodexMeterCore
import SwiftUI

/// Every complication shape for the followed provider, drawn with the same views the watch
/// face uses. Opened with `-watch-complications` for screenshots and design checks.
struct ComplicationGalleryView: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        let date = Date()
        ScrollView {
            VStack(spacing: 10) {
                HStack(spacing: 10) {
                    UsageComplicationView(provider: model.followed, shape: .circular, date: date)
                        .frame(width: 50, height: 50)
                    ResetComplicationView(provider: model.followed, shape: .circular, date: date)
                        .frame(width: 50, height: 50)
                }
                UsageComplicationView(provider: model.followed, shape: .rectangular, date: date)
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                    .padding(8)
                    .background(.gray.opacity(0.18), in: .rect(cornerRadius: 12))
                ResetComplicationView(provider: model.followed, shape: .rectangular, date: date)
                    .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
                    .padding(8)
                    .background(.gray.opacity(0.18), in: .rect(cornerRadius: 12))
                UsageComplicationView(provider: model.followed, shape: .inline, date: date)
                    .font(.footnote)
                    .lineLimit(1)
            }
            .padding(.horizontal, 4)
        }
        .navigationTitle("Complications")
    }
}
