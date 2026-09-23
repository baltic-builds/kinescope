package com.kinescope.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * Hosts the ByeDPI engine in its own Android process (`android:process=":dpi"`).
 *
 * Lifecycle: the engine starts when the first client binds and its process is killed when the last
 * client unbinds. A fresh process per run gives every start a clean engine (the C code keeps its
 * settings in globals), and a crash inside the native code, for example on an odd strategy from
 * an updated list, takes down only this process rather than the whole app.
 *
 * Nothing here calls [AppLog]: that logger is not multi-process safe and is deliberately not
 * initialised in this process (see [YtOfflineApp]). The client logs what it observes instead.
 */
class DpiEngineService : Service() {
    override fun onBind(intent: Intent): IBinder? {
        val args = intent.getStringArrayExtra(EXTRA_ARGS) ?: return null
        Thread(null, {
            val status = try {
                DpiNative.nativeRun(args)
            } catch (error: Throwable) {
                Log.e(TAG, "Engine crashed on the JNI boundary", error)
                -100
            }
            Log.i(TAG, "Engine exited with status $status")
            // The process exists only to host the engine: leave nothing behind, and let the
            // client see the disconnect immediately instead of waiting for a timeout.
            android.os.Process.killProcess(android.os.Process.myPid())
        }, "kinescope-dpi", ENGINE_STACK_BYTES).start()
        return Binder()
    }

    override fun onDestroy() {
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        const val EXTRA_ARGS = "kinescope.dpi.args"
        private const val TAG = "KinescopeDpiEngine"
        private const val ENGINE_STACK_BYTES = 4L * 1024L * 1024L
    }
}
