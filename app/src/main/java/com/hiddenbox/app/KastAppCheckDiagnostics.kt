package com.hiddenbox.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

/**
 * Collects only non-personal installation details useful to diagnose KAST
 * App Check compatibility. Does not read accounts, network traffic, tokens,
 * user identifiers, logcat, or private app data.
 *
 * An app cannot read a genuine Play Integrity verdict for another app; only
 * the app and its verification server can diagnose an attestation rejection.
 */
object KastAppCheckDiagnostics {
    const val KAST_PACKAGE = "com.kastfinance.app"
    const val SUPPORT_ADDRESS = "support@kast.xyz"
    const val ERROR_CODE = "APPCHECK-00401"

    private fun present(present: Boolean): String = if (present) "yes" else "no"

    fun collect(context: Context): String {
        val pm = context.packageManager
        val original = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(KAST_PACKAGE, 0)
        }.getOrNull()
        val boxInfo = runCatching {
            BlackBoxCore.get().getInstalledApplications(0, VirtualCore.USER_ID)
                .firstOrNull { it.packageName == KAST_PACKAGE }
        }.getOrNull()
        val installedViaPlay = if (original != null) {
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(KAST_PACKAGE).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(KAST_PACKAGE)
                }
            }.getOrNull()
            when (installer) {
                "com.android.vending" -> "Google Play (reported by Android)"
                null -> "Unknown / not reported"
                else -> "Other installer (not Google Play)"
            }
        } else "Not installed in personal profile"

        val source = boxInfo?.sourceDir
        val sourceReadable = source != null && File(source).isFile && File(source).canRead()
        val splitFiles = boxInfo?.splitSourceDirs.orEmpty()
        val readableSplits = splitFiles.count { File(it).isFile && File(it).canRead() }
        val boxDataPath = runCatching { File(context.applicationInfo.dataDir).canonicalPath }.getOrNull()
        val sandboxStorage = if (source != null && boxDataPath != null) {
            runCatching { File(source).canonicalPath.startsWith(boxDataPath + File.separator) }
                .getOrDefault(false)
        } else false

        val hostGoogle = VirtualCore.hostHasGms()
        val boxGoogle = VirtualCore.gmsInstalled()
        val boxFramework = runCatching {
            BlackBoxCore.get().isInstalled("com.google.android.gsf", VirtualCore.USER_ID)
        }.getOrDefault(false)
        val boxStore = runCatching {
            BlackBoxCore.get().isInstalled("com.android.vending", VirtualCore.USER_ID)
        }.getOrDefault(false)

        return listOf(
            "HiddenBox / KAST compatibility diagnostics",
            "Reported error: $ERROR_CODE",
            "Android SDK: ${Build.VERSION.SDK_INT}",
            "KAST original on phone: ${present(original != null)}",
            "KAST version on phone: ${original?.versionName ?: "unavailable"}",
            "KAST host install source: $installedViaPlay",
            "KAST imported in HiddenBox: ${present(boxInfo != null)}",
            "Virtual base APK readable: ${present(sourceReadable)}",
            "Virtual split APK files readable: $readableSplits / ${splitFiles.size}",
            "Virtual APK inside HiddenBox private storage: ${present(sandboxStorage)}",
            "Host Google Play services available: ${present(hostGoogle)}",
            "Virtual Google Play services installed: ${present(boxGoogle)}",
            "Virtual Google Services Framework installed: ${present(boxFramework)}",
            "Virtual Google Play Store installed: ${present(boxStore)}",
            "Play Integrity / App Check verdict: unavailable to HiddenBox",
            "",
            "This report does not contain login details, identifiers or tokens.",
            "A virtual environment may fail Google's application/device integrity",
            "requirements even when genuine APK files and Play services are present."
        ).joinToString("\n")
    }

    fun supportMessage(report: String): String =
        "Hello KAST Support,\n\n" +
        "KAST displays error $ERROR_CODE when launched from an isolated Android " +
        "app container (HiddenBox). The original APK and installed split APKs " +
        "were copied without changing their contents. I am not requesting to " +
        "bypass App Check or Play Integrity; please confirm whether the " +
        "container environment is supported, and whether you offer a " +
        "supported way to access KAST in an isolated profile.\n\n" +
        report + "\n\nThank you."
}
