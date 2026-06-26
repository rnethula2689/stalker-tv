import SwiftUI

@MainActor
final class LiveChannelsModel: ObservableObject {
    @Published var genres: [Genre] = []
    @Published var channels: [Channel] = []
    @Published var loading = false
    @Published var error: String?
    @Published var selectedGenre: Genre?

    private let portal = StalkerPortal.shared

    func start(_ account: Account) async {
        loading = true
        error = nil
        await portal.configure(portal: account.portal, mac: account.mac, sn: account.sn)
        if let err = await portal.connect() {
            error = err
            loading = false
            return
        }
        genres = await portal.liveGenres()
        channels = await portal.liveChannels()
        loading = false
    }

    func pick(_ genre: Genre) async {
        selectedGenre = genre
        loading = true
        channels = await portal.itvByGenre(genre.id)
        loading = false
    }

    /// Resolve the playable stream URL for a channel (create_link).
    func resolve(_ channel: Channel) async -> URL? {
        guard let s = await portal.createLink(channel.cmd), let u = URL(string: s) else { return nil }
        return u
    }
}

struct LiveChannelsView: View {
    @EnvironmentObject var configs: Configs
    @StateObject private var model = LiveChannelsModel()
    @State private var playerURL: URL?
    @State private var resolving = false

    var body: some View {
        Group {
            if model.loading && model.channels.isEmpty {
                ProgressView("Connecting…")
            } else if let err = model.error {
                ContentUnavailableView("Couldn't connect", systemImage: "wifi.exclamationmark", description: Text(err))
            } else {
                List {
                    if !model.genres.isEmpty {
                        Section("Folders") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack {
                                    ForEach(model.genres) { genre in
                                        Button(genre.title) {
                                            Task { await model.pick(genre) }
                                        }
                                        .buttonStyle(.bordered)
                                        .tint(model.selectedGenre?.id == genre.id ? .accentColor : .secondary)
                                    }
                                }
                            }
                        }
                    }
                    Section(model.selectedGenre?.title ?? "All channels") {
                        ForEach(model.channels) { channel in
                            Button {
                                Task { await play(channel) }
                            } label: {
                                HStack {
                                    Text(channel.number).font(.caption.monospaced())
                                        .foregroundStyle(.secondary).frame(width: 44, alignment: .trailing)
                                    Text(channel.name)
                                    Spacer()
                                    if channel.archiveDays > 0 {
                                        Image(systemName: "clock.arrow.circlepath").foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Live TV")
        .overlay { if resolving { ProgressView("Opening…").padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
        .fullScreenCover(item: $playerURL) { url in
            PlayerScreen(url: url)
        }
        .task {
            if let account = configs.active, model.channels.isEmpty {
                await model.start(account)
            }
        }
    }

    private func play(_ channel: Channel) async {
        resolving = true
        defer { resolving = false }
        playerURL = await model.resolve(channel)
    }
}

// Allow URL to drive .fullScreenCover(item:)
extension URL: Identifiable { public var id: String { absoluteString } }
