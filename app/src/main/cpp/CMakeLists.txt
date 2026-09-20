# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")

# Declares and names the project.
project(jamesdsp LANGUAGES C)
project(jamesdsp-wrapper LANGUAGES CXX)

include(libcrashlytics-connector/CMakeLists.txt)
include(libjdspimptoolbox/CMakeLists.txt)
include(libjamesdsp-wrapper/CMakeLists.txt)

file(GLOB_RECURSE LIBJAMESDSP_SOURCE_FILES CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.c
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.h)

add_library( # Sets the name of the library.
        jamesdsp

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        ${LIBJAMESDSP_SOURCE_FILES}
        EELStdOutExtension.c
        EELStdOutExtension.h)

target_compile_options(jamesdsp PRIVATE -O2)
target_compile_options(jamesdsp PRIVATE -std=gnu11)
target_include_directories(jamesdsp PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/)

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

target_link_libraries( # Specifies the target library.
        jamesdsp

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib})

# ================================================================
# System audio effect library (libjamesdsp.so for Magisk module)
# Includes jamesdsp.c (AudioEffect entry point) + peq_loudness.c
# This is the library deployed to the device's Magisk module.
# ================================================================

set(JAMESDSP_EFFECT_DIR ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp)

add_library(jamesdsp_effect SHARED
        ${JAMESDSP_EFFECT_DIR}/jamesdsp.c
        ${JAMESDSP_EFFECT_DIR}/peq_loudness.c
        ${JAMESDSP_EFFECT_DIR}/peq_loudness.h
        ${LIBJAMESDSP_SOURCE_FILES}
        EELStdOutExtension.c
        EELStdOutExtension.h)

target_compile_options(jamesdsp_effect PRIVATE -O2 -std=gnu11 -ffunction-sections -fdata-sections -DJAMESDSP_REFERENCE_IMPL -Wno-incompatible-pointer-types -Wno-implicit-int -Wno-implicit-function-declaration)
target_include_directories(jamesdsp_effect PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/
        ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/)
target_link_libraries(jamesdsp_effect ${log-lib})
# Output as libjamesdsp_effect.so to avoid conflict with core jamesdsp target.
# Rename to libjamesdsp.so when deploying to Magisk module.

# ================================================================
# Measurement engine (libmeasurement.so)
# C/NDK measurement pipeline: sweep generation, Farina deconvolution,
# IR windowing, SPL response computation. Uses kissfft (in-repo).
# ================================================================

# kissfft source files (already in repository)
set(KISSFFT_DIR ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/VeryOldOpen_source_edition/DSPManager_Free_BitPerfect/jni/main/kissfft)

add_library(measurement SHARED
        measurement/sweep_generator.c
        measurement/sweep_generator.h
        measurement/farina_deconv.c
        measurement/farina_deconv.h
        measurement/ir_windowing.c
        measurement/ir_windowing.h
        measurement/spl_response.c
        measurement/spl_response.h
        measurement/measurement_jni.c
        ${KISSFFT_DIR}/kiss_fft.c
        ${KISSFFT_DIR}/kiss_fftr.c)

target_compile_options(measurement PRIVATE -O2 -std=gnu11 -ffunction-sections -fdata-sections)
target_include_directories(measurement PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/measurement/
        ${KISSFFT_DIR})
target_link_libraries(measurement ${log-lib})
