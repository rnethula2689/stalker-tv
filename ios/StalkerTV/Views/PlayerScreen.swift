import SwiftUI

/// Full-screen player. Wraps the VLCKit view and a tap-to-dismiss overlay.
struct PlayerScreen: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var showControls = true

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            VLCPlayerView(url: url)
                .ignoresSafeArea()
                .onTapGesture { withAnimation { showControls.toggle() } }

            if showControls {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title)
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(.white)
                        .padding()
                }
                .padding(.top, 8)
            }
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
    }
}
