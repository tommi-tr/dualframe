package com.dualframe.cam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/** Contenitore che impone il rapporto 3:4 (larghezza:altezza) del fotogramma del grandangolo. */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w / FrameMath.VIEW_ASPECT).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}

/** Pulsante di scatto: cerchio bianco (foto), cerchio rosso (video), quadrato rosso (in registrazione). */
class ShutterButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Style { PHOTO, VIDEO, RECORDING }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 4f * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var morph = 0f // 0 = cerchio, 1 = quadrato
    private var animator: ValueAnimator? = null

    var style: Style = Style.PHOTO
        set(v) {
            if (field == v) return
            field = v
            animator?.cancel()
            val target = if (v == Style.RECORDING) 1f else 0f
            animator = ValueAnimator.ofFloat(morph, target).apply {
                duration = 180
                addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                start()
            }
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - ring.strokeWidth / 2f
        canvas.drawCircle(cx, cy, outer, ring)

        fill.color = if (style == Style.PHOTO) Color.WHITE else Color.rgb(229, 55, 43)
        val circleR = outer - 6f * density
        val squareHalf = 15f * density
        val half = circleR + (squareHalf - circleR) * morph
        val corner = circleR + (7f * density - circleR) * morph
        val scale = if (isPressed) 0.92f else 1f
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, fill)
        canvas.restore()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/**
 * Casella della barra impostazioni: etichetta piccola sopra, valore sotto.
 * Tutte le caselle hanno la stessa larghezza, così la riga resta equilibrata e non scorre.
 */
class SettingCell @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : android.widget.LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val captionView = android.widget.TextView(context).apply {
        textSize = 8.5f
        letterSpacing = 0.08f
        setTextColor(Color.rgb(140, 136, 132))
        gravity = android.view.Gravity.CENTER
        maxLines = 1
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val valueView = android.widget.TextView(context).apply {
        textSize = 12.5f
        setTextColor(Color.WHITE)
        gravity = android.view.Gravity.CENTER
        maxLines = 1
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
    }
    private val bg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 9f * density }
    private val accent = 0xFFFFB020.toInt()

    var caption: String = ""
        set(v) { field = v; captionView.text = v.uppercase() }

    var value: String = ""
        set(v) { field = v; valueView.text = v; contentDescription = "$caption $v" }

    /** Il menu di questa casella è aperto. */
    var active: Boolean = false
        set(v) { field = v; refresh() }

    /** Casella non utilizzabile in questo momento (es. fps in modalità foto). */
    var dimmed: Boolean = false
        set(v) { field = v; refresh() }

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = bg
        addView(captionView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(valueView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (-1 * density).toInt()
        })
        refresh()
    }

    private fun refresh() {
        bg.setColor(if (active) Color.argb(40, 255, 176, 32) else Color.TRANSPARENT)
        valueView.setTextColor(if (active) accent else Color.WHITE)
        alpha = if (dimmed) 0.35f else 1f
        contentDescription = "$caption $value"
    }
}
