# UAE4ARM 2026 docs

This folder collects documentation for **UAE4ARM 2026** (repo folder: `uae4arm_2026`).

The project is an Android-first Amiga emulator build derived from the WinUAE lineage and the **Amiberry** codebase (upstream source), with additional Android tooling and a release pipeline.

## Quick links

- Android module details: `../android/README.md`
- Release assets + Play Store tooling: `../release_dashboard/README.md`
- Flutter launcher / UI notes: `./flutter_core.md` and `./flutter/`

## Android specifics

The Android app lives under `android/`:

- Gradle plugin: `com.android.application`
- ABI: `arm64-v8a` only
- App id: `com.uae4arm26`
- SDL entry activity: `com.blitterstudio.amiberry.AmiberryActivity` (extends `org.libsdl.app.SDLActivity`)
	- Note: historical class/package naming is retained; this project is UAE4ARM 2026.

### Build debug (Windows)

From repo root:

```powershell
android\gradlew.bat -p android --no-daemon --console=plain assembleDebug
```

APK output:

- `android/build/outputs/apk/debug/uae4arm_2026-debug.apk`

### Install on device

```cmd
adb install -r "android\build\outputs\apk\debug\uae4arm_2026-debug.apk"
```

Optional sanity checks:

```cmd
adb devices
adb shell pm list packages | findstr /i uae4arm
```

## Featured graphic / store assets

The Play Store “featured graphic” and other store assets are managed under `release_dashboard/`.

See `../release_dashboard/README.md` for:

- exporting AAB/APK into the dashboard
- copying a local featured graphic into `release_dashboard/graphics/featured.png`
- regenerating launcher icons for all densities

## AROS ROMs (Android-friendly first run)

If you use `kickstart_rom_file=:AROS`, the Android build supports copying bundled AROS ROMs from assets on first launch.

Details are in `../android/README.md`.
