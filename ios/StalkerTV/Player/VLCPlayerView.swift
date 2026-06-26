import SwiftUI
import MobileVLCKit

/// SwiftUI wrapper around MobileVLCKit's `VLCMediaPlayer`. This is the iOS
/// equivalent of the Android app's `LiveVlcActivity` — libVLC handles the
/// arbitrary codecs/protocols (HLS, MPEG-TS, AC-3, etc.) that AVPlayer won't.
struct VLCPlayerView: UIViewRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        let player = context.coordinator.player
        player.drawable = view
        // Spoof the same STB user-agent the portal expects on the stream fetch.
        let media = VLCMedia(url: url)
        media.addOption(":http-user-agent=\(StalkerPortal.userAgent)")
        media.addOption(":network-caching=1500")
        player.media = media
        player.play()
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.player.stop()
    }

    final class Coordinator {
        let player = VLCMediaPlayer()
    }
}
