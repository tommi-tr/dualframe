package com.dualframe.cam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rettangolo normalizzato (0..1) nelle coordinate del mirino così come appare
 * sullo schermo (UI bloccata in verticale): x lungo il lato corto, y lungo il lato lungo.
 */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Posizione (centro) e scala di un riquadro. */
data class BoxState(val cx: Float = 0.5f, val cy: Float = 0.5f, val scale: Float = 1f)

typealias SecondaryState = BoxState

/**
 * Geometria dei due riquadri.
 *
 * Il mirino mostra l'intero fotogramma 4:3 della lente (3:4 sullo schermo).
 * Sullo schermo i ritagli hanno SEMPRE la stessa forma:
 *  - "alto" (alla scala 1: pieno in altezza, 75% in larghezza) → formato principale
 *  - "largo" (alla scala 1: pieno in larghezza, 42,19% in altezza) → formato secondario
 * Con il telefono in verticale l'alto è il 9:16 e il largo il 16:9;
 * con il telefono in orizzontale i ruoli si invertono.
 * Entrambi si possono spostare e rimpicciolire fino al 50%.
 */
object FrameMath {
    /** larghezza/altezza del mirino */
    const val VIEW_ASPECT = 3f / 4f
    /** 9/16 * (4/3) */
    const val TALL_W = 0.75f
    /** 9/16 * (3/4) */
    const val WIDE_H = 0.421875f
    const val MIN_SCALE = 0.5f

    private fun clampBox(s: BoxState, w1: Float, h1: Float): BoxState {
        val scale = s.scale.coerceIn(MIN_SCALE, 1f)
        val w = w1 * scale
        val h = h1 * scale
        return BoxState(
            cx = s.cx.coerceIn(w / 2f, 1f - w / 2f),
            cy = s.cy.coerceIn(h / 2f, 1f - h / 2f),
            scale = scale
        )
    }

    private fun rectOf(s: BoxState, w1: Float, h1: Float): NormRect {
        val c = clampBox(s, w1, h1)
        val w = w1 * c.scale
        val h = h1 * c.scale
        return NormRect(c.cx - w / 2f, c.cy - h / 2f, c.cx + w / 2f, c.cy + h / 2f)
    }

    fun clampTall(s: BoxState) = clampBox(s, TALL_W, 1f)
    fun clampWide(s: BoxState) = clampBox(s, 1f, WIDE_H)
    fun clamp(s: BoxState) = clampWide(s)

    fun tall(s: BoxState = BoxState()): NormRect = rectOf(s, TALL_W, 1f)
    fun wide(s: BoxState = BoxState()): NormRect = rectOf(s, 1f, WIDE_H)

    fun primary(s: BoxState = BoxState()): NormRect = tall(s)
    fun secondary(s: BoxState): NormRect = wide(s)

    fun isLandscape(angle: Int) = angle % 180 != 0

    /** Etichetta del formato principale (riquadro alto) per l'orientamento del telefono. */
    fun primaryLabel(angle: Int) = if (isLandscape(angle)) "16:9" else "9:16"
    fun secondaryLabel(angle: Int) = if (isLandscape(angle)) "9:16" else "16:9"

    /** Suffisso del file in base alla forma finale del ritaglio dopo la rotazione. */
    fun fileTag(rect: NormRect, angle: Int, frameW: Int, frameH: Int): String {
        val w = rect.width * frameW
        val h = rect.height * frameH
        val tallOnScreen = h > w
        val tallFinal = if (isLandscape(angle)) !tallOnScreen else tallOnScreen
        return if (tallFinal) "9x16" else "16x9"
    }

    /**
     * Angolo (0/90/180/270, in senso orario) di cui è ruotato il telefono rispetto
     * alla posizione verticale, con isteresi per evitare sfarfallii.
     */
    fun snapOrientation(degrees: Int, current: Int): Int {
        val nearest = (((degrees + 45) / 90) % 4) * 90
        if (nearest == current) return current
        val diff = abs(((degrees - nearest + 540) % 360) - 180)
        return if (diff < 30) nearest else current
    }

    fun even(v: Float): Int = max(2, (v.roundToInt() / 2) * 2)
}
