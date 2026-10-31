package com.dualframe.cam

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.activity.ComponentActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln

/**
 * Gestisce CameraX: sceglie la fotocamera più grandangolare, collega anteprima,
 * scatto e registrazione del "master" 4:3 da cui si ricavano i due formati.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val activity: ComponentActivity,
    private val previewView: PreviewView
) {
    enum class Mode { PHOTO, VIDEO }

    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var chosenInfo: CameraInfo? = null
    private var boundCameraId: String? = null

    /** Lenti posteriori del telefono, dalla più ampia alla più stretta. */
    var lenses: List<LensOption> = emptyList()
        private set
    var lens: LensOption? = null
        private set

    var camera: Camera? = null
        private set
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    val zoomLabel: String get() = lens?.label ?: "1×"

    /** fps effettivamente usati (può differire da quelli richiesti se non supportati). */
    var activeFps: Int = 30
        private set

    /** Chiamato dopo ogni collegamento riuscito (per osservare lo stato della fotocamera). */
    var onBound: ((Camera) -> Unit)? = null

    val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true
    val isRecording: Boolean get() = recording != null

    // ---- Messa a fuoco e diaframma ----
    enum class FocusMode { AUTO, POINT, MANUAL }

    var focusMode = FocusMode.AUTO
        private set
    /** 0 = infinito, 1 = distanza minima. */
    var manualFocus = 0f
        private set
    private var focusPointX = 0f
    private var focusPointY = 0f
    private var aperture: Float? = null

    /** Diaframmi della lente scelta (quasi sempre uno solo: fisso). */
    val apertures: List<Float> get() = lens?.apertures ?: emptyList()
    val currentAperture: Float? get() = aperture ?: apertures.firstOrNull()
    val apertureSelectable: Boolean get() = apertures.size > 1

    /** La lente ha la messa a fuoco (alcuni grandangoli hanno il fuoco fisso). */
    val hasFocus: Boolean get() = (lens?.minFocusDiopters ?: 0f) > 0f

    val supportsManualFocus: Boolean
        get() {
            val info = chosenInfo ?: return false
            val c = Camera2CameraInfo.from(info)
            val caps = c.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
            val min = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            return hasFocus && min > 0f &&
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        }

    fun start(preferredZoom: Float?, onReady: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val p = future.get()
            provider = p
            lenses = runCatching { LensCatalog.build(activity, p) }.getOrDefault(emptyList())
            lens = when {
                lenses.isEmpty() -> null
                preferredZoom != null -> lenses.minByOrNull { abs(ln(it.equivZoom / preferredZoom)) }
                else -> lenses.first() // il grandangolo
            }
            updateSelector()
            onReady()
        }, mainExecutor)
    }

    private fun updateSelector() {
        val p = provider ?: return
        val id = lens?.cameraId ?: return
        chosenInfo = p.availableCameraInfos.firstOrNull { Camera2CameraInfo.from(it).cameraId == id }
        selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
            .build()
    }

    /**
     * Cambia lente. Se la lente si raggiunge con lo zoom della fotocamera già collegata
     * il cambio è istantaneo (anche durante la ripresa) e restituisce false;
     * altrimenti restituisce true: serve ricollegare la fotocamera.
     */
    fun selectLens(option: LensOption): Boolean {
        val sameCamera = option.cameraId == boundCameraId && camera != null
        if (!sameCamera && recording != null) return false
        lens = option
        aperture = null
        if (sameCamera) {
            applyLensZoom()
            applyCaptureOptions()
            return false
        }
        updateSelector()
        return true
    }

    fun canSwitchWhileRecording(option: LensOption) = option.cameraId == boundCameraId

    private fun applyLensZoom() {
        val cam = camera ?: return
        val z = lens?.zoomRatio ?: 1f
        val st = cam.cameraInfo.zoomState.value
        val clamped = if (st != null) z.coerceIn(st.minZoomRatio, st.maxZoomRatio) else z
        cam.cameraControl.setZoomRatio(clamped)
    }

    fun setAutoFocus() {
        focusMode = FocusMode.AUTO
        camera?.cameraControl?.cancelFocusAndMetering()
        applyCaptureOptions()
    }

    /** Fuoco ed esposizione bloccati sul punto scelto (coordinate della PreviewView). */
    fun focusAt(x: Float, y: Float) {
        focusMode = FocusMode.POINT
        focusPointX = x
        focusPointY = y
        applyCaptureOptions()
        startPointFocus()
    }

    private fun startPointFocus() {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(focusPointX, focusPointY)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).disableAutoCancel().build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun setManualFocus(fraction: Float) {
        if (focusMode != FocusMode.MANUAL) camera?.cameraControl?.cancelFocusAndMetering()
        focusMode = FocusMode.MANUAL
        manualFocus = fraction.coerceIn(0f, 1f)
        applyCaptureOptions()
    }

    fun setAperture(f: Float) {
        aperture = f
        applyCaptureOptions()
    }

    private fun applyCaptureOptions() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        if (focusMode == FocusMode.MANUAL && supportsManualFocus) {
            val info = chosenInfo
            val min = info?.let {
                Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            } ?: 0f
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus * min)
        }
        val a = aperture
        if (a != null && apertureSelectable) {
            b.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, a)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    /** Dopo aver ricollegato la fotocamera ripristina zoom, fuoco e diaframma. */
    private fun restoreLensState() {
        applyLensZoom()
        applyCaptureOptions()
        if (focusMode == FocusMode.POINT) previewView.post { startPointFocus() }
    }

    private fun frameRateRange(fps: Int): Range<Int>? {
        val ranges = chosenInfo?.supportedFrameRateRanges ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.firstOrNull { it.upper == fps }
    }

    /**
     * Collega i casi d'uso. In VIDEO collega anche lo scatto foto, così si può
     * fotografare durante la ripresa. Se il dispositivo non regge la combinazione
     * prova configurazioni via via più leggere.
     */
    fun bind(mode: Mode, fps: Int, flash: FlashMode): Boolean {
        val p = provider ?: return false
        if (recording != null) return false
        p.unbindAll()

        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val ratio43Max = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            // ~12 MP: abbastanza per due ritagli nitidi senza esaurire la memoria
            // sui grandangoli da 48/50 MP.
            .setResolutionStrategy(
                ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()

        fun preview() = Preview.Builder().setResolutionSelector(ratio43).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        fun photo(max: Boolean) = ImageCapture.Builder()
            .setResolutionSelector(if (max) ratio43Max else ratio43)
            .setCaptureMode(
                if (mode == Mode.PHOTO) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            )
            .setTargetRotation(Surface.ROTATION_0)
            .setFlashMode(
                when (flash) {
                    FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                }
            )
            .build()

        val range = frameRateRange(fps) ?: frameRateRange(30)
        activeFps = range?.upper ?: 30

        fun video(qualities: List<Quality>): VideoCapture<Recorder> {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(qualities, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                )
                .setAspectRatio(AspectRatio.RATIO_4_3)
                .build()
            val b = VideoCapture.Builder(recorder).setTargetRotation(Surface.ROTATION_0)
            if (range != null) b.setTargetFrameRate(range)
            return b.build()
        }

        val high = listOf(Quality.UHD, Quality.FHD, Quality.HD)
        val mid = listOf(Quality.FHD, Quality.HD)

        val attempts: List<() -> List<UseCase>> = if (mode == Mode.PHOTO) {
            listOf({ listOf(preview(), photo(true)) }, { listOf(preview(), photo(false)) })
        } else {
            listOf(
                { listOf(preview(), video(high), photo(false)) },
                { listOf(preview(), video(mid), photo(false)) },
                { listOf(preview(), video(high)) },
                { listOf(preview(), video(mid)) }
            )
        }

        for (make in attempts) {
            val useCases = make()
            try {
                camera = p.bindToLifecycle(activity, selector, *useCases.toTypedArray())
                imageCapture = useCases.filterIsInstance<ImageCapture>().firstOrNull()
                @Suppress("UNCHECKED_CAST")
                videoCapture = useCases.firstOrNull { it is VideoCapture<*> } as VideoCapture<Recorder>?
                boundCameraId = lens?.cameraId
                restoreLensState()
                camera?.let { onBound?.invoke(it) }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Combinazione non supportata, provo la successiva", e)
                p.unbindAll()
            }
        }
        return false
    }

    fun setTorch(on: Boolean) {
        if (hasFlash) camera?.cameraControl?.enableTorch(on)
    }

    /**
     * Scatta e restituisce il fotogramma orientato come il mirino (3:4 verticale).
     * Se lo scatto non è disponibile durante il video (dispositivi limitati) usa l'anteprima.
     */
    fun takePhoto(callback: (Bitmap?) -> Unit) {
        val ic = imageCapture
        if (ic == null) {
            val bmp = previewView.bitmap
            if (ioExecutor.isShutdown) callback(bmp) else ioExecutor.execute { callback(bmp) }
            return
        }
        if (ioExecutor.isShutdown) { callback(null); return }
        ic.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val bmp = try { image.toBitmap() } catch (e: Exception) { null } finally { image.close() }
                callback(bmp?.let { PhotoProcessor.rotate(it, rot) })
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Scatto fallito", exception)
                callback(null)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startRecording(file: File, withAudio: Boolean, listener: Consumer<VideoRecordEvent>): Boolean {
        val vc = videoCapture ?: return false
        if (recording != null) return false
        return try {
            var pending = vc.output.prepareRecording(activity.applicationContext, FileOutputOptions.Builder(file).build())
            if (withAudio) pending = pending.withAudioEnabled()
            recording = pending.start(mainExecutor, listener)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Avvio registrazione non riuscito", e)
            recording = null
            false
        }
    }

    fun stopRecording() {
        try {
            recording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop", e)
        }
        recording = null
    }

    /** La registrazione si è chiusa da sola (fine o errore): dimentica il riferimento. */
    fun clearRecording() {
        recording = null
    }

    fun shutdown() {
        stopRecording()
        ioExecutor.shutdown()
    }

    companion object {
        private const val TAG = "DualFrame"
    }
}
