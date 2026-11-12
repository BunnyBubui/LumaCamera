# Desktop test ของ native HDR pipeline

เทสต์นี้ตรวจ `app/src/main/cpp/hdr_pipeline.cpp` บนเครื่อง Linux โดยไม่ต้องมี Android SDK
Gradle ไม่รันเทสต์นี้ แต่ workflow "Android APK" รันให้ในงาน `native-hdr` ด้วย OpenCV ของ Ubuntu

```sh
sudo apt-get install -y cmake g++ libopencv-dev
cmake -S app/src/test/cpp -B build/hdr-test
cmake --build build/hdr-test
./build/hdr-test/hdr_pipeline_test
```

ถ้าใช้ OpenCV ที่ build เอง ให้ส่ง `-DOpenCV_DIR=<prefix>/lib/cmake/opencv4` ตอน configure
