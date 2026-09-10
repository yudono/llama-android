cmake_minimum_required(VERSION 3.22)
project(llamacpp_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Ambil llama.cpp upstream (pin tag stabil), tanpa contoh/server/test.
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

add_library(llamacpp_jni SHARED llamacpp_jni.cpp)
target_link_libraries(llamacpp_jni PRIVATE llama)
find_library(LOG_LIB log)
target_link_libraries(llamacpp_jni PRIVATE ${LOG_LIB})
