# Llama.cpp — Local AI Inference (Android)

Aplikasi chat AI yang berjalan **100% lokal di HP Android** memakai engine
[llama.cpp](https://github.com/ggml-org/llama.cpp) (GGUF, CPU ARM64).
100% Kotlin + XML, UI chat dark modern.

## Fitur

- Chat dengan model GGUF lokal (streaming token real-time + tombol Stop)
- 15 model siap unduh dari HuggingFace (whitelist), default quant **Q8_0**,
  bisa pilih quant lain per model (Q6_K / Q5_K_M / Q5_0 / Q4_K_M / Q4_0)
  dengan fallback otomatis bila file quant tidak tersedia
- Tiap New Chat = session + context window baru, tersimpan di SQLite
- Auto-shrink context: riwayat terlama dibuang otomatis bila prompt
  melebihi context window (system prompt + pesan terbaru dipertahankan)
- Drawer: menu + daftar conversation (buka / hapus permanen)
- Halaman Models: filter kategori, status download (progress DownloadManager),
  hapus model permanen (file dihapus, entri kembali GET)
- Halaman Settings: HF token, folder model (folder picker + scan otomatis
  file `.gguf`), temperature, top_k, top_p, max tokens
- Banner + dialog pemandu bila model belum diunduh / token belum diisi

## Syarat

- JDK 17, Android SDK (platform 34), Android NDK 28.x, Gradle wrapper (sudah termasuk)
- HP Android arm64, RAM menyesuaikan model (mulai ~400 MB)
- Akun HuggingFace + token **Read** (HF mewajibkan login untuk download)

## Cara menjalankan

```bash
git clone https://github.com/yudono/llama-android
cd llama-android

# Build + install + jalankan (data dipertahankan, tanpa uninstall)
./run.sh

# atau manual:
./android/gradlew -p android assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Build native pertama mengunduh + mengompilasi llama.cpp (pin `b10893`,
butuh internet, ~5–10 menit). Build berikutnya inkremental.

## Pemakaian pertama kali

1. Buka aplikasi → tap **☰** → **Models** (atau tap banner).
2. Buka **Settings** (dari drawer) → isi **HF token**:
   buat gratis di `huggingface.co/settings/tokens` (tipe Read).
3. Kembali ke Models → tap **Get** pada model kecil dulu, mis.
   **SmolLM2-360M / Qwen2.5-0.5B**. Tunggu sampai `Ready to use`.
4. Tap model → **Use** → kembali → chat. Respons di-streaming kata per kata.
5. Tombol kirim berubah jadi **Stop** selama generate (anti double-send).

Tips: mulai dari model Micro/Ultra Ringan. Model besar (3B+) butuh
RAM 3 GB+ dan lebih lambat di HP entry-level.

## Daftar model

| Model | Kategori | Quant default | ±Ukuran |
|---|---|---|---|
| SmolLM2-360M-Instruct | Micro Size | Q8_0 | 250–350 MB |
| Qwen2.5-0.5B-Instruct | Ultra Ringan | Q8_0 | 350–600 MB |
| OpenELM-450M / 1.1B | Ultra Ringan | Q8_0 | 300 MB–1 GB |
| Llama-3.2-1B-Instruct | Ultra Ringan | Q8_0 | 600–900 MB |
| SmolLM2-1.7B-Instruct | Keseimbangan | Q8_0 | 1.0–1.3 GB |
| Qwen2.5-1.5B / Coder-1.5B | Keseimbangan / Coding | Q8_0 | ~1–1.2 GB |
| DeepSeek-R1-Distill 1.5B/3B | Penalaran | Q8_0 | 1.1–2.4 GB |
| Gemma-2-2B-It | Keseimbangan | Q8_0 | 1.6–1.9 GB |
| Ministral-3B / Llama-3.2-3B | Performa / Keseimbangan | Q8_0 | 1.8–2.2 GB |
| Phi-3.5-mini | Ringan & Cepat | Q8_0 | ~2.2 GB |
| Kimi-K3 | High-End / Enterprise | — | 594 GB (butuh cluster, lihat info di aplikasi) |

Quant bisa diganti per model lewat dropdown di kartunya.

## Arsitektur singkat

- `android/app/src/main/cpp/` — JNI tipis (`llamacpp_jni.cpp`):
  load model sekali, tiap generate = context window fresh,
  chat template bawaan model, sampler top_k/top_p/temp, streaming
  token UTF-8 aman, flag cancel, auto-shrink riwayat di level token.
- `LlamaBridge.kt` — API Kotlin + `Flow` streaming.
- `ChatDb.kt` — SQLite satu-satunya penyimpanan: `conversations`
  (session: model, quant, ctx_window), `messages` (urutan chat),
  `kv` (semua setting incl. HF token). Tiap generate terikat ID
  conversation agar tak bocor antar chat.
- `ModelDownloader.kt` — resolusi nama file (API HF → konvensi nama →
  fallback quant), DownloadManager + header auth, scan folder.
- `MainActivity / ModelsActivity / SettingsActivity` + adapter.

## Troubleshooting

| Gejala | Penyebab umum |
|---|---|
| "Isi HF token di Settings dulu" | Token kosong / terhapus (uninstall menghapus data) |
| "Quant tak tersedia" / fallback | File quant tsb memang tak ada di repo; app otomatis coba quant lain |
| Download 0% / gagal | Izin akses file untuk `/sdcard/models` belum diberi; atau koneksi |
| Respons kosong | Model terlalu kecil untuk prompt / context penuh; coba Retry |
| Build native gagal | Pastikan NDK 28 + CMake 3.22 + internet (unduh llama.cpp) |

## Credit

- [@yudono](https://github.com/yudono) — pemilik & pengembang utama
- Thanks to [OpenCode](https://github.com/anomalyco/opencode) untuk membantu proses development

## License

MIT
