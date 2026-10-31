import AVFoundation
import CoreImage
import Photos
import UIKit

/// Gestisce la fotocamera grandangolare e produce, per ogni ripresa,
/// un file 9:16 e uno 16:9 ritagliati in tempo reale dal fotogramma 4:3.
final class CameraModel: NSObject, ObservableObject {

    // MARK: - Stato per l'interfaccia (thread principale)

    @Published private(set) var mode: CaptureMode = .photo
    @Published private(set) var isRecording = false
    @Published private(set) var recordSeconds = 0
    @Published var secondary = SecondaryState() {
        didSet { storeSecondaryRect() }
    }
    @Published private(set) var angle = 0
    @Published private(set) var recAngle = 0
    @Published private(set) var toast: String?
    @Published private(set) var processing: String?
    @Published private(set) var zoomLabel = "0.5×"
    @Published private(set) var thumbnail: UIImage?
    @Published private(set) var flashPulse = 0
    @Published private(set) var cameraDenied = false
    @Published private(set) var hasFlash = false
    @Published private(set) var activeFps = 30
    @Published var openMenu: MenuKind?

    @Published var grid = UserDefaults.standard.bool(forKey: "grid") {
        didSet { UserDefaults.standard.set(grid, forKey: "grid") }
    }
    @Published var videoRes: VideoRes = Store.load("videoRes", VideoRes.p1080) {
        didSet { Store.save("videoRes", videoRes); reconfigureFormat() }
    }
    @Published var photoRes: PhotoRes = Store.load("photoRes", PhotoRes.max) {
        didSet { Store.save("photoRes", photoRes) }
    }
    @Published var fps: Fps = Store.load("fps", Fps.f30) {
        didSet { Store.save("fps", fps); reconfigureFormat() }
    }
    @Published var codec: Codec = Store.load("codec", Codec.hevc) {
        didSet { Store.save("codec", codec) }
    }
    @Published var flash: FlashSetting = Store.load("flash", FlashSetting.off) {
        didSet {
            Store.save("flash", flash)
            if isRecording { setTorch(flash == .on) }
        }
    }

    /// Angolo usato per le etichette: durante la ripresa resta quello di partenza.
    var labelAngle: Int { isRecording ? recAngle : angle }

    // MARK: - Fotocamera

    let session = AVCaptureSession()
    weak var previewLayer: AVCaptureVideoPreviewLayer? {
        didSet { applyPreviewRotation() }
    }

    private let sessionQueue = DispatchQueue(label: "dualframe.session")
    private let videoQueue = DispatchQueue(label: "dualframe.video", qos: .userInitiated)
    private let photoQueue = DispatchQueue(label: "dualframe.photo", qos: .userInitiated)
    private var device: AVCaptureDevice?
    private var audioInput: AVCaptureDeviceInput?
    private let videoOutput = AVCaptureVideoDataOutput()
    private let audioOutput = AVCaptureAudioDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private let ciContext = CIContext(options: [.cacheIntermediates: false])
    private let orientation = OrientationMonitor()
    private var timer: Timer?
    private var toastWork: DispatchWorkItem?
    private var configured = false

    // Condivisi tra code (protetti da `lock`)
    private let lock = NSLock()
    private var secondaryRectShared = FrameMath.secondary(SecondaryState())
    private var photoJobs: [Int64: PhotoJob] = [:]
    private var sensorSize = CGSize(width: 4032, height: 3024)

    // Solo coda video
    private var writers: [ClipWriter] = []
    private var writingAngle = 0
    private var wantsThumbnail = false

    private struct PhotoJob {
        let primary: NormRect
        let secondary: NormRect
        let angle: Int
        let maxLong: CGFloat?
        let duringVideo: Bool
    }

    // MARK: - Avvio

    func start() {
        orientation.onChange = { [weak self] a in self?.angle = a }
        orientation.start()
        guard !configured else { return }
        AVCaptureDevice.requestAccess(for: .video) { ok in
            DispatchQueue.main.async {
                guard ok else { self.cameraDenied = true; return }
                AVCaptureDevice.requestAccess(for: .audio) { _ in
                    PHPhotoLibrary.requestAuthorization(for: .addOnly) { _ in }
                    DispatchQueue.main.async {
                        let m = self.mode, f = self.fps.value, r = self.videoRes.maxLongSide
                        self.sessionQueue.async { self.configureSession(mode: m, fps: f, need: r) }
                    }
                }
            }
        }
    }

    func pause() {
        if isRecording { stopRecording() }
        orientation.stop()
    }

    private func configureSession(mode: CaptureMode, fps: Int, need: CGFloat?) {
        guard !configured else { return }
        session.beginConfiguration()
        session.sessionPreset = .inputPriority

        let ultra = AVCaptureDevice.default(.builtInUltraWideCamera, for: .video, position: .back)
        guard let dev = ultra ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: dev),
              session.canAddInput(input) else {
            session.commitConfiguration()
            DispatchQueue.main.async { self.showToast("Fotocamera non disponibile") }
            return
        }
        session.addInput(input)
        device = dev

        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: videoQueue)
        if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
        audioOutput.setSampleBufferDelegate(self, queue: videoQueue)
        if session.canAddOutput(audioOutput) { session.addOutput(audioOutput) }
        if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }

        applyMode(mode)
        applyFormat(mode: mode, fps: fps, need: need)
        setSensorRotation()
        session.commitConfiguration()
        session.startRunning()
        configured = true

        let label = ultra != nil ? "0.5×" : "1×"
        let flashAvailable = dev.hasFlash
        DispatchQueue.main.async {
            self.zoomLabel = label
            self.hasFlash = flashAvailable
            self.applyPreviewRotation()
            if ultra == nil { self.showToast("Grandangolo non presente: uso la fotocamera principale") }
        }
    }

    /// I buffer arrivano nell'orientamento nativo del sensore: lo ruotiamo noi con Core Image.
    private func setSensorRotation() {
        for c in [videoOutput.connection(with: .video), photoOutput.connection(with: .video)].compactMap({ $0 }) {
            if #available(iOS 17.0, *) {
                if c.isVideoRotationAngleSupported(0) { c.videoRotationAngle = 0 }
            } else if c.isVideoOrientationSupported {
                c.videoOrientation = .landscapeRight
            }
        }
    }

    func applyPreviewRotation() {
        guard let c = previewLayer?.connection else { return }
        if #available(iOS 17.0, *) {
            if c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
        } else if c.isVideoOrientationSupported {
            c.videoOrientation = .portrait
        }
    }

    /// Microfono solo in modalità video (così in foto non compare l'indicatore arancione).
    private func applyMode(_ mode: CaptureMode) {
        if mode == .video {
            if audioInput == nil,
               AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
               let mic = AVCaptureDevice.default(for: .audio),
               let input = try? AVCaptureDeviceInput(device: mic),
               session.canAddInput(input) {
                session.addInput(input)
                audioInput = input
            }
        } else if let a = audioInput {
            session.removeInput(a)
            audioInput = nil
        }
        videoOutput.connection(with: .video)?.isEnabled = (mode == .video)
    }

    /// Sceglie il formato 4:3 del sensore adatto a fps e risoluzione richiesti.
    private func applyFormat(mode: CaptureMode, fps wantFps: Int, need: CGFloat?) {
        guard let dev = device else { return }
        let targetFps = mode == .video ? wantFps : 30
        let full = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        let candidates: [(AVCaptureDevice.Format, CMVideoDimensions)] = dev.formats.compactMap { f in
            let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            guard d.height > 0, abs(Double(d.width) / Double(d.height) - 4.0 / 3.0) < 0.01 else { return nil }
            let sub = CMFormatDescriptionGetMediaSubType(f.formatDescription)
            guard sub == full || sub == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange else { return nil }
            return (f, d)
        }
        func supports(_ f: AVCaptureDevice.Format, _ fps: Int) -> Bool {
            f.videoSupportedFrameRateRanges.contains {
                $0.maxFrameRate >= Double(fps) - 0.01 && $0.minFrameRate <= Double(fps) + 0.01
            }
        }
        var used = targetFps
        var pool = candidates.filter { supports($0.0, targetFps) }
        if pool.isEmpty { used = 30; pool = candidates.filter { supports($0.0, 30) } }
        if pool.isEmpty { pool = candidates }
        guard !pool.isEmpty else { return }

        pool.sort { a, b in
            if a.1.width != b.1.width { return a.1.width < b.1.width }
            let af = CMFormatDescriptionGetMediaSubType(a.0.formatDescription) == full
            let bf = CMFormatDescriptionGetMediaSubType(b.0.formatDescription) == full
            return !af && bf // a parità di dimensioni, il full-range va per ultimo
        }
        var chosen = pool[pool.count - 1] // il più grande
        if mode == .video, let need {
            // Per entrambi i formati serve che il lato corto del sensore copra il lato lungo dell'uscita:
            // prendo il formato più piccolo che basta (meno carico, meno calore).
            let enough = pool.filter { CGFloat($0.1.height) >= need }
            if let minW = enough.map({ $0.1.width }).min(),
               let pick = enough.last(where: { $0.1.width == minW }) {
                chosen = pick
            }
        }

        do {
            try dev.lockForConfiguration()
            dev.activeFormat = chosen.0
            if !supports(chosen.0, used) {
                used = Int(chosen.0.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30)
            }
            let dur = CMTime(value: 1, timescale: CMTimeScale(used))
            dev.activeVideoMinFrameDuration = dur
            dev.activeVideoMaxFrameDuration = dur
            dev.unlockForConfiguration()
        } catch {
            return
        }
        if #available(iOS 16.0, *) {
            if let best = chosen.0.supportedMaxPhotoDimensions.max(by: { $0.width * $0.height < $1.width * $1.height }) {
                photoOutput.maxPhotoDimensions = best
            }
        }
        lock.lock()
        sensorSize = CGSize(width: CGFloat(chosen.1.width), height: CGFloat(chosen.1.height))
        lock.unlock()
        let requested = targetFps
        DispatchQueue.main.async {
            self.activeFps = used
            if mode == .video && used != requested {
                self.showToast("\(requested) fps non supportati dal grandangolo: uso \(used) fps")
            }
        }
    }

    private func reconfigureFormat() {
        guard !isRecording else { return }
        let m = mode, f = fps.value, r = videoRes.maxLongSide
        sessionQueue.async {
            guard self.configured else { return }
            self.session.beginConfiguration()
            self.applyFormat(mode: m, fps: f, need: r)
            self.session.commitConfiguration()
        }
    }

    // MARK: - Comandi

    func setMode(_ m: CaptureMode) {
        guard !isRecording, m != mode else { return }
        mode = m
        openMenu = nil
        let f = fps.value, r = videoRes.maxLongSide
        sessionQueue.async {
            guard self.configured else { return }
            self.session.beginConfiguration()
            self.applyMode(m)
            self.applyFormat(mode: m, fps: f, need: r)
            self.setSensorRotation()
            self.session.commitConfiguration()
        }
    }

    func toggleMenu(_ kind: MenuKind) {
        if isRecording {
            showToast("Impostazioni bloccate durante la ripresa")
            return
        }
        openMenu = openMenu == kind ? nil : kind
    }

    func recenter() { secondary = SecondaryState() }

    func shutter() {
        openMenu = nil
        if mode == .photo { takePhoto() } else if isRecording { stopRecording() } else { startRecording() }
    }

    func showToast(_ message: String) {
        toast = message
        toastWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.toast = nil }
        toastWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8, execute: work)
    }

    func focus(at point: CGPoint) {
        guard let layer = previewLayer else { return }
        let p = layer.captureDevicePointConverted(fromLayerPoint: point)
        sessionQueue.async {
            guard let d = self.device else { return }
            do {
                try d.lockForConfiguration()
                if d.isFocusPointOfInterestSupported, d.isFocusModeSupported(.autoFocus) {
                    d.focusPointOfInterest = p
                    d.focusMode = .autoFocus
                }
                if d.isExposurePointOfInterestSupported, d.isExposureModeSupported(.autoExpose) {
                    d.exposurePointOfInterest = p
                    d.exposureMode = .autoExpose
                }
                d.unlockForConfiguration()
            } catch {}
        }
    }

    private func setTorch(_ on: Bool) {
        sessionQueue.async {
            guard let d = self.device, d.hasTorch else { return }
            do {
                try d.lockForConfiguration()
                d.torchMode = on ? .on : .off
                d.unlockForConfiguration()
            } catch {}
        }
    }

    private func storeSecondaryRect() {
        let r = FrameMath.secondary(secondary)
        lock.lock()
        secondaryRectShared = r
        lock.unlock()
    }

    private static func timestamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss_SSS"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Date())
    }

    // MARK: - Foto

    func takePhoto() {
        flashPulse += 1
        let job = PhotoJob(primary: FrameMath.primary, secondary: FrameMath.secondary(secondary),
                           angle: labelAngle, maxLong: photoRes.maxLongSide, duringVideo: isRecording)
        let flashSetting = flash
        let recording = isRecording
        sessionQueue.async {
            guard self.configured else { return }
            let settings = AVCapturePhotoSettings()
            if #available(iOS 16.0, *) {
                settings.maxPhotoDimensions = self.photoOutput.maxPhotoDimensions
            }
            let fm: AVCaptureDevice.FlashMode
            switch flashSetting {
            case .off: fm = .off
            case .auto: fm = .auto
            case .on: fm = .on
            }
            let wanted: AVCaptureDevice.FlashMode = recording ? .off : fm
            if self.photoOutput.supportedFlashModes.contains(wanted) { settings.flashMode = wanted }
            self.lock.lock()
            self.photoJobs[settings.uniqueID] = job
            self.lock.unlock()
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    private func processPhoto(_ cg: CGImage, job: PhotoJob) {
        // Il sensore consegna l'immagine "sdraiata": .right la porta come appare nel mirino.
        let base = CropRenderer.normalized(CIImage(cgImage: cg).oriented(.right))
        let frame = base.extent.size
        var datas: [Data] = []
        var thumb: UIImage?
        for (i, rect) in [job.primary, job.secondary].enumerated() {
            let size = FrameMath.outputSize(for: rect, frame: frame, angle: job.angle, maxLongSide: job.maxLong)
            let img = CropRenderer.render(base: base, rect: rect, angle: job.angle, outputSize: size)
            guard let out = ciContext.createCGImage(img, from: CGRect(origin: .zero, size: size)) else { continue }
            let ui = UIImage(cgImage: out)
            if let d = ui.jpegData(compressionQuality: 0.95) { datas.append(d) }
            if i == 0 { thumb = ui.preparingThumbnail(of: CGSize(width: size.width / 10, height: size.height / 10)) }
        }
        PHPhotoLibrary.shared().performChanges({
            for d in datas {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: d, options: nil)
            }
        }) { ok, _ in
            DispatchQueue.main.async {
                if ok && datas.count == 2 {
                    if let thumb { self.thumbnail = thumb }
                    self.showToast(job.duringVideo ? "Foto dal video · 9:16 + 16:9" : "2 foto salvate · 9:16 + 16:9")
                } else {
                    self.showToast("Salvataggio non riuscito: consenti l'accesso a Foto")
                }
            }
        }
    }

    // MARK: - Video

    func startRecording() {
        guard mode == .video, !isRecording, configured else { return }
        openMenu = nil
        let a = angle
        recAngle = a
        isRecording = true
        recordSeconds = 0

        lock.lock()
        let sensor = sensorSize
        lock.unlock()
        // Fotogramma orientato come il mirino: verticale.
        let frame = CGSize(width: sensor.height, height: sensor.width)
        let maxL = videoRes.maxLongSide
        let primarySize = FrameMath.outputSize(for: FrameMath.primary, frame: frame, angle: a, maxLongSide: maxL)
        let secondarySize = FrameMath.outputSize(for: FrameMath.secondary(SecondaryState()), frame: frame, angle: a, maxLongSide: maxL)
        let fpsValue = activeFps
        let c = codec
        let ts = Self.timestamp()
        let tmp = FileManager.default.temporaryDirectory

        videoQueue.async {
            let audio: [String: Any]? = self.audioOutput.connection(with: .audio) != nil
                ? self.audioOutput.recommendedAudioSettingsForAssetWriter(writingTo: .mp4) as? [String: Any]
                : nil
            do {
                let w1 = try ClipWriter(url: tmp.appendingPathComponent("DualFrame_\(ts)_\(FrameMath.tag(for: primarySize)).mp4"),
                                        size: primarySize, fps: fpsValue, codec: c, audioSettings: audio, isPrimary: true)
                let w2 = try ClipWriter(url: tmp.appendingPathComponent("DualFrame_\(ts)_\(FrameMath.tag(for: secondarySize)).mp4"),
                                        size: secondarySize, fps: fpsValue, codec: c, audioSettings: audio, isPrimary: false)
                self.writingAngle = a
                self.wantsThumbnail = true
                self.writers = [w1, w2]
            } catch {
                DispatchQueue.main.async {
                    self.isRecording = false
                    self.showToast("Registrazione non disponibile")
                }
            }
        }
        if flash == .on { setTorch(true) }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.recordSeconds += 1
        }
    }

    func stopRecording() {
        guard isRecording else { return }
        isRecording = false
        timer?.invalidate()
        timer = nil
        setTorch(false)
        processing = "Salvo i video 9:16 e 16:9…"
        videoQueue.async {
            let ws = self.writers
            self.writers = []
            let group = DispatchGroup()
            let urlLock = NSLock()
            var urls: [URL] = []
            for w in ws {
                group.enter()
                w.finish { url in
                    if let url {
                        urlLock.lock(); urls.append(url); urlLock.unlock()
                    }
                    group.leave()
                }
            }
            group.notify(queue: .main) {
                self.saveVideos(urls) { ok in
                    self.processing = nil
                    self.showToast(ok ? "2 video salvati · 9:16 + 16:9" : "Salvataggio video non riuscito")
                }
            }
        }
    }

    private func saveVideos(_ urls: [URL], completion: @escaping (Bool) -> Void) {
        guard !urls.isEmpty else { completion(false); return }
        PHPhotoLibrary.shared().performChanges({
            for url in urls {
                let req = PHAssetCreationRequest.forAsset()
                let opts = PHAssetResourceCreationOptions()
                opts.shouldMoveFile = true
                req.addResource(with: .video, fileURL: url, options: opts)
            }
        }) { ok, _ in
            DispatchQueue.main.async { completion(ok && urls.count == 2) }
        }
    }

    private func makeThumbnail(_ image: CIImage) {
        let s = 160 / max(image.extent.width, image.extent.height)
        let small = image.transformed(by: CGAffineTransform(scaleX: s, y: s))
        guard let cg = ciContext.createCGImage(small, from: small.extent) else { return }
        let ui = UIImage(cgImage: cg)
        DispatchQueue.main.async { self.thumbnail = ui }
    }
}

// MARK: - Fotogrammi e audio

extension CameraModel: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard !writers.isEmpty else { return }
        if output === videoOutput {
            guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
            let time = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            let base = CropRenderer.normalized(CIImage(cvPixelBuffer: pb).oriented(.right))
            lock.lock()
            let sec = secondaryRectShared
            lock.unlock()
            for w in writers {
                let rect = w.isPrimary ? FrameMath.primary : sec
                let img = CropRenderer.render(base: base, rect: rect, angle: writingAngle, outputSize: w.outputSize)
                w.appendVideo(img, at: time, context: ciContext)
                if w.isPrimary && wantsThumbnail {
                    wantsThumbnail = false
                    makeThumbnail(img)
                }
            }
        } else if output === audioOutput {
            for w in writers { w.appendAudio(sampleBuffer) }
        }
    }
}

// MARK: - Scatto foto

extension CameraModel: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        lock.lock()
        let job = photoJobs.removeValue(forKey: photo.resolvedSettings.uniqueID)
        lock.unlock()
        guard error == nil, let job, let cg = photo.cgImageRepresentation() else {
            DispatchQueue.main.async { self.showToast("Scatto non riuscito") }
            return
        }
        photoQueue.async { self.processPhoto(cg, job: job) }
    }
}
