import CoreGraphics

/// Rettangolo normalizzato (0…1) nelle coordinate del mirino come appare sullo schermo
/// (interfaccia bloccata in verticale): x lungo il lato corto, y lungo il lato lungo, origine in alto a sinistra.
struct NormRect: Equatable {
    var left: CGFloat
    var top: CGFloat
    var right: CGFloat
    var bottom: CGFloat

    var width: CGFloat { right - left }
    var height: CGFloat { bottom - top }

    func rect(in size: CGSize) -> CGRect {
        CGRect(x: left * size.width, y: top * size.height,
               width: width * size.width, height: height * size.height)
    }
}

/// Posizione e scala del riquadro secondario (quello che si sposta col dito).
struct SecondaryState: Equatable {
    var cx: CGFloat = 0.5
    var cy: CGFloat = 0.5
    var scale: CGFloat = 1
}

/// Geometria dei due ritagli.
///
/// Il mirino mostra l'intero fotogramma 4:3 del grandangolo (3:4 sullo schermo).
/// Sullo schermo i ritagli hanno sempre la stessa forma:
///  - "alto": pieno in altezza, 75% in larghezza → formato principale (fisso)
///  - "largo": pieno in larghezza, 42,19% in altezza → formato secondario (spostabile)
/// Telefono in verticale: alto = 9:16, largo = 16:9. In orizzontale i ruoli si scambiano.
enum FrameMath {
    static let viewAspect: CGFloat = 3.0 / 4.0
    static let tallW: CGFloat = 0.75        // 9/16 · 4/3
    static let wideH: CGFloat = 0.421875    // 9/16 · 3/4
    static let minScale: CGFloat = 0.5

    static var primary: NormRect {
        NormRect(left: (1 - tallW) / 2, top: 0, right: (1 + tallW) / 2, bottom: 1)
    }

    static func clamp(_ s: SecondaryState) -> SecondaryState {
        let scale = min(1, max(minScale, s.scale))
        let w = scale
        let h = wideH * scale
        return SecondaryState(
            cx: min(1 - w / 2, max(w / 2, s.cx)),
            cy: min(1 - h / 2, max(h / 2, s.cy)),
            scale: scale
        )
    }

    static func secondary(_ s: SecondaryState) -> NormRect {
        let c = clamp(s)
        let w = c.scale
        let h = wideH * c.scale
        return NormRect(left: c.cx - w / 2, top: c.cy - h / 2, right: c.cx + w / 2, bottom: c.cy + h / 2)
    }

    static func isLandscape(_ angle: Int) -> Bool { angle % 180 != 0 }
    static func primaryLabel(_ angle: Int) -> String { isLandscape(angle) ? "16:9" : "9:16" }
    static func secondaryLabel(_ angle: Int) -> String { isLandscape(angle) ? "9:16" : "16:9" }

    /// Dimensione in pixel del ritaglio dopo averlo raddrizzato secondo l'angolo del telefono.
    static func outputSize(for rect: NormRect, frame: CGSize, angle: Int, maxLongSide: CGFloat?) -> CGSize {
        var w = rect.width * frame.width
        var h = rect.height * frame.height
        if isLandscape(angle) { swap(&w, &h) }
        let long = max(w, h)
        let s = maxLongSide.map { min(1, $0 / long) } ?? 1
        return CGSize(width: even(w * s), height: even(h * s))
    }

    static func tag(for size: CGSize) -> String { size.height > size.width ? "9x16" : "16x9" }

    static func even(_ v: CGFloat) -> CGFloat { max(2, CGFloat(Int((v / 2).rounded()) * 2)) }
}
