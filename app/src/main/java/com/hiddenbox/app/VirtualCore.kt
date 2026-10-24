package com.hiddenbox.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.BAccountManager
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
     * by its package name. The engine reads the real base and split APKs
     * from the system and copies them into independent private box storage.
     * Application data and security attestations are not migrated.
     * Always verify the virtual copy after a reboot before uninstalling the original.
     * @return null on success, or an error message.
     */
    fun cloneInstalled(packageName: String): String? {
        return try {
            val result = core.installPackageAsUser(packageName, USER_ID)
            if (!result.success) result.msg ?: "unknown error"
            else if (packageName !in installedPackageNames()) "Installation returned success but the app was not registered in the sandbox."
            else null
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

    /** Presence of a virtual package is not proof that Play services are running. */
    fun gmsInstalled(): Boolean = runCatching { core.isInstallGms(USER_ID) }.getOrDefault(false)

    /** Report separate host and virtual package states, without faking integrity. */
    fun googleServicesStatus(): String {
        val host = hostHasGms()
        val gms = gmsInstalled()
        val gsf = runCatching { core.isInstalled("com.google.android.gsf", USER_ID) }.getOrDefault(false)
        val store = runCatching { core.isInstalled("com.android.vending", USER_ID) }.getOrDefault(false)
        val googleAuthenticator = runCatching {
            BAccountManager.get().getAuthenticatorTypes()
                ?.any { it.type == "com.google" } ?: false
        }.getOrDefault(false)
        val yes = "✓"
        val no = "✗"
        return "Google Play services on phone: ${if (host) yes else no}\n" +
                "Google Play services inside HiddenBox: ${if (gms) yes else no}\n" +
                "Google Services Framework inside HiddenBox: ${if (gsf) yes else no}\n" +
                "Play Store inside HiddenBox: ${if (store) yes else no}\n" +
                "Google account authenticator registered: ${if (googleAuthenticator) yes else no}"
    }

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
