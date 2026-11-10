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
# libjpeg-turbo refuses add_subdirectory(), so FetchContent only downloads the
# source and ExternalProject builds it as its own project with the same NDK
# toolchain, ABI and platform as this build.
include(FetchContent)
include(ExternalProject)
FetchContent_Declare(luma_jpeg_source
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.3/libjpeg-turbo-3.1.3.tar.gz
    URL_HASH SHA256=075920b826834ac4ddf97661cc73491047855859affd671d52079c6867c1c6c0)
FetchContent_GetProperties(luma_jpeg_source)
if(NOT luma_jpeg_source_POPULATED)
    FetchContent_Populate(luma_jpeg_source)
endif()
set(LUMA_JPEG_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/luma-jpeg)
set(LUMA_JPEG_LIB ${LUMA_JPEG_PREFIX}/lib/${CMAKE_STATIC_LIBRARY_PREFIX}jpeg${CMAKE_STATIC_LIBRARY_SUFFIX})
set(LUMA_JPEG_ARGS
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_PREFIX=${LUMA_JPEG_PREFIX}
    -DCMAKE_INSTALL_LIBDIR=lib
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DENABLE_SHARED=OFF
    -DENABLE_STATIC=ON
    -DWITH_TURBOJPEG=OFF
    -DWITH_TOOLS=OFF
    -DWITH_TESTS=OFF
    -DWITH_SIMD=OFF)
if(CMAKE_TOOLCHAIN_FILE)
    list(APPEND LUMA_JPEG_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM})
endif()
if(CMAKE_MAKE_PROGRAM)
    list(APPEND LUMA_JPEG_ARGS -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM})
endif()
ExternalProject_Add(luma_jpeg_build
    SOURCE_DIR ${luma_jpeg_source_SOURCE_DIR}
    BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/luma-jpeg-build
    CMAKE_ARGS ${LUMA_JPEG_ARGS}
    BUILD_BYPRODUCTS ${LUMA_JPEG_LIB}
    UPDATE_COMMAND ""
    PATCH_COMMAND "")
add_library(luma_jpeg_static STATIC IMPORTED)
set_target_properties(luma_jpeg_static PROPERTIES IMPORTED_LOCATION ${LUMA_JPEG_LIB})
add_dependencies(luma_jpeg_static luma_jpeg_build)

add_library(luma_jpeg SHARED jpeg_strip_bridge.cpp)
add_dependencies(luma_jpeg luma_jpeg_build)
target_include_directories(luma_jpeg PRIVATE ${LUMA_JPEG_PREFIX}/include)
target_compile_options(luma_jpeg PRIVATE -O3 -Wall -Wextra -fvisibility=hidden)
target_link_options(luma_jpeg PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
target_link_libraries(luma_jpeg luma_jpeg_static)
