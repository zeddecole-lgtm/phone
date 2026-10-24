package com.hiddenbox.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Admin of a work profile that the user explicitly provisions using Android.
 * The admin never applies device-wide restrictions or changes system identity.
 */
class NativeProfileAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        val dpm = NativeProfileManager.policyManager(context)
        val admin = NativeProfileManager.admin(context)
        if (!dpm.isProfileOwnerApp(context.packageName)) {
            Log.e(TAG, "Provisioning callback without profile ownership")
            return
        }
        // These are the genuine preinstalled Android system applications,
        // not copied or fake services. Availability is device-dependent.
        for (pkg in arrayOf("com.google.android.gms", "com.google.android.gsf",
                "com.android.vending")) {
            try {
                dpm.enableSystemApp(admin, pkg)
            } catch (t: Exception) {
                Log.i(TAG, "Unable to enable optional system package: " + pkg, t)
            }
        }
        try {
            dpm.setProfileName(admin, "HiddenBox Native")
        } catch (t: Exception) {
            Log.w(TAG, "Cannot rename native profile", t)
        }
        try {
            dpm.setProfileEnabled(admin)
        } catch (t: Exception) {
            Log.e(TAG, "Cannot enable native profile", t)
        }
    }

    companion object {
        private const val TAG = "HiddenBoxNative"
    }
}
