package com.dualframe.cam

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Controlla su GitHub se esiste una versione più recente dell'app. */
object UpdateChecker {
    const val REPO = "tommi-tr/dualframe"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    sealed class Result {
        data class Newer(val version: String, val pageUrl: String, val apkUrl: String?) : Result()
        data class UpToDate(val version: String) : Result()
        /** GitHub non raggiungibile, repository privato o nessuna versione pubblicata. */
        data class Unavailable(val reason: String) : Result()
    }

    /** Da chiamare fuori dal thread principale. */
    fun check(current: String): Result {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CameraDual")
        }
        return try {
            when (val code = conn.responseCode) {
                200 -> {
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = o.optString("tag_name").removePrefix("v").removePrefix("android-v")
                    val page = o.optString("html_url", RELEASES_PAGE)
                    val assets = o.optJSONArray("assets")
                    var apk: String? = null
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
                    }
                    if (tag.isNotEmpty() && isNewer(tag, current)) Result.Newer(tag, page, apk)
                    else Result.UpToDate(current)
                }
                404 -> Result.Unavailable("Il repository è privato oppure non ci sono ancora versioni pubblicate.")
                else -> Result.Unavailable("GitHub ha risposto con il codice $code.")
            }
        } catch (e: Exception) {
            Result.Unavailable("Nessuna connessione a GitHub.")
        } finally {
            conn.disconnect()
        }
    }

    /** "1.10" è più recente di "1.9". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
