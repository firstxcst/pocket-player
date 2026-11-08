package com.silas.pocketplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从本项目的 GitHub Release 检测新版本。
 * 只用 HttpURLConnection + org.json，不引 OkHttp/Retrofit/Gson，保持 APK 体积不变。
 */
object UpdateChecker {

    private const val OWNER = "firstxcst"
    private const val REPO = "pocket-player"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    const val RELEASES_URL = "https://github.com/$OWNER/$REPO/releases"

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String?
    )

    /** @return 最新 Release；网络异常 / 尚未发版 / 404 时返回 null（静默失败） */
    suspend fun latest(flavor: String = "lite"): Release? = withContext(Dispatchers.IO) {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PocketPlayer/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return@withContext null

            var apkUrl: String? = null
            var bestScore = -1
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val url = asset.optString("browser_download_url")
                    if (!url.endsWith(".apk", ignoreCase = true)) continue
                    val name = url.substringAfterLast('/')
                    // lite 用户下 PocketPlayer-release.apk，full 用户下 PocketPlayer-full.apk
                    val score = when {
                        flavor == "full" && name.equals("PocketPlayer-full.apk", true) -> 3
                        flavor != "full" && name.equals("PocketPlayer-release.apk", true) -> 3
                        name.contains("release", true) && !name.contains("full", true) -> 2
                        !name.contains("debug", true) -> 1
                        else -> 0
                    }
                    if (score > bestScore) {
                        bestScore = score
                        apkUrl = url
                    }
                }
            }
            Release(
                tag = tag,
                name = json.optString("name").ifBlank { tag },
                notes = json.optString("body"),
                pageUrl = json.optString("html_url").ifBlank { RELEASES_URL },
                apkUrl = apkUrl
            )
        } catch (t: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 支持 v1.2.3 / 1.2.3-beta 这类 tag，逐段数字比较 */
    fun isNewer(remoteTag: String, localVersion: String): Boolean {
        fun parts(raw: String): List<Long> = raw.trim()
            .removePrefix("v").removePrefix("V")
            .split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }
            .map { it.toLongOrNull() ?: 0L }
        val remote = parts(remoteTag)
        val local = parts(localVersion)
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0L }
            val l = local.getOrElse(i) { 0L }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
