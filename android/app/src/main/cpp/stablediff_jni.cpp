// stablediff_jni.cpp — JNI tipis di atas stable-diffusion.cpp (txt2img).
//
// Desain: context SD di-load SEKALI per model (handle). Tiap generate()
// BLOCKING di Dispatchers.IO, progres step dilaporkan via callback,
// hasil ditulis ke file PNG oleh native (stb_image_write) lalu Kotlin
// tinggal menampilkan + share. Pola cancel mengikuti LlamaBridge:
// flag atomik + sd_cancel_generation(SD_CANCEL_ALL).
//
// Return generate(): 0 = ok, -1 = gagal, -2 = dibatalkan, -3 = PNG gagal tulis.

#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <ctime>
#include <string>

#include "stable-diffusion.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#define TAG "stablediff-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct NativeSd {
    sd_ctx_t* ctx;
    std::atomic<bool> cancelled{false};
};

struct ProgressData {
    JavaVM* vm;
    jobject cb;  // global ref
    jmethodID mid;
};

static void progress_cb(int step, int steps, float /*time*/, void* data) {
    auto* d = static_cast<ProgressData*>(data);
    if (!d || !d->cb) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (d->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (d->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(d->cb, d->mid, step, steps);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) d->vm->DetachCurrentThread();
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_llamacpp_local_StableDiffusionBridge_loadContext(
    JNIEnv* env, jobject, jstring jpath, jint nThreads) {
    const char* raw = env->GetStringUTFChars(jpath, nullptr);
    std::string modelPath(raw ? raw : "");
    if (raw) env->ReleaseStringUTFChars(jpath, raw);
    if (modelPath.empty()) return 0;

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = modelPath.c_str();
    if (nThreads > 0) p.n_threads = static_cast<int>(nThreads);
    // Hemat RAM HP: mmap file GGUF (±2.6GB) alih-alih dibaca penuh ke RAM.
    p.enable_mmap = true;

    sd_ctx_t* ctx = new_sd_ctx(&p);
    if (!ctx) {
        LOGE("loadContext gagal: %s", modelPath.c_str());
        return 0;
    }
    if (!sd_ctx_supports_image_generation(ctx)) {
        LOGE("model bukan image model");
        free_sd_ctx(ctx);
        return 0;
    }
    auto* h = new NativeSd{ctx};
    LOGI("ctx ok: %s (%s)", modelPath.c_str(), sd_get_model_version_name(ctx));
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_llamacpp_local_StableDiffusionBridge_freeContext(JNIEnv*, jobject, jlong h) {
    auto* nh = reinterpret_cast<NativeSd*>(h);
    if (!nh) return;
    if (nh->ctx) free_sd_ctx(nh->ctx);
    delete nh;
}

JNIEXPORT void JNICALL
Java_com_llamacpp_local_StableDiffusionBridge_cancel(JNIEnv*, jobject, jlong h) {
    auto* nh = reinterpret_cast<NativeSd*>(h);
    if (!nh || !nh->ctx) return;
    nh->cancelled.store(true);
    sd_cancel_generation(nh->ctx, SD_CANCEL_ALL);
}

JNIEXPORT jint JNICALL
Java_com_llamacpp_local_StableDiffusionBridge_generate(
    JNIEnv* env, jobject, jlong h,
    jstring jprompt, jstring jnegative,
    jint jwidth, jint jheight, jint jsteps, jfloat jcfg,
    jstring jsampler, jlong jseed, jstring jout, jobject cb) {
    auto* nh = reinterpret_cast<NativeSd*>(h);
    if (!nh || !nh->ctx) return -1;

    const char* rp = jprompt ? env->GetStringUTFChars(jprompt, nullptr) : nullptr;
    const char* rn = jnegative ? env->GetStringUTFChars(jnegative, nullptr) : nullptr;
    const char* rs = jsampler ? env->GetStringUTFChars(jsampler, nullptr) : nullptr;
    const char* ro = jout ? env->GetStringUTFChars(jout, nullptr) : nullptr;
    std::string prompt(rp ? rp : "");
    std::string negative(rn ? rn : "");
    std::string sampler(rs ? rs : "");
    std::string outPath(ro ? ro : "");
    if (rp) env->ReleaseStringUTFChars(jprompt, rp);
    if (rn) env->ReleaseStringUTFChars(jnegative, rn);
    if (rs) env->ReleaseStringUTFChars(jsampler, rs);
    if (ro) env->ReleaseStringUTFChars(jout, ro);
    if (prompt.empty() || outPath.empty()) return -1;

    sd_img_gen_params_t gp;
    sd_img_gen_params_init(&gp);
    gp.prompt = prompt.c_str();
    gp.negative_prompt = negative.c_str();

    int w = (static_cast<int>(jwidth) / 8) * 8;
    int hh = (static_cast<int>(jheight) / 8) * 8;
    if (w < 256) w = 256;
    if (hh < 256) hh = 256;
    if (w > 2048) w = 2048;
    if (hh > 2048) hh = 2048;
    gp.width = w;
    gp.height = hh;

    int steps = static_cast<int>(jsteps);
    if (steps < 1) steps = 1;
    if (steps > 150) steps = 150;
    gp.sample_params.sample_steps = steps;
    gp.sample_params.guidance.txt_cfg = jcfg > 0.0f ? jcfg : 1.0f;

    sample_method_t method = str_to_sample_method(sampler.c_str());
    if (method == SAMPLE_METHOD_COUNT) {
        method = sd_get_default_sample_method(nh->ctx);
    }
    gp.sample_params.sample_method = method;

    int64_t seed = static_cast<int64_t>(jseed);
    if (seed < 0) {
        seed = static_cast<int64_t>(time(nullptr)) * 1000LL +
               static_cast<int64_t>(clock());
    }
    gp.seed = seed;
    LOGI("generate: %dx%d steps=%d cfg=%.1f sampler=%s seed=%lld",
         w, hh, steps, (double)gp.sample_params.guidance.txt_cfg,
         sd_sample_method_name(method), (long long)seed);

    ProgressData pd{nullptr, nullptr, nullptr};
    jobject cbRef = nullptr;
    if (cb) {
        jmethodID mid =
            env->GetMethodID(env->GetObjectClass(cb), "onProgress", "(II)V");
        if (mid) {
            env->GetJavaVM(&pd.vm);
            cbRef = env->NewGlobalRef(cb);
            pd.cb = cbRef;
            pd.mid = mid;
            sd_set_progress_callback(progress_cb, &pd);
        }
    }

    nh->cancelled.store(false);
    sd_image_t* images = nullptr;
    int nImages = 0;
    bool ok = generate_image(nh->ctx, &gp, &images, &nImages);

    sd_set_progress_callback(nullptr, nullptr);
    if (cbRef) env->DeleteGlobalRef(cbRef);

    if (!ok || !images || nImages <= 0) {
        if (images) free_sd_images(images, nImages > 0 ? nImages : 0);
        LOGI("generate %s", nh->cancelled.load() ? "dibatalkan" : "gagal");
        return nh->cancelled.load() ? -2 : -1;
    }
    const sd_image_t& img = images[0];
    int wrote = stbi_write_png(outPath.c_str(), static_cast<int>(img.width),
                               static_cast<int>(img.height),
                               static_cast<int>(img.channel), img.data,
                               static_cast<int>(img.width * img.channel));
    free_sd_images(images, nImages);
    if (!wrote) {
        LOGE("tulis PNG gagal: %s", outPath.c_str());
        return -3;
    }
    LOGI("generate ok -> %s", outPath.c_str());
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_llamacpp_local_StableDiffusionBridge_version(JNIEnv* env, jobject) {
    return env->NewStringUTF(sd_version());
}

JNIEXPORT jstring JNICALL
Java_com_llamacpp_local_StableDiffusionBridge_modelVersion(JNIEnv* env, jobject, jlong h) {
    auto* nh = reinterpret_cast<NativeSd*>(h);
    if (!nh || !nh->ctx) return env->NewStringUTF("Unknown");
    return env->NewStringUTF(sd_get_model_version_name(nh->ctx));
}

}  // extern "C"
