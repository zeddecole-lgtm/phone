package com.hiddenbox.app

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hiddenbox.app.databinding.ActivityInstalledAppsBinding
import com.hiddenbox.app.databinding.ItemPhoneAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One launchable app found on the phone. */
data class PhoneApp(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val alreadyInBox: Boolean
)

/**
 * Lists the apps installed on the phone and clones the chosen one into the
 * sandbox. No APK file is needed: the engine copies the real app's base APK
 * from the system by package name.
 */
class InstalledAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstalledAppsBinding
    private val adapter = PhoneAppsAdapter { app -> clone(app) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstalledAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        load()
    }

    private fun load() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { queryPhoneApps() }
            adapter.submit(apps)
            binding.progress.visibility = View.GONE
            binding.empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Launchable, user-facing apps, excluding this host app itself. */
    private fun queryPhoneApps(): List<PhoneApp> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val self = packageName
        val inBox = VirtualCore.installedPackageNames()
        val seen = HashSet<String>()
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(main, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                // Never offer to clone our own host app (the engine also blocks it).
                if (pkg == self || pkg == "top.niunaijun.blackbox") return@mapNotNull null
                if (!seen.add(pkg)) return@mapNotNull null
                PhoneApp(
                    label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                    packageName = pkg,
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                    alreadyInBox = pkg in inBox
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun clone(app: PhoneApp) {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { VirtualCore.cloneInstalled(app.packageName) }
            binding.progress.visibility = View.GONE
            if (err == null) {
                Toast.makeText(
                    this@InstalledAppsActivity,
                    getString(R.string.clone_ok, app.label),
                    Toast.LENGTH_SHORT
                ).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(
                    this@InstalledAppsActivity,
                    getString(R.string.install_fail, err),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

private class PhoneAppsAdapter(
    val onClick: (PhoneApp) -> Unit
) : RecyclerView.Adapter<PhoneAppsAdapter.VH>() {

    private val items = mutableListOf<PhoneApp>()

    fun submit(list: List<PhoneApp>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(val b: ItemPhoneAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPhoneAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.b.name.text = app.label
        holder.b.pkg.text = app.packageName
        holder.b.badge.visibility = if (app.alreadyInBox) View.VISIBLE else View.GONE
        if (app.icon != null) holder.b.icon.setImageDrawable(app.icon)
        else holder.b.icon.setImageResource(R.mipmap.ic_launcher)
        holder.b.root.setOnClickListener { onClick(app) }
    }
}
