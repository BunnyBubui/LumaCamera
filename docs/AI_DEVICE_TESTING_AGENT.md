# Lumora AI Device Testing Agent — Phase 1

Status: **foundation / unverified on owner's physical phone**. This document does not claim an OPPO Reno 15 or any other phone has been connected or tested.

## Goal

Enable a coding agent (Codex CLI or Claude Code) running on the owner's Windows PC to control a **physical Android phone**, inspect the UI, run repeatable scenarios, capture evidence, propose fixes on an isolated branch, rebuild, and retest. The user must review and approve all merges.

## Architecture

1. Windows PC: JDK 17+, Android SDK Platform-Tools, Gradle, Git, Maestro CLI, Codex or Claude Code.
2. Physical Android device with USB debugging enabled and authorized in ADB.
3. scripts/ai-device-test.ps1: deterministic build/install/launch/screenshot/logcat preflight.
4. Maestro MCP: live screen inspection, taps, swipes, assertions and generated repeatable YAML journeys.
5. Existing app/src/androidTest/: instrumented checks for native Android behavior; run explicitly.
6. Local evidence: artifacts/ai-device/TIMESTAMP/result.json, launch.png and logcat.txt.
7. AI reproduces a failure, writes a minimal patch, reruns checks and opens a **draft** PR. Human must approve merge.

## Repository-specific facts confirmed on main

- app/build.gradle.kts: applicationId = com.lumora, minSdk 23, targetSdk 35.
- app/src/main/AndroidManifest.xml: launcher is HomeActivity; CameraActivity is exported.
- scripts/auto-deploy.ps1 used a stale th.ogay.luma.preview package. This branch updates it to com.lumora.
- .github/workflows/device.yml runs camera and film tests on **emulators**, not on an OPPO physical device.
- Existing tests include CameraFlowTest, GlassGestureTest, VideoSessionDeviceTest, StudioPreviewMetricsAndroidTest, EditorFlowTest, FilmPreviewApi33DeviceTest.
- docs/RENO15_CAMERA_PROBE.md describes checking actual capture FPS, MP4 playback and high-speed evidence. Advertised FPS is not proof of actual capture rate.

## First run on Windows

Clone the repo and check out feature/ai-device-testing-phase1. On the phone, enable Developer Options and USB debugging, plug the phone into the PC and accept the USB authorization dialog. Run from PowerShell at the repository root:

    & 'C:\Android\platform-tools\adb.exe' devices
    java -version
    .\scripts\ai-device-test.ps1

The runner builds debug APK, installs it **in place**, launches via the Android launcher, checks its process, captures a screenshot and last logcat entries. It does **not** uninstall the app, wipe its data, or remove media.

If more than one device is connected:

    .\scripts\ai-device-test.ps1 -Serial YOUR_ADB_SERIAL

If the APK was already built:

    .\scripts\ai-device-test.ps1 -SkipBuild

The generated report directory is local and ignored by Git. A passed Phase 1 smoke **only verifies build/install/launch/screenshot**; it does not prove shutter, HDR, zoom, 60 FPS or export quality.

## Maestro CLI and AI control

Install Maestro CLI for native Windows following the official documentation:
https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli

Requires Java 17+. Test locally:

    maestro --help
    maestro list-devices
    maestro test tests/maestro/lumora-launch.yaml

Or run with the PowerShell preflight:

    .\scripts\ai-device-test.ps1 -RunMaestro

Connect a coding agent **on the USB-connected PC** using one of:

    codex mcp add maestro -- maestro mcp
    claude mcp add maestro -- maestro mcp

Restart the coding agent. Official MCP instructions:
https://docs.maestro.dev/get-started/maestro-mcp

Ask the agent to open the Maestro Viewer, inspect the connected device's accessibility tree, identify actual selectors, execute a test journey, save evidence and create a deterministic regression flow. The starter YAML has only a launch and screenshot; do not treat it as a full functionality test.

To run existing instrumentation tests (they may create media and modify app settings):

    .\scripts\ai-device-test.ps1 -RunAndroidTests

## Test coverage roadmap

| Area | Evidence required |
|---|---|
| App launch | Installed package, running process, screenshot, no startup FATAL/ANR |
| Camera/photo | Shutter, new file, successful decode, dimensions and EXIF |
| Video/60 FPS | Record/stop, MP4 decode, actual frame timestamps and measured FPS |
| 120/240 FPS | Physical-device camera probe, actual capture-vs-playback evidence |
| Zoom/transition | Gestures, accessibility selectors, screenshot checkpoints, lifecycle recovery |
| HDR/SR2/SR3 | Same-scene outputs, image artifacts, text fidelity, processing latency, memory |
| Film/editor | LUT selection, preview transitions, export, duration, audio and playback |
| Performance | Android Macrobenchmark FrameTimingMetric and Perfetto traces; screenshots cannot measure jank |
| Stability | Relevant crash/ANR logs, reproducible failure scenario, repeated runs |

## Autonomous fix-and-retest rules

- Work on a branch from current main; do not edit other agents' owned files.
- First reproduce the bug and save exact steps, device model, Android version, app version, screenshots and logs.
- Make a focused change; run unit tests, lint, build, relevant UI tests and a device rerun.
- Limit autonomous fixes to **three attempts per failure**, then stop with a blocker report.
- Do not auto-merge, force-push, uninstall apps, clear user data, change release signing, update stores, or send private media/screenshots to a public PR.
- Do not claim an AI agent connected to the physical phone from remote GitHub access. Direct USB control requires a local agent on the Windows PC.
- For external/more privileged actions, seek user approval.

## Milestones

1. **Phase 1:** Local Windows runner, Maestro launch smoke, report and stale package fix (**this PR**).
2. **Phase 2:** AI-driven UI exploration; add stable camera, zoom, gallery, video and editor flows.
3. **Phase 3:** Objective output validation (EXIF/MP4/FPS) and performance profiling.
4. **Phase 4:** AI triage + isolated code patch → rebuild → retest, max three iterations, draft PR.
5. **Phase 5:** Optional self-hosted CI; isolate USB phones from untrusted PR code.
6. **Phase 6:** Regression dashboard, multiple physical device coverage and release approval gate.

## Validation status

Source files can be reviewed on GitHub. PowerShell, ADB, Maestro and the phone-based test still need execution on the owner's actual Windows PC. This PR does **not** assert successful device execution or fully autonomous bug repair.
