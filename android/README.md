# UAE4ARM 2026 (Android, arm64-v8a)

This folder is a **Gradle Android application module** that builds UAE4ARM 2026 for Android as an SDL2-based native core (`libmain.so`) packaged into an APK/AAB.

## ABI

- `arm64-v8a` only (per current target).

## What this module provides

- Main SDL entry activity: `com.blitterstudio.amiberry.AmiberryActivity` (extends `org.libsdl.app.SDLActivity`)
  - Note: the class/package naming is kept for compatibility/history; the app you build here is UAE4ARM 2026.
- `externalNativeBuild` wiring to the repo root `CMakeLists.txt`

App IDs:

- Android package / app id: `com.uae4arm26`
- Android namespace (Java/Kotlin package for resources): `com.blitterstudio.amiberry`

## Gradle wrapper

This folder includes a Gradle wrapper so you can run builds from here:

- `./gradlew help`
- `./gradlew assembleDebug`

The debug APK is emitted to:

- `android/build/outputs/apk/debug/uae4arm_2026-debug.apk`

## Required: SDL2 Android Java glue

This module **does not include SDL2**.

You must provide SDL2 so the class `org.libsdl.app.SDLActivity` is available.

Two common ways:

1. **SDL2 as a source module** in your Android project
   - Recommended for this repo.
   - Add SDL2 as a git submodule:

     - `git submodule add https://github.com/libsdl-org/SDL.git external/SDL`
     - `git submodule update --init --recursive`

   - This `android/` module is already set up to compile SDL2’s Java glue from:
     `../external/SDL/android-project/app/src/main/java`
   - The native build is set up to build SDL2 from `external/SDL` when targeting Android.
2. **SDL2 as a prebuilt AAR**
   - Drop a prebuilt `SDL2.aar` into `android/libs/` and uncomment the dependency in `android/build.gradle`.

## Launching from Flutter

From Flutter, use a platform channel (or a small Flutter plugin) to start `AmiberryActivity` via an Android `Intent`.

## AROS ROMs

If you use `kickstart_rom_file=:AROS`, the emulator expects these files to exist in its ROMs directory:

- `aros-rom.bin`
- `aros-ext.bin`

This module supports an Android-friendly first-run flow:

- Put `aros-rom.bin` and `aros-ext.bin` under `android/src/main/assets/roms/`.
- On first launch, `AmiberryActivity` copies them into app-private storage (under the app ROMs directory).

See ../docs/flutter_core.md for the overall Flutter flow and build notes.
