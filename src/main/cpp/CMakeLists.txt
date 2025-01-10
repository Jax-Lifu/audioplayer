
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("audioplayer")

#set(icu_path "${CMAKE_CURRENT_SOURCE_DIR}/icu/libicu.so")
#add_library(icu STATIC IMPORTED)
#set_target_properties(icu  PROPERTIES IMPORTED_LOCATION ${icu_path})

set(ffmpeg_location "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg")
set(ffmpeg_binaries "${ffmpeg_location}/android-libs/${ANDROID_ABI}")
foreach (ffmpeg_lib swscale swresample avutil avformat avfilter avdevice avcodec)
    set(ffmpeg_lib_filename lib${ffmpeg_lib}.a)
    set(ffmpeg_lib_file_path ${ffmpeg_binaries}/${ffmpeg_lib_filename})
    add_library(${ffmpeg_lib} STATIC IMPORTED)
    set_target_properties(${ffmpeg_lib} PROPERTIES IMPORTED_LOCATION ${ffmpeg_lib_file_path})
    message("FFmpeg library: ${ffmpeg_lib} ${ffmpeg_lib_file_path}")  # 输出库路径
endforeach ()

include_directories(${ffmpeg_location})

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        jni_ffprobe.cpp
        FFAudioPlayer.cpp
        jni_ffaudioplayer.cpp
        Utils.cpp
)


# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android
        z
        log
#        icu
        swscale
        swresample
        avutil
        avformat
        avfilter
        avdevice
        avcodec
)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-Bsymbolic")
endif ()

# Enable 16 KB ELF alignment.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
