// Deklarasi shim utk 2 op INT8 eksklusif ggml fork leejet.
// Di-include paksa (-include) saat mengkompilasi target stable-diffusion
// karena ggml.h upstream tak mendeklarasikannya. Definisi ada di
// sd_ggml_compat.cpp (di-link ke stablediff_jni).
#pragma once

#include "ggml.h"

// 2 tipe F8 (E4M3=43, E5M2=44) hanya ada di ggml fork leejet; ggml.h
// upstream tak mendefinisikannya. Sediakan sbg macro cast agar situs
// pemakaian SD (perbandingan tipe / mapping dtype, khusus model FP8)
// tetap kompilasi. Nilai cocok dgn SD_TYPE_* di stable-diffusion.h.
#define GGML_TYPE_F8_E4M3 ((ggml_type)43)
#define GGML_TYPE_F8_E5M2 ((ggml_type)44)

#ifdef __cplusplus
extern "C" {
#endif

GGML_API struct ggml_tensor* sd_compat_mul_mat_i8_tensorwise(
    struct ggml_context* ctx,
    struct ggml_tensor* weight,
    struct ggml_tensor* input,
    struct ggml_tensor* weight_scale,
    struct ggml_tensor* bias,
    int convrot_group_size);

GGML_API struct ggml_tensor* sd_compat_quantize_i8_convrot(
    struct ggml_context* ctx,
    struct ggml_tensor* a,
    int group_size);

#ifdef __cplusplus
}
#endif
