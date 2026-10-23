package com.hiddenbox.app

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

    private fun installGms() {
        if (!VirtualCore.hostHasGms()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.gms_host_missing)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        if (VirtualCore.gmsInstalled()) {
            toast(getString(R.string.gms_already))
            return
        }
        binding.progress.visibility = View.VISIBLE
        toast(getString(R.string.gms_installing))
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { VirtualCore.installGms() }
            binding.progress.visibility = View.GONE
            toast(if (err == null) getString(R.string.gms_done) else getString(R.string.gms_fail, err))
            refresh()
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
