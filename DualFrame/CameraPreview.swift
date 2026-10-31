import AVFoundation
import SwiftUI

final class PreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

/// Anteprima della fotocamera: riempie il riquadro 3:4 con l'intero fotogramma 4:3 del grandangolo.
struct CameraPreview: UIViewRepresentable {
    let model: CameraModel

    func makeUIView(context: Context) -> PreviewUIView {
        let v = PreviewUIView()
        v.backgroundColor = .clear
        v.previewLayer.session = model.session
        v.previewLayer.videoGravity = .resizeAspectFill
        model.previewLayer = v.previewLayer
        return v
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {
        model.applyPreviewRotation()
    }
}
