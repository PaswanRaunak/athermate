package io.ather.pro.domain.update

import kotlinx.coroutines.flow.StateFlow

/** Release metadata contains public app information only. */
data class AppRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Long?,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?
)

data class AppUpdateState(
    val release: AppRelease? = null,
    val checking: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val readyToInstall: Boolean = false,
    val error: String? = null
)

interface AppUpdateRepository {
    val state: StateFlow<AppUpdateState>
    suspend fun check(force: Boolean = false)
    suspend fun download()
}

/** Only stable numbered tags are eligible; local builds compare their numbered portion. */
object AppVersions {
    private fun parts(value: String, installed: Boolean): List<Long>? {
        val pattern = if (installed) Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-local)?$")
            else Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")
        return pattern.matchEntire(value)?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }
    }
    fun newer(tag: String, installed: String): Boolean {
        val target = parts(tag, false) ?: return false
        val current = parts(installed, true) ?: return false
        return target.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
    }
    fun isStable(tag: String) = parts(tag, false) != null
}
