import SwiftUI

enum Palette {
    static let accent = Color(red: 1.0, green: 0.69, blue: 0.125)      // #FFB020
    static let ink = Color(red: 0.10, green: 0.07, blue: 0.0)           // #1A1200
    static let ground = Color(red: 0.043, green: 0.043, blue: 0.047)    // #0B0B0C
    static let text = Color(red: 0.96, green: 0.96, blue: 0.957)
    static let muted = Color(red: 0.66, green: 0.635, blue: 0.62)
    static let rec = Color(red: 0.898, green: 0.216, blue: 0.169)       // #E5372B
}

struct ContentView: View {
    @StateObject private var model = CameraModel()
    @Environment(\.scenePhase) private var scenePhase

    private let topBarHeight: CGFloat = 64

    var body: some View {
        ZStack(alignment: .top) {
            Palette.ground.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                Viewfinder(model: model)
                    .aspectRatio(FrameMath.viewAspect, contentMode: .fit)
                Spacer(minLength: 0)
                bottomBar
            }

            if model.openMenu != nil {
                MenuPanel(model: model)
                    .padding(.horizontal, 12)
                    .padding(.top, topBarHeight)
                    .transition(.opacity)
            }

            if let t = model.toast {
                ToastView(text: t)
                    .padding(.top, topBarHeight + 56)
                    .transition(.opacity)
            }

            if model.cameraDenied {
                PermissionView()
            }
        }
        .animation(.easeOut(duration: 0.15), value: model.openMenu)
        .animation(.easeOut(duration: 0.15), value: model.toast)
        .preferredColorScheme(.dark)
        .onAppear { model.start() }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .background: model.pause()
            case .active: model.start()
            default: break
            }
        }
    }

    /// Rotazione delle icone per restare dritte quando il telefono è girato.
    private var iconRotation: Angle {
        switch model.angle {
        case 90: return .degrees(-90)
        case 180: return .degrees(180)
        case 270: return .degrees(90)
        default: return .zero
        }
    }

    // MARK: Barra superiore

    private var resKind: MenuKind { model.mode == .video ? .videoRes : .photoRes }

    private var topBar: some View {
        HStack(spacing: 0) {
            RoundIcon(symbol: model.flash.symbol, label: "Flash") { model.flash = model.flash.next }
                .opacity(model.hasFlash ? 1 : 0.4)
                .rotationEffect(iconRotation)
            Spacer(minLength: 8)
            HStack(spacing: 6) {
                Chip(text: model.mode == .video ? model.videoRes.label : model.photoRes.label,
                     open: model.openMenu == resKind, label: "Risoluzione") { model.toggleMenu(resKind) }
                if model.mode == .video {
                    Chip(text: model.fps.label, open: model.openMenu == .fps, label: "Fotogrammi al secondo") {
                        model.toggleMenu(.fps)
                    }
                    Chip(text: model.codec.label, open: model.openMenu == .codec, label: "Codec") {
                        model.toggleMenu(.codec)
                    }
                }
            }
            .opacity(model.isRecording ? 0.5 : 1)
            Spacer(minLength: 8)
            RoundIcon(symbol: "squareshape.split.3x3", label: "Griglia") { model.grid.toggle() }
                .opacity(model.grid ? 1 : 0.55)
                .rotationEffect(iconRotation)
        }
        .padding(.horizontal, 16)
        .frame(height: topBarHeight)
        .animation(.easeOut(duration: 0.2), value: model.angle)
    }

    // MARK: Barra inferiore

    private var bottomBar: some View {
        let secLabel = FrameMath.secondaryLabel(model.labelAngle)
        return VStack(spacing: 6) {
            if let p = model.processing {
                Text(p)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(Palette.accent)
                    .padding(.horizontal, 12)
                    .frame(height: 30)
                    .background(Capsule().fill(Color.black.opacity(0.85)))
            }
            Text(model.isRecording
                 ? "REC su 2 file · puoi spostare il \(secLabel) anche ora"
                 : "Tocca un punto o trascina per spostare il \(secLabel)")
                .font(.system(size: 12))
                .foregroundColor(Palette.muted)
                .padding(.top, 8)

            HStack(spacing: 20) {
                modeButton("FOTO", .photo)
                modeButton("VIDEO", .video)
            }
            .opacity(model.isRecording ? 0 : 1)
            .disabled(model.isRecording)

            ZStack {
                HStack {
                    if model.isRecording {
                        Color.clear.frame(width: 56, height: 56)
                    } else {
                        GalleryButton(image: model.thumbnail)
                            .rotationEffect(iconRotation)
                    }
                    Spacer()
                    if model.isRecording {
                        Button(action: model.takePhoto) {
                            Image(systemName: "camera.fill")
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundColor(.black)
                                .frame(width: 56, height: 56)
                                .background(Circle().fill(Color.white))
                        }
                        .accessibilityLabel("Scatta foto durante il video")
                        .rotationEffect(iconRotation)
                    } else {
                        Button(action: model.recenter) {
                            Image(systemName: "viewfinder")
                                .font(.system(size: 22))
                                .foregroundColor(Palette.text)
                                .frame(width: 52, height: 52)
                                .background(Circle().fill(Color.white.opacity(0.1)))
                        }
                        .accessibilityLabel("Ricentra il riquadro")
                        .rotationEffect(iconRotation)
                    }
                }
                .padding(.horizontal, 36)

                ShutterButton(mode: model.mode, recording: model.isRecording, action: model.shutter)
            }
            .frame(height: 96)
            .animation(.easeOut(duration: 0.2), value: model.angle)
        }
        .padding(.bottom, 8)
    }

    private func modeButton(_ title: String, _ m: CaptureMode) -> some View {
        Button { model.setMode(m) } label: {
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .tracking(1.5)
                .foregroundColor(model.mode == m ? Palette.accent : Color(white: 0.84))
                .frame(minWidth: 44, minHeight: 40)
        }
    }
}

// MARK: - Mirino con i due riquadri

struct Viewfinder: View {
    @ObservedObject var model: CameraModel

    private struct DragStart {
        let inside: Bool
        let state: SecondaryState
    }

    @State private var dragStart: DragStart?
    @State private var pinchBase: CGFloat?
    @State private var pinched = false
    @State private var dragging = false
    @State private var ring: CGPoint?
    @State private var flashOpacity = 0.0

    private var labelRotation: Angle { .degrees(Double(-model.labelAngle)) }

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let p = FrameMath.primary.rect(in: size)
            let s = FrameMath.secondary(model.secondary).rect(in: size)

            ZStack(alignment: .topLeading) {
                CameraPreview(model: model)

                Canvas { ctx, sz in
                    // Oscura ciò che non finisce in nessuno dei due file
                    ctx.drawLayer { layer in
                        layer.fill(Path(CGRect(origin: .zero, size: sz)), with: .color(.black.opacity(0.6)))
                        layer.blendMode = .destinationOut
                        layer.fill(Path(p), with: .color(.black))
                        layer.fill(Path(s), with: .color(.black))
                    }
                    if model.grid {
                        var g = Path()
                        for i in 1...2 {
                            let x = p.minX + p.width * CGFloat(i) / 3
                            let y = p.minY + p.height * CGFloat(i) / 3
                            g.move(to: CGPoint(x: x, y: p.minY)); g.addLine(to: CGPoint(x: x, y: p.maxY))
                            g.move(to: CGPoint(x: p.minX, y: y)); g.addLine(to: CGPoint(x: p.maxX, y: y))
                        }
                        ctx.stroke(g, with: .color(.white.opacity(0.35)), lineWidth: 1)
                    }
                    ctx.stroke(Path(p), with: .color(.white), lineWidth: 1.5)
                    if dragging {
                        ctx.stroke(Path(s), with: .color(Palette.accent.opacity(0.35)), lineWidth: 8)
                    }
                    ctx.stroke(Path(s), with: .color(Palette.accent), lineWidth: 2)
                }
                .allowsHitTesting(false)

                FrameTag(text: FrameMath.primaryLabel(model.labelAngle), bg: .white, fg: Color(white: 0.07))
                    .rotationEffect(labelRotation)
                    .position(x: p.minX + 30, y: p.maxY - 19)
                    .allowsHitTesting(false)

                FrameTag(text: FrameMath.secondaryLabel(model.labelAngle), bg: Palette.accent, fg: Palette.ink)
                    .rotationEffect(labelRotation)
                    .position(x: s.minX + 30, y: s.minY + 19)
                    .allowsHitTesting(false)

                Image(systemName: "arrow.up.and.down")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(Palette.accent)
                    .frame(width: 32, height: 48)
                    .background(Capsule().fill(Color.black.opacity(0.55)))
                    .position(x: s.maxX - 8 - 16, y: s.midY)
                    .allowsHitTesting(false)

                if let ring {
                    Circle()
                        .stroke(Palette.accent, lineWidth: 2)
                        .frame(width: 56, height: 56)
                        .position(ring)
                        .transition(.opacity)
                        .allowsHitTesting(false)
                }

                StatusChip(model: model)
                    .position(x: size.width / 2, y: 24)
                    .allowsHitTesting(false)

                Color.white.opacity(flashOpacity).allowsHitTesting(false)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        if dragStart == nil {
                            dragStart = DragStart(inside: s.insetBy(dx: -16, dy: -16).contains(v.startLocation),
                                                  state: model.secondary)
                            pinched = false
                        }
                        guard let start = dragStart, !pinched, pinchBase == nil else { return }
                        guard hypot(v.translation.width, v.translation.height) > 8 else { return }
                        dragging = true
                        var n = model.secondary
                        if start.inside {
                            n.cx = start.state.cx + v.translation.width / size.width
                            n.cy = start.state.cy + v.translation.height / size.height
                        } else {
                            // Trascinamento iniziato fuori: il riquadro va sotto al dito e lo segue.
                            n.cx = v.location.x / size.width
                            n.cy = v.location.y / size.height
                        }
                        model.secondary = FrameMath.clamp(n)
                    }
                    .onEnded { v in
                        if hypot(v.translation.width, v.translation.height) <= 8 && !pinched {
                            // Tocco: il riquadro salta subito nel punto toccato.
                            var n = model.secondary
                            n.cx = v.location.x / size.width
                            n.cy = v.location.y / size.height
                            model.secondary = FrameMath.clamp(n)
                            model.openMenu = nil
                            model.focus(at: v.location)
                            withAnimation(.easeOut(duration: 0.1)) { ring = v.location }
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                                withAnimation(.easeOut(duration: 0.3)) { ring = nil }
                            }
                        }
                        dragStart = nil
                        dragging = false
                    }
            )
            .simultaneousGesture(
                MagnificationGesture()
                    .onChanged { m in
                        if pinchBase == nil { pinchBase = model.secondary.scale }
                        pinched = true
                        var n = model.secondary
                        n.scale = (pinchBase ?? 1) * m
                        model.secondary = FrameMath.clamp(n)
                    }
                    .onEnded { _ in pinchBase = nil }
            )
        }
        .background(Color(red: 0.106, green: 0.149, blue: 0.2))
        .clipped()
        .onChange(of: model.flashPulse) { _ in
            flashOpacity = 0.85
            withAnimation(.easeOut(duration: 0.22)) { flashOpacity = 0 }
        }
    }
}

// MARK: - Componenti

struct FrameTag: View {
    let text: String
    let bg: Color
    let fg: Color

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .bold, design: .monospaced))
            .foregroundColor(fg)
            .padding(.horizontal, 8)
            .frame(height: 22)
            .background(RoundedRectangle(cornerRadius: 6).fill(bg))
            .fixedSize()
    }
}

struct StatusChip: View {
    @ObservedObject var model: CameraModel

    var body: some View {
        if model.isRecording {
            HStack(spacing: 8) {
                Circle().fill(Color.white).frame(width: 8, height: 8)
                Text(String(format: "%02d:%02d", model.recordSeconds / 60, model.recordSeconds % 60))
                    .font(.system(size: 15, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
            }
            .padding(.horizontal, 12)
            .frame(height: 30)
            .background(Capsule().fill(Palette.rec))
        } else {
            HStack(spacing: 6) {
                Text(model.zoomLabel)
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(Palette.accent)
                Text("Grandangolo")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(Color(white: 0.9))
            }
            .padding(.horizontal, 10)
            .frame(height: 26)
            .background(Capsule().fill(Color.black.opacity(0.55)))
        }
    }
}

struct RoundIcon: View {
    let symbol: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(Palette.text)
                .frame(width: 44, height: 44)
                .background(Circle().fill(Color.white.opacity(0.1)))
        }
        .accessibilityLabel(label)
    }
}

struct Chip: View {
    let text: String
    let open: Bool
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 3) {
                Text(text)
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .bold))
            }
            .foregroundColor(.white)
            .padding(.horizontal, 10)
            .frame(height: 40)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.1)))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(open ? Palette.accent : Color.white.opacity(0.28), lineWidth: 1.5)
            )
        }
        .accessibilityLabel(label)
        .accessibilityValue(text)
    }
}

struct MenuPanel: View {
    @ObservedObject var model: CameraModel

    private struct Item: Identifiable {
        let id: String
        let label: String
        let sub: String
        let selected: Bool
        let pick: () -> Void
    }

    private func items<T: MenuOption>(_ current: T, _ set: @escaping (T) -> Void) -> [Item] {
        T.allCases.map { o in
            Item(id: o.rawValue, label: o.label, sub: o.sub, selected: o == current) {
                set(o)
                model.openMenu = nil
            }
        }
    }

    private var content: (String, [Item]) {
        switch model.openMenu {
        case .videoRes: return ("Risoluzione video (per ogni formato)", items(model.videoRes) { model.videoRes = $0 })
        case .photoRes: return ("Risoluzione foto (lato lungo)", items(model.photoRes) { model.photoRes = $0 })
        case .fps: return ("Fotogrammi al secondo", items(model.fps) { model.fps = $0 })
        case .codec: return ("Codec video", items(model.codec) { model.codec = $0 })
        case .none: return ("", [])
        }
    }

    var body: some View {
        let c = content
        let title = c.0
        let list = c.1
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased())
                .font(.system(size: 12, weight: .semibold))
                .tracking(0.8)
                .foregroundColor(Palette.muted)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(list) { o in
                    Button(action: o.pick) {
                        VStack(spacing: 2) {
                            Text(o.label)
                                .font(.system(size: 15, weight: .bold, design: .monospaced))
                            Text(o.sub)
                                .font(.system(size: 11))
                                .foregroundColor(o.selected ? Color(red: 0.24, green: 0.17, blue: 0) : Palette.muted)
                        }
                        .foregroundColor(o.selected ? Palette.ink : Palette.text)
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .fill(o.selected ? Palette.accent : Color.white.opacity(0.06))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(o.selected ? Palette.accent : Color.white.opacity(0.16), lineWidth: 1.5)
                        )
                    }
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(white: 0.07).opacity(0.96)))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.14), lineWidth: 1))
    }
}

struct ShutterButton: View {
    let mode: CaptureMode
    let recording: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().stroke(Color.white, lineWidth: 4).frame(width: 80, height: 80)
                RoundedRectangle(cornerRadius: recording ? 7 : 32)
                    .fill(mode == .photo ? Color.white : Palette.rec)
                    .frame(width: recording ? 30 : 64, height: recording ? 30 : 64)
            }
            .animation(.easeOut(duration: 0.18), value: recording)
        }
        .accessibilityLabel(recording ? "Ferma registrazione" : (mode == .photo ? "Scatta foto" : "Avvia registrazione"))
    }
}

struct GalleryButton: View {
    let image: UIImage?

    var body: some View {
        Button {
            if let url = URL(string: "photos-redirect://") { UIApplication.shared.open(url) }
        } label: {
            ZStack {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Color(white: 0.16)
                }
            }
            .frame(width: 52, height: 52)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.35), lineWidth: 2))
        }
        .accessibilityLabel("Galleria")
    }
}

struct ToastView: View {
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(Color(red: 0.49, green: 0.89, blue: 0.55))
            Text(text)
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(Palette.text)
        }
        .padding(.horizontal, 14)
        .frame(height: 34)
        .background(Capsule().fill(Color(white: 0.08).opacity(0.9)))
        .overlay(Capsule().stroke(Color.white.opacity(0.14), lineWidth: 1))
    }
}

struct PermissionView: View {
    var body: some View {
        VStack(spacing: 20) {
            Text("Per riprendere servono la fotocamera e il microfono.")
                .multilineTextAlignment(.center)
                .foregroundColor(Palette.text)
            Button("Apri Impostazioni") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(Capsule().fill(Palette.accent))
            .foregroundColor(Palette.ink)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.ground.ignoresSafeArea())
    }
}
