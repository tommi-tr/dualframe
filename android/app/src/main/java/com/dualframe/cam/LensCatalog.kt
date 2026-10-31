package com.dualframe.cam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Una lente del telefono, raggiungibile o come fotocamera a sé (cameraId)
 * o come zoom ottico della fotocamera "logica" (zoomRatio).
 */
data class LensOption(
    /** Fotocamera CameraX da collegare. */
    val cameraId: String,
    /** Zoom da impostare su quella fotocamera (null = 1). */
    val zoomRatio: Float?,
    /** Ingrandimento equivalente rispetto alla lente principale (0.5 = grandangolo). */
    val equivZoom: Float,
    /** Diaframmi disponibili su questa lente (quasi sempre uno solo). */
    val apertures: List<Float>,
    /** Distanza minima di messa a fuoco in diottrie (0 = fuoco fisso). */
    val minFocusDiopters: Float,
    /** true se diaframma e fuoco minimo vengono dalla lente fisica vera. */
    val physical: Boolean = true,
    /** Fotocamera interna (selfie). */
    val front: Boolean = false
) {
    val label: String
        get() {
            if (front) return "Interna"
            val z = equivZoom
            return if (z < 1f) String.format(Locale.US, "%.1f×", z)
            else if (abs(z - z.roundToInt()) < 0.12f) "${z.roundToInt()}×"
            else String.format(Locale.US, "%.1f×", z)
        }

    /** Etichetta corta per il chip in alto: ".5×", "1×", "3×", "Int." */
    val shortLabel: String
        get() = if (front) "Int." else label.removePrefix("0")

    /** Valore da ricordare tra un avvio e l'altro (-1 = interna). */
    val memoKey: Float get() = if (front) -1f else equivZoom

    val name: String
        get() = when {
            front -> "Fotocamera interna"
            equivZoom < 0.8f -> "Grandangolo"
            equivZoom < 1.45f -> "Principale"
            else -> "Teleobiettivo"
        }
}

@OptIn(ExperimentalCamera2Interop::class)
object LensCatalog {

    private fun tanHalfFov(ch: CameraCharacteristics): Float? {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (focal <= 0f) return null
        return max(size.width, size.height) / (2f * focal)
    }

    private fun isColorCamera(ch: CameraCharacteristics): Boolean {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return false
        if (Build.VERSION.SDK_INT >= 28 &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME)) return false
        return true
    }

    private fun apertures(ch: CameraCharacteristics): List<Float> =
        ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList()?.sorted() ?: emptyList()

    private fun minFocus(ch: CameraCharacteristics): Float =
        ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    private fun zoomRange(ch: CameraCharacteristics): ClosedFloatingPointRange<Float> {
        if (Build.VERSION.SDK_INT >= 30) {
            ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it.lower..it.upper }
        }
        val maxDigital = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f..maxDigital
    }

    /** Elenco delle lenti posteriori, ordinate dalla più ampia alla più stretta. */
    fun build(context: Context, provider: ProcessCameraProvider): List<LensOption> {
        val mgr = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val back = runCatching { buildBack(mgr, provider) }.getOrDefault(emptyList())
        // La fotocamera interna, per girare verso di sé.
        val front = provider.availableCameraInfos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            ?.let { info ->
                val id = Camera2CameraInfo.from(info).cameraId
                val ch = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull()
                LensOption(id, null, 1f, ch?.let { apertures(it) } ?: emptyList(),
                    ch?.let { minFocus(it) } ?: 0f, physical = true, front = true)
            }
        return back + listOfNotNull(front)
    }

    private fun buildBack(mgr: CameraManager, provider: ProcessCameraProvider): List<LensOption> {
        val backs = provider.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        if (backs.isEmpty()) return emptyList()

        val ids = backs.map { Camera2CameraInfo.from(it).cameraId }
        val mainId = ids.first()
        val mainChars = mgr.getCameraCharacteristics(mainId)
        val refTan = tanHalfFov(mainChars) ?: return listOf(
            LensOption(mainId, null, 1f, apertures(mainChars), minFocus(mainChars))
        )

        val found = mutableListOf<LensOption>()
        for (id in ids) {
            val ch = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val baseTan = tanHalfFov(ch) ?: continue
            val range = zoomRange(ch)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val logical = Build.VERSION.SDK_INT >= 28 &&
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

            // La fotocamera stessa, allo zoom 1 (e, se scende sotto 1, al suo zoom minimo).
            found += LensOption(id, null, refTan / baseTan, apertures(ch), minFocus(ch), physical = !logical)
            if (range.start < 0.95f) {
                found += LensOption(id, range.start, (refTan / baseTan) * range.start,
                    apertures(ch), minFocus(ch), physical = false)
            }

            // Le lenti fisiche nascoste dietro la fotocamera logica, raggiunte con lo zoom.
            if (logical && Build.VERSION.SDK_INT >= 28) {
                for (pid in ch.physicalCameraIds) {
                    val pch = runCatching { mgr.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    if (!isColorCamera(pch)) continue
                    val pTan = tanHalfFov(pch) ?: continue
                    var zr = baseTan / pTan
                    if (zr < range.start) {
                        if (zr < range.start * 0.85f) continue
                        zr = range.start
                    }
                    if (zr > range.endInclusive) continue
                    found += LensOption(id, zr, (refTan / baseTan) * zr, apertures(pch), minFocus(pch))
                }
            }
        }

        // Unisce le voci che indicano la stessa lente; preferisce lo zoom sulla fotocamera principale
        // (cambiare lente senza ricollegare la fotocamera = funziona anche mentre registri).
        val sorted = found.sortedBy { it.equivZoom }
        val merged = mutableListOf<LensOption>()
        for (o in sorted) {
            val last = merged.lastOrNull()
            if (last != null && o.equivZoom / last.equivZoom < 1.15f) {
                val route = when {
                    o.cameraId == mainId && last.cameraId != mainId -> o
                    last.cameraId == mainId && o.cameraId != mainId -> last
                    o.zoomRatio != null && last.zoomRatio == null -> o
                    else -> last
                }
                // Diaframma e fuoco minimo: quelli della lente fisica, se li conosciamo.
                val info = listOf(o, last).firstOrNull { it.physical } ?: route
                merged[merged.size - 1] = route.copy(
                    apertures = info.apertures,
                    minFocusDiopters = info.minFocusDiopters,
                    physical = info.physical
                )
            } else {
                merged += o
            }
        }
        return merged
    }
}
