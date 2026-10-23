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

    /**
     * Clone an app that is already installed on the phone into the sandbox,
     * by its package name. The engine reads the real app's base APK from the
     * system (ApplicationInfo.sourceDir) and installs a copy inside the box.
     * The box copy is independent, so the original can be uninstalled from the
     * phone afterwards and the cloned one keeps working.
     * @return null on success, or an error message.
     */
    fun cloneInstalled(packageName: String): String? {
        return try {
            val result = core.installPackageAsUser(packageName, USER_ID)
            if (result.success) null else (result.msg ?: "unknown error")
        } catch (e: Throwable) {
            e.message ?: e.toString()
        }
    }

    /** Package names already installed inside the sandbox. */
    fun installedPackageNames(): Set<String> =
        runCatching {
            core.getInstalledApplications(0, USER_ID).mapNotNull { it.packageName }.toSet()
        }.getOrDefault(emptySet())

    /** Launch an installed virtual app inside the sandbox. */
    fun launch(packageName: String): Boolean =
        runCatching { core.launchApk(packageName, USER_ID) }.getOrDefault(false)

    /** Whether the host phone itself has Google Play services available. */
    fun hostHasGms(): Boolean = runCatching { core.isSupportGms }.getOrDefault(false)

    /** Whether Google Play services are already installed inside the sandbox. */
    fun gmsInstalled(): Boolean = runCatching { core.isInstallGms(USER_ID) }.getOrDefault(false)

    /**
     * Install Google Play services into the sandbox by cloning them from the
     * host phone. Only works if the host actually has them (many de-Googled
     * phones don't). Returns null on success, or an error message.
     */
    fun installGms(): String? {
        if (!hostHasGms()) return "HOST_NO_GMS"
        return try {
            val result = core.installGms(USER_ID)
            if (result.success) null else (result.msg ?: "unknown error")
        } catch (e: Throwable) {
            e.message ?: e.toString()
        }
    }

    /** Remove a virtual app and its data from the sandbox. */
    fun uninstall(packageName: String) {
        runCatching { core.uninstallPackageAsUser(packageName, USER_ID) }
    }
}
