// Luma HDR EF2: OpenCV exposure fusion followed by base/detail tone shaping on
// CIE L*. Pure OpenCV, no JNI, so the same code builds for Android and for the
// desktop test in app/src/test/cpp.
#pragma once

#include <opencv2/core.hpp>

#include <functional>
#include <stdexcept>
#include <vector>

namespace luma {
namespace hdr {

constexpr int kMaxFrames = 9;
constexpr long long kMaxPixels = 50'000'000LL;

struct Params {
    // cv::createMergeMertens weights. exposureWeight = 0 switches off the
    // well-exposedness term, so only contrast and saturation choose pixels.
    float contrastWeight = 1.0f;
    float saturationWeight = 1.0f;
    float exposureWeight = 0.0f;
    // Self-guided filter on L* scaled to 0..1. Radius is in pixels of the
    // image being processed; eps is a variance on the 0..1 scale.
    int guidedRadius = 16;
    float guidedEps = 0.01f;
    // L_final = base^baseGamma + detailGain * (L - base), all on 0..1.
    float detailGain = 1.35f;
    float baseGamma = 0.95f;
};

struct Timing {
    double fuseMs = 0;
    double toneMs = 0;
    double totalMs = 0;
};

// Returns true when the caller wants the work to stop.
using StopCheck = std::function<bool()>;

class Cancelled : public std::runtime_error {
public:
    Cancelled() : std::runtime_error("HDR fusion cancelled") {}
};

// Throws std::invalid_argument when a value is out of range.
void validate(const Params& params);

// Copies src shifted so that output(x, y) = src(x + dx, y + dy). Pixels whose
// source falls outside src are taken from reference instead, so the border
// carries only the reference frame rather than smeared edge pixels.
cv::Mat shiftWithFallback(const cv::Mat& src, const cv::Mat& reference, int dx, int dy);

// Mertens exposure fusion of CV_8UC3 frames. Returns CV_32FC3 clamped to 0..1.
cv::Mat fuseExposures(const std::vector<cv::Mat>& frames, const Params& params);

// He et al. guided filter with the image as its own guide (CV_32FC1 in and out).
// Built from box filters because cv::ximgproc is in opencv_contrib, which the
// Maven OpenCV package does not include.
cv::Mat guidedFilterSelf(const cv::Mat& image, int radius, float eps);

// Steps 2 to 5: BGR float 0..1 -> L*a*b*, base/detail split on L*, detail
// boost and base curve, back to CV_8UC3 BGR.
cv::Mat toneMapLightness(const cv::Mat& fusedBgr, const Params& params,
                         const StopCheck& stop = StopCheck());

// Whole pipeline. frames are CV_8UC3 BGR of equal size, already aligned,
// frame 0 is the reference. Returns CV_8UC3 BGR.
cv::Mat process(const std::vector<cv::Mat>& frames, const Params& params,
                const StopCheck& stop = StopCheck(), Timing* timing = nullptr);

}  // namespace hdr
}  // namespace luma
