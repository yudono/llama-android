# Llama.cpp — Local AI Inference (Android)

Aplikasi AI yang berjalan **100% lokal di HP Android**: chat LLM memakai
engine [llama.cpp](https://github.com/ggml-org/llama.cpp) (GGUF, CPU ARM64)
dan text-to-image memakai
[stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp).
100% Kotlin + XML, UI dark modern.

## Fitur

- Chat dengan model GGUF lokal (streaming token real-time + tombol Stop)
- **Image Gen**: text-to-image on-device (SDXL-Turbo GGUF, progress per-step
  + tombol Cancel, simpan PNG, Share)
- 13 model chat + 2 model gambar siap unduh dari HuggingFace (whitelist),
  default quant **Q8_0**, bisa pilih quant lain per model chat
  (Q6_K / Q5_K_M / Q5_0 / Q4_K_M / Q4_0) dengan fallback otomatis bila
  file quant tidak tersedia
- Tiap model (chat maupun gambar) bisa diunduh (**Get**) atau dipasang
  dari file sendiri (**Pick file** `.gguf`, mis. hasil `adb push`) —
  progress 2 desimal (`2.99%`) + tombol **Cancel** saat transfer berjalan
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

Build native pertama mengunduh + mengompilasi llama.cpp (pin `b10893`)
dan stable-diffusion.cpp (pin `cc515a0`, butuh internet, ~10–20 menit).
Sumber native: `third_party/` (gitignored). Bila kosong, CMake otomatis
mengunduh via FetchContent (llama.cpp `b10893`, stable-diffusion.cpp
`cc515a0` + submodule `ggml`). Untuk build offline / pin manual:

```bash
mkdir -p third_party
git clone --recursive https://github.com/leejet/stable-diffusion.cpp third_party/stable-diffusion.cpp
```

Catatan build: SD memakai ulang `ggml` upstream milik llama (urutan
`add_subdirectory` penting). 2 op INT8 + 2 tipe F8 eksklusif fork leejet
yang dipakai SD dialihkan ke shim kompat di `sd_ggml_compat.*` — hanya
dieksekusi model ber-weight I8/FP8 (tak ada di katalog). `GGML_MAX_NAME=160`
di-define global agar layout `struct ggml_tensor` konsisten di semua lib.
Build berikutnya inkremental.

## GPU (Vulkan, hybrid CPU+GPU)

Build menyertakan backend Vulkan (`GGML_VULKAN=ON`, `libggml-vulkan.so`).
Runtime otomatis hybrid: llama offload layer ke GPU,
SD menaruh difusi di GPU + text-encoder/VAE di CPU. Tanpa GPU yang cocok,
otomatis jatuh kembali ke CPU. Indikator di layar Image Gen kanan atas:
`· GPU` / `· CPU`.

Syarat di mesin build (macOS): `glslc` + header Vulkan C++ + SPIRV-Headers:

```bash
brew install glslang shaderc
mkdir -p ~/.vulkan-deps
git clone --depth 1 https://github.com/KhronosGroup/SPIRV-Headers ~/.vulkan-deps/SPIRV-Headers
git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers ~/.vulkan-deps/Vulkan-Headers
cmake -S ~/.vulkan-deps/SPIRV-Headers -B ~/.vulkan-deps/SPIRV-Headers/build \
  -DCMAKE_INSTALL_PREFIX=~/.vulkan-deps/install
cmake --build ~/.vulkan-deps/SPIRV-Headers/build
cmake --install ~/.vulkan-deps/SPIRV-Headers/build
```

Path di atas sudah terhubung via `arguments` cmake di
`android/app/build.gradle.kts` (bisa dioverride env `VULKAN_GLSLC`).
HP perlu driver Vulkan (mis. Mali `vulkan.mali.so`, diverifikasi di
TECNO Helio G99 / Mali-G57).

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
| Llama-3.2-1B-Instruct | Ultra Ringan | Q8_0 | 600–900 MB |
| SmolLM2-1.7B-Instruct | Keseimbangan | Q8_0 | 1.0–1.3 GB |
| Qwen2.5-1.5B / Coder-1.5B | Keseimbangan / Coding | Q8_0 | ~1–1.2 GB |
| DeepSeek-R1-Distill 1.5B/3B | Penalaran | Q8_0 | 1.1–2.4 GB |
| Gemma-2-2B-It | Keseimbangan | Q8_0 | 1.6–1.9 GB |
| Ministral-3B / Llama-3.2-3B | Performa / Keseimbangan | Q8_0 | 1.8–2.2 GB |
| Phi-3.5-mini | Ringan & Cepat | Q8_0 | ~2.2 GB |
| Kimi-K3 | High-End / Enterprise | — | 594 GB (butuh cluster, lihat info di aplikasi) |

Quant bisa diganti per model lewat dropdown di kartunya.

## Image Generation (DreamShaper-XL-Turbo)

Halaman **Image Gen** (drawer) menjalankan Stable Diffusion XL Turbo
100% on-device (CPU, tanpa server):

| Model | File | ±Ukuran | RAM |
|---|---|---|---|
| DreamShaper-XL-v2-Turbo-Q4_K | `dreamshaper-xl-v2-turbo-Q4_K.gguf` | ~2.6 GB | ~4 GB |
| DreamShaper-XL-v2-Turbo-Q8_0 | `dreamshaper-xl-v2-turbo-Q8_0.gguf` | ~3.9 GB | ~6 GB |

Repo: [`offgrid-ai/dreamshaper-xl-v2-turbo-GGUF`](https://huggingface.co/offgrid-ai/dreamshaper-xl-v2-turbo-GGUF)
(SDXL-Turbo, lisensi OpenRAIL++).

Parameter default setara contoh CLI
(`-W 512 -H 512 --steps 6 --cfg-scale 2.0 --sampling-method euler_a`):
size 512×512 (s.d. 1024×1024), steps, CFG, sampler
(`euler_a` default; opsi: `euler`, `heun`, `dpm2`, `dpm++2m`, `lcm`, …),
seed (kosong = acak). Hasil PNG tersimpan di
`Android/data/com.llamacpp.local/files/images/` + bisa Share.
Model di-load sekali (mmap, hemat RAM) + progress per-step + Cancel.

Sideload model besar (disarankan, download HP sering timeout untuk file
GB-an):

```bash
adb push dreamshaper-xl-v2-turbo-Q4_K.gguf /sdcard/Download/
```

lalu di aplikasi tap **Pick file** (tersedia juga untuk model chat).
Pastikan ukuran file pas (Q4_K = 2.798.084.896 byte) sebelum Generate;
file terpotong menyebabkan `loadModel gagal`.

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
- `app/src/main/cpp/stablediff_jni.cpp` — JNI image gen: load context
  sekali (mmap), generate txt2img (prompt/negatif/size/steps/CFG/sampler/
  seed), progress callback per-step, cancel, tulis PNG (stb_image_write).
  Return: 0 ok, -1 gagal, -2 dibatalkan, -3 PNG gagal tulis.
- `StableDiffusionBridge.kt` + `ImageGenActivity.kt` — API Kotlin,
  layar Image Gen (download/pick model, parameter, hasil + share).
- `MainActivity / ModelsActivity / SettingsActivity / ImageGenActivity` + adapter.

## Troubleshooting

| Gejala | Penyebab umum |
|---|---|
| "Isi HF token di Settings dulu" | Token kosong / terhapus (uninstall menghapus data) |
| "Quant tak tersedia" / fallback | File quant tsb memang tak ada di repo; app otomatis coba quant lain |
| Download 0% / gagal | Koneksi putus di tengah (umum untuk file GB-an — lihat `HTTP_DATA_ERROR` di logcat); tap Get ulang untuk lanjut, atau sideload via `adb push` + **Pick file** |
| `loadModel gagal` (Image Gen) | File model belum lengkap (cek ukuran byte) atau RAM kurang — tutup aplikasi lain; SDXL-Q4_K butuh ~4 GB |
| Respons kosong | Model terlalu kecil untuk prompt / context penuh; coba Retry |
| Build native gagal | Pastikan NDK 28 + CMake 3.22 + internet (unduh llama.cpp) |

## Credit

- [@yudono](https://github.com/yudono) — pemilik & pengembang utama
- Thanks to [OpenCode](https://github.com/anomalyco/opencode) untuk membantu proses development

## License

MIT
