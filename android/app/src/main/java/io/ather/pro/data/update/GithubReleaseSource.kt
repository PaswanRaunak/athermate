package io.ather.pro.data.update

import com.google.gson.JsonParser
import io.ather.pro.domain.update.AppRelease
import io.ather.pro.domain.update.AppVersions
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A dedicated unauthenticated client: scooter tokens never reach GitHub. */
internal class GithubReleaseSource {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    data class Fetch(val release: AppRelease?, val etag: String?, val unchanged: Boolean = false)
    fun latest(etag: String?): Fetch {
        val request = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "AtherMate-Android")
        etag?.let { request.header("If-None-Match", it) }
        client.newCall(request.build()).execute().use { response ->
            if (response.code == 304) return Fetch(null, etag, unchanged = true)
            if (response.code == 404) return Fetch(null, null)
            check(response.isSuccessful) {
                if (response.code == 403 || response.code == 429) "GitHub is busy. Try again later."
                else "Couldn’t check for updates. Try again when connected."
            }
            val json = JsonParser.parseString(response.body?.string() ?: error("Empty release response")).asJsonObject
            val tag = json.get("tag_name")?.asString ?: error("Release has no version")
            if (json.get("draft")?.asBoolean == true || json.get("prerelease")?.asBoolean == true || !AppVersions.isStable(tag)) {
                return Fetch(null, response.header("ETag"))
            }
            val assets = json.getAsJsonArray("assets").map { it.asJsonObject }
                .filter { it.get("state")?.asString == "uploaded" }
            val apks = assets.filter { it.get("name")?.asString?.endsWith(".apk", true) == true }
            val apk = apks.firstOrNull { it.get("name").asString.equals("AtherMate-$tag-release.apk", true) }
                ?: apks.firstOrNull { it.get("name").asString.contains("universal", true) }
                ?: apks.singleOrNull() ?: error("Release APK is not ready. Try again later.")
            val apkUrl = apk.get("browser_download_url").asString
            require(apkUrl.startsWith("https://github.com/$REPO/releases/download/")) { "Invalid release download" }
            val size = apk.get("size").asLong
            require(size in 1..MAX_APK_BYTES) { "Invalid APK size" }
            val pageUrl = json.get("html_url").asString
            require(pageUrl.startsWith("https://github.com/$REPO/releases/tag/"))
            var versionCode: Long? = null
            var digest = apk.get("digest")?.takeUnless { it.isJsonNull }?.asString
                ?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            val manifest = assets.firstOrNull { it.get("name")?.asString == "update.json" }
            if (manifest != null) {
                val manifestUrl = manifest.get("browser_download_url").asString
                require(manifestUrl.startsWith("https://github.com/$REPO/releases/download/"))
                require(manifest.get("size").asLong in 1..16_384)
                client.newCall(Request.Builder().url(manifestUrl).build()).execute().use { metadataResponse ->
                    check(metadataResponse.isSuccessful) { "Release metadata is not ready. Try again later." }
                    val metadata = JsonParser.parseString(metadataResponse.body?.string()).asJsonObject
                    require(metadata.get("applicationId").asString == "io.ather.pro")
                    require(metadata.get("versionName").asString == tag.removePrefix("v"))
                    require(metadata.get("apk").asString == apk.get("name").asString)
                    versionCode = metadata.get("versionCode").asLong.also { require(it > 0) }
                    val hash = metadata.get("sha256").asString.lowercase()
                    require(hash.matches(Regex("[a-f0-9]{64}")))
                    require(digest == null || digest.equals(hash, true))
                    digest = hash
                }
            }
            return Fetch(AppRelease(tag, tag.removePrefix("v"), versionCode,
                json.get("body")?.takeUnless { it.isJsonNull }?.asString?.take(8_000).orEmpty(),
                pageUrl, apkUrl, size, digest), response.header("ETag"))
        }
    }

    companion object {
        const val REPO = "PaswanRaunak/athr-plus"
        const val MAX_APK_BYTES = 150L * 1024 * 1024
    }
}
