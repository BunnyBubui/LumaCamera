# Lumora SR3 Test — separately installable test APK

Build variant: `sr3test`; application ID: `com.lumora.sr3test`;
launcher label: **Lumora SR3 Test**. It uses the debug signing configuration and
the same runtime code as the ordinary debug app. The dedicated package allows
testing alongside an existing Lumora installation with independent app settings.
The regular debug/beta/release package names, labels and signing rules are unchanged.

Build with `bash gradlew :app:lintSr3test :app:assembleSr3test` after preparing the
existing SR2 assets using `python model/build_detail.py --verify`.
APK: `app/build/outputs/apk/sr3test/app-sr3test.apk`.
The Android APK workflow also includes `Lumora-SR3-Test.apk` in its artifacts.

This build includes the SR3 v2 audit fixes: matching baseline sampling for
Preview/Export, cancellation/OOM handling in preview, and accurate source/model/
applied-strength diagnostics. **It still uses the existing SR2 neural weights
with Natural rendering guards. No newly trained photographic SR3 is shipped.**

## Test on OPPO Reno 15

1. Install the APK and open **Lumora SR3 Test**. Import the same original image
   used in the previous app, without taking a screenshot or recompressing it.
2. Compare Natural SR3 and Classic SR2 at the same x2/x4 scale and 100% strength.
   Inspect the preview and saved files at the same crop and displayed zoom.
3. Include foliage, faces/hair, Thai/English text and a low-light image. Note any
   smoothing, halo, changed characters, tile lines, slowdowns or cancellation issue.
4. Keep the original plus both exported files for the next photographic quality
   evaluation. Small display differences alone cannot validate a new model.

The final native x2/x4 model still needs the curated HR corpus, training and
physical-phone acceptance described in `SR3_V2_PROGRESS.md` before integration.
