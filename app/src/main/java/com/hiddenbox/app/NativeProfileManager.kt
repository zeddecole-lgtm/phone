package com.hiddenbox.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.CrossProfileApps
import android.os.Build
import android.os.UserHandle

/**
 * Native Android profile support. Unlike BlackBox's virtual process, this
 * profile is created and managed by Android itself. It does not spoof device
 * identity or promise that a third-party Play Integrity policy will accept it.
 */
object NativeProfileManager {
    const val KAST_PACKAGE = "com.kastfinance.app"

    fun policyManager(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun admin(context: Context): ComponentName =
        ComponentName(context, NativeProfileAdminReceiver::class.java)

    fun isOurWorkProfile(context: Context): Boolean =
        runCatching { policyManager(context).isProfileOwnerApp(context.packageName) }
            .getOrDefault(false)

    fun targetProfile(context: Context): UserHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            context.getSystemService(CrossProfileApps::class.java)
                .targetUserProfiles.firstOrNull()
        }.getOrNull()
    }

    /**
     * CrossProfileApps may only start the caller's own exported launcher.
     * The launcher in the managed profile opens NativeProfileActivity and
     * can then launch the genuine installed KAST package locally.
     */
    fun openWorkProfile(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val target = targetProfile(activity) ?: return false
        return runCatching {
            val cross = activity.getSystemService(CrossProfileApps::class.java)
            cross.startMainActivity(ComponentName(activity, LockActivity::class.java), target)
            true
        }.getOrDefault(false)
    }
}
