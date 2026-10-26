cmake_minimum_required(VERSION 3.22)
project(marginalia_whisper C CXX)

# whisper.cpp (MIT licence) is fetched by the build into ./whisper.cpp at a pinned release
# (see .github/workflows/release.yml); it is not kept in the repository.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(marginalia_whisper SHARED whisper_jni.cpp)
target_link_libraries(marginalia_whisper PRIVATE whisper)
if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(marginalia_whisper PRIVATE ${LOG_LIB})
    target_compile_options(marginalia_whisper PRIVATE -O3)
    target_link_options(marginalia_whisper PRIVATE -Wl,--gc-sections)
endif ()
