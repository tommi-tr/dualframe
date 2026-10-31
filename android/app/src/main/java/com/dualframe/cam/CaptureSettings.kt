package com.dualframe.cam

import android.content.Context

enum class VideoRes(val label: String, val sub: String, val maxLongSide: Int?) {
    P720("720p", "1280×720", 1280),
    P1080("1080p", "1920×1080", 1920),
    MAX("MAX", "nativa sensore", null)
}

enum class PhotoRes(val label: String, val sub: String, val maxLongSide: Int?) {
    FHD("FHD", "1920 px", 1920),
    UHD("4K", "3840 px", 3840),
    MAX("MAX", "piena", null)
}

enum class Fps(val label: String, val sub: String, val value: Int) {
    F24("24 fps", "cinema", 24),
    F30("30 fps", "standard", 30),
    F60("60 fps", "fluido", 60)
}

enum class Codec(val label: String, val sub: String) {
    HEVC("HEVC", "file leggeri"),
    H264("H.264", "compatibile")
}

enum class FlashMode { OFF, AUTO, ON }

/** Impostazioni scelte dall'utente, salvate tra un avvio e l'altro. */
class CaptureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var videoRes: VideoRes
        get() = enumOr(prefs.getString("videoRes", null), VideoRes.P1080)
        set(v) = prefs.edit().putString("videoRes", v.name).apply()

    var photoRes: PhotoRes
        get() = enumOr(prefs.getString("photoRes", null), PhotoRes.MAX)
        set(v) = prefs.edit().putString("photoRes", v.name).apply()

    var fps: Fps
        get() = enumOr(prefs.getString("fps", null), Fps.F30)
        set(v) = prefs.edit().putString("fps", v.name).apply()

    var codec: Codec
        get() = enumOr(prefs.getString("codec", null), Codec.HEVC)
        set(v) = prefs.edit().putString("codec", v.name).apply()

    var flash: FlashMode
        get() = enumOr(prefs.getString("flash", null), FlashMode.OFF)
        set(v) = prefs.edit().putString("flash", v.name).apply()

    /** Ingrandimento equivalente dell'ultima lente scelta (null = grandangolo). */
    var lensZoom: Float?
        get() = if (prefs.contains("lensZoom")) prefs.getFloat("lensZoom", 0.5f) else null
        set(v) {
            if (v == null) prefs.edit().remove("lensZoom").apply()
            else prefs.edit().putFloat("lensZoom", v).apply()
        }

    var grid: Boolean
        get() = prefs.getBoolean("grid", false)
        set(v) = prefs.edit().putBoolean("grid", v).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: def
}
