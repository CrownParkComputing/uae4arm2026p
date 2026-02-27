---
layout: default
title: UAE4ARM 2026 — Amiga Emulator for Android
description: UAE4ARM 2026 is an open-source Amiga emulator for Android, natively built from the Amiberry/WinUAE lineage with an arm64 SDL2 core and a modern Android UI.
---

# UAE4ARM 2026

**Open-source Amiga emulator for Android** — a native arm64 port derived from [Amiberry](https://github.com/BlitterStudio/amiberry) and [WinUAE](https://www.winuae.net/), with a modern Android shell, SDL2 rendering, and WHDLoad support built in.

[![Latest Release](https://img.shields.io/github/v/release/CrownParkComputing/uae4arm_2026?label=Latest%20Release&style=for-the-badge)](https://github.com/CrownParkComputing/uae4arm_2026/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](https://github.com/CrownParkComputing/uae4arm_2026/blob/main/LICENSE)

---

## Download

| File | Description |
|------|-------------|
| [uae4arm_2026-release.apk](https://github.com/CrownParkComputing/uae4arm_2026/releases/latest/download/uae4arm_2026-release.apk) | Android APK — sideload directly on your device |
| [uae4arm_2026-release.aab](https://github.com/CrownParkComputing/uae4arm_2026/releases/latest/download/uae4arm_2026-release.aab) | Android App Bundle — for Google Play submission |

---

## Features

- 🎮 **Gamepad support** — Full support for Bluetooth and USB controllers
- 📱 **Touch controls** — On-screen virtual keyboard and joystick overlay
- 💾 **WHDLoad** — Pre-bundled WHDLoad boot files for one-click game launches
- 🖥️ **AROS ROM** — Ships with the AROS Kickstart replacement for legal out-of-box use
- 🔊 **SDL2 audio/video** — Native SDL2 core for smooth rendering and low-latency audio
- 🧩 **arm64 only** — Optimised exclusively for `arm64-v8a` (Android 7.0+)

---

## What Changed from Amiberry to Native

UAE4ARM 2026 starts from the Amiberry source tree and makes the following native Android changes:

### Android-native entry point
- Replaced Amiberry's `main()` / desktop `SDL_main` with a dedicated `amiberry_android_sdlmain.cpp` that integrates into `SDLActivity` (Android lifecycle, JNI, `SDL_main()` export).

### Desktop GUI replaced with Android UI
- All of Amiberry's SDL-based desktop GUI panels (`PanelCPU`, `PanelChipset`, `PanelDisplay`, etc.) are excluded.
- Configuration and game selection are handled by the native Android Java/Kotlin layer instead.
- Stub implementations in `amiberry_gui_stub.cpp` satisfy linker dependencies without pulling in the GTK/SDL GUI stack.

### Virtual keyboard removed
- Amiberry's built-in SDL virtual keyboard (`src/osdep/vkbd/`) is excluded; Android's own input methods and an on-screen overlay are used instead.

### Minimal dependency build
- `AMIBERRY_ANDROID_MINIMAL=ON` is the default for Android builds.
- Removes heavy optional dependencies: `SDL2_image`, `SDL2_ttf`, `libpng`, `mpg123`, `FLAC`, and CHD support.
- Keeps the APK small and avoids build complexity not needed on mobile.

### CMake / NDK build system
- Switched from Amiberry's autotools/`Makefile` to a CMake project targeting the Android NDK (`cmake;3.22.1`, NDK r27).
- SDL2 is vendored as an `external/` git submodule and built alongside the emulator core.

### AROS ROM provisioning
- Amiberry expects ROMs to be pre-placed on disk; UAE4ARM 2026 bundles `aros-rom.bin` / `aros-ext.bin` as Android assets and copies them to app-private storage on first run.

### Storage & permissions
- Integrated Android Storage Access Framework for reading ADF, HDF, and WHD zip files from the user's chosen directories.

### Excluded components (not needed on Android)
| Component | Reason |
|-----------|--------|
| Desktop GUI (`src/osdep/gui/`) | Android Java UI replaces it |
| Virtual keyboard (`src/osdep/vkbd/`) | Android input stack used instead |
| CHD archiver (`src/archivers/chd/`) | Reduces binary size; uncommon on Android |
| PCEm x86 (`src/pcem/`) | PC bridgeboard emulation not needed on mobile |
| FloppyBridge | Requires dedicated USB hardware |
| Libretro core | Separate RetroArch project |
| Desktop packaging (`packaging/`, `debian/`) | Linux desktop only |

---

## System Requirements

- Android 7.0 (API level 24) or higher
- 64-bit ARM device (`arm64-v8a`)
- ~50 MB storage for the app; additional space for ROMs and game files

---

## Quick Start

1. [Download the APK](https://github.com/CrownParkComputing/uae4arm_2026/releases/latest/download/uae4arm_2026-release.apk)
2. On your Android device, enable **Install from unknown sources** (Settings → Security)
3. Open the downloaded APK to install
4. Launch UAE4ARM 2026 and point it to your Amiga ROM and ADF/HDF game files

> **No Kickstart ROM?** The app ships with the AROS Kickstart replacement so you can test without a proprietary ROM right away.

---

## Source Code

The full source is available on GitHub under the GNU GPL v3.0:

- **Private (development) repo**: [CrownParkComputing/uae4arm_2026](https://github.com/CrownParkComputing/uae4arm_2026)
- **Public (source export) repo**: [CrownParkComputing/uae4arm2026p](https://github.com/CrownParkComputing/uae4arm2026p)

The public repo is automatically kept in sync with the development repo via the `export-to-public` GitHub Actions workflow.

---

## License

**GNU General Public License v3.0** — see [LICENSE](https://github.com/CrownParkComputing/uae4arm_2026/blob/main/LICENSE).

Upstream attribution:
- **WinUAE** — Toni Wilen (<https://www.winuae.net/>)
- **Amiberry** — Dimitris Panokostas (<https://amiberry.com/>)