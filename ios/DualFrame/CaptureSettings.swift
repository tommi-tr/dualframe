import Foundation
import CoreGraphics

protocol MenuOption: CaseIterable, Identifiable, Equatable, RawRepresentable where RawValue == String {
    var label: String { get }
    var sub: String { get }
}

extension MenuOption {
    var id: String { rawValue }
}

enum VideoRes: String, MenuOption {
    case p720, p1080, max
    var label: String {
        switch self {
        case .p720: return "720p"
        case .p1080: return "1080p"
        case .max: return "MAX"
        }
    }
    var sub: String {
        switch self {
        case .p720: return "1280×720"
        case .p1080: return "1920×1080"
        case .max: return "nativa sensore"
        }
    }
    var maxLongSide: CGFloat? {
        switch self {
        case .p720: return 1280
        case .p1080: return 1920
        case .max: return nil
        }
    }
}

enum PhotoRes: String, MenuOption {
    case fhd, uhd, max
    var label: String {
        switch self {
        case .fhd: return "FHD"
        case .uhd: return "4K"
        case .max: return "MAX"
        }
    }
    var sub: String {
        switch self {
        case .fhd: return "1920 px"
        case .uhd: return "3840 px"
        case .max: return "piena"
        }
    }
    var maxLongSide: CGFloat? {
        switch self {
        case .fhd: return 1920
        case .uhd: return 3840
        case .max: return nil
        }
    }
}

enum Fps: String, MenuOption {
    case f24, f30, f60
    var value: Int {
        switch self {
        case .f24: return 24
        case .f30: return 30
        case .f60: return 60
        }
    }
    var label: String { "\(value) fps" }
    var sub: String {
        switch self {
        case .f24: return "cinema"
        case .f30: return "standard"
        case .f60: return "fluido"
        }
    }
}

enum Codec: String, MenuOption {
    case hevc, h264
    var label: String { self == .hevc ? "HEVC" : "H.264" }
    var sub: String { self == .hevc ? "file leggeri" : "compatibile" }
}

enum FlashSetting: String, CaseIterable {
    case off, auto, on
    var next: FlashSetting {
        switch self {
        case .off: return .auto
        case .auto: return .on
        case .on: return .off
        }
    }
    var symbol: String {
        switch self {
        case .off: return "bolt.slash"
        case .auto: return "bolt.badge.a"
        case .on: return "bolt.fill"
        }
    }
}

enum CaptureMode { case photo, video }

enum MenuKind { case videoRes, photoRes, fps, codec }

/// Piccolo wrapper su UserDefaults per ricordare le scelte.
enum Store {
    static func load<T: RawRepresentable>(_ key: String, _ def: T) -> T where T.RawValue == String {
        UserDefaults.standard.string(forKey: key).flatMap(T.init(rawValue:)) ?? def
    }
    static func save<T: RawRepresentable>(_ key: String, _ v: T) where T.RawValue == String {
        UserDefaults.standard.set(v.rawValue, forKey: key)
    }
}
