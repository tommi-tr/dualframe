import AVFoundation
import CoreImage

/// Scrive uno dei due video (9:16 o 16:9) in tempo reale.
/// Tutti i metodi vanno chiamati dalla coda video della fotocamera.
final class ClipWriter {
    let url: URL
    let outputSize: CGSize
    let isPrimary: Bool

    private let writer: AVAssetWriter
    private let videoInput: AVAssetWriterInput
    private let audioInput: AVAssetWriterInput?
    private let adaptor: AVAssetWriterInputPixelBufferAdaptor
    private let colorSpace = CGColorSpaceCreateDeviceRGB()
    private(set) var started = false

    init(url: URL, size: CGSize, fps: Int, codec: Codec, audioSettings: [String: Any]?, isPrimary: Bool) throws {
        self.url = url
        self.outputSize = size
        self.isPrimary = isPrimary
        try? FileManager.default.removeItem(at: url)
        writer = try AVAssetWriter(outputURL: url, fileType: .mp4)

        let pixels = Double(size.width * size.height)
        let bitsPerPixel = codec == .hevc ? 0.10 : 0.16
        let compression: [String: Any] = [
            AVVideoAverageBitRateKey: Int(pixels * Double(fps) * bitsPerPixel),
            AVVideoExpectedSourceFrameRateKey: fps,
            AVVideoMaxKeyFrameIntervalKey: fps
        ]
        let videoSettings: [String: Any] = [
            AVVideoCodecKey: codec == .hevc ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
            AVVideoWidthKey: Int(size.width),
            AVVideoHeightKey: Int(size.height),
            AVVideoCompressionPropertiesKey: compression
        ]
        videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        videoInput.expectsMediaDataInRealTime = true
        adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: Int(size.width),
                kCVPixelBufferHeightKey as String: Int(size.height),
                kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()
            ]
        )
        guard writer.canAdd(videoInput) else { throw NSError(domain: "DualFrame", code: 1) }
        writer.add(videoInput)

        if let audioSettings {
            let a = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
            a.expectsMediaDataInRealTime = true
            if writer.canAdd(a) {
                writer.add(a)
                audioInput = a
            } else {
                audioInput = nil
            }
        } else {
            audioInput = nil
        }
    }

    func appendVideo(_ image: CIImage, at time: CMTime, context: CIContext) {
        if !started {
            guard writer.startWriting() else { return }
            writer.startSession(atSourceTime: time)
            started = true
        }
        guard writer.status == .writing, videoInput.isReadyForMoreMediaData,
              let pool = adaptor.pixelBufferPool else { return }
        var buffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
        guard let pb = buffer else { return }
        context.render(image, to: pb, bounds: CGRect(origin: .zero, size: outputSize), colorSpace: colorSpace)
        adaptor.append(pb, withPresentationTime: time)
    }

    func appendAudio(_ sample: CMSampleBuffer) {
        guard started, writer.status == .writing, let a = audioInput, a.isReadyForMoreMediaData else { return }
        a.append(sample)
    }

    func finish(_ completion: @escaping (URL?) -> Void) {
        guard started, writer.status == .writing else {
            writer.cancelWriting()
            completion(nil)
            return
        }
        videoInput.markAsFinished()
        audioInput?.markAsFinished()
        writer.finishWriting { [writer, url] in
            completion(writer.status == .completed ? url : nil)
        }
    }
}

/// Ritaglia un'immagine già orientata come il mirino e la raddrizza secondo l'angolo del telefono.
enum CropRenderer {
    /// Porta l'origine dell'immagine a (0,0).
    static func normalized(_ image: CIImage) -> CIImage {
        image.transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
    }

    /// Rotazione oraria necessaria per raddrizzare il ritaglio.
    static func orientation(for angle: Int) -> CGImagePropertyOrientation {
        switch angle {
        case 90: return .right
        case 180: return .down
        case 270: return .left
        default: return .up
        }
    }

    /// - Parameters:
    ///   - base: fotogramma orientato come il mirino (verticale 3:4), origine (0,0)
    ///   - rect: ritaglio normalizzato (origine in alto a sinistra)
    static func render(base: CIImage, rect: NormRect, angle: Int, outputSize: CGSize) -> CIImage {
        let w = base.extent.width
        let h = base.extent.height
        // Core Image ha l'origine in basso a sinistra.
        let r = CGRect(x: rect.left * w, y: (1 - rect.bottom) * h, width: rect.width * w, height: rect.height * h)
        var img = base.cropped(to: r)
            .transformed(by: CGAffineTransform(translationX: -r.minX, y: -r.minY))
        img = normalized(img.oriented(orientation(for: angle)))
        let sx = outputSize.width / max(1, img.extent.width)
        let sy = outputSize.height / max(1, img.extent.height)
        // clampedToExtent evita bordi sfumati quando si scala il ritaglio.
        img = img.clampedToExtent().transformed(by: CGAffineTransform(scaleX: sx, y: sy))
        return img.cropped(to: CGRect(origin: .zero, size: outputSize))
    }
}
