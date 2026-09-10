package com.llamacpp.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

// Jembatan JNI ke libllamacpp_jni.so (lihat app/src/main/cpp/).
// Generate BLOCKING + STREAMING: panggil dari Dispatchers.IO.
object LlamaBridge {
    init {
        System.loadLibrary("llamacpp_jni")
    }

    fun interface TokenCallback {
        fun onToken(t: String)
    }

    @JvmStatic external fun loadModel(path: String, nCtx: Int): Long
    @JvmStatic external fun unloadModel(handle: Long)
    @JvmStatic external fun systemInfo(): String

    @JvmStatic external fun generate(
        handle: Long,
        roles: Array<String>,
        texts: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        cb: TokenCallback
    ): Int

    // Aliran token (dikumpulkan di Main untuk update bubble).
    fun generateFlow(
        handle: Long,
        history: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float
    ): Flow<String> = callbackFlow {
        withContext(Dispatchers.IO) {
            generate(
                handle,
                history.map { it.first }.toTypedArray(),
                history.map { it.second }.toTypedArray(),
                maxTokens,
                temperature,
                topK,
                topP,
                TokenCallback { trySend(it).isSuccess }
            )
            close()
        }
        awaitClose()
    }
}
