# Desktop build of the native HDR pipeline test (not part of the Android build).
cmake_minimum_required(VERSION 3.16)
project(luma_hdr_test LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(OpenCV REQUIRED COMPONENTS core imgproc photo)
add_executable(hdr_pipeline_test
    hdr_pipeline_test.cpp
    ../../main/cpp/hdr_pipeline.cpp)
target_include_directories(hdr_pipeline_test PRIVATE ../../main/cpp ${OpenCV_INCLUDE_DIRS})
target_compile_options(hdr_pipeline_test PRIVATE -Wall -Wextra -O2)
target_link_libraries(hdr_pipeline_test ${OpenCV_LIBS})
