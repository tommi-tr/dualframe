package com.dualframe.cam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Traiettoria del ritaglio nel tempo (coordinate del mirino).
 * Registra ogni spostamento fatto durante la ripresa per riprodurlo in esportazione.
 */
class CropTrack {
    private val times = ArrayList<Long>()
    private val rects = ArrayList<NormRect>()

    @Synchronized
    fun add(timeUs: Long, rect: NormRect) {
        val t = max(0L, timeUs)
        if (times.isNotEmpty() && t <= times.last()) {
            rects[rects.size - 1] = rect
            return
        }
        times.add(t); rects.add(rect)
    }

    @Synchronized
    fun snapshot(): CropTrack = CropTrack().also { c -> c.times.addAll(times); c.rects.addAll(rects) }

    /** Interpolazione lineare tra i campioni (i campioni sono densi, quindi segue il dito). */
    @Synchronized
    fun rectAt(timeUs: Long): NormRect {
        if (rects.isEmpty()) return FrameMath.primary()
        if (timeUs <= times.first()) return rects.first()
        if (timeUs >= times.last()) return rects.last()
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= timeUs) lo = mid else hi = mid
        }
        val f = (timeUs - times[lo]).toFloat() / (times[hi] - times[lo]).toFloat()
        val a = rects[lo]
        val b = rects[hi]
        fun l(x: Float, y: Float) = x + (y - x) * f
        return NormRect(l(a.left, b.left), l(a.top, b.top), l(a.right, b.right), l(a.bottom, b.bottom))
    }

    @Synchronized
    fun toJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (i in times.indices) {
            val r = rects[i]
            arr.put(org.json.JSONArray().apply {
                put(times[i]); put(r.left.toDouble()); put(r.top.toDouble()); put(r.right.toDouble()); put(r.bottom.toDouble())
            })
        }
        return arr
    }

    companion object {
        fun fixed(rect: NormRect) = CropTrack().also { it.add(0, rect) }

        fun fromJson(arr: org.json.JSONArray?): CropTrack? {
            if (arr == null || arr.length() == 0) return null
            val t = CropTrack()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONArray(i)
                t.add(e.getLong(0), NormRect(e.getDouble(1).toFloat(), e.getDouble(2).toFloat(),
                    e.getDouble(3).toFloat(), e.getDouble(4).toFloat()))
            }
            return t
        }
    }
}

/**
 * Ritaglio che può muoversi fotogramma per fotogramma.
 *
 * Il percorso è espresso nelle coordinate del mirino (video "come lo vedi").
 * Se il decoder consegna i fotogrammi ancora nell'orientamento del sensore,
 * il rettangolo viene convertito con la rotazione del file master.
 */
@OptIn(UnstableApi::class)
class PanCropTransformation(
    private val track: CropTrack,
    private val masterRotation: Int,
    private val displayIsLandscape: Boolean,
    private val maxLongSide: Int?
) : MatrixTransformation {

    private var sensorOriented = false
    private val matrix = Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        sensorOriented = (inputWidth > inputHeight) != displayIsLandscape
        val r = map(track.rectAt(0))
        val cw = r.width * inputWidth
        val ch = r.height * inputHeight
        val long = max(cw, ch)
        val s = if (maxLongSide != null) min(1f, maxLongSide / long) else 1f
        return Size(FrameMath.even(cw * s), FrameMath.even(ch * s))
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val r = map(track.rectAt(presentationTimeUs))
        // Da coordinate normalizzate (y verso il basso) a NDC (-1..1, y verso l'alto)
        val left = 2f * r.left - 1f
        val right = 2f * r.right - 1f
        val top = 1f - 2f * r.top
        val bottom = 1f - 2f * r.bottom
        val scaleX = (right - left) / 2f
        val scaleY = (top - bottom) / 2f
        matrix.reset()
        matrix.postTranslate(-(left + right) / 2f, -(top + bottom) / 2f)
        matrix.postScale(1f / scaleX, 1f / scaleY)
        return matrix
    }

    private fun map(d: NormRect): NormRect {
        if (!sensorOriented) return d
        return when ((masterRotation % 360 + 360) % 360) {
            90 -> NormRect(d.top, 1f - d.right, d.bottom, 1f - d.left)
            270 -> NormRect(1f - d.bottom, d.left, 1f - d.top, d.right)
            180 -> NormRect(1f - d.right, 1f - d.bottom, 1f - d.left, 1f - d.top)
            else -> d
        }
    }
}

/** Crea i due video (9:16 e 16:9) a partire dal master 4:3 registrato dal grandangolo. */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    data class MasterInfo(val width: Int, val height: Int, val rotation: Int) {
        val displayW get() = if (rotation % 180 == 0) width else height
        val displayH get() = if (rotation % 180 == 0) height else width
    }

    fun probe(file: File): MasterInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return MasterInfo(w, h, rot)
        } finally {
            r.release()
        }
    }

    fun thumbnail(file: File): Bitmap? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val b = r.getFrameAtTime(0)
        r.release()
        b?.let { Bitmap.createScaledBitmap(it, max(1, it.width / 6), max(1, it.height / 6), true) }
    } catch (e: Exception) {
        null
    }

    /** Va chiamato sul thread principale (Transformer usa il Looper del thread chiamante). */
    suspend fun export(
        master: File,
        track: CropTrack,
        angle: Int,
        codec: Codec,
        maxLongSide: Int?,
        out: File
    ) {
        val info = probe(master)
        val effects = listOf(
            PanCropTransformation(track, info.rotation, info.displayW > info.displayH, maxLongSide),
            // Raddrizza il ritaglio secondo come era tenuto il telefono (rotazione antioraria per Media3).
            ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(((360 - angle) % 360).toFloat())
                .build()
        )
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(master)))
            .setEffects(Effects(emptyList(), effects))
            .build()

        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(if (codec == Codec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(item, out.absolutePath)
            cont.invokeOnCancellation {
                android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() }
            }
        }
    }
}
