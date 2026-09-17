package com.llamacpp.local

// Jembatan JNI ke libstablediff_jni.so (lihat app/src/main/cpp/).
// Generate BLOCKING: panggil dari Dispatchers.IO. Hasil ditulis native
// ke file PNG (outPath); return 0=ok, -1=gagal, -2=dibatalkan, -3=PNG gagal.
object StableDiffusionBridge {
    init {
        System.loadLibrary("stablediff_jni")
    }

    fun interface ProgressCallback {
        fun onProgress(step: Int, steps: Int)
    }

    @JvmStatic external fun loadContext(modelPath: String, nThreads: Int): Long
    @JvmStatic external fun freeContext(handle: Long)
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun version(): String
    @JvmStatic external fun listDevices(): String
    @JvmStatic external fun modelVersion(handle: Long): String

    @JvmStatic external fun generate(
        handle: Long,
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        sampler: String,
        seed: Long,
        outPath: String,
        cb: ProgressCallback
    ): Int

    // Sampler yang didukung stable-diffusion.cpp (nama persis CLI).
    val SAMPLERS = listOf(
        "euler_a", "euler", "heun", "dpm2", "dpm++2s_a",
        "dpm++2m", "dpm++2mv2", "lcm", "ddim_trailing", "tcd", "lms"
    )
}
