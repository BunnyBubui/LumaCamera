#include <jni.h>
#include <cstdio>
#include <csetjmp>
#include <vector>
#include <new>
#include <jpeglib.h>

namespace {
struct Error { jpeg_error_mgr base; jmp_buf jump; char message[JMSG_LENGTH_MAX]; };
struct Writer {
    jpeg_compress_struct jpeg{};
    Error error{};
    FILE* file = nullptr;
    std::vector<unsigned char> row;
    std::vector<jint> argb;
    bool created = false;
    bool finished = false;
    ~Writer() {
        if (created) jpeg_destroy_compress(&jpeg);
        if (file) fclose(file);
    }
};
void fail(j_common_ptr info) {
    auto* e = reinterpret_cast<Error*>(info->err);
    (*info->err->format_message)(info, e->message);
    longjmp(e->jump, 1);
}
void exception(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
Writer* writer(JNIEnv* env, jlong handle) {
    auto* w = reinterpret_cast<Writer*>(handle);
    if (!w) exception(env, "JPEG writer is closed");
    return w;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_th_ogay_luma_ai_StripJpeg_start(JNIEnv* env, jobject, jstring path, jint width, jint height, jint quality) {
    if (!path || width < 1 || height < 1 || width > 32768 || height > 32768 ||
        static_cast<long long>(width)*height > 220000000 || quality < 1 || quality > 100) {
        exception(env, "Unsupported JPEG dimensions or quality"); return 0;
    }
    auto* w = new(std::nothrow) Writer();
    if (!w) { exception(env, "JPEG allocation failed"); return 0; }
    const char* name = env->GetStringUTFChars(path, nullptr);
    if (!name) { delete w; return 0; }
    w->file = fopen(name, "wb");
    env->ReleaseStringUTFChars(path, name);
    if (!w->file) { delete w; exception(env, "Cannot create JPEG"); return 0; }
    try { w->row.resize(static_cast<size_t>(width)*3); w->argb.resize(width); }
    catch (const std::bad_alloc&) { delete w; exception(env, "JPEG allocation failed"); return 0; }
    w->jpeg.err = jpeg_std_error(&w->error.base);
    w->error.base.error_exit = fail;
    if (setjmp(w->error.jump)) {
        char message[JMSG_LENGTH_MAX]; snprintf(message, sizeof(message), "%s", w->error.message);
        delete w; exception(env, message); return 0;
    }
    jpeg_create_compress(&w->jpeg); w->created = true;
    jpeg_stdio_dest(&w->jpeg, w->file);
    w->jpeg.image_width = width; w->jpeg.image_height = height;
    w->jpeg.input_components = 3; w->jpeg.in_color_space = JCS_RGB;
    jpeg_set_defaults(&w->jpeg);
    // Full-resolution chroma matters for fine lettering and coloured fabric.
    for (int c = 0; c < 3; ++c) {
        w->jpeg.comp_info[c].h_samp_factor = 1;
        w->jpeg.comp_info[c].v_samp_factor = 1;
    }
    jpeg_set_quality(&w->jpeg, quality, TRUE);
    w->jpeg.optimize_coding = FALSE; // A second pass could retain the whole image.
    jpeg_start_compress(&w->jpeg, TRUE);
    return reinterpret_cast<jlong>(w);
}

extern "C" JNIEXPORT void JNICALL
Java_th_ogay_luma_ai_StripJpeg_write(JNIEnv* env, jobject, jlong handle, jintArray pixels, jint rows) {
    auto* w = writer(env, handle); if (!w) return;
    const auto width = w->jpeg.image_width;
    if (w->finished || !pixels || rows < 1 ||
        static_cast<unsigned long long>(rows)*width > static_cast<unsigned>(env->GetArrayLength(pixels)) ||
        w->jpeg.next_scanline + rows > w->jpeg.image_height) {
        exception(env, "Invalid JPEG strip"); return;
    }
    if (setjmp(w->error.jump)) { exception(env, w->error.message); return; }
    for (int y = 0; y < rows; ++y) {
        env->GetIntArrayRegion(pixels, y*width, width, w->argb.data());
        if (env->ExceptionCheck()) return;
        for (unsigned x = 0; x < width; ++x) {
            const auto c = static_cast<unsigned>(w->argb[x]);
            w->row[x*3] = (c >> 16) & 255;
            w->row[x*3+1] = (c >> 8) & 255;
            w->row[x*3+2] = c & 255;
        }
        JSAMPROW row = w->row.data();
        if (jpeg_write_scanlines(&w->jpeg, &row, 1) != 1) {
            exception(env, "JPEG write suspended"); return;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_th_ogay_luma_ai_StripJpeg_finish(JNIEnv* env, jobject, jlong handle) {
    auto* w = writer(env, handle); if (!w) return;
    if (w->finished || w->jpeg.next_scanline != w->jpeg.image_height) {
        exception(env, "JPEG is incomplete"); return;
    }
    if (setjmp(w->error.jump)) { exception(env, w->error.message); return; }
    jpeg_finish_compress(&w->jpeg);
    w->finished = true;
    if (fflush(w->file) != 0 || ferror(w->file)) exception(env, "JPEG flush failed");
}

extern "C" JNIEXPORT void JNICALL
Java_th_ogay_luma_ai_StripJpeg_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Writer*>(handle);
}
