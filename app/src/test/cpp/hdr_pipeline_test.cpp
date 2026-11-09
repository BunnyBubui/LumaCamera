// Desktop test for app/src/main/cpp/hdr_pipeline.cpp. Gradle does not run
// it; CI builds it against Ubuntu's OpenCV (see .github/workflows/android.yml),
// and app/src/test/cpp/README.md shows how to run it by hand.
#include "hdr_pipeline.h"

#include <opencv2/imgproc.hpp>

#include <cmath>
#include <cstdio>
#include <functional>
#include <random>
#include <string>
#include <vector>

using namespace luma::hdr;

namespace {

int failures = 0;

void check(bool ok, const std::string& what) {
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) ++failures;
}

template <class E>
bool throws(const std::function<void()>& f) {
    try { f(); } catch (const E&) { return true; } catch (...) { return false; }
    return false;
}

// Scene radiance (linear, arbitrary units) with a bright textured window, a
// dark textured corner and a mid-grey textured wall. The texture is what the
// tests look for after fusion.
cv::Mat sceneRadiance(int w, int h) {
    cv::Mat r(h, w, CV_32FC3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const float tex = 1.0f + 0.25f * std::sin(x * 0.7f) * std::sin(y * 0.5f);
            float v;
            cv::Vec3f tint(1.0f, 1.0f, 1.0f);
            if (x > w / 2 && y < h / 2) {        // window: clips at EV 0, fits at EV -2
                v = 0.6f * tex;
                tint = cv::Vec3f(1.05f, 1.0f, 0.9f);
            } else if (x < w / 4 && y > h / 2) {  // dark corner
                v = 0.012f * tex;
                tint = cv::Vec3f(0.9f, 1.0f, 1.1f);
            } else {
                v = 0.18f * tex;
            }
            r.at<cv::Vec3f>(y, x) = tint * v;
        }
    return r;
}

// Simple camera: exposure in stops, gamma 2.2, 8-bit quantisation, small noise.
cv::Mat shoot(const cv::Mat& radiance, float ev, unsigned seed) {
    std::mt19937 rng(seed);
    std::normal_distribution<float> noise(0.0f, 0.6f);
    cv::Mat out(radiance.size(), CV_8UC3);
    const float gain = std::pow(2.0f, ev);
    for (int y = 0; y < radiance.rows; ++y)
        for (int x = 0; x < radiance.cols; ++x) {
            const cv::Vec3f v = radiance.at<cv::Vec3f>(y, x);
            cv::Vec3b px;
            for (int c = 0; c < 3; ++c) {
                const float lin = std::min(1.0f, std::max(0.0f, v[c] * gain));
                px[c] = cv::saturate_cast<uchar>(255.0f * std::pow(lin, 1.0f / 2.2f) + noise(rng));
            }
            out.at<cv::Vec3b>(y, x) = px;
        }
    return out;
}

double lumaStd(const cv::Mat& bgr, const cv::Rect& roi) {
    cv::Mat grey;
    cv::cvtColor(bgr(roi), grey, cv::COLOR_BGR2GRAY);
    cv::Scalar mean, sd;
    cv::meanStdDev(grey, mean, sd);
    return sd[0];
}

double meanAbsDiff(const cv::Mat& a, const cv::Mat& b) {
    cv::Mat d;
    cv::absdiff(a, b, d);
    const cv::Scalar s = cv::mean(d);
    return (s[0] + s[1] + s[2]) / 3.0;
}

double maxAbsDiff(const cv::Mat& a, const cv::Mat& b) {
    cv::Mat d;
    cv::absdiff(a, b, d);
    double mx;
    cv::minMaxLoc(d.reshape(1), nullptr, &mx);
    return mx;
}

}  // namespace

int main() {
    const int w = 320, h = 240;
    const cv::Mat radiance = sceneRadiance(w, h);
    const std::vector<cv::Mat> brackets = {shoot(radiance, 2.0f, 1),   // EV 0 reference
                                           shoot(radiance, 0.0f, 2),   // EV -2
                                           shoot(radiance, 4.0f, 3)};  // EV +2
    const cv::Rect window(w / 2 + 20, 20, w / 2 - 40, h / 2 - 40);
    const cv::Rect corner(10, h / 2 + 20, w / 4 - 20, h / 2 - 40);
    const cv::Rect wall(w / 4 + 20, h / 2 + 20, w / 4 - 20, h / 2 - 40);
    const Params spec;  // the values in the request: 1,1,0 / r16 eps0.01 / 1.35 / 0.95

    {
        const cv::Mat out = process(brackets, spec);
        const double ref = lumaStd(brackets[0], window), fused = lumaStd(out, window);
        std::printf("      window luma std: EV0 %.2f, EV-2 %.2f, EF2 %.2f\n", ref,
                    lumaStd(brackets[1], window), fused);
        check(ref < 2.0 && fused > 6.0, "clipped window gets its texture back");
        const double refC = lumaStd(brackets[0], corner), fusedC = lumaStd(out, corner);
        std::printf("      corner luma std: EV0 %.2f, EV+2 %.2f, EF2 %.2f\n", refC,
                    lumaStd(brackets[2], corner), fusedC);
        check(fusedC > refC * 1.5, "dark corner shows more texture than EV 0");
        check(out.type() == CV_8UC3 && out.size() == brackets[0].size(), "output is 8-bit BGR, same size");
    }

    {
        const cv::Mat fused = fuseExposures(brackets, spec);
        double mn, mx;
        cv::minMaxLoc(fused.reshape(1), &mn, &mx);
        check(fused.type() == CV_32FC3 && mn >= 0.0 && mx <= 1.0, "fusion output clamped to 0..1 on every channel");
    }

    {
        Params neutral = spec;
        neutral.detailGain = 1.0f;
        neutral.baseGamma = 1.0f;
        cv::Mat in;
        brackets[0].convertTo(in, CV_32FC3, 1.0 / 255.0);
        const cv::Mat out = toneMapLightness(in, neutral);
        const double mean = meanAbsDiff(out, brackets[0]), mx = maxAbsDiff(out, brackets[0]);
        std::printf("      gain 1 / gamma 1 vs input: mean %.3f, max %.0f levels\n", mean, mx);
        check(mean < 0.6 && mx <= 3, "tone step with gain 1 and gamma 1 returns the input (Lab round trip only)");
    }

    {
        // Fine texture far below eps lands in the detail layer, so the boost
        // should scale its contrast by about detailGain.
        cv::Mat flat(h, w, CV_8UC3);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const uchar v = static_cast<uchar>(128 + ((x / 2 + y / 2) % 2 ? 6 : -6));
                flat.at<cv::Vec3b>(y, x) = cv::Vec3b(v, v, v);
            }
        Params noCurve = spec;
        noCurve.baseGamma = 1.0f;
        cv::Mat in;
        flat.convertTo(in, CV_32FC3, 1.0 / 255.0);
        const cv::Mat out = toneMapLightness(in, noCurve);
        const cv::Rect inner(40, 40, w - 80, h - 80);
        const double ratio = lumaStd(out, inner) / lumaStd(flat, inner);
        std::printf("      fine texture contrast ratio: %.3f (gain %.2f)\n", ratio, noCurve.detailGain);
        check(std::abs(ratio - noCurve.detailGain) < 0.08, "fine detail is boosted by about detailGain");
    }

    {
        cv::Mat constant(64, 64, CV_32F, cv::Scalar(0.42f));
        const cv::Mat base = guidedFilterSelf(constant, 16, 0.01f);
        check(maxAbsDiff(base, constant) < 1e-5, "guided filter leaves a constant image unchanged");
        // A step much larger than sqrt(eps) is an edge to keep: the base must
        // stay close to the input a few pixels away from the step.
        cv::Mat step(64, 64, CV_32F, cv::Scalar(0.1f));
        step(cv::Rect(32, 0, 32, 64)).setTo(0.9f);
        const cv::Mat edge = guidedFilterSelf(step, 16, 0.01f);
        const float left = edge.at<float>(32, 26), right = edge.at<float>(32, 38);
        std::printf("      step 0.1/0.9, base 6 px from the edge: %.3f / %.3f\n", left, right);
        check(std::abs(left - 0.1f) < 0.05f && std::abs(right - 0.9f) < 0.05f, "guided filter keeps strong edges in the base");
    }

    {
        cv::Mat a(4, 5, CV_8UC3), ref(4, 5, CV_8UC3, cv::Scalar(7, 7, 7));
        for (int y = 0; y < 4; ++y)
            for (int x = 0; x < 5; ++x) a.at<cv::Vec3b>(y, x) = cv::Vec3b(x, y, 100);
        const cv::Mat s = shiftWithFallback(a, ref, 2, -1);
        // output(x, y) = a(x + 2, y - 1) where that exists, else ref.
        check(s.at<cv::Vec3b>(1, 0) == cv::Vec3b(2, 0, 100) && s.at<cv::Vec3b>(3, 2) == cv::Vec3b(4, 2, 100),
              "shift copies src(x+dx, y+dy)");
        check(s.at<cv::Vec3b>(0, 0) == cv::Vec3b(7, 7, 7) && s.at<cv::Vec3b>(2, 3) == cv::Vec3b(7, 7, 7),
              "shift fills pixels without a source from the reference");
        check(shiftWithFallback(a, ref, 9, 0).at<cv::Vec3b>(1, 1) == cv::Vec3b(7, 7, 7), "shift beyond the frame returns the reference");
    }

    {
        const std::vector<cv::Mat> same = {brackets[0], brackets[0].clone(), brackets[0].clone()};
        cv::Mat in;
        brackets[0].convertTo(in, CV_32FC3, 1.0 / 255.0);
        const double d = meanAbsDiff(process(same, spec), toneMapLightness(in, spec));
        std::printf("      identical frames vs single frame: mean %.3f levels\n", d);
        check(d < 0.6, "identical brackets fuse to the single frame");
        check(maxAbsDiff(process({brackets[0]}, spec), toneMapLightness(in, spec)) == 0, "one frame skips Mertens");
    }

    {
        Params p = spec;
        check(throws<std::invalid_argument>([&] { p.guidedEps = 0; validate(p); }), "eps 0 rejected");
        p = spec;
        check(throws<std::invalid_argument>([&] { p.baseGamma = NAN; validate(p); }), "NaN gamma rejected");
        p = spec;
        check(throws<std::invalid_argument>([&] { p.guidedRadius = 0; validate(p); }), "radius 0 rejected");
        p = spec;
        check(throws<std::invalid_argument>([&] { p.contrastWeight = p.saturationWeight = p.exposureWeight = 0; validate(p); }), "all-zero weights rejected");
        check(throws<std::invalid_argument>([&] { process({}, spec); }), "no frames rejected");
        check(throws<std::invalid_argument>([&] { process({brackets[0], cv::Mat(10, 10, CV_8UC3)}, spec); }), "size mismatch rejected");
        cv::Mat grey;
        cv::cvtColor(brackets[0], grey, cv::COLOR_BGR2GRAY);
        check(throws<std::invalid_argument>([&] { process({grey}, spec); }), "non-BGR frame rejected");
        int calls = 0;
        check(throws<Cancelled>([&] { process(brackets, spec, [&] { return ++calls >= 2; }); }), "stop request cancels between stages");
    }

    {
        // Size HdrCapture uses today (it caps fusion at 3 MP).
        const int bw = 2000, bh = 1500;
        const cv::Mat big = sceneRadiance(bw, bh);
        const std::vector<cv::Mat> frames = {shoot(big, 2.0f, 4), shoot(big, 0.0f, 5), shoot(big, 4.0f, 6)};
        Timing t;
        process(frames, spec, StopCheck(), &t);
        std::printf("      3 MP x3 on this machine: fuse %.0f ms, tone %.0f ms, total %.0f ms\n", t.fuseMs, t.toneMs, t.totalMs);
        check(t.totalMs > 0, "3 MP run completes");
    }

    std::printf("%s: %d failure(s)\n", failures ? "FAILED" : "OK", failures);
    return failures ? 1 : 0;
}
