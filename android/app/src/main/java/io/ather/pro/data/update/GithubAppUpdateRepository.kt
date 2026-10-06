package io.ather.pro.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import io.ather.pro.BuildConfig
import io.ather.pro.MainActivity
import io.ather.pro.R
import io.ather.pro.domain.update.AppRelease
import io.ather.pro.domain.update.AppUpdateRepository
import io.ather.pro.domain.update.AppUpdateState
import io.ather.pro.domain.update.AppVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

class GithubAppUpdateRepository private constructor(private val context: Context) : AppUpdateRepository {
    private val source = GithubReleaseSource()
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Mutex()
    private val apkFile get() = File(context.cacheDir, "updates/update.apk")
    private fun cachedRelease() = runCatching {
        gson.fromJson(prefs.getString("release", null), AppRelease::class.java)
    }.getOrNull()
    private fun eligible(release: AppRelease?): AppRelease? = release?.takeIf {
        if (it.versionCode != null) it.versionCode > BuildConfig.VERSION_CODE else AppVersions.newer(it.tag, BuildConfig.VERSION_NAME)
    }
    private val mutableState = MutableStateFlow(AppUpdateState(
        release = eligible(cachedRelease()), lastCheckedAt = prefs.getLong("checked_at", 0)
    ))
    override val state = mutableState.asStateFlow()

    override suspend fun check(force: Boolean) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        try {
            val now = System.currentTimeMillis()
            val elapsed = now - prefs.getLong("attempted_at", 0)
            if (!force && elapsed in 0 until OPEN_CHECK_INTERVAL) return@withContext
            prefs.edit().putLong("attempted_at", now).apply()
            mutableState.update { it.copy(checking = true, error = null) }
            val result = source.latest(prefs.getString("etag", null))
            val release = if (result.unchanged) cachedRelease() else result.release
            prefs.edit().putString("release", gson.toJson(release)).putString("etag", result.etag)
                .putLong("checked_at", now).apply()
            val available = eligible(release)
            mutableState.update {
                it.copy(release = available, checking = false, lastCheckedAt = now,
                    readyToInstall = it.readyToInstall && available?.tag == it.release?.tag)
            }
            if (available != null) notifyUpdate(available)
            else (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(checking = false) }
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(checking = false, error = error.message ?: "Couldn’t check for updates.") }
        } finally { lock.unlock() }
    }

    override suspend fun download() = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        val release = state.value.release
        if (release == null) { lock.unlock(); return@withContext }
        val part = File(context.cacheDir, "updates/update.part")
        try {
            mutableState.update { it.copy(downloading = true, downloadProgress = 0f, readyToInstall = false, error = null) }
            apkFile.parentFile?.mkdirs()
            apkFile.delete()
            source.client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed. Try again when connected." }
                val body = response.body ?: error("Empty APK download")
                var received = 0L
                var reportedAt = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= release.apkSize && received <= GithubReleaseSource.MAX_APK_BYTES) { "Unexpected APK size." }
                            output.write(buffer, 0, count)
                            val now = System.currentTimeMillis()
                            if (now - reportedAt >= 200) {
                                mutableState.update { it.copy(downloadProgress = (received.toFloat() / release.apkSize).coerceIn(0f, 1f)) }
                                reportedAt = now
                            }
                        }
                    }
                }
            }
            ApkVerifier.verify(context, part, release)
            check(part.renameTo(apkFile)) { "Couldn’t save update. Please try again." }
            mutableState.update { it.copy(downloading = false, downloadProgress = 1f, readyToInstall = true) }
        } catch (cancelled: CancellationException) {
            part.delete()
            mutableState.update { it.copy(downloading = false) }
            throw cancelled
        } catch (error: Exception) {
            part.delete()
            mutableState.update { it.copy(downloading = false, readyToInstall = false, error = error.message ?: "Download failed.") }
        } finally { lock.unlock() }
    }

    /** Re-verify off the UI thread before granting the installer access to this one file. */
    suspend fun installationUri(): Uri? = withContext(Dispatchers.IO) {
        try {
            val release = state.value.release ?: return@withContext null
            if (!state.value.readyToInstall) return@withContext null
            ApkVerifier.verify(context, apkFile, release)
            FileProvider.getUriForFile(context, "${context.packageName}.updates", apkFile)
        } catch (error: Exception) {
            mutableState.update { it.copy(readyToInstall = false, error = error.message ?: "Please download the update again.") }
            null
        }
    }

    fun reportInstallError() {
        mutableState.update { it.copy(error = "Android couldn’t open the installer. You can get the APK from Release details.") }
    }

    private fun notifyUpdate(release: AppRelease) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        if ((Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ||
            prefs.getString("notified", null) == release.tag) return
        val intent = Intent(context, MainActivity::class.java).putExtra(OPEN_UPDATES, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.widget_refresh).setContentTitle("AtherMate ${release.versionName} is available")
            .setContentText("See what’s new and update your app.").setContentIntent(pending).setAutoCancel(true).build())
        prefs.edit().putString("notified", release.tag).apply()
    }

    companion object {
        const val OPEN_UPDATES = "io.ather.pro.OPEN_UPDATES"
        private const val CHANNEL = "app_updates"
        private const val NOTIFICATION_ID = 7140
        private const val OPEN_CHECK_INTERVAL = 6L * 60 * 60 * 1000
        @Volatile private var instance: GithubAppUpdateRepository? = null
        fun getInstance(context: Context): GithubAppUpdateRepository = instance ?: synchronized(this) {
            instance ?: GithubAppUpdateRepository(context.applicationContext).also { instance = it }
        }
    }
}
