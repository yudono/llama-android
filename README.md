# Llama.cpp — Local AI Inference (Android)

Aplikasi chat AI lokal untuk Android, **100% Kotlin + XML** (standar Android),
UI dark terinspirasi aplikasi chat modern dengan identitas sendiri
(logo "L" + aksen teal).

## Fitur

- Chat bubbles (user kanan, AI kiri + avatar logo)
- Kartu saran saat chat kosong
- Drawer kiri: daftar model + filter kategori + status download
- Input bar + keyboard sistem (adjustResize)
- Dark theme, safe area & font standar Android (tidak ada masalah skala)
- Dummy inference (siap disambung llama.cpp via JNI)

## Prasyarat

- JDK 17, Android SDK (platform 34), NDK (tidak wajib untuk build debug saat ini)
- Gradle wrapper sudah termasuk (`android/gradlew`)

## Build & Install

```bash
./run.sh
# atau manual:
./android/gradlew -p android assembleDebug
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

## Struktur

```
android/
├── gradlew, gradle/            # Gradle wrapper 8.7 (AGP 8.5.2, Kotlin 1.9.24)
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts        # appId com.llamacpp.local
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/llamacpp/local/  # MainActivity, adapters, Data
        └── res/                      # layout, drawable, mipmap, values
logo.png                            # logo L (launcher + in-app)
build.sh / run.sh                   # build & install ke device
```

## Model (dummy, siap download HF)

Qwen2.5-0.5B/1.5B/Coder-1.5B, Llama-3.2-1B/3B, Phi-3.5-mini.

## License

MIT
