package com.dualframe.cam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var viewfinder: AspectFrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: FrameOverlayView
    private lateinit var flashView: View
    private lateinit var statusChip: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var btnFlash: SettingCell
    private lateinit var btnGrid: SettingCell
    private lateinit var chipRes: SettingCell
    private lateinit var chipFps: SettingCell
    private lateinit var chipCodec: SettingCell
    private lateinit var chipFocus: SettingCell
    private lateinit var chipAperture: SettingCell
    private lateinit var chipLens: SettingCell
    private lateinit var appVersion: TextView
    private lateinit var sensorManager: android.hardware.SensorManager
    private lateinit var menuPanel: LinearLayout
    private lateinit var menuTitle: TextView
    private lateinit var menuOptions: LinearLayout
    private lateinit var menuSeekRow: LinearLayout
    private lateinit var focusSeek: SeekBar
    private lateinit var menuNote: TextView
    private lateinit var toastView: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var processing: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var gallery: ImageView
    private lateinit var shutter: ShutterButton
    private lateinit var btnRecenter: ImageButton
    private lateinit var btnSnap: ImageButton
    private lateinit var permissionView: LinearLayout

    private lateinit var settings: CaptureSettings
    private lateinit var controller: CameraController
    private lateinit var orientationListener: OrientationEventListener

    private val handler = Handler(Looper.getMainLooper())
    private var mode = CameraController.Mode.PHOTO
    private var angle = 0
    private var openMenu: String? = null
    private var cameraStarted = false
    private var observedCamera: Camera? = null

    // ---- Stato della ripresa ----
    private var recordingActive = false
    private var recAngle = 0
    private var recStartNs = 0L
    private var recSeconds = 0L
    private var currentJob: PendingJob? = null
    private var primaryTrack: CropTrack? = null
    private var secondaryTrack: CropTrack? = null
    private var pendingCount = 0

    private val accent by lazy { ContextCompat.getColor(this, R.color.accent) }
    private val dp by lazy { resources.displayMetrics.density }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (hasPermission(Manifest.permission.CAMERA)) startCamera() else showPermissionView()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()

        settings = CaptureSettings(this)
        controller = CameraController(this, previewView)
        controller.onBound = { cam -> observeCamera(cam) }

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding((12 * dp).toInt(), bars.top + (2 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            bottomBar.setPadding(0, 0, 0, bars.bottom + (8 * dp).toInt())
            topBar.post { layoutBelowTopBar() }
            insets
        }

        overlay.accent = accent
        overlay.onChanged = { onFramesMoved() }
        overlay.onTap = { _, _ -> closeMenu() }
        overlay.onLongPress = { x, y -> setFocusPoint(x, y) }

        shutter.setOnClickListener { onShutter() }
        btnSnap.setOnClickListener { takePhoto() }
        btnRecenter.setOnClickListener { overlay.recenter() }
        modePhoto.setOnClickListener { setMode(CameraController.Mode.PHOTO) }
        modeVideo.setOnClickListener { setMode(CameraController.Mode.VIDEO) }
        chipRes.setOnClickListener { toggleMenu(if (mode == CameraController.Mode.VIDEO) "vres" else "pres") }
        chipFps.setOnClickListener {
            if (mode == CameraController.Mode.VIDEO) toggleMenu("fps") else showToast("Gli fps valgono per i video: passa a VIDEO")
        }
        chipCodec.setOnClickListener {
            if (mode == CameraController.Mode.VIDEO) toggleMenu("codec") else showToast("Il codec vale per i video: passa a VIDEO")
        }
        chipFocus.setOnClickListener { toggleMenu("focus") }
        chipAperture.setOnClickListener { toggleMenu("aperture") }
        chipLens.setOnClickListener { toggleMenu("lens") }
        btnFlash.setOnClickListener { cycleFlash() }
        // GUIDE: nessuna → livella → griglia → entrambe
        btnGrid.setOnClickListener {
            val (g, l) = when {
                !settings.grid && !settings.level -> false to true
                !settings.grid && settings.level -> true to false
                settings.grid && !settings.level -> true to true
                else -> false to false
            }
            settings.grid = g
            settings.level = l
            refreshUi()
        }
        appVersion.text = "Camera Dual ${BuildConfig.VERSION_NAME}"
        appVersion.setOnClickListener { checkForUpdate() }
        chipLens.caption = "Lente"; chipRes.caption = "Risol."; chipFps.caption = "fps"
        chipCodec.caption = "Codec"; chipFocus.caption = "Fuoco"; chipAperture.caption = "Diafr."
        btnFlash.caption = "Flash"; btnGrid.caption = "Guide"
        sensorManager = getSystemService(android.hardware.SensorManager::class.java)
        UpdateWorker.schedule(applicationContext)
        gallery.setOnClickListener { startActivity(Intent(this, TakesActivity::class.java)) }
        findViewById<Button>(R.id.btnPermission).setOnClickListener { requestPermissions() }
        focusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Macro a sinistra, infinito a destra
                controller.setManualFocus(1f - progress / 1000f)
                overlay.focusMarker = null
                refreshUi()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val snapped = FrameMath.snapOrientation(orientation, angle)
                if (snapped != angle) {
                    angle = snapped
                    if (!recordingActive) overlay.deviceAngle = angle
                    rotateIcons()
                    refreshUi()
                }
            }
        }

        refreshUi()
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
            askNotificationsOnce()
        } else {
            requestPermissions()
        }
    }

    /** Chi aggiorna dalla versione precedente: chiede una sola volta il permesso per la notifica del taglio. */
    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotif", false)) return
        prefs.edit().putBoolean("askedNotif", true).apply()
        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        viewfinder = findViewById(R.id.viewfinder)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        flashView = findViewById(R.id.flashView)
        statusChip = findViewById(R.id.statusChip)
        topBar = findViewById(R.id.topBar)
        btnFlash = findViewById(R.id.btnFlash)
        btnGrid = findViewById(R.id.btnGrid)
        appVersion = findViewById(R.id.appVersion)
        chipRes = findViewById(R.id.chipRes)
        chipFps = findViewById(R.id.chipFps)
        chipCodec = findViewById(R.id.chipCodec)
        chipFocus = findViewById(R.id.chipFocus)
        chipAperture = findViewById(R.id.chipAperture)
        chipLens = findViewById(R.id.chipLens)
        menuPanel = findViewById(R.id.menuPanel)
        menuTitle = findViewById(R.id.menuTitle)
        menuOptions = findViewById(R.id.menuOptions)
        menuSeekRow = findViewById(R.id.menuSeekRow)
        focusSeek = findViewById(R.id.focusSeek)
        menuNote = findViewById(R.id.menuNote)
        toastView = findViewById(R.id.toast)
        bottomBar = findViewById(R.id.bottomBar)
        processing = findViewById(R.id.processing)
        modeRow = findViewById(R.id.modeRow)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        gallery = findViewById(R.id.gallery)
        shutter = findViewById(R.id.shutter)
        btnRecenter = findViewById(R.id.btnRecenter)
        btnSnap = findViewById(R.id.btnSnap)
        permissionView = findViewById(R.id.permissionView)
    }

    private fun layoutBelowTopBar() {
        val h = topBar.height
        viewfinder.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = h }
        menuPanel.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = h }
        toastView.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = h + (44 * dp).toInt() }
        viewfinder.post { centerBottomControls() }
    }

    /**
     * I comandi in basso stanno al centro dello spazio sotto il mirino, invece di
     * restare schiacciati sul fondo con un vuoto sopra. Se lo spazio è poco restano in fondo.
     */
    private fun centerBottomControls() {
        val free = root.height - (topBar.height + viewfinder.height)
        bottomBar.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val need = bottomBar.measuredHeight
        val target = if (free > need) free else ViewGroup.LayoutParams.WRAP_CONTENT
        if (bottomBar.layoutParams.height != target) {
            bottomBar.updateLayoutParams<FrameLayout.LayoutParams> { height = target }
            bottomBar.gravity = Gravity.CENTER
        }
    }

    // ---------- Ciclo di vita ----------

    override fun onStart() {
        super.onStart()
        // Se la ripresa si era interrotta mentre l'app era in sottofondo, chiudila per bene.
        if (recordingActive && !controller.isRecording) finishRecording(null, forced = true)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { PendingStore.recoverOrphans(applicationContext) }
            ExportScheduler.kick(applicationContext)
            refreshPending()
            loadGalleryThumb()
        }
        handler.post(pendingPoll)
    }

    private val levelListener = object : android.hardware.SensorEventListener {
        private var gx = 0f
        private var gy = 9.8f
        private var gz = 0f
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            // Filtro leggero per una livella stabile
            gx += (e.values[0] - gx) * 0.2f
            gy += (e.values[1] - gy) * 0.2f
            gz += (e.values[2] - gz) * 0.2f
            val flat = kotlin.math.abs(gz) > 8.3f // telefono quasi orizzontale sul tavolo: livella nascosta
            overlay.setLevel(Math.toDegrees(kotlin.math.atan2(gx.toDouble(), gy.toDouble())).toFloat(), !flat)
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        (sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER))?.let {
            sensorManager.registerListener(levelListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        orientationListener.disable()
        sensorManager.unregisterListener(levelListener)
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(pendingPoll)
        // Uscendo dall'app la ripresa si chiude e il video viene salvato.
        if (recordingActive || controller.isRecording) requestStop()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (recordingActive || controller.isRecording) {
            controller.stopRecording()
            finishRecording(null, forced = true)
        }
        controller.shutdown()
        super.onDestroy()
    }

    private val pendingPoll = object : Runnable {
        override fun run() {
            lifecycleScope.launch { refreshPending() }
            handler.postDelayed(this, 2000)
        }
    }

    private suspend fun refreshPending() {
        val n = withContext(Dispatchers.IO) {
            PendingStore.list(applicationContext).count { it.state == "ready" }
        }
        if (n != pendingCount) {
            val finished = n < pendingCount
            pendingCount = n
            updateProcessing()
            if (finished) loadGalleryThumb()
        }
    }

    // ---------- Permessi ----------

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        // Per la notifica "Taglio i video…" mentre l'elaborazione continua ad app chiusa.
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun showPermissionView() {
        permissionView.visibility = View.VISIBLE
    }

    private fun startCamera() {
        permissionView.visibility = View.GONE
        if (cameraStarted) return
        cameraStarted = true
        controller.start(settings.lensZoom) {
            buildLensRow()
            rebind()
        }
    }

    private fun rebind() {
        if (recordingActive || controller.isRecording) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return
        val ok = try {
            controller.bind(mode, settings.fps.value, settings.flash)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            showToast("Fotocamera non disponibile")
        } else if (mode == CameraController.Mode.VIDEO && controller.activeFps != settings.fps.value) {
            showToast("${settings.fps.value} fps non supportati da questa lente: uso ${controller.activeFps} fps")
        }
        refreshUi()
    }

    /** Se un'altra app prende la fotocamera durante la ripresa, il video viene chiuso e salvato. */
    private fun observeCamera(cam: Camera) {
        observedCamera?.cameraInfo?.cameraState?.removeObservers(this)
        observedCamera = cam
        cam.cameraInfo.cameraState.observe(this) { state ->
            val closed = state.type == CameraState.Type.CLOSED || state.type == CameraState.Type.CLOSING ||
                state.type == CameraState.Type.PENDING_OPEN
            if (closed && (recordingActive || controller.isRecording)) requestStop()
            val err = state.error
            if (err != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                when (err.code) {
                    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                        showToast("La fotocamera è usata da un'altra app")
                    CameraState.ERROR_CAMERA_DISABLED -> showToast("Fotocamera disattivata dal sistema")
                }
            }
        }
    }

    // ---------- Interfaccia ----------

    private fun setMode(m: CameraController.Mode) {
        if (recordingActive || m == mode) return
        mode = m
        closeMenu()
        rebind()
    }

    private fun refreshUi() {
        val video = mode == CameraController.Mode.VIDEO
        val rec = recordingActive

        val resKey = if (video) "vres" else "pres"
        chipLens.value = controller.lens?.shortLabel ?: "1×"
        chipRes.value = if (video) settings.videoRes.label else settings.photoRes.label
        chipFps.value = "${if (video) controller.activeFps else settings.fps.value}"
        chipCodec.value = if (settings.codec == Codec.HEVC) "HEVC" else "H.264"
        chipFocus.value = when (controller.focusMode) {
            CameraController.FocusMode.AUTO -> "AF"
            CameraController.FocusMode.POINT -> "AF·P"
            CameraController.FocusMode.MANUAL -> "MF"
        }
        chipAperture.value = controller.currentAperture?.let { "f/" + fmtAperture(it) } ?: "—"
        btnFlash.value = when (settings.flash) {
            FlashMode.OFF -> "Off"
            FlashMode.AUTO -> "Auto"
            FlashMode.ON -> "On"
        }
        btnGrid.value = when {
            settings.grid && settings.level -> "Tutte"
            settings.grid -> "Griglia"
            settings.level -> "Livella"
            else -> "Off"
        }
        chipLens.active = openMenu == "lens"
        chipRes.active = openMenu == resKey
        chipFps.active = openMenu == "fps"
        chipCodec.active = openMenu == "codec"
        chipFocus.active = openMenu == "focus"
        chipAperture.active = openMenu == "aperture"
        // In foto fps e codec non contano; durante la ripresa sono bloccati.
        chipRes.dimmed = rec
        chipFps.dimmed = rec || !video
        chipCodec.dimmed = rec || !video
        btnFlash.dimmed = controller.camera != null && !controller.hasFlash
        overlay.showLevel = settings.level
        appVersion.setTextColor(if (rec) 0x40FFFFFF else 0x33FFFFFF)

        modePhoto.setTextColor(if (!video) accent else Color.rgb(214, 211, 209))
        modeVideo.setTextColor(if (video) accent else Color.rgb(214, 211, 209))
        modeRow.visibility = if (rec) View.INVISIBLE else View.VISIBLE

        // Sfondo rosso scuro sopra e sotto mentre si registra
        val bg = ContextCompat.getColor(this, if (rec) R.color.rec_bg else R.color.bg)
        root.setBackgroundColor(bg)
        topBar.setBackgroundColor(bg)
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg

        val secLabel = FrameMath.secondaryLabel(if (rec) recAngle else angle)
        if (rec) {
            statusChip.setBackgroundResource(R.drawable.bg_pill_rec)
            statusChip.text = String.format(Locale.US, "● REC  %02d:%02d", recSeconds / 60, recSeconds % 60)
            statusChip.typeface = Typeface.MONOSPACE
            statusChip.textSize = 13f
        } else {
            statusChip.setBackgroundResource(R.drawable.bg_pill_overlay)
            statusChip.text = "Tocca: sposta il $secLabel · Pizzica: ridimensiona · Tieni premuto: fuoco"
            statusChip.typeface = Typeface.DEFAULT_BOLD
            statusChip.textSize = 10f
        }

        shutter.style = when {
            rec -> ShutterButton.Style.RECORDING
            video -> ShutterButton.Style.VIDEO
            else -> ShutterButton.Style.PHOTO
        }
        gallery.visibility = if (rec) View.INVISIBLE else View.VISIBLE
        btnRecenter.visibility = if (rec) View.GONE else View.VISIBLE
        btnSnap.visibility = if (rec) View.VISIBLE else View.GONE
        // Tasto foto durante il video: a sinistra in verticale; in orizzontale resta in alto (per chi guarda).
        val snapGravity = (if (angle == 270) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        if ((btnSnap.layoutParams as FrameLayout.LayoutParams).gravity != snapGravity) {
            btnSnap.updateLayoutParams<FrameLayout.LayoutParams> { gravity = snapGravity }
        }

        overlay.showGrid = settings.grid
    }

    private fun updateProcessing() {
        if (pendingCount <= 0) {
            processing.visibility = View.GONE
            return
        }
        val n = pendingCount
        processing.text = when {
            recordingActive -> "$n video da tagliare dopo la ripresa"
            n == 1 -> "Taglio 1 video nei due formati…"
            else -> "Taglio $n video nei due formati…"
        }
        processing.visibility = View.VISIBLE
    }

    private fun iconRotation() = when (angle) {
        90 -> -90f
        180 -> 180f
        270 -> 90f
        else -> 0f
    }

    private fun rotateIcons() {
        val r = iconRotation()
        listOf<View>(gallery, btnRecenter, btnSnap).forEach {
            it.animate().rotation(r).setDuration(200).start()
        }
    }

    private fun showToast(msg: String) {
        toastView.text = msg
        toastView.visibility = View.VISIBLE
        toastView.removeCallbacks(hideToast)
        toastView.postDelayed(hideToast, 1800)
    }

    private val hideToast = Runnable { toastView.visibility = View.GONE }

    private fun flashScreen() {
        flashView.animate().cancel()
        flashView.alpha = 0.85f
        flashView.animate().alpha(0f).setDuration(220).start()
    }

    private fun cycleFlash() {
        settings.flash = when (settings.flash) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
        }
        if (!controller.hasFlash && controller.camera != null) {
            showToast("Questa lente non ha il flash")
        }
        if (recordingActive) controller.setTorch(settings.flash == FlashMode.ON) else rebind()
        refreshUi()
    }

    private fun loadGalleryThumb() {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                TakeStore.prune(applicationContext)
                val take = TakeStore.all(applicationContext).firstOrNull() ?: return@withContext null
                val ref = take.vertical ?: take.horizontal ?: take.photos.lastOrNull()?.let { it.vertical ?: it.horizontal }
                    ?: return@withContext null
                runCatching {
                    contentResolver.loadThumbnail(Uri.parse(ref.uri), Size(160, 160), null)
                }.getOrNull()
            }
            if (bmp != null) gallery.setImageBitmap(bmp)
        }
    }

    // ---------- Aggiornamenti ----------

    private fun checkForUpdate() {
        val current = BuildConfig.VERSION_NAME
        appVersion.text = "Controllo aggiornamenti…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { UpdateChecker.check(current) }
            appVersion.text = "Camera Dual $current"
            val dialog = android.app.AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            when (r) {
                is UpdateChecker.Result.UpToDate -> {
                    showToast("Hai già l'ultima versione ($current)")
                    return@launch
                }
                is UpdateChecker.Result.Newer -> dialog
                    .setTitle("Nuova versione ${r.version}")
                    .setMessage("Hai la $current. Scarica l'APK da GitHub e aprilo per aggiornare: le impostazioni e i video in attesa restano.")
                    .setPositiveButton("Scarica") { _, _ -> openUrl(r.apkUrl ?: r.pageUrl) }
                    .setNegativeButton("Più tardi", null)
                is UpdateChecker.Result.Unavailable -> dialog
                    .setTitle("Non riesco a controllare")
                    .setMessage("${r.reason}\n\nVuoi aprire la pagina delle versioni su GitHub?")
                    .setPositiveButton("Apri GitHub") { _, _ -> openUrl(UpdateChecker.RELEASES_PAGE) }
                    .setNegativeButton("Chiudi", null)
            }
            if (!isFinishing) dialog.show()
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { showToast("Nessun browser trovato") }
    }

    // ---------- Menu caratteristiche ripresa ----------

    private data class Option(
        val label: String,
        val sub: String,
        val selected: Boolean,
        val keepOpen: Boolean = false,
        val apply: () -> Unit
    )

    private fun fmtAperture(f: Float) = String.format(Locale.US, "%.1f", f)

    private fun toggleMenu(key: String) {
        if (recordingActive && key != "focus" && key != "aperture" && key != "lens") {
            showToast("Durante la ripresa puoi cambiare solo lente, fuoco e diaframma")
            return
        }
        if (openMenu == key) {
            closeMenu()
            return
        }
        openMenu = key
        val (title, options) = when (key) {
            "vres" -> "Risoluzione video (per ogni formato)" to VideoRes.entries.map { v ->
                Option(v.label, v.sub, v == settings.videoRes) { settings.videoRes = v }
            }
            "pres" -> "Risoluzione foto (lato lungo)" to PhotoRes.entries.map { v ->
                Option(v.label, v.sub, v == settings.photoRes) { settings.photoRes = v }
            }
            "fps" -> "Fotogrammi al secondo" to Fps.entries.map { v ->
                Option(v.label, v.sub, v == settings.fps) {
                    val changed = v != settings.fps
                    settings.fps = v
                    if (changed) rebind()
                }
            }
            "focus" -> "Messa a fuoco" to listOf(
                Option("AUTO", "continuo", controller.focusMode == CameraController.FocusMode.AUTO) {
                    controller.setAutoFocus()
                    overlay.focusMarker = null
                },
                Option("PUNTO", "tieni premuto", controller.focusMode == CameraController.FocusMode.POINT) {
                    if (controller.focusMode != CameraController.FocusMode.POINT) {
                        setFocusPoint(overlay.width / 2f, overlay.height / 2f)
                    }
                },
                Option("MANUALE", "a mano", controller.focusMode == CameraController.FocusMode.MANUAL, keepOpen = true) {
                    if (controller.supportsManualFocus) {
                        controller.setManualFocus(controller.manualFocus)
                        overlay.focusMarker = null
                    } else {
                        showToast(if (!controller.hasFocus) "Questa lente ha il fuoco fisso"
                        else "Il fuoco manuale non è supportato da questa lente")
                    }
                }
            )
            "lens" -> "Lente" to controller.lenses.map { o ->
                val sub = when {
                    o.front -> "interna"
                    o.equivZoom < 0.8f -> "grandang."
                    o.equivZoom < 1.45f -> "principale"
                    else -> "tele"
                }
                Option(o.shortLabel, sub, o == controller.lens) { onLensSelected(o) }
            }
            "aperture" -> "Diaframma · lente ${controller.zoomLabel}" to controller.apertures.map { f ->
                Option("f/" + fmtAperture(f), if (controller.apertureSelectable) "" else "fisso",
                    f == controller.currentAperture) {
                    if (controller.apertureSelectable) controller.setAperture(f)
                }
            }
            else -> "Codec video" to Codec.entries.map { v ->
                Option(v.label, v.sub, v == settings.codec) { settings.codec = v }
            }
        }
        menuTitle.text = title
        menuOptions.removeAllViews()
        options.forEachIndexed { i, o ->
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(if (o.selected) R.drawable.bg_option_selected else R.drawable.bg_option)
                setTextColor(if (o.selected) Color.rgb(26, 18, 0) else Color.rgb(245, 245, 244))
                val text = SpannableString(if (o.sub.isEmpty()) o.label else "${o.label}\n${o.sub}")
                val n = o.label.length
                text.setSpan(TypefaceSpan("monospace"), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(RelativeSizeSpan(1.1f), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (o.sub.isNotEmpty()) {
                    text.setSpan(RelativeSizeSpan(0.8f), n + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(
                        ForegroundColorSpan(if (o.selected) Color.rgb(61, 44, 0) else Color.rgb(168, 162, 158)),
                        n + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                this.text = text
                textSize = 13f
                setOnClickListener {
                    o.apply()
                    if (o.keepOpen) {
                        val k = openMenu
                        openMenu = null
                        if (k != null) toggleMenu(k)
                    } else {
                        closeMenu()
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (i > 0) lp.marginStart = (8 * dp).toInt()
            menuOptions.addView(tv, lp)
        }
        if (options.size < 3) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (3 - options.size).toFloat())
            lp.marginStart = (8 * dp).toInt()
            menuOptions.addView(View(this), lp)
        }

        val note: String? = when (key) {
            "focus" -> when {
                !controller.hasFocus -> "Questa lente ha il fuoco fisso: è sempre nitida da vicino a infinito."
                controller.focusMode == CameraController.FocusMode.POINT ->
                    "Tieni premuto sul mirino per spostare il punto. Il tocco semplice sposta il riquadro."
                else -> null
            }
            "aperture" -> when {
                controller.apertures.isEmpty() -> "Il telefono non indica il diaframma di questa lente."
                !controller.apertureSelectable ->
                    "Diaframma fisso: su questa lente il valore non si può cambiare (succede su quasi tutti i telefoni). Cambia lente per un valore diverso."
                else -> null
            }
            else -> null
        }
        menuNote.text = note ?: ""
        menuNote.visibility = if (note != null) View.VISIBLE else View.GONE
        val showSeek = key == "focus" && controller.focusMode == CameraController.FocusMode.MANUAL &&
            controller.supportsManualFocus
        menuSeekRow.visibility = if (showSeek) View.VISIBLE else View.GONE
        if (showSeek) focusSeek.progress = ((1f - controller.manualFocus) * 1000).toInt()

        menuPanel.visibility = View.VISIBLE
        refreshUi()
    }

    private fun closeMenu() {
        if (openMenu == null) return
        openMenu = null
        menuPanel.visibility = View.GONE
        refreshUi()
    }

    // ---------- Lenti e messa a fuoco ----------

    private fun buildLensRow() {
        chipLens.dimmed = controller.lenses.size <= 1
        refreshUi()
    }

    private fun onLensSelected(o: LensOption) {
        if (o == controller.lens) return
        if (controller.isRecording && !controller.canSwitchWhileRecording(o)) {
            showToast("Questa lente non si può attivare durante la ripresa")
            return
        }
        val needRebind = controller.selectLens(o)
        settings.lensZoom = o.memoKey
        if (needRebind) rebind()
        if (controller.focusMode == CameraController.FocusMode.POINT && !controller.hasFocus) {
            controller.setAutoFocus()
            overlay.focusMarker = null
        }
        buildLensRow()
        if (openMenu != null && openMenu != "lens") {
            val k = openMenu!!
            openMenu = null
            toggleMenu(k)
        }
        refreshUi()
        val ap = controller.currentAperture?.let { " · f/" + fmtAperture(it) } ?: ""
        showToast("${o.label} ${o.name}$ap")
    }

    private fun setFocusPoint(x: Float, y: Float) {
        if (!controller.hasFocus) {
            showToast("Questa lente ha il fuoco fisso")
            return
        }
        controller.focusAt(x, y)
        overlay.focusMarker = PointF(x, y)
        if (openMenu == "focus") {
            openMenu = null
            toggleMenu("focus")
        }
        showToast("Fuoco ed esposizione bloccati qui")
        refreshUi()
    }

    // ---------- Scatto ----------

    private fun onShutter() {
        closeMenu()
        when {
            mode == CameraController.Mode.PHOTO -> takePhoto()
            recordingActive || controller.isRecording -> requestStop()
            else -> startRecording()
        }
    }

    private fun settingsLabel() =
        "${settings.videoRes.label} · ${controller.activeFps} fps · ${settings.codec.label}"

    private fun takePhoto() {
        flashScreen()
        val primary = cap(overlay.primaryRect())
        val secondary = cap(overlay.secondaryRect())
        val a = capAngle(if (recordingActive) recAngle else angle)
        val duringVideo = recordingActive
        val videoTakeId = currentJob?.id
        val maxLong = settings.photoRes.maxLongSide
        val lensLabel = controller.zoomLabel
        val app = applicationContext
        controller.takePhoto { bmp ->
            if (bmp == null) {
                runOnUiThread { showToast("Scatto non riuscito") }
                return@takePhoto
            }
            val result = try {
                PhotoProcessor.saveDual(app, bmp, primary, secondary, a, maxLong)
            } catch (t: Throwable) {
                null
            } finally {
                bmp.recycle()
            }
            if (result?.primary != null) {
                val now = System.currentTimeMillis()
                val pair = PhotoPair(now, MediaRef.of(result.vertical), MediaRef.of(result.horizontal))
                if (duringVideo && videoTakeId != null) {
                    TakeStore.addPhoto(app, videoTakeId, pair)
                } else {
                    TakeStore.upsert(app, Take(
                        id = "p" + MediaSaver.timestamp(), type = "photo", createdAt = now, lens = lensLabel,
                        settings = settings.photoRes.label, status = "done", photos = listOf(pair)
                    ))
                }
            }
            runOnUiThread {
                if (result?.primary != null) {
                    result.thumbnail?.let { gallery.setImageBitmap(it) }
                    showToast(if (duringVideo) "Foto dal video · 9:16 + 16:9" else "2 foto salvate · 9:16 + 16:9")
                } else {
                    showToast("Salvataggio non riuscito")
                }
            }
        }
    }

    // ---------- Video ----------

    private fun startRecording() {
        if (recordingActive || controller.isRecording) return
        val id = MediaSaver.timestamp()
        val file = PendingStore.newMasterFile(this, id)
        recAngle = angle
        overlay.deviceAngle = recAngle
        val pt = CropTrack().also { it.add(0, cap(overlay.primaryRect())) }
        val st = CropTrack().also { it.add(0, cap(overlay.secondaryRect())) }
        primaryTrack = pt
        secondaryTrack = st
        val job = PendingJob(
            id = id, masterPath = file.absolutePath, angle = capAngle(recAngle), codec = settings.codec,
            maxLongSide = settings.videoRes.maxLongSide, fps = controller.activeFps,
            lensLabel = controller.zoomLabel, createdAt = System.currentTimeMillis(),
            primaryTrack = pt, secondaryTrack = st, state = "recording"
        )
        currentJob = job

        // La ripresa ha la priorità: l'elaborazione in sottofondo si ferma.
        RecordingGate.isRecording = true
        ExportScheduler.pause(applicationContext)
        PendingStore.save(this, job)
        TakeStore.upsert(this, Take(
            id = id, type = "video", createdAt = job.createdAt, lens = job.lensLabel,
            settings = settingsLabel(), status = "pending"
        ))

        recStartNs = SystemClock.elapsedRealtimeNanos()
        recSeconds = 0
        val audio = hasPermission(Manifest.permission.RECORD_AUDIO)
        val ok = controller.startRecording(file, audio) { event -> onRecordEvent(event, job) }
        if (!ok) {
            // La fotocamera è ancora occupata (es. sta chiudendo il video precedente): niente voce vuota nell'elenco.
            finishRecording(job, forced = true, failed = true)
            TakeStore.remove(applicationContext, id)
            showToast("Fotocamera occupata, riprova tra un attimo")
            return
        }
        // Se la fotocamera non parte entro 5 s, sblocca tutto.
        handler.postDelayed(startWatchdog, 5000)
    }

    private val startWatchdog = Runnable {
        if (!recordingActive && controller.isRecording) {
            controller.stopRecording()
            finishRecording(currentJob, forced = true)
        }
    }

    private val stopWatchdog = Runnable {
        if (recordingActive || controller.isRecording) {
            controller.clearRecording()
            finishRecording(currentJob, forced = true)
        }
    }

    private fun onRecordEvent(event: VideoRecordEvent, job: PendingJob) {
        if (currentJob?.id != job.id) return // evento di una ripresa già chiusa
        when (event) {
            is VideoRecordEvent.Start -> {
                handler.removeCallbacks(startWatchdog)
                recStartNs = SystemClock.elapsedRealtimeNanos()
                recordingActive = true
                if (settings.flash == FlashMode.ON) controller.setTorch(true)
                updateProcessing()
                refreshUi()
            }
            is VideoRecordEvent.Status -> {
                val s = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                if (s != recSeconds) {
                    recSeconds = s
                    refreshUi()
                }
            }
            is VideoRecordEvent.Finalize -> {
                val failed = event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
                finishRecording(job, forced = false, failed = failed)
            }
        }
    }

    /** Ferma la ripresa; se entro 4 s non arriva la conferma, la chiude comunque. */
    private fun requestStop() {
        controller.stopRecording()
        handler.removeCallbacks(stopWatchdog)
        handler.postDelayed(stopWatchdog, 4000)
    }

    /**
     * Chiude la ripresa (normalmente o perché bloccata) e la mette in coda
     * per il taglio. È sicura da chiamare più volte.
     */
    private fun finishRecording(job0: PendingJob?, forced: Boolean, failed: Boolean = false) {
        val job = job0 ?: currentJob
        handler.removeCallbacks(startWatchdog)
        handler.removeCallbacks(stopWatchdog)
        controller.clearRecording()
        controller.setTorch(false)
        recordingActive = false
        RecordingGate.isRecording = false
        currentJob = null
        overlay.deviceAngle = angle

        if (job != null) {
            val file = File(job.masterPath)
            val usable = !failed && file.exists() && file.length() > 0
            val app = applicationContext
            if (usable) {
                val now = (SystemClock.elapsedRealtimeNanos() - recStartNs) / 1000
                primaryTrack?.add(now, cap(overlay.primaryRect()))
                secondaryTrack?.add(now, cap(overlay.secondaryRect()))
                val ready = job.copy(
                    primaryTrack = (primaryTrack ?: job.primaryTrack).snapshot(),
                    secondaryTrack = (secondaryTrack ?: job.secondaryTrack).snapshot(),
                    state = "ready"
                )
                PendingStore.save(app, ready)
                if (forced) showToast("Ripresa chiusa: il video è al sicuro e verrà tagliato a breve")
                else showToast("Video salvato · lo taglio nei due formati")
            } else {
                PendingStore.remove(app, job)
                TakeStore.update(app, job.id) { it.copy(status = "failed", note = "Registrazione non riuscita") }
                if (!forced) showToast("Registrazione non riuscita")
            }
        }
        primaryTrack = null
        secondaryTrack = null
        ExportScheduler.kick(applicationContext)
        lifecycleScope.launch { refreshPending() }
        updateProcessing()
        refreshUi()
    }

    /**
     * Rettangolo da usare sul file: con la fotocamera interna l'anteprima è a specchio
     * mentre foto e video no, quindi il ritaglio va ribaltato.
     */
    private fun cap(r: NormRect): NormRect =
        if (controller.isFront) NormRect(1f - r.right, r.top, 1f - r.left, r.bottom) else r

    /** Con l'immagine a specchio anche la rotazione per raddrizzarla va al contrario. */
    private fun capAngle(a: Int): Int = if (controller.isFront) (360 - a) % 360 else a

    private fun onFramesMoved() {
        if (recordingActive) {
            val t = (SystemClock.elapsedRealtimeNanos() - recStartNs) / 1000
            primaryTrack?.add(t, cap(overlay.primaryRect()))
            secondaryTrack?.add(t, cap(overlay.secondaryRect()))
        }
    }
}
