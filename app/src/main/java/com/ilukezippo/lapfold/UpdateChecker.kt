package com.ilukezippo.lapfold

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val pageUrl: String, val apkUrl: String?, val notes: String)

/** Checks the latest GitHub release of ilukezippo/LapFold. */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/ilukezippo/LapFold/releases/latest"
    const val REPO_URL = "https://github.com/ilukezippo/LapFold"

    private fun parts(v: String): List<Int>? {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val p = clean.split('.').map { it.toIntOrNull() ?: return null }
        return if (p.isEmpty()) null else p
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote) ?: return false
        val l = parts(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** result(null, error) on failure; result(release, null) otherwise. Callback runs on the main thread. */
    fun fetchLatest(result: (Release?, String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val code = c.responseCode
                if (code == 404) { main.post { result(null, "No releases published yet") }; return@Thread }
                if (code != 200) { main.post { result(null, "GitHub returned $code") }; return@Thread }
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val assets = j.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                }
                val rel = Release(j.optString("tag_name"), j.optString("html_url"), apk, j.optString("body"))
                main.post { result(rel, null) }
            } catch (e: Exception) {
                main.post { result(null, "Couldn't reach GitHub") }
            }
        }.start()
    }
}
