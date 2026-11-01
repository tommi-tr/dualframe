package com.dualframe.cam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ------------------------------------------------------------------
// Video in attesa di essere tagliati nei due formati
// ------------------------------------------------------------------

/**
 * Un video registrato (master 4:3) che va ancora elaborato.
 * Il master resta nella memoria dell'app finché i due file non sono nella galleria:
 * se l'app si chiude o si blocca, l'elaborazione riparte al prossimo avvio.
 */
data class PendingJob(
    val id: String,
    val masterPath: String,
    val angle: Int,
    val codec: Codec,
    val maxLongSide: Int?,
    val fps: Int,
    val lensLabel: String,
    val createdAt: Long,
    val primaryTrack: CropTrack,
    val secondaryTrack: CropTrack,
    /** recording = ancora in ripresa (o app chiusa durante la ripresa), ready = da elaborare */
    val state: String,
    val attempts: Int = 0,
    val recovered: Boolean = false,
    /** Formati già salvati nella galleria (tag → file), per non rifarli dopo un errore. */
    val done: Map<String, MediaRef> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("master", masterPath); put("angle", angle); put("codec", codec.name)
        put("maxLong", maxLongSide ?: -1); put("fps", fps); put("lens", lensLabel)
        put("createdAt", createdAt); put("primary", primaryTrack.toJson())
        put("secondary", secondaryTrack.toJson()); put("state", state)
        put("attempts", attempts); put("recovered", recovered)
        put("done", JSONObject().apply { done.forEach { (k, v) -> put(k, v.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = PendingJob(
            id = o.getString("id"),
            masterPath = o.getString("master"),
            angle = o.optInt("angle", 0),
            codec = runCatching { Codec.valueOf(o.optString("codec", "HEVC")) }.getOrDefault(Codec.HEVC),
            maxLongSide = o.optInt("maxLong", 1920).takeIf { it > 0 },
            fps = o.optInt("fps", 30),
            lensLabel = o.optString("lens", ""),
            createdAt = o.optLong("createdAt", 0L),
            primaryTrack = CropTrack.fromJson(o.optJSONArray("primary")) ?: CropTrack.fixed(FrameMath.tall()),
            secondaryTrack = CropTrack.fromJson(o.optJSONArray("secondary")) ?: CropTrack.fixed(FrameMath.wide()),
            state = o.optString("state", "ready"),
            attempts = o.optInt("attempts", 0),
            recovered = o.optBoolean("recovered", false),
            done = o.optJSONObject("done")?.let { d ->
                d.keys().asSequence().mapNotNull { k -> MediaRef.fromJson(d.optJSONObject(k))?.let { k to it } }.toMap()
            } ?: emptyMap()
        )
    }
}

object PendingStore {
    private val lock = Any()

    fun dir(context: Context): File = File(context.filesDir, "pending").apply { mkdirs() }

    fun newMasterFile(context: Context, id: String) = File(dir(context), "master_$id.mp4")

    fun save(context: Context, job: PendingJob) = synchronized(lock) {
        val f = File(dir(context), "job_${job.id}.json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(job.toJson().toString())
        tmp.renameTo(f)
    }

    fun list(context: Context): List<PendingJob> = synchronized(lock) {
        dir(context).listFiles { f -> f.name.startsWith("job_") && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { PendingJob.fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?.sortedBy { it.createdAt }
            ?: emptyList()
    }

    fun remove(context: Context, job: PendingJob) = synchronized(lock) {
        File(dir(context), "job_${job.id}.json").delete()
        File(job.masterPath).delete()
    }

    /**
     * Recupero: master rimasti dalla versione precedente (nella cache) o master
     * senza scheda. Diventano lavori da elaborare con le impostazioni predefinite.
     */
    fun recoverOrphans(context: Context): Int = synchronized(lock) {
        var n = 0
        val known = list(context).map { File(it.masterPath).name }.toSet()
        val candidates = (context.cacheDir.listFiles() ?: emptyArray()).filter {
            it.name.startsWith("master_") && it.name.endsWith(".mp4") && it.length() > 0
        } + (dir(context).listFiles() ?: emptyArray()).filter {
            it.name.startsWith("master_") && it.name.endsWith(".mp4") && it.name !in known && it.length() > 0
        }
        for (f in candidates) {
            val id = "rec" + f.lastModified()
            val dest = if (f.parentFile == dir(context)) f else File(dir(context), "master_$id.mp4")
            if (dest != f && !f.renameTo(dest)) {
                runCatching { f.copyTo(dest, overwrite = true); f.delete() }
            }
            if (!dest.exists()) continue
            val job = PendingJob(
                id = id, masterPath = dest.absolutePath, angle = 0, codec = Codec.HEVC,
                maxLongSide = 1920, fps = 30, lensLabel = "", createdAt = f.lastModified(),
                primaryTrack = CropTrack.fixed(FrameMath.tall()),
                secondaryTrack = CropTrack.fixed(FrameMath.wide()),
                state = "ready", recovered = true
            )
            save(context, job)
            TakeStore.upsert(context, Take(
                id = id, type = "video", createdAt = job.createdAt, lens = "",
                settings = "recuperato", status = "pending",
                note = "Video recuperato da una sessione precedente"
            ))
            n++
        }
        // Lavori rimasti "in ripresa" perché l'app si è chiusa: si prova comunque a elaborarli.
        for (j in list(context)) {
            if (j.state == "recording" && !RecordingGate.isRecording) save(context, j.copy(state = "ready"))
        }
        n
    }
}

/** Stato condiviso: mentre si registra l'elaborazione in sottofondo si ferma. */
object RecordingGate {
    @Volatile
    var isRecording = false
}

// ------------------------------------------------------------------
// Diario delle riprese (schermata "Ultimi scatti")
// ------------------------------------------------------------------

data class MediaRef(val uri: String, val name: String, val width: Int, val height: Int) {
    fun toJson() = JSONObject().apply { put("uri", uri); put("name", name); put("w", width); put("h", height) }

    companion object {
        fun fromJson(o: JSONObject?) = o?.let {
            MediaRef(it.getString("uri"), it.optString("name"), it.optInt("w"), it.optInt("h"))
        }

        fun of(s: SavedMedia?) = s?.let { MediaRef(it.uri.toString(), it.name, it.width, it.height) }
    }
}

data class PhotoPair(val time: Long, val vertical: MediaRef?, val horizontal: MediaRef?) {
    fun toJson() = JSONObject().apply {
        put("time", time); vertical?.let { put("v", it.toJson()) }; horizontal?.let { put("h", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject) = PhotoPair(
            o.optLong("time"), MediaRef.fromJson(o.optJSONObject("v")), MediaRef.fromJson(o.optJSONObject("h"))
        )
    }
}

data class Take(
    val id: String,
    /** video | photo */
    val type: String,
    val createdAt: Long,
    val lens: String,
    val settings: String,
    /** pending | done | failed */
    val status: String,
    val durationMs: Long = 0,
    val vertical: MediaRef? = null,
    val horizontal: MediaRef? = null,
    val photos: List<PhotoPair> = emptyList(),
    val note: String? = null
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("type", type); put("createdAt", createdAt); put("lens", lens)
        put("settings", settings); put("status", status); put("duration", durationMs)
        vertical?.let { put("v", it.toJson()) }; horizontal?.let { put("h", it.toJson()) }
        put("photos", JSONArray().apply { photos.forEach { put(it.toJson()) } })
        note?.let { put("note", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): Take {
            val arr = o.optJSONArray("photos") ?: JSONArray()
            return Take(
                id = o.getString("id"), type = o.optString("type", "video"),
                createdAt = o.optLong("createdAt"), lens = o.optString("lens"),
                settings = o.optString("settings"), status = o.optString("status", "done"),
                durationMs = o.optLong("duration"),
                vertical = MediaRef.fromJson(o.optJSONObject("v")),
                horizontal = MediaRef.fromJson(o.optJSONObject("h")),
                photos = (0 until arr.length()).map { PhotoPair.fromJson(arr.getJSONObject(it)) },
                note = o.optString("note").takeIf { it.isNotEmpty() }
            )
        }
    }
}

object TakeStore {
    private const val MAX = 200
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, "takes.json")

    fun all(context: Context): List<Take> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyList()
        runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Take.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.createdAt }
    }

    private fun write(context: Context, list: List<Take>) {
        val arr = JSONArray()
        list.sortedByDescending { it.createdAt }.take(MAX).forEach { arr.put(it.toJson()) }
        val f = file(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }

    fun get(context: Context, id: String): Take? = all(context).firstOrNull { it.id == id }

    fun upsert(context: Context, take: Take) = synchronized(lock) {
        val list = all(context).filter { it.id != take.id } + take
        write(context, list)
    }

    fun update(context: Context, id: String, change: (Take) -> Take) = synchronized(lock) {
        val list = all(context)
        val t = list.firstOrNull { it.id == id } ?: return@synchronized
        write(context, list.map { if (it.id == id) change(t) else it })
    }

    /**
     * Toglie dall'elenco i file cancellati (o messi nel cestino) dalla galleria;
     * una ripresa senza più nessun file sparisce del tutto.
     */
    fun prune(context: Context) {
        val res = context.contentResolver
        fun alive(r: MediaRef?): MediaRef? = r?.takeIf { exists(context, it) }
        synchronized(lock) {
            val list = all(context)
            var changed = false
            val out = list.mapNotNull { t ->
                if (t.status == "pending") return@mapNotNull t
                val photos = t.photos.mapNotNull { p ->
                    val v = alive(p.vertical)
                    val h = alive(p.horizontal)
                    if (v == null && h == null) null else p.copy(vertical = v, horizontal = h)
                }
                val nt = t.copy(vertical = alive(t.vertical), horizontal = alive(t.horizontal), photos = photos)
                if (nt != t) changed = true
                val hadMedia = t.vertical != null || t.horizontal != null || t.photos.isNotEmpty()
                if (hadMedia && nt.vertical == null && nt.horizontal == null && nt.photos.isEmpty()) {
                    changed = true
                    null
                } else nt
            }
            if (changed) write(context, out)
        }
    }

    /**
     * Il file c'è ancora ed è visibile? Un file "cancellato" da una galleria su Android 11+
     * finisce nel cestino (IS_TRASHED = 1): per noi è come sparito.
     */
    fun exists(context: Context, ref: MediaRef): Boolean = runCatching {
        val uri = android.net.Uri.parse(ref.uri)
        val res = context.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val args = android.os.Bundle().apply {
                putInt(android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED, android.provider.MediaStore.MATCH_INCLUDE)
            }
            res.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID,
                android.provider.MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
                c.moveToFirst() && c.getInt(1) == 0
            } ?: false
        } else {
            res.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }
    }.getOrDefault(true) // nel dubbio non si toglie nulla

    fun remove(context: Context, id: String) = synchronized(lock) {
        write(context, all(context).filter { it.id != id })
    }

    fun addPhoto(context: Context, takeId: String, pair: PhotoPair) =
        update(context, takeId) { it.copy(photos = it.photos + pair) }
}
