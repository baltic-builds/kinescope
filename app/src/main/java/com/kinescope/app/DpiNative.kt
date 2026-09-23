package com.kinescope.app

/**
 * JNI binding to the bundled ByeDPI engine (see app/src/main/cpp/dpi_jni.c).
 *
 * Only [DpiEngineService] touches this object, and only inside its own `:dpi` process, so the
 * engine's process-wide globals are discarded together with that process after every run.
 */
object DpiNative {
    init {
        System.loadLibrary("kinescope_dpi")
    }

    /** Runs the proxy on the calling thread until it exits and returns its exit status. */
    external fun nativeRun(args: Array<String>): Int

    /** Asks a running proxy to leave its event loop. Returns 0 when the request was delivered. */
    external fun nativeStop(): Int
}
