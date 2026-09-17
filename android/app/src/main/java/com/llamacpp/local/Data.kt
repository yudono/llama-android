package com.llamacpp.local

data class AiModel(
    val name: String,
    val shortName: String,
    val category: String,
    val paramSize: String,
    val fileSize: String,
    val ram: String,
    val repo: String,
    val mobileCtx: Int,
    // Link info resmi model; bila diisi, tombol Get membuka link ini.
    val infoUrl: String? = null,
    // Quant pilihan user (ala `llama serve -hf repo:QUANT`); default = terbaik.
    var quant: String = DEFAULT_QUANT,
    var downloaded: Boolean = false,
    var downloading: Boolean = false,
    // Persen 0-100 dgn 2 desimal (tampil "2.99%").
    var progress: Float = 0f,
    var downloadId: Long = -1L
) {
    fun progressLabel(): String = "%.2f%%".format(java.util.Locale.US, progress)
    companion object {
        const val DEFAULT_QUANT = "Q8_0"
        val QUANTS = listOf("Q8_0", "Q6_K", "Q5_K_M", "Q5_0", "Q4_K_M", "Q4_0")
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isTyping: Boolean = false
)

// Model image generation (stable-diffusion.cpp, file GGUF utuh — tanpa quant).
data class ImageModel(
    val name: String,
    val shortName: String,
    val category: String,
    val paramSize: String,
    val fileSize: String,
    val ram: String,
    val repo: String,
    val filename: String,
    var downloaded: Boolean = false,
    var downloading: Boolean = false,
    // Persen 0-100 dgn 2 desimal (tampil "2.99%").
    var progress: Float = 0f,
    var downloadId: Long = -1L
) {
    fun progressLabel(): String = "%.2f%%".format(java.util.Locale.US, progress)
}

object AppData {
    val models = mutableListOf(
        AiModel("SmolLM2-360M-Instruct", "SmolLM2-360M", "Micro Size", "0.36B", "250-350MB", "~700MB", "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF", 512),
        AiModel("Qwen2.5-0.5B-Instruct", "Qwen2.5-0.5B", "Ultra Ringan", "0.5B", "350-600MB", "~1GB", "Qwen/Qwen2.5-0.5B-Instruct-GGUF", 512),
        AiModel("Llama-3.2-1B-Instruct", "Llama-3.2-1B", "Ultra Ringan", "1B", "600-900MB", "~1.5GB", "unsloth/Llama-3.2-1B-Instruct-GGUF", 512),
        AiModel("SmolLM2-1.7B-Instruct", "SmolLM2-1.7B", "Keseimbangan Terbaik", "1.7B", "1.0-1.3GB", "~2GB", "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF", 1024),
        AiModel("Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B", "Keseimbangan Terbaik", "1.5B", "900MB-1.2GB", "~2GB", "Qwen/Qwen2.5-1.5B-Instruct-GGUF", 1024),
        AiModel("Qwen2.5-Coder-1.5B-Instruct", "Qwen-Coder-1.5B", "Spesialis Coding", "1.5B", "~1.1GB", "~2GB", "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF", 1024),
        AiModel("DeepSeek-R1-Distill-Qwen-1.5B", "R1-Distill-1.5B", "Spesialis Penalaran", "1.5B", "1.1-1.3GB", "~2.2GB", "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF", 1024),
        AiModel("Gemma-2-2B-It", "Gemma-2-2B", "Keseimbangan Terbaik", "2.6B", "1.6-1.9GB", "~2.5GB", "unsloth/gemma-2-2b-it-GGUF", 1024),
        AiModel("Ministral-3B-Instruct", "Ministral-3B", "Performa Tinggi", "3B", "1.9-2.2GB", "~3GB", "bartowski/Ministral-3B-Instruct-2410-GGUF", 1024),
        AiModel("Llama-3.2-3B-Instruct", "Llama-3.2-3B", "Keseimbangan Terbaik", "3B", "1.8-2.2GB", "~3GB", "unsloth/Llama-3.2-3B-Instruct-GGUF", 1024),
        AiModel("DeepSeek-R1-Distill-Qwen-3B", "R1-Distill-3B", "Spesialis Penalaran", "3B", "2.0-2.4GB", "~3.5GB", "unsloth/DeepSeek-R1-Distill-Qwen-3B-GGUF", 1024),
        AiModel("Phi-3.5-mini-instruct", "Phi-3.5-mini", "Ringan & Cepat", "3.8B", "~2.2GB", "~3.5GB", "bartowski/Phi-3.5-mini-instruct-GGUF", 1024),
        AiModel("Kimi-K3", "Kimi-K3", "High-End / Enterprise", "2.8T (MoE 104B aktif)", "594 GB", "~610 GB+", "unsloth/Kimi-K3-GGUF", 512,
            "https://www.youtube.com/watch?v=t1udwdcRT0A")
    )

    // Kategori dinamis sesuai urutan kemunculan (tambah "Semua" di depan).
    val categories: List<String> =
        listOf("Semua") + models.map { it.category }.distinct()

    // Model text-to-image (SDXL-Turbo, cocok HP: steps kecil + cfg rendah).
    val imageModels = mutableListOf(
        ImageModel("DreamShaper-XL-v2-Turbo-Q4_K", "DreamShaper-XL", "Image Generation", "3.5B", "~2.6GB", "~4GB", "offgrid-ai/dreamshaper-xl-v2-turbo-GGUF", "dreamshaper-xl-v2-turbo-Q4_K.gguf"),
        ImageModel("DreamShaper-XL-v2-Turbo-Q8_0", "DreamShaper-XL-Q8", "Image Generation", "3.5B", "~3.9GB", "~6GB", "offgrid-ai/dreamshaper-xl-v2-turbo-GGUF", "dreamshaper-xl-v2-turbo-Q8_0.gguf")
    )

    val suggestions = listOf(
        "What can you do?" to "See what I can help with",
        "Explain GGUF" to "Quantization for mobile",
        "Write a haiku" to "A poem about AI",
        "LLM tips" to "Run models on phones"
    )
}
