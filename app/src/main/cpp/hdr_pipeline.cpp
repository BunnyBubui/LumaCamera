#include "hdr_pipeline.h"

#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>

#include <chrono>
#include <cmath>
#include <string>

namespace luma {
namespace hdr {
namespace {

using Clock = std::chrono::steady_clock;

double millisSince(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

void checkStop(const StopCheck& stop) {
    if (stop && stop()) throw Cancelled();
}

bool finite(float v) { return std::isfinite(v); }

void validateFrames(const std::vector<cv::Mat>& frames) {
    if (frames.empty() || static_cast<int>(frames.size()) > kMaxFrames)
        throw std::invalid_argument("HDR needs 1 to " + std::to_string(kMaxFrames) + " frames");
    const cv::Size size = frames[0].size();
    if (size.width <= 0 || size.height <= 0 ||
        static_cast<long long>(size.width) * size.height > kMaxPixels)
        throw std::invalid_argument("HDR frame size is out of range");
    for (const cv::Mat& f : frames) {
        if (f.type() != CV_8UC3) throw std::invalid_argument("HDR frames must be CV_8UC3");
        if (f.size() != size) throw std::invalid_argument("HDR frames must have the same size");
    }
}

}  // namespace

void validate(const Params& p) {
    if (!finite(p.contrastWeight) || !finite(p.saturationWeight) || !finite(p.exposureWeight) ||
        p.contrastWeight < 0 || p.saturationWeight < 0 || p.exposureWeight < 0)
        throw std::invalid_argument("Mertens weights must be finite and not negative");
    if (p.contrastWeight + p.saturationWeight + p.exposureWeight <= 0)
        throw std::invalid_argument("At least one Mertens weight must be positive");
    if (p.guidedRadius < 1 || p.guidedRadius > 256)
        throw std::invalid_argument("Guided filter radius must be 1 to 256");
    if (!finite(p.guidedEps) || p.guidedEps <= 0)
        throw std::invalid_argument("Guided filter eps must be positive");
    if (!finite(p.detailGain) || p.detailGain < 0 || p.detailGain > 8)
        throw std::invalid_argument("Detail gain must be 0 to 8");
    if (!finite(p.baseGamma) || p.baseGamma <= 0 || p.baseGamma > 4)
        throw std::invalid_argument("Base gamma must be above 0 and at most 4");
}

cv::Mat shiftWithFallback(const cv::Mat& src, const cv::Mat& reference, int dx, int dy) {
    CV_Assert(src.size() == reference.size() && src.type() == reference.type());
    cv::Mat out = reference.clone();
    const int w = src.cols, h = src.rows;
    // Output columns x where 0 <= x + dx < w, same for rows.
    const int x0 = std::max(0, -dx), x1 = std::min(w, w - dx);
    const int y0 = std::max(0, -dy), y1 = std::min(h, h - dy);
    if (x1 > x0 && y1 > y0) {
        const cv::Rect dst(x0, y0, x1 - x0, y1 - y0);
        src(dst + cv::Point(dx, dy)).copyTo(out(dst));
    }
    return out;
}

cv::Mat fuseExposures(const std::vector<cv::Mat>& frames, const Params& p) {
    validateFrames(frames);
    if (frames.size() == 1) {
        cv::Mat single;
        frames[0].convertTo(single, CV_32FC3, 1.0 / 255.0);
        return single;
    }
    cv::Ptr<cv::MergeMertens> merge =
        cv::createMergeMertens(p.contrastWeight, p.saturationWeight, p.exposureWeight);
    cv::Mat fused;
    merge->process(frames, fused);
    // Laplacian blending can overshoot slightly outside 0..1.
    cv::min(cv::max(fused, 0.0), 1.0, fused);
    return fused;
}

cv::Mat guidedFilterSelf(const cv::Mat& image, int radius, float eps) {
    CV_Assert(image.type() == CV_32FC1);
    const cv::Size k(2 * radius + 1, 2 * radius + 1);
    auto box = [&](const cv::Mat& in) {
        cv::Mat out;
        cv::boxFilter(in, out, CV_32F, k, cv::Point(-1, -1), true, cv::BORDER_REFLECT);
        return out;
    };
    cv::Mat mean = box(image);
    cv::Mat meanSq = box(image.mul(image));
    cv::Mat variance = meanSq - mean.mul(mean);
    cv::max(variance, 0.0, variance);  // rounding can make it slightly negative
    cv::Mat a = variance / (variance + eps);
    cv::Mat b = mean - a.mul(mean);
    variance.release();
    meanSq.release();
    cv::Mat meanA = box(a);
    cv::Mat meanB = box(b);
    return meanA.mul(image) + meanB;
}

cv::Mat toneMapLightness(const cv::Mat& fusedBgr, const Params& p, const StopCheck& stop) {
    CV_Assert(fusedBgr.type() == CV_32FC3);
    validate(p);

    // Float L*a*b* keeps L* at full precision (0..100); an 8-bit Lab image
    // would quantise L* to 256 steps before the detail boost amplifies them.
    cv::Mat lab;
    cv::cvtColor(fusedBgr, lab, cv::COLOR_BGR2Lab);
    std::vector<cv::Mat> ch;
    cv::split(lab, ch);
    lab.release();
    checkStop(stop);

    cv::Mat l;
    ch[0].convertTo(l, CV_32F, 1.0 / 100.0);  // 0..1, the scale eps is defined on
    cv::Mat base = guidedFilterSelf(l, p.guidedRadius, p.guidedEps);
    checkStop(stop);

    cv::Mat detail = l - base;
    l.release();
    cv::max(base, 0.0, base);  // pow of a negative value is NaN
    cv::Mat curved;
    cv::pow(base, p.baseGamma, curved);
    base.release();
    cv::Mat finalL = curved + detail * p.detailGain;
    cv::min(cv::max(finalL, 0.0), 1.0, finalL);
    finalL.convertTo(ch[0], CV_32F, 100.0);
    checkStop(stop);

    cv::merge(ch, lab);
    ch.clear();
    cv::Mat bgr;
    cv::cvtColor(lab, bgr, cv::COLOR_Lab2BGR);
    lab.release();
    cv::Mat out;
    bgr.convertTo(out, CV_8UC3, 255.0);  // saturating cast clamps out-of-gamut values
    return out;
}

cv::Mat process(const std::vector<cv::Mat>& frames, const Params& p, const StopCheck& stop,
                Timing* timing) {
    validate(p);
    validateFrames(frames);
    const auto start = Clock::now();
    checkStop(stop);
    cv::Mat fused = fuseExposures(frames, p);
    const double fuseMs = millisSince(start);
    checkStop(stop);
    const auto toneStart = Clock::now();
    cv::Mat out = toneMapLightness(fused, p, stop);
    if (timing) {
        timing->fuseMs = fuseMs;
        timing->toneMs = millisSince(toneStart);
        timing->totalMs = millisSince(start);
    }
    return out;
}

}  // namespace hdr
}  // namespace luma
