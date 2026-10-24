package com.hiddenbox.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hiddenbox.app.databinding.ActivityMainBinding
import com.hiddenbox.app.databinding.ItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = AppsAdapter(
        onClick = { app -> launch(app) },
        onLongClick = { app -> confirmUninstall(app) }
    )

    // SAF picker for choosing an APK from the device.
    private val pickApk = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { doInstall(it) } }

    // Picker that clones an app already installed on the phone.
    private val pickInstalled = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.list.layoutManager = GridLayoutManager(this, 4)
        binding.list.adapter = adapter

        binding.fabAdd.setOnClickListener { chooseAddSource() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { VirtualCore.listInstalled() }
            adapter.submit(apps)
            binding.empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Ask whether to add from a phone app (clone) or from an APK file. */
    private fun chooseAddSource() {
        val options = arrayOf(getString(R.string.add_from_phone), getString(R.string.add_from_apk))
        AlertDialog.Builder(this)
            .setTitle(R.string.add_source_title)
            .setItems(options) { _, which ->
                if (which == 0) {
                    pickInstalled.launch(Intent(this, InstalledAppsActivity::class.java))
                } else {
                    ensureStorageThenPick()
                }
            }
            .show()
    }

    /** Engine needs all-files access from Android 11+ to run some apps. */
    private fun ensureStorageThenPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_msg)
                .setPositiveButton(R.string.perm_grant) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName"))
                        )
                    }
                }
                .setNegativeButton(R.string.perm_later) { _, _ -> pickApkFile() }
                .show()
        } else {
            pickApkFile()
        }
    }

    private fun pickApkFile() {
        // Most providers report APKs as octet-stream; accept broadly.
        pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
    }

    private fun doInstall(uri: Uri) {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { VirtualCore.installFromUri(this@MainActivity, uri) }
            binding.progress.visibility = View.GONE
            if (err == null) {
                toast(getString(R.string.install_ok))
                refresh()
            } else {
                toast(getString(R.string.install_fail, err))
            }
        }
    }

    private fun launch(app: VApp) {
        // KAST requires Google Play services. Offer genuine dependency
        // installation before attempting to launch it without those services.
        if (app.packageName == "com.kastfinance.app" && !VirtualCore.gmsInstalled()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.gms_required_title)
                .setMessage(R.string.gms_required_body)
                .setPositiveButton(R.string.gms_try_install) { _, _ ->
                    installGms { launchDirect(app) }
                }
                .setNeutralButton(R.string.gms_continue) { _, _ -> launchDirect(app) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        launchDirect(app)
    }

    private fun launchDirect(app: VApp) {
        if (!VirtualCore.launch(app.packageName)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.launch_fail_title)
                .setMessage(getString(R.string.launch_fail_body, app.label))
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.menu_help) { _, _ -> showHelp() }
                .show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_install_gms -> { installGms(); true }
        R.id.action_gms_diagnostics -> { showGoogleServicesStatus(); true }
        R.id.action_kast_appcheck -> { showKastAppCheckDiagnostics(); true }
        R.id.action_help -> { showHelp(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_body)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showKastAppCheckDiagnostics() {
        val report = KastAppCheckDiagnostics.collect(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.kast_check_title)
            .setMessage(report + "\n\n" + getString(R.string.kast_check_explanation))
            .setPositiveButton(R.string.kast_check_email) { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(
                    "mailto:${KastAppCheckDiagnostics.SUPPORT_ADDRESS}"
                )).apply {
                    putExtra(Intent.EXTRA_SUBJECT,
                        "KAST ${KastAppCheckDiagnostics.ERROR_CODE} — supported environment")
                    putExtra(Intent.EXTRA_TEXT,
                        KastAppCheckDiagnostics.supportMessage(report))
                }
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    copyAppCheckReport(report)
                }
            }
            .setNeutralButton(R.string.kast_check_copy) { _, _ ->
                copyAppCheckReport(report)
            }
            .setNegativeButton(R.string.ok, null)
            .show()
    }

    private fun copyAppCheckReport(report: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("KAST App Check report", report))
        toast(getString(R.string.kast_check_copied))
    }

    private fun showGoogleServicesStatus() {
        AlertDialog.Builder(this)
            .setTitle(R.string.gms_status_title)
            .setMessage(VirtualCore.googleServicesStatus() + "\n\n" + getString(R.string.gms_status_warning))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun installGms(afterSuccess: (() -> Unit)? = null) {
        if (!VirtualCore.hostHasGms()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.gms_status_title)
                .setMessage(R.string.gms_host_missing)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        if (VirtualCore.gmsInstalled()) {
            if (afterSuccess != null) afterSuccess() else showGoogleServicesStatus()
            return
        }
        binding.progress.visibility = View.VISIBLE
        toast(getString(R.string.gms_installing))
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { VirtualCore.installGms() }
            binding.progress.visibility = View.GONE
            if (err != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.gms_status_title)
                    .setMessage(getString(R.string.gms_fail, err) + "\n\n" +
                            VirtualCore.googleServicesStatus())
                    .setPositiveButton(R.string.ok, null)
                    .show()
            } else {
                refresh()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.gms_status_title)
                    .setMessage(getString(R.string.gms_done) + "\n\n" +
                            getString(R.string.gms_status_warning))
                    .setPositiveButton(if (afterSuccess == null) getString(R.string.ok)
                        else getString(R.string.gms_continue)) { _, _ ->
                        afterSuccess?.invoke()
                    }
                    .show()
            }
        }
    }

    private fun confirmUninstall(app: VApp) {
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setMessage(getString(R.string.uninstall_confirm, app.label))
            .setPositiveButton(R.string.uninstall) { _, _ ->
                VirtualCore.uninstall(app.packageName)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}

/** Grid adapter for the installed virtual apps. */
private class AppsAdapter(
    val onClick: (VApp) -> Unit,
    val onLongClick: (VApp) -> Unit
) : RecyclerView.Adapter<AppsAdapter.VH>() {

    private val items = mutableListOf<VApp>()

    fun submit(list: List<VApp>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.b.name.text = app.label
        if (app.icon != null) holder.b.icon.setImageDrawable(app.icon)
        else holder.b.icon.setImageResource(R.mipmap.ic_launcher)
        holder.b.root.setOnClickListener { onClick(app) }
        holder.b.root.setOnLongClickListener { onLongClick(app); true }
    }
}
