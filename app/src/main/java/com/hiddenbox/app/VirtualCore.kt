package com.hiddenbox.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

/** One installed virtual app, as shown in the list. */
data class VApp(
    val label: String,
    val packageName: String,
    val icon: Drawable?
)

/**
 * Thin, friendly wrapper around BlackBoxCore.
 *
 * Everything here happens inside the virtual environment. Apps installed
 * through [install] are NOT registered with the real Android PackageManager:
 * they never show up in the launcher or in Settings > Apps. They exist only
 * inside this process's sandbox and run only when launched from here.
 */
object VirtualCore {

    /** Single virtual user space. Could be extended to multiple profiles. */
    const val USER_ID = 0

    private val core: BlackBoxCore get() = BlackBoxCore.get()

    /** Apps currently installed inside the sandbox. */
    fun listInstalled(): List<VApp> {
        val pm = BlackBoxCore.getPackageManager()
        return core.getInstalledApplications(0, USER_ID).mapNotNull { info ->
            val pkg = info.packageName ?: return@mapNotNull null
            VApp(
                label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg),
                packageName = pkg,
                icon = runCatching { info.loadIcon(pm) }.getOrNull()
            )
        }
    }

    /**
     * Install an APK file into the sandbox.
     * @return null on success, or an error message.
     */
    fun install(apk: File): String? {
        return try {
            val result = core.installPackageAsUser(apk, USER_ID)
            if (result.success) null else (result.msg ?: "unknown error")
        } catch (e: Throwable) {
            e.message ?: e.toString()
        }
    }

    /** Copy a picked SAF uri into cache, then install it. */
    fun installFromUri(ctx: Context, uri: Uri): String? {
        val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.apk")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return "cannot open file"
            install(tmp)
        } catch (e: Throwable) {
            e.message ?: e.toString()
        } finally {
            tmp.delete()
        }
    }

    /** Launch an installed virtual app inside the sandbox. */
    fun launch(packageName: String): Boolean =
        runCatching { core.launchApk(packageName, USER_ID) }.getOrDefault(false)

    /** Remove a virtual app and its data from the sandbox. */
    fun uninstall(packageName: String) {
        runCatching { core.uninstallPackageAsUser(packageName, USER_ID) }
    }
}
