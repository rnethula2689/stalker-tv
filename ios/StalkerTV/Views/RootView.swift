import SwiftUI

/// Entry point. Shows the account list if there's no active provider yet,
/// otherwise jumps straight into browsing.
struct RootView: View {
    @EnvironmentObject var configs: Configs

    var body: some View {
        NavigationStack {
            if configs.active == nil {
                AccountsView()
            } else {
                HomeView()
            }
        }
    }
}

/// Top-level browse hub for the active provider (Live TV today; VOD/Series wired
/// to the portal client and ready to add as additional tabs).
struct HomeView: View {
    @EnvironmentObject var configs: Configs

    var body: some View {
        List {
            Section("Browse") {
                NavigationLink {
                    LiveChannelsView()
                } label: {
                    Label("Live TV", systemImage: "tv")
                }
            }
            Section("Provider") {
                if let a = configs.active {
                    Text(a.name).foregroundStyle(.secondary)
                }
                NavigationLink {
                    AccountsView()
                } label: {
                    Label("Switch / manage providers", systemImage: "person.2")
                }
            }
        }
        .navigationTitle("StalkerTV")
    }
}
