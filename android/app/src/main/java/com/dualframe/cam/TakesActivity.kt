package com.dualframe.cam

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Ultimi scatti": ogni ripresa con i suoi due formati e le foto affiancate,
 * giusto per capire cosa si è scattato e quando.
 */
class TakesActivity : ComponentActivity() {

    private lateinit var list: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }
    private val handler = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<String, Bitmap>(80)
    private var lastSignature = ""

    private val accent = Color.rgb(255, 176, 32)
    private val muted = Color.rgb(168, 162, 158)
    private val fg = Color.rgb(245, 245, 244)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 11, 12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(16), px(8))
        }
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(fg)
            gravity = Gravity.CENTER
            contentDescription = "Indietro"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(48), px(48)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@TakesActivity).apply {
                text = getString(R.string.takes_title)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(fg)
            })
            addView(TextView(this@TakesActivity).apply {
                text = "Tocca una miniatura per aprirla nella galleria"
                textSize = 12f
                setTextColor(muted)
            })
        })
        root.addView(header)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), 0, px(16), px(24))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    private val refresh = object : Runnable {
        override fun run() {
            val self = this
            lifecycleScope.launch {
                val takes = withContext(Dispatchers.IO) { TakeStore.all(applicationContext) }
                val sig = takes.joinToString { "${it.id}:${it.status}:${it.photos.size}:${it.note}" }
                if (sig != lastSignature) {
                    lastSignature = sig
                    render(takes)
                }
                if (takes.any { it.status == "pending" }) handler.postDelayed(self, 3000)
            }
        }
    }

    private fun px(v: Int) = (v * dp).toInt()

    private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.ITALIAN)

    private fun render(takes: List<Take>) {
        list.removeAllViews()
        if (takes.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Ancora nessuno scatto."
                setTextColor(muted)
                textSize = 14f
                setPadding(0, px(32), 0, 0)
            })
            return
        }
        takes.forEachIndexed { i, t ->
            if (i > 0) {
                list.addView(View(this).apply { setBackgroundColor(Color.argb(50, 255, 255, 255)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        topMargin = px(14); bottomMargin = px(14)
                    })
            } else {
                list.addView(View(this), LinearLayout.LayoutParams(1, px(8)))
            }
            list.addView(takeView(t))
        }
    }

    private fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun takeView(t: Take): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val isVideo = t.type == "video"

        // Titolo
        val title = (if (isVideo) "Video" else "Foto") + " · " + dateFmt.format(Date(t.createdAt))
        box.addView(TextView(this).apply {
            text = title.replaceFirstChar { it.uppercase() }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
        })

        // Metadati
        val meta = listOfNotNull(
            if (isVideo && t.durationMs > 0) fmtDuration(t.durationMs) else null,
            t.settings.takeIf { it.isNotEmpty() },
            t.lens.takeIf { it.isNotEmpty() }?.let { "lente $it" },
            if (t.photos.isNotEmpty() && isVideo) "${t.photos.size} foto" else null
        ).joinToString(" · ")
        if (meta.isNotEmpty()) box.addView(TextView(this).apply {
            text = meta
            textSize = 12f
            setTextColor(muted)
        })
        val names = listOfNotNull(t.vertical?.name, t.horizontal?.name) +
            if (!isVideo) t.photos.flatMap { listOfNotNull(it.vertical?.name, it.horizontal?.name) } else emptyList()
        if (names.isNotEmpty()) box.addView(TextView(this).apply {
            text = names.joinToString("\n")
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(muted)
            setPadding(0, px(2), 0, 0)
        })

        // Stato
        val status = when (t.status) {
            "pending" -> (t.note ?: "In elaborazione: comparirà nella galleria tra poco") to accent
            "failed" -> (t.note ?: "Non riuscito") to Color.rgb(255, 120, 110)
            else -> t.note?.let { it to muted }
        }
        status?.let { (msg, color) ->
            box.addView(TextView(this).apply {
                text = msg
                textSize = 12f
                setTextColor(color)
                setPadding(0, px(4), 0, 0)
            })
        }

        // Miniature principali
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(8), 0, 0)
        }
        val big = 110
        if (isVideo) {
            row.addView(thumb(t.vertical, "9:16", true, big, t.status == "pending"))
            row.addView(View(this), LinearLayout.LayoutParams(px(8), 1))
            row.addView(thumb(t.horizontal, "16:9", true, big, t.status == "pending"))
        } else {
            val p = t.photos.firstOrNull()
            row.addView(thumb(p?.vertical, "9:16", false, big, false))
            row.addView(View(this), LinearLayout.LayoutParams(px(8), 1))
            row.addView(thumb(p?.horizontal, "16:9", false, big, false))
        }
        box.addView(row)

        // Foto scattate durante il video, affiancate a coppie
        if (isVideo && t.photos.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "Foto durante il video"
                textSize = 12f
                setTextColor(muted)
                setPadding(0, px(10), 0, px(4))
            })
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val pairs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            t.photos.forEachIndexed { i, p ->
                if (i > 0) pairs.addView(View(this), LinearLayout.LayoutParams(px(14), 1))
                pairs.addView(thumb(p.vertical, "9:16", false, 72, false))
                pairs.addView(View(this), LinearLayout.LayoutParams(px(4), 1))
                pairs.addView(thumb(p.horizontal, "16:9", false, 72, false))
            }
            hs.addView(pairs)
            box.addView(hs)
        }
        return box
    }

    /** Miniatura con proporzioni reali (9:16 o 16:9) e altezza fissa. */
    private fun thumb(ref: MediaRef?, label: String, video: Boolean, heightDp: Int, pending: Boolean): View {
        val vertical = label == "9:16"
        val h = px(heightDp)
        val w = if (vertical) h * 9 / 16 else h * 16 / 9
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(Color.rgb(32, 32, 34))
            }
            clipToOutline = true
        }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(iv, FrameLayout.LayoutParams(w, h))

        if (ref == null) {
            frame.addView(TextView(this).apply {
                text = if (pending) "in\nelaborazione" else "—"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(muted)
            }, FrameLayout.LayoutParams(w, h))
        } else {
            val cached = thumbs.get(ref.uri)
            if (cached != null) iv.setImageBitmap(cached) else lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.loadThumbnail(Uri.parse(ref.uri), Size(w, h), null)
                    }.getOrNull()
                }
                if (bmp != null) {
                    thumbs.put(ref.uri, bmp)
                    iv.setImageBitmap(bmp)
                } else {
                    frame.addView(TextView(this@TakesActivity).apply {
                        text = "eliminato"
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(muted)
                    }, FrameLayout.LayoutParams(w, h))
                }
            }
            frame.setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(ref.uri), if (video) "video/*" else "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }
            frame.contentDescription = "${if (video) "Video" else "Foto"} $label ${ref.name}"
        }

        // Etichetta del formato e simbolo play
        frame.addView(TextView(this).apply {
            text = if (video) "▶ $label" else label
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(px(4), px(1), px(4), px(1))
            background = GradientDrawable().apply {
                cornerRadius = 4 * dp
                setColor(Color.argb(150, 0, 0, 0))
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = px(4); bottomMargin = px(4)
        })
        frame.layoutParams = LinearLayout.LayoutParams(w, h)
        return frame
    }
}
