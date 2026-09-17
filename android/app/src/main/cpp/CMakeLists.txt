cmake_minimum_required(VERSION 3.22)
project(llamacpp_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ABI konsisten utk SEMUA target native: stable-diffusion.cpp memakai
# GGML_MAX_NAME=160 di build standalone-nya (ukuran char name[] di struct
# ggml_tensor). Bila ggml dikompilasi dgn nilai beda dari consumer-nya,
# layout struct beda -> memory corrupt. Define di top-level agar ggml,
# llama, dan SD semuanya pakai nilai sama.
add_definitions(-DGGML_MAX_NAME=160)

# Urutan add_subdirectory: llama.cpp DULUAN agar target `ggml` adalah ggml
# upstream (llama b10893 butuh API ggml baru yg tak ada di fork leejet).
# 2 op INT8 eksklusif fork leejet yg dipakai SD (ggml_mul_mat_i8_tensorwise,
# ggml_quantize_i8_convrot) dialihkan ke implementasi kompat lokal, lihat
# sd_ggml_compat.{h,cpp}. Aman: hanya dieksekusi model ber-weight I8, tak
# ada di katalog app (DreamShaper Q4_K/Q8_0).

# --- llama.cpp upstream (pin tag stabil), tanpa contoh/server/test ---
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Sumber llama.cpp: pakai clone lokal (third_party) bila ada,
# kalau tidak unduh saat build (butuh internet sekali saja).
set(LLAMA_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp")
if(EXISTS "${LLAMA_SRC_DIR}/include/llama.h")
    message(STATUS "llama.cpp: pakai clone lokal ${LLAMA_SRC_DIR}")
    add_subdirectory(${LLAMA_SRC_DIR} llama_build)
else()
    message(STATUS "llama.cpp: clone lokal tak ada, FetchContent dari ggml-org")
    include(FetchContent)
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
        GIT_TAG b10893
        GIT_SHALLOW TRUE
        GIT_PROGRESS TRUE
    )
    FetchContent_MakeAvailable(llama)
endif()

# --- stable-diffusion.cpp (image generation, pin commit stabil) ---
# Memakai ulang target `ggml` milik llama di atas (SD guard
# `if (NOT TARGET ggml)` sehingga tak ada tabrakan).
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/stable-diffusion.cpp")
if(EXISTS "${SD_SRC_DIR}/include/stable-diffusion.h")
    message(STATUS "stable-diffusion: pakai clone lokal ${SD_SRC_DIR}")
    add_subdirectory(${SD_SRC_DIR} sd_build)
else()
    message(STATUS "stable-diffusion: clone lokal tak ada, FetchContent dari leejet")
    include(FetchContent)
    FetchContent_Declare(
        sdcpp
        GIT_REPOSITORY https://github.com/leejet/stable-diffusion.cpp
        GIT_TAG cc515a01f9d0e3f6b975234cc934b807f55bcd35
        GIT_PROGRESS TRUE
        GIT_SUBMODULES ggml
    )
    FetchContent_MakeAvailable(sdcpp)
    set(SD_SRC_DIR "${sdcpp_SOURCE_DIR}")
endif()

# Pengalihan simbol fork-leejet yg tak ada di ggml upstream (khusus target SD):
# - 2 fungsi INT8 -> shim kompat (definisi di sd_ggml_compat.cpp).
# - 2 nilai enum tipe F8 -> macro cast di sd_ggml_compat_decl.h (via -include
#   di bawah; tak bisa lewat -D karena tanda kurung merusak shell ninja).
# Aman utk katalog app (Q4_K/Q8_0): semua path tsb dead-code saat runtime.
target_compile_definitions(stable-diffusion PRIVATE
    ggml_mul_mat_i8_tensorwise=sd_compat_mul_mat_i8_tensorwise
    ggml_quantize_i8_convrot=sd_compat_quantize_i8_convrot)
target_compile_options(stable-diffusion PRIVATE
    -include ${CMAKE_CURRENT_SOURCE_DIR}/sd_ggml_compat_decl.h)

add_library(llamacpp_jni SHARED llamacpp_jni.cpp)
target_link_libraries(llamacpp_jni PRIVATE llama)
find_library(LOG_LIB log)
target_link_libraries(llamacpp_jni PRIVATE ${LOG_LIB})

add_library(stablediff_jni SHARED stablediff_jni.cpp sd_ggml_compat.cpp)
target_include_directories(stablediff_jni PRIVATE
    ${SD_SRC_DIR}/include
    ${SD_SRC_DIR}/thirdparty)
target_link_libraries(stablediff_jni PRIVATE stable-diffusion ${LOG_LIB})
