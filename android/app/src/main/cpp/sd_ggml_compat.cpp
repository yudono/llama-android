// Kompat INT8 utk build stable-diffusion.cpp di atas ggml upstream.
//
// Latar: 2 op (ggml_mul_mat_i8_tensorwise, ggml_quantize_i8_convrot) hanya
// ada di ggml fork leejet ("native INT8 convrot support"), tak ada di ggml
// upstream yg dipakai target `ggml` di build ini. Panggilan SD dialihkan
// ke sini via -D di CMakeLists (lihat sd_ggml_compat_decl.h).
//
// Aman utk katalog app: kedua fungsi hanya dieksekusi model ber-weight I8
// (ggml_block.hpp mensyaratkan w->type == I8; model app Q4_K/Q8_0). Jalur
// non-convrot diimplementasi eksak dlm F32; jalur convrot abort eksplisit.

#include <android/log.h>

#include <cstdlib>

#include "ggml.h"

#define TAG "sd-compat"
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, TAG, __VA_ARGS__)

[[noreturn]] static void unsupported(const char* what) {
    LOGF("tak didukung build ini (butuh ggml fork leejet): %s", what);
    abort();
}

extern "C" {

struct ggml_tensor* sd_compat_mul_mat_i8_tensorwise(
    struct ggml_context* ctx,
    struct ggml_tensor* weight,
    struct ggml_tensor* input,
    struct ggml_tensor* weight_scale,
    struct ggml_tensor* bias,
    int convrot_group_size) {
    // Non-convrot: SD sudah meng-cast aktivasi ke F32 sebelum memanggil.
    if (convrot_group_size != 0 || input->type != GGML_TYPE_F32) {
        unsupported("ggml_mul_mat_i8_tensorwise convrot");
    }
    // out[n,m] = sum_k ((float)w[k,n] * scale[n]) * x[k,m] + bias[n]
    struct ggml_tensor* w_f32 = ggml_cast(ctx, weight, GGML_TYPE_F32);
    struct ggml_tensor* s = ggml_reshape_2d(ctx, weight_scale, 1, weight->ne[1]);
    struct ggml_tensor* w_scaled = ggml_mul(ctx, w_f32, ggml_repeat(ctx, s, w_f32));
    w_scaled = ggml_cont(ctx, w_scaled);
    struct ggml_tensor* out = ggml_mul_mat(ctx, w_scaled, input);
    if (bias != nullptr) {
        out = ggml_add(ctx, out, ggml_repeat(ctx, bias, out));
    }
    return out;
}

struct ggml_tensor* sd_compat_quantize_i8_convrot(
    struct ggml_context* ctx,
    struct ggml_tensor* a,
    int group_size) {
    (void)ctx;
    (void)a;
    (void)group_size;
    // Hanya dipanggil saat load model I8-convrot (tak ada di katalog app).
    unsupported("ggml_quantize_i8_convrot");
    return nullptr;
}

}  // extern "C"
