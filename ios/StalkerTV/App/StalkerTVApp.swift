import SwiftUI

@main
struct StalkerTVApp: App {
    @StateObject private var configs = Configs.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(configs)
                .preferredColorScheme(.dark)
        }
    }
}
