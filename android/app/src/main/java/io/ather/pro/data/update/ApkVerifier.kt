package io.ather.pro.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.ather.pro.BuildConfig
import io.ather.pro.domain.update.AppRelease
import java.io.File
import java.security.MessageDigest

internal object ApkVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: AppRelease) {
        require(file.length() == release.apkSize) { "Incomplete update. Please download again." }
        release.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actual.equals(expected, true)) { "Update checksum differs. Please download again." }
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Android couldn’t read this APK. Please download again.")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "This APK belongs to another app." }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(code > BuildConfig.VERSION_CODE) { "This APK is not newer than your installed app." }
        require(release.versionCode == null || code == release.versionCode) { "Release version code differs from the APK." }
        require(archive.versionName == release.versionName) { "Release version differs from the APK." }
        fun signatures(info: PackageInfo): Set<String> {
            val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signers.orEmpty().map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }.toSet()
        }
        val trusted = signatures(installed)
        require(trusted.isNotEmpty() && signatures(archive) == trusted) {
            "This release uses a different signing key. The publisher must sign it with the original app key."
        }
    }
}
