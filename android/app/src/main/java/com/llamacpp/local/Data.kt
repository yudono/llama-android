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
    // Quant pilihan user (ala `llama serve -hf repo:QUANT`); default = terbaik.
    var quant: String = DEFAULT_QUANT,
    var downloaded: Boolean = false,
    var downloading: Boolean = false,
    var progress: Int = 0,
    var downloadId: Long = -1L
) {
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

object AppData {
    val models = mutableListOf(
        AiModel("SmolLM2-135M-Instruct", "SmolLM2-135M", "Micro Size", "0.13B", "140-200MB", "~400MB", "HuggingFaceTB/SmolLM2-135M-Instruct-GGUF", 512),
        AiModel("SmolLM2-360M-Instruct", "SmolLM2-360M", "Micro Size", "0.36B", "250-350MB", "~700MB", "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF", 512),
        AiModel("Qwen2.5-0.5B-Instruct", "Qwen2.5-0.5B", "Ultra Ringan", "0.5B", "350-600MB", "~1GB", "Qwen/Qwen2.5-0.5B-Instruct-GGUF", 512),
        AiModel("Apple-OpenELM-450M-Instruct", "OpenELM-450M", "Ultra Ringan", "0.45B", "300-450MB", "~800MB", "lmstudio-community/OpenELM-450M-Instruct-GGUF", 512),
        AiModel("Llama-3.2-1B-Instruct", "Llama-3.2-1B", "Ultra Ringan", "1B", "600-900MB", "~1.5GB", "unsloth/Llama-3.2-1B-Instruct-GGUF", 512),
        AiModel("Apple-OpenELM-1.1B-Instruct", "OpenELM-1.1B", "Ultra Ringan", "1.1B", "700MB-1.0GB", "~1.5GB", "lmstudio-community/OpenELM-1_1B-Instruct-GGUF", 512),
        AiModel("SmolLM2-1.7B-Instruct", "SmolLM2-1.7B", "Keseimbangan Terbaik", "1.7B", "1.0-1.3GB", "~2GB", "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF", 1024),
        AiModel("Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B", "Keseimbangan Terbaik", "1.5B", "900MB-1.2GB", "~2GB", "Qwen/Qwen2.5-1.5B-Instruct-GGUF", 1024),
        AiModel("Qwen2.5-Coder-1.5B-Instruct", "Qwen-Coder-1.5B", "Spesialis Coding", "1.5B", "~1.1GB", "~2GB", "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF", 1024),
        AiModel("DeepSeek-R1-Distill-Qwen-1.5B", "R1-Distill-1.5B", "Spesialis Penalaran", "1.5B", "1.1-1.3GB", "~2.2GB", "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF", 1024),
        AiModel("Gemma-2-2B-It", "Gemma-2-2B", "Keseimbangan Terbaik", "2.6B", "1.6-1.9GB", "~2.5GB", "unsloth/gemma-2-2b-it-GGUF", 1024),
        AiModel("Ministral-3B-Instruct", "Ministral-3B", "Performa Tinggi", "3B", "1.9-2.2GB", "~3GB", "bartowski/Ministral-3B-Instruct-2410-GGUF", 1024),
        AiModel("Llama-3.2-3B-Instruct", "Llama-3.2-3B", "Keseimbangan Terbaik", "3B", "1.8-2.2GB", "~3GB", "unsloth/Llama-3.2-3B-Instruct-GGUF", 1024),
        AiModel("DeepSeek-R1-Distill-Qwen-3B", "R1-Distill-3B", "Spesialis Penalaran", "3B", "2.0-2.4GB", "~3.5GB", "unsloth/DeepSeek-R1-Distill-Qwen-3B-GGUF", 1024),
        AiModel("Phi-3.5-mini-instruct", "Phi-3.5-mini", "Ringan & Cepat", "3.8B", "~2.2GB", "~3.5GB", "bartowski/Phi-3.5-mini-instruct-GGUF", 1024)
    )

    // Kategori dinamis sesuai urutan kemunculan (tambah "Semua" di depan).
    val categories: List<String> =
        listOf("Semua") + models.map { it.category }.distinct()

    val suggestions = listOf(
        "What can you do?" to "See what I can help with",
        "Explain GGUF" to "Quantization for mobile",
        "Write a haiku" to "A poem about AI",
        "LLM tips" to "Run models on phones"
    )
}
