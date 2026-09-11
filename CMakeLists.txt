cmake_minimum_required(VERSION 3.22.1)
project(questinput)

# ---------------------------------------------------------------------------
# OpenXR headers
# ---------------------------------------------------------------------------
# Copy the SDK's header folder into your project so this path is portable
# (doesn't depend on your Downloads folder / another dev's machine):
#
#   From: C:\Users\Admin\Downloads\ovr_openxr_mobile_sdk_85.0\OpenXR\meta_openxr_preview\include
#   To:   app/src/main/cpp/third_party/openxr/include
#
# That gives this layout:
#   app/src/main/cpp/CMakeLists.txt          <- this file
#   app/src/main/cpp/xr_input.cpp
#   app/src/main/cpp/third_party/openxr/include/openxr/openxr.h  etc.
set(OPENXR_HEADERS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/openxr/include)

# ---------------------------------------------------------------------------
# android_native_app_glue (ships with the NDK, not the OpenXR SDK)
# ---------------------------------------------------------------------------
# ANDROID_NDK is set automatically by the Android Gradle Plugin's CMake
# toolchain file, so this resolves without you hardcoding an NDK path.
set(NATIVE_APP_GLUE_DIR ${ANDROID_NDK}/sources/android/native_app_glue)

add_library(questinput SHARED
    xr_input.cpp
    ${NATIVE_APP_GLUE_DIR}/android_native_app_glue.c
)

target_include_directories(questinput public
    ${NATIVE_APP_GLUE_DIR}
    ${OPENXR_HEADERS_DIR}
)

# Without this, the linker strips ANativeActivity_onCreate since nothing
# in native code appears to call it directly (android_native_app_glue relies
# on the dynamic loader finding it by symbol name).
target_link_options(questinput public "-u" "ANativeActivity_onCreate")

# ---------------------------------------------------------------------------
# Khronos OpenXR loader, pulled in via the Gradle dependency + prefab
# (org.khronos.openxr:openxr_loader_for_android:1.0.34 in app/build.gradle)
# ---------------------------------------------------------------------------
find_package(openxr_loader REQUIRED CONFIG)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)

target_link_libraries(questinput
    openxr_loader::openxr_loader
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles3-lib}
)