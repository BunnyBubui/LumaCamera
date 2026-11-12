// JNI entry point for th.ogay.luma.hdr.HdrNative. Converts the ARGB int arrays
// that HdrCapture already holds into cv::Mat, applies the integer alignment
// shifts, runs hdr_pipeline and writes RGBA_8888 into a caller-owned Bitmap.
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <opencv2/imgproc.hpp>

#include <new>
#include <string>
#include <vector>

#include "hdr_pipeline.h"

namespace {

constexpr const char* kTag = "LumaHdrNative";

// Thrown after a Java exception is already pending, to unwind without
// replacing it.
struct JavaExceptionPending {};

void throwJava(JNIEnv* env, const char* cls, const std::string& message) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c != nullptr) env->ThrowNew(c, message.c_str());
}

class BitmapLock {
public:
    BitmapLock(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
        if (AndroidBitmap_lockPixels(env_, bitmap_, &pixels_) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels_ == nullptr)
            throw std::runtime_error("Cannot lock the output bitmap");
    }
    ~BitmapLock() { AndroidBitmap_unlockPixels(env_, bitmap_); }
    void* pixels() const { return pixels_; }

private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_ = nullptr;
};

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_th_ogay_luma_hdr_HdrNative_nativeFuse(JNIEnv* env, jclass, jobjectArray frames, jint width,
                                           jint height, jintArray dxArray, jintArray dyArray,
                                           jfloatArray paramArray, jint radius, jobject output,
                                           jobject cancellation, jdoubleArray timingOut) {
    using namespace luma::hdr;
    try {
        if (frames == nullptr || dxArray == nullptr || dyArray == nullptr ||
            paramArray == nullptr || output == nullptr)
            throw std::invalid_argument("Arguments must not be null");
        const jsize count = env->GetArrayLength(frames);
        if (count < 1 || count > kMaxFrames)
            throw std::invalid_argument("HDR needs 1 to 9 frames");
        if (width <= 0 || height <= 0 || static_cast<long long>(width) * height > kMaxPixels)
            throw std::invalid_argument("HDR frame size is out of range");
        if (env->GetArrayLength(dxArray) != count || env->GetArrayLength(dyArray) != count)
            throw std::invalid_argument("dx and dy must have one entry per frame");
        if (env->GetArrayLength(paramArray) != 6)
            throw std::invalid_argument("params must hold 6 values");

        std::vector<jint> dx(count), dy(count);
        env->GetIntArrayRegion(dxArray, 0, count, dx.data());
        env->GetIntArrayRegion(dyArray, 0, count, dy.data());
        if (dx[0] != 0 || dy[0] != 0) throw std::invalid_argument("Frame 0 is the reference and must not be shifted");
        for (jsize i = 0; i < count; ++i)
            if (dx[i] <= -width || dx[i] >= width || dy[i] <= -height || dy[i] >= height)
                throw std::invalid_argument("Shift is larger than the frame");

        jfloat raw[6];
        env->GetFloatArrayRegion(paramArray, 0, 6, raw);
        Params params;
        params.contrastWeight = raw[0];
        params.saturationWeight = raw[1];
        params.exposureWeight = raw[2];
        params.guidedEps = raw[3];
        params.detailGain = raw[4];
        params.baseGamma = raw[5];
        params.guidedRadius = radius;
        validate(params);

        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, output, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::invalid_argument("Cannot read the output bitmap");
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            info.width != static_cast<uint32_t>(width) ||
            info.height != static_cast<uint32_t>(height))
            throw std::invalid_argument("Output bitmap must be ARGB_8888 with the frame size");

        // Java int 0xAARRGGBB is stored little-endian as B, G, R, A, which is
        // exactly CV_8UC4 BGRA. Android only runs on little-endian ABIs.
        std::vector<cv::Mat> bgr;
        bgr.reserve(count);
        cv::Mat bgra(height, width, CV_8UC4);
        const jsize pixels = width * height;
        for (jsize i = 0; i < count; ++i) {
            auto frame = static_cast<jintArray>(env->GetObjectArrayElement(frames, i));
            if (frame == nullptr) throw std::invalid_argument("Frame must not be null");
            if (env->GetArrayLength(frame) != pixels) {
                env->DeleteLocalRef(frame);
                throw std::invalid_argument("Frame length must be width * height");
            }
            env->GetIntArrayRegion(frame, 0, pixels, reinterpret_cast<jint*>(bgra.data));
            env->DeleteLocalRef(frame);
            cv::Mat converted;
            cv::cvtColor(bgra, converted, cv::COLOR_BGRA2BGR);
            if (i == 0)
                bgr.push_back(converted);
            else
                bgr.push_back(shiftWithFallback(converted, bgr[0], dx[i], dy[i]));
        }
        bgra.release();

        jmethodID isStopped = nullptr;
        if (cancellation != nullptr) {
            jclass cls = env->GetObjectClass(cancellation);
            isStopped = env->GetMethodID(cls, "isStopped", "()Z");
            env->DeleteLocalRef(cls);
            if (isStopped == nullptr) throw JavaExceptionPending();
        }
        StopCheck stop = [&]() {
            if (isStopped == nullptr) return false;
            const jboolean stopped = env->CallBooleanMethod(cancellation, isStopped);
            if (env->ExceptionCheck()) throw JavaExceptionPending();
            return stopped == JNI_TRUE;
        };

        Timing timing;
        cv::Mat result = process(bgr, params, stop, &timing);
        bgr.clear();

        {
            BitmapLock lock(env, output);
            cv::Mat target(height, width, CV_8UC4, lock.pixels(), info.stride);
            // dst already has the right size and type, so cvtColor writes into
            // the locked bitmap memory instead of allocating.
            cv::cvtColor(result, target, cv::COLOR_BGR2RGBA);
            CV_Assert(target.data == lock.pixels());
        }

        if (timingOut != nullptr && env->GetArrayLength(timingOut) >= 3) {
            const jdouble t[3] = {timing.fuseMs, timing.toneMs, timing.totalMs};
            env->SetDoubleArrayRegion(timingOut, 0, 3, t);
        }
        __android_log_print(ANDROID_LOG_INFO, kTag, "EF2 %dx%d x%d: fuse %.0f ms, tone %.0f ms",
                            width, height, static_cast<int>(count), timing.fuseMs, timing.toneMs);
    } catch (const JavaExceptionPending&) {
        // Leave the pending Java exception as it is.
    } catch (const Cancelled& e) {
        throwJava(env, "java/util/concurrent/CancellationException", e.what());
    } catch (const std::invalid_argument& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Native HDR ran out of memory");
    } catch (const cv::Exception& e) {
        if (e.code == cv::Error::StsNoMem)
            throwJava(env, "java/lang/OutOfMemoryError", "Native HDR ran out of memory");
        else
            throwJava(env, "java/lang/IllegalStateException", std::string("OpenCV: ") + e.what());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", e.what());
    }
}
