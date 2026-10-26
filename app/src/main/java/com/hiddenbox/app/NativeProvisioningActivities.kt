package com.hiddenbox.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Android 12+ provisioning may request these activities on some devices.
 * We only support managed profiles; never fully managed device ownership.
 */
class NativeGetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val allowed = intent.getIntegerArrayListExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
            if (allowed == null ||
                allowed.contains(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE)) {
                setResult(RESULT_OK, Intent().putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE))
            } else {
                setResult(RESULT_CANCELED)
            }
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}

class NativePolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No enterprise restrictions are requested. Android performs its own
        // managed-profile provisioning and consent checks.
        setResult(RESULT_OK)
        finish()
    }
}
