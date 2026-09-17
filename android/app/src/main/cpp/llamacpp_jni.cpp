// llamacpp_jni.cpp — JNI tipis di atas llama.cpp (pin b10893).
//
// Desain session: model di-load SEKALI (handle). Tiap generate() membuat
// context window FRESH (KV kosong) lalu prompt dibangun dari riwayat
// SQLite conversation aktif -> tiap conversation = session terisolasi.
// Generate BLOCKING + STREAMING token via callback (jalan di Dispatchers.IO).

#include <jni.h>
#include <string>
#include <vector>
#include <random>
#include <algorithm>
#include <atomic>
#include <android/log.h>
#include "llama.h"

#define TAG "llamacpp-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Minta generate berhenti (tombol Stop). Dicek tiap token.
static std::atomic<bool> g_abort{false};

struct NativeModel {
    llama_model* model;
    int n_ctx;
};

jint JNI_OnLoad(JavaVM*, void*) {
    llama_backend_init();
    return JNI_VERSION_1_6;
}

// Prefix valid terpanjang agar sequence UTF-8 multi-byte tak terbelah antar token.
static size_t utf8_valid_prefix(const char* s, size_t n) {
    size_t i = 0, ok = 0;
    while (i < n) {
        unsigned char c = (unsigned char) s[i];
        size_t need = c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
        if (i + need > n) break;
        bool valid = true;
        for (size_t k = 1; k < need; k++)
            if (((unsigned char) s[i + k] & 0xC0) != 0x80) { valid = false; break; }
        if (!valid) break;
        i += need;
        ok = i;
    }
    return ok;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_llamacpp_local_LlamaBridge_loadModel(JNIEnv* env, jobject, jstring jpath, jint nCtx) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP; // eksplisit: map file GGUF, bukan baca penuh (hemat RAM)
    llama_model* m = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!m) { LOGE("loadModel gagal"); return 0; }
    auto* h = new NativeModel{m, (int) nCtx};
    LOGI("model ok, n_ctx=%d", (int) nCtx);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_llamacpp_local_LlamaBridge_unloadModel(JNIEnv*, jobject, jlong h) {
    auto* nm = reinterpret_cast<NativeModel*>(h);
    if (!nm) return;
    llama_model_free(nm->model);
    delete nm;
}

JNIEXPORT jstring JNICALL
Java_com_llamacpp_local_LlamaBridge_systemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT void JNICALL
Java_com_llamacpp_local_LlamaBridge_cancel(JNIEnv*, jobject) {
    g_abort.store(true);
}

JNIEXPORT jint JNICALL
Java_com_llamacpp_local_LlamaBridge_generate(
        JNIEnv* env, jobject, jlong h,
        jobjectArray jroles, jobjectArray jtexts,
        jint maxTokens, jfloat temperature, jint topK, jfloat topP, jobject cb) {
    auto* nm = reinterpret_cast<NativeModel*>(h);
    if (!nm || !nm->model) return -1;
    llama_model* model = nm->model;
    const llama_vocab* vocab = llama_model_get_vocab(model);

    jmethodID onToken = env->GetMethodID(env->GetObjectClass(cb), "onToken", "(Ljava/lang/String;)V");
    if (!onToken) return -1;
    auto emit = [&](const std::string& s) {
        if (s.empty()) return;
        jstring js = env->NewStringUTF(s.c_str());
        env->CallVoidMethod(cb, onToken, js);
        env->DeleteLocalRef(js);
    };

    // 1. Pesan -> template chat bawaan model (jinja dari metadata GGUF)
    jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roleS(n), textS(n);
    std::vector<llama_chat_message> msgs((size_t) n);
    for (jsize i = 0; i < n; i++) {
        jstring jr = (jstring) env->GetObjectArrayElement(jroles, i);
        jstring jt = (jstring) env->GetObjectArrayElement(jtexts, i);
        const char* r = env->GetStringUTFChars(jr, nullptr);
        const char* t = env->GetStringUTFChars(jt, nullptr);
        roleS[i] = r; textS[i] = t;
        env->ReleaseStringUTFChars(jr, r);
        env->ReleaseStringUTFChars(jt, t);
        env->DeleteLocalRef(jr); env->DeleteLocalRef(jt);
        msgs[i] = {roleS[i].c_str(), textS[i].c_str()};
    }
    // 1b. AUTO-SHRINK: prompt harus muat di context window (n_ctx -
    // maxTokens). Buang exchange tertua (user+assistant) satu per satu,
    // system prompt + pesan terbaru selalu dipertahankan.
    int32_t room0 = nm->n_ctx - (int32_t) maxTokens - 4;
    if (room0 < 16) room0 = 16;
    std::string prompt;
    std::vector<llama_token> toks;
    for (;;) {
        int32_t need = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(), true, nullptr, 0);
        if (need <= 0) { LOGE("apply_template gagal"); return -1; }
        prompt.assign((size_t) need, '\0');
        int32_t got = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(), true, prompt.data(), need);
        if (got <= 0) { LOGE("apply_template tulis gagal"); return -1; }
        prompt.resize((size_t) got);

        // 2. Tokenisasi (parse_special: token spesial template dikenali)
        toks.assign(prompt.size() + 32, 0);
        int32_t nTok = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                                      toks.data(), (int32_t) toks.size(), false, true);
        if (nTok < 0) { LOGE("tokenize gagal"); return -1; }
        toks.resize((size_t) nTok);

        if ((int32_t) toks.size() <= room0 || msgs.size() <= 2) break;
        msgs.erase(msgs.begin() + 1); // user tertua (setelah system)
        if (msgs.size() > 1) msgs.erase(msgs.begin() + 1); // jawabannya
        LOGI("shrink: prompt kepanjangan, buang turn tertua (%zu pesan tersisa)", msgs.size());
    }

    // 3. Pengaman terakhir: potong depan bila tetap kepanjangan.
    int32_t room = room0;
    if ((int32_t) toks.size() > room) toks.erase(toks.begin(), toks.end() - room);
    LOGI("prompt final: %d token dari %zu pesan (n_ctx=%d)", (int) toks.size(), msgs.size(), nm->n_ctx);

    const int32_t nBatch = 512;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nm->n_ctx;
    cp.n_batch = (uint32_t) nBatch;
    cp.n_threads = 4;
    cp.no_perf = true;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("init ctx gagal"); return -1; }

    std::mt19937 rng(1234u);
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (topK > 1) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    float p = topP <= 0.0f ? 0.95f : (topP > 1.0f ? 1.0f : topP);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(rng()));

    auto decode = [&](llama_token* t, int32_t k) {
        for (int32_t off = 0; off < k;) {
            int32_t cur = std::min(nBatch, k - off);
            if (llama_decode(ctx, llama_batch_get_one(t + off, cur)) != 0) return false;
            off += cur;
        }
        return true;
    };
    int32_t done = -1;
    g_abort.store(false);
    if (!decode(toks.data(), (int32_t) toks.size())) {
        LOGE("decode prompt gagal");
    } else {
        done = 0;
        std::string pending;
        char piece[64];
        while (done < maxTokens && !g_abort.load()) {
            llama_token id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, id);
            if (llama_vocab_is_eog(vocab, id)) break;
            int32_t np = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, false);
            if (np > 0) {
                pending.append(piece, (size_t) np);
                size_t ok = utf8_valid_prefix(pending.data(), pending.size());
                if (ok > 0) { emit(pending.substr(0, ok)); pending.erase(0, ok); }
            }
            if (!decode(&id, 1)) break;
            done++;
        }
        if (!pending.empty()) emit(pending);
    }
    llama_sampler_free(smpl);
    llama_free(ctx);
    LOGI("generate selesai: %d token", (int) done);
    return done;
}

} // extern "C"
