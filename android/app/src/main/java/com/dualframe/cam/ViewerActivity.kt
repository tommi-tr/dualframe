package com.dualframe.cam

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * Visore interno: mostra INSIEME la versione orizzontale (16:9, in alto) e quella
 * verticale (9:16, sotto). Per i video una sola barra comanda entrambi, sincronizzati.
 * Ogni versione ha i suoi tasti Galleria, Condividi, Cestina.
 */
class ViewerActivity : ComponentActivity() {

    companion object {
        private const val V_URI = "v_uri"
        private const val V_NAME = "v_name"
        private const val H_URI = "h_uri"
        private const val H_NAME = "h_name"
        private const val VIDEO = "video"
        private const val TITLE = "title"

        fun intent(ctx: Context, vertical: MediaRef?, horizontal: MediaRef?, video: Boolean, title: String) =
            Intent(ctx, ViewerActivity::class.java)
                .putExtra(V_URI, vertical?.uri).putExtra(V_NAME, vertical?.name)
                .putExtra(H_URI, horizontal?.uri).putExtra(H_NAME, horizontal?.name)
                .putExtra(VIDEO, video).putExtra(TITLE, title)
    }

    /** Una delle due versioni mostrate. */
    private inner class Item(val uri: Uri?, val name: String, val vertical: Boolean) {
        val frame = FrameLayout(this@ViewerActivity)
        var video: VideoView? = null
        var duration = 0
        var ready = false
        var gone = false
    }

    private var isVideo = false
    private lateinit var hItem: Item
    private lateinit var vItem: Item
    private var seek: SeekBar? = null
    private var time: TextView? = null
    private var playBtn: TextView? = null
    private var playing = false
    private var pendingTrash: Item? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()
    private val muted = Color.rgb(168, 162, 158)
    private val accent = Color.rgb(255, 176, 32)

    private val trashLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            val it = pendingTrash
            pendingTrash = null
            if (r.resultCode == RESULT_OK && it != null) trashed(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        isVideo = intent.getBooleanExtra(VIDEO, false)
        hItem = Item(intent.getStringExtra(H_URI)?.let(Uri::parse), intent.getStringExtra(H_NAME) ?: "", vertical = false)
        vItem = Item(intent.getStringExtra(V_URI)?.let(Uri::parse), intent.getStringExtra(V_NAME) ?: "", vertical = true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Intestazione
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), 0, px(16), 0)
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Indietro"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(48), px(48)))
        top.addView(TextView(this).apply {
            text = intent.getStringExtra(TITLE) ?: ""
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        // 16:9 a tutta larghezza, con i suoi tasti sotto
        val hPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(12), 0)
        }
        hPanel.addView(hItem.frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).also {
            hItem.frame.post {
                hItem.frame.layoutParams = (hItem.frame.layoutParams as LinearLayout.LayoutParams).apply {
                    height = hItem.frame.width * 9 / 16
                }
            }
        })
        hPanel.addView(caption("16:9 · ${hItem.name}"))
        hPanel.addView(actionsRow(hItem, horizontalLayout = true))
        root.addView(hPanel)

        // 9:16 nello spazio che resta, con i tasti in colonna accanto
        val vPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(px(12), px(14), px(12), px(4))
        }
        vPanel.addView(vItem.frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), 0, 0, 0)
        }
        side.addView(caption("9:16"))
        side.addView(caption(vItem.name).apply { textSize = 10f; typeface = Typeface.MONOSPACE })
        side.addView(View(this), LinearLayout.LayoutParams(1, px(10)))
        side.addView(actionsRow(vItem, horizontalLayout = false))
        vPanel.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(vPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // la larghezza del 9:16 segue l'altezza disponibile
        vPanel.post {
            val h = vItem.frame.height
            vItem.frame.layoutParams = (vItem.frame.layoutParams as LinearLayout.LayoutParams).apply {
                width = minOf(h * 9 / 16, vPanel.width * 6 / 10)
            }
        }

        // Barra comune per i due video
        if (isVideo) root.addView(playerBar())

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom + px(8))
            insets
        }

        load(hItem)
        load(vItem)
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(muted)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        setPadding(0, px(6), 0, px(2))
    }

    private fun actionsRow(item: Item, horizontalLayout: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = if (horizontalLayout) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val entries = listOf(
            Triple("Galleria", false) { openInGallery(item) },
            Triple("Condividi", false) { share(item) },
            Triple("Cestina", true) { trash(item) }
        )
        entries.forEachIndexed { i, (label, danger, action) ->
            val b = TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (danger) Color.rgb(255, 120, 110) else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(Color.argb(30, 255, 255, 255))
                }
                setOnClickListener { if (!item.gone && item.uri != null) action() }
            }
            val lp = if (horizontalLayout) LinearLayout.LayoutParams(0, px(40), 1f).apply { if (i > 0) marginStart = px(8) }
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)).apply { if (i > 0) topMargin = px(8) }
            row.addView(b, lp)
        }
        return row
    }

    private fun placeholder(item: Item, text: String) {
        item.frame.removeAllViews()
        item.frame.background = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(Color.rgb(28, 28, 30)) }
        item.frame.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun load(item: Item) {
        val uri = item.uri ?: run { placeholder(item, "Non disponibile"); item.gone = true; return }
        if (isVideo) {
            val v = VideoView(this)
            item.video = v
            item.frame.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            v.setVideoURI(uri)
            v.setOnPreparedListener { mp ->
                // L'audio è lo stesso nei due file: lo sente solo il 16:9.
                if (item.vertical && !hItem.gone) mp.setVolume(0f, 0f)
                item.duration = v.duration
                item.ready = true
                v.seekTo(1) // mostra il primo fotogramma
                onVideosReady()
            }
            v.setOnCompletionListener { if (activeVideos().none { it.video?.isPlaying == true }) setPlaying(false) }
            v.setOnErrorListener { _, _, _ ->
                placeholder(item, "Non riesco a riprodurlo")
                item.gone = true
                item.video = null
                onVideosReady()
                true
            }
            item.frame.setOnClickListener { setPlaying(!playing) }
        } else {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            item.frame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        val m = resources.displayMetrics
                        contentResolver.loadThumbnail(uri, android.util.Size(m.widthPixels, m.heightPixels), null)
                    }.getOrNull()
                }
                if (bmp != null) iv.setImageBitmap(bmp) else { placeholder(item, "Non trovata"); item.gone = true }
            }
        }
    }

    // ---------- Riproduzione sincronizzata ----------

    private fun activeVideos() = listOf(hItem, vItem).filter { !it.gone && it.video != null }

    private fun onVideosReady() {
        val vids = activeVideos()
        if (vids.isEmpty() || vids.any { !it.ready }) return
        seek?.max = vids.maxOf { it.duration }
        updateTime()
        setPlaying(true)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        val vids = activeVideos()
        if (p) {
            // se erano finiti ripartono dall'inizio, insieme
            if (vids.all { it.video!!.currentPosition >= it.duration - 100 }) vids.forEach { it.video!!.seekTo(0) }
            vids.forEach { it.video!!.start() }
        } else {
            vids.forEach { it.video!!.pause() }
        }
        playBtn?.text = if (p) "❚❚" else "▶"
    }

    private fun seekAll(ms: Int) {
        activeVideos().forEach { it.video!!.seekTo(minOf(ms, it.duration)) }
    }

    private val tick = object : Runnable {
        override fun run() {
            val vids = activeVideos()
            val lead = vids.firstOrNull()?.video
            if (lead != null) {
                val pos = lead.currentPosition
                seek?.progress = pos
                // tiene il secondo video agganciato al primo
                vids.drop(1).forEach { f ->
                    val v = f.video!!
                    if (playing && abs(v.currentPosition - pos) > 120) v.seekTo(pos)
                }
                if (playing && vids.none { it.video!!.isPlaying }) setPlaying(false)
            }
            updateTime()
            handler.postDelayed(this, 150)
        }
    }

    private fun fmt(ms: Int): String {
        val s = maxOf(0, ms) / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun updateTime() {
        time?.text = "${fmt(seek?.progress ?: 0)} / ${fmt(seek?.max ?: 0)}"
    }

    private fun playerBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(4), px(16), 0)
        }
        playBtn = TextView(this).apply {
            text = "▶"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Play / pausa"
            setOnClickListener { setPlaying(!playing) }
        }
        bar.addView(playBtn, LinearLayout.LayoutParams(px(48), px(48)))
        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) seekAll(p)
                    updateTime()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        bar.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        time = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(muted)
        }
        bar.addView(time)
        return bar
    }

    override fun onPause() {
        if (playing) setPlaying(false)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- Azioni per singola versione ----------

    private fun mime() = if (isVideo) "video/*" else "image/*"

    private fun openInGallery(item: Item) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, mime())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { Toast.makeText(this, "Nessuna galleria trovata", Toast.LENGTH_SHORT).show() }
    }

    private fun share(item: Item) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Condividi"))
    }

    /** Nel cestino della galleria (Android 11+); su Android 10 elimina dopo conferma. */
    private fun trash(item: Item) {
        val uri = item.uri ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            // Richiesta di sistema: sposta davvero nel cestino condiviso, quello che vedono tutte le gallerie.
            runCatching {
                pendingTrash = item
                val pi = MediaStore.createTrashRequest(contentResolver, listOf(uri), true)
                trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }.onFailure {
                pendingTrash = null
                Toast.makeText(this, "Non riesco a cestinarlo", Toast.LENGTH_SHORT).show()
            }
        } else {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Eliminare definitivamente il file ${if (item.vertical) "9:16" else "16:9"}?")
                .setPositiveButton("Elimina") { _, _ ->
                    val ok = runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
                    if (ok) trashed(item) else Toast.makeText(this, "Non riesco a eliminarlo", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    private fun trashed(item: Item) {
        item.video?.stopPlayback()
        item.video = null
        item.gone = true
        placeholder(item, if (Build.VERSION.SDK_INT >= 30) "Nel cestino" else "Eliminato")
        Toast.makeText(this, "${if (item.vertical) "9:16" else "16:9"} spostato nel cestino", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { TakeStore.prune(applicationContext) }
            if (hItem.gone && vItem.gone) finish()
        }
    }
}
