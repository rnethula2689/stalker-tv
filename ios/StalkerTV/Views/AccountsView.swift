import SwiftUI

/// Add / edit / select IPTV providers. Equivalent to the Android settings screen
/// where you enter Portal URL + MAC + Serial Number.
struct AccountsView: View {
    @EnvironmentObject var configs: Configs
    @State private var showingEditor = false

    var body: some View {
        List {
            if configs.accounts.isEmpty {
                ContentUnavailableView(
                    "No providers yet",
                    systemImage: "antenna.radiowaves.left.and.right",
                    description: Text("Add your Stalker/Ministra portal to get started.")
                )
            }
            ForEach(Array(configs.accounts.enumerated()), id: \.element.id) { index, account in
                Button {
                    configs.setActive(index)
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(account.name).font(.headline)
                            Text(account.portal).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if index == configs.activeIndex {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint)
                        }
                    }
                }
                .swipeActions {
                    Button(role: .destructive) { configs.remove(at: index) } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        }
        .navigationTitle("Providers")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showingEditor = true } label: { Image(systemName: "plus") }
            }
        }
        .sheet(isPresented: $showingEditor) {
            AccountEditor()
        }
    }
}

struct AccountEditor: View {
    @EnvironmentObject var configs: Configs
    @Environment(\.dismiss) private var dismiss

    @State private var name = "My Provider"
    @State private var portal = ""
    @State private var mac = "00:1A:79:"
    @State private var sn = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Provider") {
                    TextField("Display name", text: $name)
                }
                Section("Portal") {
                    TextField("Portal URL (http://host:port)", text: $portal)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("MAC address", text: $mac)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    TextField("Serial Number (optional)", text: $sn)
                        .autocorrectionDisabled()
                }
            }
            .navigationTitle("Add provider")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let account = Account(
                            name: name.trimmingCharacters(in: .whitespaces),
                            portal: portal.trimmingCharacters(in: .whitespaces),
                            mac: mac.trimmingCharacters(in: .whitespaces),
                            sn: sn.trimmingCharacters(in: .whitespaces)
                        )
                        configs.add(account)
                        configs.setActive(configs.accounts.count - 1)
                        dismiss()
                    }
                    .disabled(portal.isEmpty || mac.isEmpty)
                }
            }
        }
    }
}
