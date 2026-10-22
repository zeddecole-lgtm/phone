package com.hiddenbox.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Application entry point.
 *
 * The virtual engine (BlackBoxCore) MUST be attached here, in attachBaseContext,
 * before anything else runs. This is what makes the host process able to host
 * other apps' processes. Every call is wrapped in try/catch because an engine
 * failure in a child ("black") process must not crash the whole host.
 */
class App : Application() {

    companion object {
        private const val TAG = "HiddenBox"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        lateinit var context: Context
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        context = base
        try {
            // Must run before the engine attaches.
            BlackBoxCore.get().closeCodeInit()
            BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)

            // Attach the engine with our own minimal configuration.
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                // We deliberately do NOT hide root or spoof anything:
                override fun isHideRoot(): Boolean = false
                override fun isEnableDaemonService(): Boolean = false
                // IMPORTANT privacy fix: the engine ships the whole device
                // logcat + device info to a third-party endpoint keyed by this
                // id, and its DEFAULT is the upstream author's chat. Returning
                // an empty string turns that upload into a no-op.
                override fun getLogSenderChatId(): String = ""
            })

            BlackBoxCore.get().onAfterMainApplicationAttach(this, base)
        } catch (e: Throwable) {
            Log.e(TAG, "engine attach failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            BlackBoxCore.get().doCreate()
        } catch (e: Throwable) {
            Log.e(TAG, "engine create failed", e)
        }
    }
}
