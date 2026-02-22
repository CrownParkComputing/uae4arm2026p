# Running Amiberry from a Flutter app (core shared library + AROS ROM)

This repo primarily builds **Amiberry as a native SDL2 app** (Linux/macOS). For Flutter embedding, the lowest-friction path is to build a **shared library** (`amiberry_core`) and call into it from a Flutter desktop app using **`dart:ffi`**.

This document covers:

- Building `amiberry_core` (shared library)
- Using the built-in AROS Kickstart replacement (`:AROS`)
- Calling into the library from Flutter (desktop)

> If your target is **Android/iOS**, you will also need platform-specific work to render into a Flutter `Texture`/`Surface` and manage input/audio; the current `amiberry_core` entrypoint still runs the normal SDL windowed frontend.

For **Android-first**, the most reliable approach is to run Amiberry inside an **Android `Activity` that extends `org.libsdl.app.SDLActivity`**, and launch that activity from Flutter (via a plugin / platform channel). This keeps SDL’s Android lifecycle + JNI wiring intact.

---

## 1) AROS ROM replacement (what Amiberry expects)

Amiberry supports a Kickstart replacement identified as `:AROS`.

Implementation details:

- The config value `kickstart_rom_file=:AROS` triggers AROS ROM loading.
- The Amiberry build expects these files in the **ROMs directory**:
  - `aros-rom.bin`
  - `aros-ext.bin`

If the files are missing, you’ll see a GUI error similar to:

- `Could not find the 'aros-rom.bin' file in the ROMs directory!`

Where to put them:

- Put `aros-rom.bin` and `aros-ext.bin` in whatever directory your build uses as the ROMs path.
- If you’re shipping this with a Flutter app, typically you:
  1. Bundle those ROM files as Flutter assets
  2. Copy them on first run to an app-writable directory
  3. Point Amiberry’s ROM path to that directory (via its config/layout)

---

## 2) Building the shared library

A new CMake option was added:

- `AMIBERRY_BUILD_CORE_LIB=ON` → builds a shared library target `amiberry_core`.

### Example (Linux/macOS)

```bash
cmake -S . -B build \
  -DCMAKE_BUILD_TYPE=Release \
  -DAMIBERRY_BUILD_CORE_LIB=ON

cmake --build build -j
```

Outputs (location depends on generator/platform):

- `libamiberry_core.so` (Linux)
- `libamiberry_core.dylib` (macOS)

---

## 3) Running from Flutter via FFI (desktop)

The exported C API is declared in:

- `src/include/amiberry_core_api.h`

The main entrypoint is:

- `int amiberry_core_run(int argc, const char** argv);`

### Minimal Dart sketch

Load the library:

- Linux: `DynamicLibrary.open('libamiberry_core.so')`
- macOS: `DynamicLibrary.open('libamiberry_core.dylib')`

Call:

- `amiberry_core_run(argc, argv)`

Important notes:

- This entrypoint runs Amiberry’s normal SDL initialization and event loop.
- On desktop, that means it will create its **own window**.
- For *true embedding* (render into a Flutter widget), a follow-up step is needed:
  - Provide a platform window handle/surface to SDL (`SDL_CreateWindowFrom`) or
  - Add an offscreen renderer path and expose the framebuffer.

---

## 4) Recommended next step (if you want real Flutter embedding)

---

## Android-first (recommended): launch an SDLActivity from Flutter

Why this is recommended:

- Amiberry is SDL2-based.
- SDL2 on Android is designed around `SDLActivity` (Java side) calling into a native `libmain.so` exporting `SDL_main()`.
- Pure `dart:ffi` calling `SDL_Init()` inside Flutter’s main activity is usually brittle because SDL’s Android JNI/bootstrap isn’t set up the way SDL expects.

### What I added in this repo for Android

- An Android build mode that creates a shared library target named `main` → output is `libmain.so`.
- A small wrapper that exports `SDL_main()` and forwards to Amiberry’s `amiberry_run()`:
  - `src/osdep/amiberry_android_sdlmain.cpp`

### CMake behavior on Android

When `CMAKE_SYSTEM_NAME=Android`:

- The primary target becomes `main` (a shared library), not a desktop executable.
- The existing desktop `amiberry_core` shared library option is still there, but for Android you generally want the SDLActivity route first.

### Android minimal build (recommended first milestone)

This repo now supports an Android-friendly **minimal** build mode that avoids the desktop GUI stack and its heavy dependencies.

On Android, `AMIBERRY_ANDROID_MINIMAL` defaults to **ON**.

What it does:

- Excludes the desktop GUI sources (`src/osdep/gui/*`, `src/osdep/amiberry_gui.cpp`)
- Excludes the virtual keyboard implementation (`src/osdep/vkbd/vkbd.cpp`)
- Disables optional dependencies like `SDL2_image`, `SDL2_ttf`, `libpng`, `mpg123`, `FLAC`, and CHD support at build time

This keeps the initial SDLActivity + `libmain.so` integration achievable; you can bring back GUI/TTF/image support later.

### Building `libmain.so` with the NDK (example)

You normally do this from Gradle, but this shows the shape of the CMake invocation:

```bash
cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DAMIBERRY_ANDROID_MINIMAL=ON \
  -DCMAKE_BUILD_TYPE=Release

cmake --build build-android -j
```

SDL2 note:

- This repo’s desktop build expects `find_package(SDL2 ...)`.
- On Android, you typically build SDL2 from source as part of the same Gradle/CMake project (the standard SDL2 Android layout) and link against it.
- This repo supports vendoring SDL2 as a git submodule at `external/SDL` and building it as part of the Android CMake build.

### What you build into your Flutter app

In your Flutter app’s Android module:

1. Add SDL2’s Android Java sources (or dependency) so you have `org.libsdl.app.SDLActivity`.
2. Add a new activity, e.g. `AmiberryActivity extends SDLActivity`.
3. Package:
   - `libmain.so` (built from this repo)
   - Any other required `.so` dependencies (SDL2, and whatever CMake links in)
4. From Flutter, launch `AmiberryActivity` with an `Intent`.

### AROS ROMs on Android

Amiberry’s AROS replacement expects:

- `aros-rom.bin`
- `aros-ext.bin`

Typical Android app flow:

- Bundle these files in your Android app.
- On first run, copy them to app-private storage and ensure Amiberry’s ROM path resolves there.

This repo already includes an `android/` Gradle module scaffold and an `AmiberryActivity` that can auto-provision the AROS ROM files if you place them in:

- `android/src/main/assets/roms/aros-rom.bin`
- `android/src/main/assets/roms/aros-ext.bin`

Then launch Amiberry with `kickstart_rom_file=:AROS` (via a config file or `-s` overrides).

### Next concrete step

The remaining blocker for an end-to-end Android build is wiring in SDL2’s Android Java glue (so `org.libsdl.app.SDLActivity` is available) and ensuring SDL2 native libs are packaged.

ABI choice:

- This repo’s `android/` Gradle module scaffold is configured for `arm64-v8a` only.

To do that cleanly, tell me which ABIs you want first (you picked `arm64-v8a`, which is the best starting point).

Tell me your target(s):

- Flutter **Windows** / **Linux** / **macOS**
- Flutter **Android** (NDK) / **iOS**

…and how you want video:

- Separate native SDL window (fastest to ship)
- Embedded inside Flutter widget (requires platform texture/surface integration)

With that, I can:

- Add a `amiberry_core_set_video_surface(...)` API for your platform
- Add an offscreen framebuffer mode that Flutter can paint
- Add a minimal Flutter plugin scaffold (C++/JNI/ObjC) for the target
