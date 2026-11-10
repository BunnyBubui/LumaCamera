cmake_minimum_required(VERSION 3.22.1)
project(luma_hdr LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenCV comes from the org.opencv:opencv Maven AAR through Prefab
# (android.buildFeatures.prefab = true). The AAR publishes one module,
# opencv_java4, built against c++_shared, so this library must use the same STL
# (set with -DANDROID_STL=c++_shared in app/build.gradle.kts). The package name
# follows the AAR's Gradle project name, which has been both "OpenCV" and
# "opencv" across releases, so accept either.
find_package(OpenCV CONFIG QUIET)
if(TARGET OpenCV::opencv_java4)
    set(LUMA_OPENCV OpenCV::opencv_java4)
else()
    find_package(opencv CONFIG REQUIRED)
    set(LUMA_OPENCV opencv::opencv_java4)
endif()

add_library(luma_hdr SHARED
    hdr_pipeline.cpp
    hdr_native_bridge.cpp)

target_compile_options(luma_hdr PRIVATE -Wall -Wextra -O3 -fvisibility=hidden)

# 16 KB page alignment for Android 15+ devices. NDK r28 does this by default;
# AGP 8.9 uses NDK r27, which needs the flag.
target_link_options(luma_hdr PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections")

target_link_libraries(luma_hdr
    ${LUMA_OPENCV}
    jnigraphics
    log)

# Pinned upstream source; no runtime download. Static, hidden symbols avoid collisions
# with the JPEG implementation inside OpenCV. Baseline scanlines bound encoder RAM.
include(FetchContent)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG OFF CACHE BOOL "" FORCE)
set(WITH_TOOLS OFF CACHE BOOL "" FORCE)
set(WITH_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_SIMD OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(luma_jpeg_source
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.3/libjpeg-turbo-3.1.3.tar.gz
    URL_HASH SHA256=075920b826834ac4ddf97661cc73491047855859affd671d52079c6867c1c6c0)
FetchContent_MakeAvailable(luma_jpeg_source)
add_library(luma_jpeg SHARED jpeg_strip_bridge.cpp)
target_include_directories(luma_jpeg PRIVATE ${luma_jpeg_source_SOURCE_DIR} ${luma_jpeg_source_BINARY_DIR})
target_compile_options(luma_jpeg PRIVATE -O3 -Wall -Wextra -fvisibility=hidden)
target_link_options(luma_jpeg PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
target_link_libraries(luma_jpeg jpeg-static)

