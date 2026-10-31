package com.dualframe.cam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Cartelle della galleria: foto e video insieme, divisi per orientamento. */
object Folders {
    const val VERTICAL = "DCIM/Camera Dual Verticali"
    const val HORIZONTAL = "DCIM/Camera Dual Orizzontali"
    const val ORIGINALS = "DCIM/Camera Dual Originali"

    fun forTag(tag: String) = if (tag == "9x16") VERTICAL else HORIZONTAL
}

/** File salvato nella galleria. */
data class SavedMedia(val uri: Uri, val name: String, val width: Int, val height: Int)

object MediaSaver {
    const val PREFIX = "CameraDual"

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun saveJpeg(context: Context, bitmap: Bitmap, name: String, tag: String): SavedMedia? {
        val file = "$name.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Folders.forTag(tag))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            SavedMedia(uri, file, bitmap.width, bitmap.height)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun saveVideo(context: Context, src: File, name: String, folder: String, width: Int, height: Int): SavedMedia? {
        val file = "$name.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, folder)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            SavedMedia(uri, file, width, height)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}

/** Ritaglia una foto (già orientata come il mirino) nei due formati e li salva. */
object PhotoProcessor {

    data class Result(val primary: SavedMedia?, val secondary: SavedMedia?, val thumbnail: Bitmap?) {
        val vertical get() = listOfNotNull(primary, secondary).firstOrNull { it.height > it.width }
        val horizontal get() = listOfNotNull(primary, secondary).firstOrNull { it.width >= it.height }
    }

    fun saveDual(
        context: Context,
        frame: Bitmap,
        primary: NormRect,
        secondary: NormRect,
        angle: Int,
        maxLongSide: Int?
    ): Result {
        val ts = MediaSaver.timestamp()
        val a = crop(frame, primary, angle, maxLongSide)
        val tagA = FrameMath.fileTag(primary, angle, frame.width, frame.height)
        val savedA = MediaSaver.saveJpeg(context, a, "${MediaSaver.PREFIX}_${ts}_$tagA", tagA)
        val thumb = Bitmap.createScaledBitmap(a, max(1, a.width / 8), max(1, a.height / 8), true)
        a.recycle()

        val b = crop(frame, secondary, angle, maxLongSide)
        val tagB = FrameMath.fileTag(secondary, angle, frame.width, frame.height)
        val savedB = MediaSaver.saveJpeg(context, b, "${MediaSaver.PREFIX}_${ts}_$tagB", tagB)
        b.recycle()
        return Result(savedA, savedB, thumb)
    }

    private fun crop(src: Bitmap, r: NormRect, angle: Int, maxLongSide: Int?): Bitmap {
        val x = (r.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val y = (r.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = (r.width * src.width).roundToInt().coerceIn(1, src.width - x)
        val h = (r.height * src.height).roundToInt().coerceIn(1, src.height - y)
        val m = Matrix()
        val long = max(w, h)
        if (maxLongSide != null && long > maxLongSide) {
            val s = maxLongSide.toFloat() / long
            m.postScale(s, s)
        }
        // Ruota in senso orario dell'angolo del telefono → il risultato è dritto.
        if (angle != 0) m.postRotate(angle.toFloat())
        return Bitmap.createBitmap(src, x, y, w, h, m, true)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }
}
