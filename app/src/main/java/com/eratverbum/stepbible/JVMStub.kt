package com.eratverbum.stepbible

object JVMStub {
    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("step_jvm_stub")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("JVMStub", "Failed to load native library", e)
            false
        }
    }

    external fun startServer(
        jreDir: String,
        classPath: String,
        warPath: String,
        port: Int
    ): Int
}
