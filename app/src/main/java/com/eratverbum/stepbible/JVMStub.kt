package com.eratverbum.stepbible

object JVMStub {
    init {
        System.loadLibrary("step_jvm_stub")
    }

    external fun startServer(
        jreDir: String,
        classPath: String,
        warPath: String,
        port: Int
    ): Int
}
