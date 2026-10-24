package com.hiddenbox.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * A genuine Android managed profile created on the user's own phone.
 *
 * Personal side: user-approved provisioning, switching to its own app in
 * the work profile. Work side: official Android package install / launch.
 * No BlackBox virtualization is used for KAST in this mode.
 */
class NativeProfileActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout

    private val provisioning = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            message(getString(R.string.native_provision_cancelled))
        } else {
            message(getString(R.string.native_provision_pending))
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.native_mode_title)
        val scroller = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val space = px(20)
            setPadding(space, space, space, space)
        }
        scroller.addView(content)
        setContentView(scroller)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun px(dp: Int): Int = (resources.displayMetrics.density * dp).toInt()

    private fun info(value: String, header: Boolean = false) {
        val view = TextView(this).apply {
            text = value
            textSize = if (header) 21f else 15f
            if (header) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(if (header) 12 else 7), 0, px(11))
        }
        content.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun action(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        content.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun message(body: String, title: String = getString(R.string.native_mode_title)) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun render() {
        content.removeAllViews()
        info(getString(R.string.native_mode_title), header = true)
        info(getString(R.string.native_mode_description))
        if (NativeProfileManager.isOurWorkProfile(this)) {
            renderWorkProfile()
        } else {
            renderPersonalProfile()
        }
        action(getString(R.string.native_refresh)) { render() }
    }

    private fun renderPersonalProfile() {
        val target = NativeProfileManager.targetProfile(this)
        if (target != null) {
            info(getString(R.string.native_available), header = true)
            info(getString(R.string.native_available_explainer))
            action(getString(R.string.native_open_space)) { openWorkProfile() }
            return
        }
        info(getString(R.string.native_not_created), header = true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            info(getString(R.string.native_old_android))
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)) {
            info(getString(R.string.native_no_feature))
            return
        }
        val allowed = runCatching {
            NativeProfileManager.policyManager(this)
                .isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        }.getOrDefault(false)
        if (!allowed) {
            info(getString(R.string.native_provision_unavailable))
            return
        }
        action(getString(R.string.native_create)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.native_create)
                .setMessage(R.string.native_owner_consent)
                .setPositiveButton(R.string.native_create) { _, _ -> provision() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun provision() {
        val dpm = NativeProfileManager.policyManager(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            message(getString(R.string.native_provision_unavailable))
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                NativeProfileManager.admin(this@NativeProfileActivity).flattenToString())
        }
        if (intent.resolveActivity(packageManager) == null) {
            message(getString(R.string.native_provision_unavailable))
            return
        }
        try {
            provisioning.launch(intent)
        } catch (e: Exception) {
            message(getString(R.string.native_provision_error, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun openWorkProfile() {
        if (!NativeProfileManager.openWorkProfile(this)) {
            message(getString(R.string.native_open_failed))
        }
    }

    private fun renderWorkProfile() {
        info(getString(R.string.native_work_ready), header = true)
        val pm = packageManager
        val installed = pm.getLaunchIntentForPackage(NativeProfileManager.KAST_PACKAGE) != null
        val gms = runCatching { pm.getPackageInfo("com.google.android.gms", 0) }.isSuccess
        val store = runCatching { pm.getPackageInfo("com.android.vending", 0) }.isSuccess
        info(getString(R.string.native_status, if (installed) "✓" else "✗",
            if (gms) "✓" else "✗", if (store) "✓" else "✗"))
        if (installed) {
            action(getString(R.string.native_launch_kast)) { launchKast() }
        } else {
            action(getString(R.string.native_install_existing)) { tryInstallExisting() }
            action(getString(R.string.native_install_play)) { openOfficialPlayStore() }
            info(getString(R.string.native_play_recommendation))
        }
        action(getString(R.string.native_store)) { openOfficialPlayStore() }
        info(getString(R.string.native_integrity_warning))
    }

    private fun tryInstallExisting() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            message(getString(R.string.native_install_restricted))
            return
        }
        val dpm = NativeProfileManager.policyManager(this)
        try {
            val success = dpm.installExistingPackage(
                NativeProfileManager.admin(this), NativeProfileManager.KAST_PACKAGE)
            if (success &&
                packageManager.getLaunchIntentForPackage(NativeProfileManager.KAST_PACKAGE) != null) {
                message(getString(R.string.native_install_success))
                render()
            } else {
                cannotTransfer()
            }
        } catch (e: SecurityException) {
            // Regular personal work profiles are usually not affiliated with a
            // device owner. This API is intentionally unavailable in that case.
            cannotTransfer()
        } catch (e: Exception) {
            cannotTransfer()
        }
    }

    private fun cannotTransfer() {
        AlertDialog.Builder(this)
            .setTitle(R.string.native_install_restricted_title)
            .setMessage(R.string.native_install_restricted)
            .setPositiveButton(R.string.native_install_play) { _, _ ->
                openOfficialPlayStore()
            }
            .setNegativeButton(R.string.ok, null)
            .show()
    }

    private fun openOfficialPlayStore() {
        val url = "market://details?id=" + NativeProfileManager.KAST_PACKAGE
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.android.vending")
            })
        } catch (_: ActivityNotFoundException) {
            message(getString(R.string.native_store_missing))
        } catch (e: SecurityException) {
            message(getString(R.string.native_store_missing))
        }
    }

    private fun launchKast() {
        val intent = packageManager.getLaunchIntentForPackage(NativeProfileManager.KAST_PACKAGE)
        if (intent == null) {
            message(getString(R.string.native_missing_kast))
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            message(getString(R.string.native_launch_error, e.message ?: e.javaClass.simpleName))
        }
    }
}
