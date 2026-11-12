cmake_minimum_required(VERSION 3.22.1)
project(luma_hdr LANGUAGES CXX)

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
