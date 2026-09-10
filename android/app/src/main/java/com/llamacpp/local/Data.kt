package com.llamacpp.local

data class AiModel(
    val name: String,
    val shortName: String,
    val category: String,
    val paramSize: String,
    val fileSize: String,
    val ram: String,
    var downloaded: Boolean = false,
    var downloading: Boolean = false,
    var progress: Int = 0
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isTyping: Boolean = false
)

object DummyData {
    val models = mutableListOf(
        AiModel("Qwen2.5-0.5B-Instruct", "Qwen2.5-0.5B", "Ultra Ringan", "0.5B", "350-600MB", "~1GB", downloaded = true),
        AiModel("Llama-3.2-1B-Instruct", "Llama-3.2-1B", "Ultra Ringan", "1B", "600-900MB", "~1.5GB"),
        AiModel("Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B", "Keseimbangan Terbaik", "1.5B", "900MB-1.2GB", "~2GB"),
        AiModel("Qwen2.5-Coder-1.5B-Instruct", "Qwen-Coder", "Spesialis Coding", "1.5B", "~1.1GB", "~2GB"),
        AiModel("Llama-3.2-3B-Instruct", "Llama-3.2-3B", "Keseimbangan Terbaik", "3B", "1.8-2.2GB", "~3GB"),
        AiModel("Phi-3.5-mini-instruct", "Phi-3.5-mini", "Ringan & Cepat", "3.8B", "~2.2GB", "~3.5GB")
    )

    val categories = listOf("Semua", "Ultra Ringan", "Keseimbangan Terbaik", "Spesialis Coding", "Ringan & Cepat")

    private val responses = listOf(
        "Halo! Saya AI assistant di device Anda memakai llama.cpp. Siap membantu!",
        "Model ini memakai GGUF quantized weights (Q4_K_M) agar kencang di HP.",
        "Semua inference 100% offline. Tidak ada data keluar dari device.",
        "Engine llama.cpp v0.1.0 | Material UI + 100% Kotlin | APK ringan"
    )
    private var idx = 0
    fun nextResponse(): String = responses[idx++ % responses.size]

    val suggestions = listOf(
        "What can you do?" to "See what I can help with",
        "Explain GGUF" to "Quantization for mobile",
        "Write a haiku" to "A poem about AI",
        "LLM tips" to "Run models on phones"
    )
}
