import SwiftUI
import UIKit

struct DiagnosticsView: View {
    @State private var enabled = DiagnosticLog.isEnabled
    @State private var stats = DiagnosticLog.stats()
    @State private var exportURL: URL?
    @State private var isExporting = false
    @State private var confirmingClear = false

    var body: some View {
        List {
            Section {
                Toggle("Write diagnostic logs", isOn: $enabled)
                    .onChange(of: enabled) { _, isOn in
                        DiagnosticLog.setEnabled(isOn)
                        refreshStats()
                    }
            } footer: {
                Text("Logs stay on this device as sanitized JSONL. Credentials, emails, JWTs, and OAuth query parameters are redacted. Nothing is uploaded.")
            }

            Section("Stored logs") {
                LabeledContent("Size", value: DiagnosticLog.formatBytes(stats.bytes))
                LabeledContent("Files", value: "\(stats.files)")
                Button("Export logs") {
                    exportLogs()
                }
                .disabled(!stats.hasLogs)
                Button("Clear logs", role: .destructive) {
                    confirmingClear = true
                }
                .disabled(!stats.hasLogs)
            }
        }
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: refreshStats)
        .sheet(isPresented: $isExporting) {
            if let exportURL {
                ShareSheet(items: [exportURL])
            }
        }
        .confirmationDialog(
            "Clear diagnostic logs?",
            isPresented: $confirmingClear,
            titleVisibility: .visible
        ) {
            Button("Clear", role: .destructive) {
                DiagnosticLog.clear()
                refreshStats()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes every locally stored diagnostic log file.")
        }
    }

    private func refreshStats() {
        stats = DiagnosticLog.stats()
    }

    private func exportLogs() {
        do {
            exportURL = try DiagnosticLog.exportURL()
            isExporting = exportURL != nil
            refreshStats()
        } catch {
            exportURL = nil
        }
    }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
