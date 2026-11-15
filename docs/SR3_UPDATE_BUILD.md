# Lumora SR3 — update the existing app

The user requires an **in-place update**, retaining app data/settings. Do not
deliver an alternate package such as `com.lumora.sr3test` or ask the user to
uninstall their current app. Current main's application ID is `com.lumora`;
confirm the actually installed package before claiming update compatibility.

Requirements: identical application ID, the original signing key (or an already
valid signing lineage), and a versionCode at least as high as the installed APK.
An old APK can identify the package/version/public certificate but cannot recover
the private signing key. Do not claim a newly generated debug key is the old key.

## Existing-key builds

Ordinary local `assembleDebug` uses that computer's existing Android debug
keystore. If the installed app was built on that computer with the same package,
rebuild there using the original keystore and an appropriate LUMA_BUILD_NUMBER.

Alternatively, supply the **original** key through the existing environment
variables LUMA_KEYSTORE, LUMA_STORE_PASSWORD, LUMA_KEY_ALIAS and LUMA_KEY_PASSWORD.
Debug now honors this configuration as beta/release already do. Do not commit
private keys or passwords, generate a substitute key, or print secrets.

Build: `bash gradlew :app:lintDebug :app:assembleDebug`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
Compare `apksigner verify --print-certs` and `aapt dump badging` on the old and
new APKs before describing the new file as an update. These checks expose only
public signing-certificate metadata, not private key material.

## Current evidence

Main run 43377692448 built debug/probe APKs. Its signed job skipped checkout,
build and upload because no testing/release key was selected. No original
Windows debug keystore has been supplied to this workspace. Consequently,
in-place installation cannot yet be verified or promised.

The archived main APK from that run was inspected: package `com.lumora`,
versionCode 281, signer SHA256
`cee6290b77eafec8c2452c9ef7a61eb1ae238b7c4b2671e2c56e4c2aa0c6c917`.
The workspace's newly generated local debug key has a different public
certificate. Its APK must not be described as an in-place update to that main
APK. The installed APK's actual source and original key still need confirmation.

The code includes the SR3 v2 Preview/Export and diagnostic fixes. Neural weights
remain the existing SR2 weights with Natural guards; no newly trained production
SR3 is shipped. Photograph training/evaluation and physical-device acceptance
remain required as described in SR3_V2_PROGRESS.md.
