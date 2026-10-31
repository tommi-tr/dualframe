package com.dualframe.cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Disegna i due riquadri sopra l'anteprima e gestisce i gesti:
 *  - tocco in un punto → il riquadro secondario salta lì all'istante
 *  - trascinamento → sposta il secondario (oppure il principale se si prende la sua maniglia bianca)
 *  - pizzico → ridimensiona il riquadro sotto le dita (50–100%)
 *  - pressione prolungata → punto di messa a fuoco
 *
 * Etichette e maniglie seguono l'orientamento del telefono: l'etichetta sta sempre
 * in alto a sinistra (per chi guarda), le frecce in basso per i riquadri verticali
 * e a destra per quelli orizzontali.
 */
class FrameOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Target { PRIMARY, SECONDARY }

    var accent: Int = 0xFFFFB020.toInt()
        set(v) { field = v; invalidate() }

    /** Rotazione del telefono (0/90/180/270). */
    var deviceAngle: Int = 0
        set(v) { field = v; invalidate() }

    var showGrid: Boolean = false
        set(v) { field = v; invalidate() }

    var primaryState = BoxState()
        private set
    var secondaryState = BoxState()
        private set

    /** Chiamato ad ogni spostamento/ridimensionamento. */
    var onChanged: (() -> Unit)? = null
    var onTap: ((Float, Float) -> Unit)? = null
    var onLongPress: ((Float, Float) -> Unit)? = null

    var focusMarker: PointF? = null
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 1.5f * density
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(90, 255, 255, 255); strokeWidth = 1f * density
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val gripBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val gripStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val focusDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density
    }

    private val pRect = RectF()
    private val sRect = RectF()
    private val path = Path()

    private var dragTarget: Target? = null
    private var followFinger = false
    private var pendingTap = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pinchTarget = Target.SECONDARY

    private var ringX = 0f
    private var ringY = 0f
    private var ringUntil = 0L

    private val longPress = Runnable {
        if (pendingTap) {
            pendingTap = false
            dragTarget = null
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke(downX, downY)
            invalidate()
        }
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pendingTap = false
                removeCallbacks(longPress)
                updateRects()
                pinchTarget = when {
                    sRect.contains(d.focusX, d.focusY) -> Target.SECONDARY
                    pRect.contains(d.focusX, d.focusY) -> Target.PRIMARY
                    else -> Target.SECONDARY
                }
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (pinchTarget == Target.PRIMARY) {
                    setPrimary(primaryState.copy(scale = primaryState.scale * d.scaleFactor))
                } else {
                    setSecondary(secondaryState.copy(scale = secondaryState.scale * d.scaleFactor))
                }
                return true
            }
        })

    fun primaryRect(): NormRect = FrameMath.tall(primaryState)
    fun secondaryRect(): NormRect = FrameMath.wide(secondaryState)

    fun setPrimary(s: BoxState) {
        val c = FrameMath.clampTall(s)
        if (c != primaryState) { primaryState = c; onChanged?.invoke() }
        invalidate()
    }

    fun setSecondary(s: BoxState) {
        val c = FrameMath.clampWide(s)
        if (c != secondaryState) { secondaryState = c; onChanged?.invoke() }
        invalidate()
    }

    fun recenter() {
        primaryState = BoxState()
        secondaryState = BoxState()
        onChanged?.invoke()
        invalidate()
    }

    private fun toPx(r: NormRect, out: RectF) {
        out.set(r.left * width, r.top * height, r.right * width, r.bottom * height)
    }

    private fun updateRects() {
        toPx(primaryRect(), pRect)
        toPx(secondaryRect(), sRect)
    }

    // ---- Coordinate "per chi guarda" (dipendono da come è tenuto il telefono) ----

    private fun landscape() = FrameMath.isLandscape(deviceAngle)

    /** Larghezza/altezza del rettangolo come le vede l'utente. */
    private fun userW(r: RectF) = if (landscape()) r.height() else r.width()
    private fun userH(r: RectF) = if (landscape()) r.width() else r.height()

    /** Punto a (ux, uy) pixel dall'angolo in alto a sinistra visto dall'utente → coordinate schermo. */
    private fun userPoint(r: RectF, ux: Float, uy: Float, out: PointF): PointF {
        when (deviceAngle) {
            90 -> out.set(r.left + uy, r.bottom - ux)
            180 -> out.set(r.right - ux, r.bottom - uy)
            270 -> out.set(r.right - uy, r.top + ux)
            else -> out.set(r.left + ux, r.top + uy)
        }
        return out
    }

    /** Centro della maniglia: in basso al centro se il riquadro è verticale per l'utente, a destra se è orizzontale. */
    private fun gripCenter(r: RectF, out: PointF): PointF {
        val m = 8f * density
        val uw = userW(r)
        val uh = userH(r)
        return if (uh > uw) userPoint(r, uw / 2f, uh - m - 16f * density, out)
        else userPoint(r, uw - m - 16f * density, uh / 2f, out)
    }

    private val tmpPt = PointF()

    private fun nearGrip(r: RectF, x: Float, y: Float): Boolean {
        val c = gripCenter(r, tmpPt)
        return hypot(x - c.x, y - c.y) < 36f * density
    }

    // ---- Gesti ----

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateRects()
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                val pad = 16f * density
                dragTarget = when {
                    nearGrip(sRect, e.x, e.y) -> Target.SECONDARY
                    nearGrip(pRect, e.x, e.y) -> Target.PRIMARY
                    RectF(sRect).apply { inset(-pad, -pad) }.contains(e.x, e.y) -> Target.SECONDARY
                    else -> null
                }
                followFinger = false
                pendingTap = true
                postDelayed(longPress, longPressTimeout)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pendingTap = false
                removeCallbacks(longPress)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || e.pointerCount > 1) {
                    lastX = e.x; lastY = e.y
                    return true
                }
                if (pendingTap && hypot(e.x - downX, e.y - downY) > touchSlop) {
                    pendingTap = false
                    removeCallbacks(longPress)
                    if (dragTarget == null) {
                        // Trascinamento iniziato fuori dal riquadro: il 16:9/9:16 va sotto al dito e lo segue.
                        dragTarget = Target.SECONDARY
                        followFinger = true
                    }
                }
                if (!pendingTap && width > 0 && height > 0) {
                    when {
                        followFinger -> setSecondary(secondaryState.copy(cx = e.x / width, cy = e.y / height))
                        dragTarget == Target.SECONDARY -> setSecondary(secondaryState.copy(
                            cx = secondaryState.cx + (e.x - lastX) / width,
                            cy = secondaryState.cy + (e.y - lastY) / height))
                        dragTarget == Target.PRIMARY -> setPrimary(primaryState.copy(
                            cx = primaryState.cx + (e.x - lastX) / width,
                            cy = primaryState.cy + (e.y - lastY) / height))
                    }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (pendingTap && width > 0 && height > 0) {
                    // Tocco singolo: il riquadro secondario si sposta subito lì.
                    setSecondary(secondaryState.copy(cx = e.x / width, cy = e.y / height))
                    ringX = e.x; ringY = e.y
                    ringUntil = System.currentTimeMillis() + 600
                    onTap?.invoke(e.x, e.y)
                    performClick()
                }
                dragTarget = null; followFinger = false; pendingTap = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                dragTarget = null; followFinger = false; pendingTap = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---- Disegno ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateRects()
        val p = pRect
        val s = sRect

        // Oscura tutto ciò che non finisce in nessuno dei due file.
        canvas.save()
        canvas.clipOutRect(p)
        canvas.clipOutRect(s)
        canvas.drawPaint(dimPaint)
        canvas.restore()

        if (showGrid) {
            for (i in 1..2) {
                val x = p.left + p.width() * i / 3f
                val y = p.top + p.height() * i / 3f
                canvas.drawLine(x, p.top, x, p.bottom, gridPaint)
                canvas.drawLine(p.left, y, p.right, y, gridPaint)
            }
        }

        val dragging = !pendingTap && dragTarget != null
        if (dragging && dragTarget == Target.PRIMARY) {
            glowPaint.color = Color.argb(90, 255, 255, 255)
            canvas.drawRect(p, glowPaint)
        }
        canvas.drawRect(p, primaryPaint)

        secondaryPaint.color = accent
        if (dragging && dragTarget == Target.SECONDARY) {
            glowPaint.color = (accent and 0x00FFFFFF) or 0x55000000
            canvas.drawRect(s, glowPaint)
        }
        canvas.drawRect(s, secondaryPaint)

        val m = 8f * density
        val lh = 22f * density
        // Etichette: secondario in alto a sinistra, principale in basso a sinistra (per chi guarda).
        val sl = FrameMath.secondaryLabel(deviceAngle)
        val pl = FrameMath.primaryLabel(deviceAngle)
        drawLabelAt(canvas, sl, userPoint(s, m + labelW(sl) / 2f, m + lh / 2f, PointF()),
            accent, Color.rgb(26, 18, 0))
        drawLabelAt(canvas, pl, userPoint(p, m + labelW(pl) / 2f, userH(p) - m - lh / 2f, PointF()),
            Color.WHITE, Color.rgb(17, 17, 17))

        drawGrip(canvas, s, accent)
        drawGrip(canvas, p, Color.WHITE)

        focusMarker?.let { f -> drawFocus(canvas, f) }

        val now = System.currentTimeMillis()
        if (now < ringUntil) {
            val t = 1f - (ringUntil - now) / 600f
            ringPaint.color = accent
            ringPaint.alpha = ((1f - t) * 255).toInt()
            canvas.drawCircle(ringX, ringY, (28f + 8f * t) * density, ringPaint)
            postInvalidateOnAnimation()
        }
    }

    private fun labelW(text: String) = labelText.measureText(text) + 16f * density

    private fun drawLabelAt(canvas: Canvas, text: String, c: PointF, bg: Int, fg: Int) {
        val h = 22f * density
        val w = labelW(text)
        labelBg.color = bg
        labelText.color = fg
        canvas.save()
        canvas.rotate(-deviceAngle.toFloat(), c.x, c.y)
        canvas.drawRoundRect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f, 6f * density, 6f * density, labelBg)
        val ty = c.y - (labelText.descent() + labelText.ascent()) / 2f
        canvas.drawText(text, c.x, ty, labelText)
        canvas.restore()
    }

    /** Maniglia con le frecce nelle direzioni in cui il riquadro si può muovere. */
    private fun drawGrip(canvas: Canvas, r: RectF, color: Int) {
        val canH = r.width() < width - 1f   // si muove in orizzontale sullo schermo
        val canV = r.height() < height - 1f // si muove in verticale sullo schermo
        val c = gripCenter(r, PointF())
        val uw = userW(r)
        val uh = userH(r)
        val tall = uh > uw
        // Assi per chi guarda
        val userH_ = if (landscape()) canV else canH
        val userV_ = if (landscape()) canH else canV
        val pw = (if (tall) 48f else 32f) * density
        val ph = (if (tall) 32f else 48f) * density
        canvas.save()
        canvas.rotate(-deviceAngle.toFloat(), c.x, c.y)
        canvas.drawRoundRect(c.x - pw / 2f, c.y - ph / 2f, c.x + pw / 2f, c.y + ph / 2f,
            16f * density, 16f * density, gripBg)
        gripStroke.color = color
        val a = 5f * density
        val len = 11f * density
        path.reset()
        if (userV_ || (!userH_ && !tall)) {
            path.moveTo(c.x, c.y - len); path.lineTo(c.x, c.y + len)
            path.moveTo(c.x - a, c.y - len + a); path.lineTo(c.x, c.y - len); path.lineTo(c.x + a, c.y - len + a)
            path.moveTo(c.x - a, c.y + len - a); path.lineTo(c.x, c.y + len); path.lineTo(c.x + a, c.y + len - a)
        }
        if (userH_ || (!userV_ && tall)) {
            path.moveTo(c.x - len, c.y); path.lineTo(c.x + len, c.y)
            path.moveTo(c.x - len + a, c.y - a); path.lineTo(c.x - len, c.y); path.lineTo(c.x - len + a, c.y + a)
            path.moveTo(c.x + len - a, c.y - a); path.lineTo(c.x + len, c.y); path.lineTo(c.x + len - a, c.y + a)
        }
        canvas.drawPath(path, gripStroke)
        canvas.restore()
    }

    private fun drawFocus(canvas: Canvas, f: PointF) {
        val h = 34f * density
        val t = 10f * density
        path.reset()
        path.moveTo(f.x - h, f.y - h + t); path.lineTo(f.x - h, f.y - h); path.lineTo(f.x - h + t, f.y - h)
        path.moveTo(f.x + h - t, f.y - h); path.lineTo(f.x + h, f.y - h); path.lineTo(f.x + h, f.y - h + t)
        path.moveTo(f.x + h, f.y + h - t); path.lineTo(f.x + h, f.y + h); path.lineTo(f.x + h - t, f.y + h)
        path.moveTo(f.x - h + t, f.y + h); path.lineTo(f.x - h, f.y + h); path.lineTo(f.x - h, f.y + h - t)
        canvas.drawPath(path, focusPaint)
        canvas.drawCircle(f.x, f.y, 3f * density, focusDot)
    }
}
