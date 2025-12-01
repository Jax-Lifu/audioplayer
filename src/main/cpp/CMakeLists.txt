cmake_minimum_required(VERSION 3.22.1)
project("audioplayer")

# ========= 全局优化配置 =========
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -march=armv8-a+simd+crypto+crc")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -march=armv8-a+simd+crypto+crc")

set(CMAKE_C_FLAGS_DEBUG "-O0 -g -march=armv8-a")
set(CMAKE_CXX_FLAGS_DEBUG "-O0 -g -march=armv8-a")
message(STATUS "BUILD TYPE = ${CMAKE_BUILD_TYPE}")

# ========= FFmpeg 库配置 =========
set(ffmpeg_binaries "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}")
foreach (ffmpeg_lib swscale swresample avutil avformat avfilter avdevice avcodec postproc ssl crypto chromaprint iconv charset)
    set(ffmpeg_lib_filename lib${ffmpeg_lib}.a)
    set(ffmpeg_lib_file_path ${ffmpeg_binaries}/${ffmpeg_lib_filename})
    add_library(${ffmpeg_lib} STATIC IMPORTED)
    set_target_properties(${ffmpeg_lib} PROPERTIES IMPORTED_LOCATION ${ffmpeg_lib_file_path})
    message("FFmpeg library: ${ffmpeg_lib} ${ffmpeg_lib_file_path}")  # 输出库路径
endforeach ()

# ========= 项目头文件配置 =========
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/include/chromaprint
        ${CMAKE_CURRENT_SOURCE_DIR}/player
        ${CMAKE_CURRENT_SOURCE_DIR}/utils
        ${CMAKE_CURRENT_SOURCE_DIR}/libcommon
        ${CMAKE_CURRENT_SOURCE_DIR}/libdstdec
        ${CMAKE_CURRENT_SOURCE_DIR}/libid3
        ${CMAKE_CURRENT_SOURCE_DIR}/libsacd

)

file(GLOB common_sources libcommon/*.c)
file(GLOB dstdec_sources libdstdec/*.c)
file(GLOB id3_sources libid3/*.c)
file(GLOB sacd_sources libsacd/*.c)
list(REMOVE_ITEM sacd_sources
        ${CMAKE_CURRENT_SOURCE_DIR}/libsacd/scarletbook_xml.c
)
# ========= 项目库配置 =========
add_library(${CMAKE_PROJECT_NAME} SHARED
        ${common_sources}
        ${dstdec_sources}
        ${id3_sources}
        ${sacd_sources}
        parser/AudioProbe.cpp
        player/FFPlayer.cpp
        player/SacdPlayer.cpp
        player/FFmpegD2pDecoder.cpp
        utils/DsdUtils.cpp
        jni_audioprobe.cpp
        jni_audioplayer.cpp
        jni_main.cpp)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
        -O3
        -funroll-loops
        -fstrict-aliasing
        -ffast-math
        -Wno-incompatible-pointer-types
        -fpermissive
)

# ========= 项目库链接配置 =========
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android
        log
        z
        swscale
        swresample
        avutil
        avformat
        avfilter
        avdevice
        avcodec
        postproc
        ssl
        crypto
        chromaprint
        iconv
        charset
)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-Bsymbolic")
endif ()

# Enable 16 KB ELF alignment.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_definitions(-DDEBUG)
endif ()