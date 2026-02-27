# UAE4ARM 2026

Android-first Amiga emulator build (arm64-v8a) with an SDL2-based native core and a Gradle Android app wrapper.

This project is derived from the WinUAE lineage and the Amiberry codebase. Amiberry is the original upstream source for much of the non-Windows portability and surrounding tooling.

## Project Website

📄 **[https://uae4arm.crownparkcomputing.com](https://uae4arm.crownparkcomputing.com)** — download links, feature overview, and full changelog.

## License

This project is released under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the full license text.

### Attribution

This project derives from:
- **WinUAE** - Developed by Toni Wilen (https://www.winuae.net/)
- **Amiberry** - Developed by Dimitris Panokostas (https://amiberry.com/)

Both upstream projects are released under the GNU General Public License.

### License Files
- `LICENSE` - GNU GPL v3.0 (main license)
- `LICENSES/AMIBERRY_LICENSE.txt` - Amiberry license copy
- `LICENSES/AMIBERRY_AUTHORS.txt` - Amiberry authors

## Project Structure

```
uae4arm_2026/
├── amiberry-src/        # Active source code (built into executable)
├── amiberry-upstream/   # Git submodule: upstream Amiberry (reference)
├── winuae-upstream/     # Git submodule: upstream WinUAE (reference)
├── android/             # Android app module (arm64-v8a)
├── external/            # External dependencies (SDL2, libguisan, etc.)
├── data/                # Assets (icons, fonts, VKBD graphics)
├── roms/                # AROS ROMs bundled with APK
├── whdboot/             # WHDLoad boot files
├── cmake/               # CMake build configuration
├── scripts/             # Build and deployment scripts
├── docs/                # Documentation
└── LICENSES/            # License files
```

## Git Submodules

This project uses Git submodules to track upstream sources:

- **amiberry-upstream** - [Amiberry GitHub](https://github.com/BlitterStudio/amiberry)
- **winuae-upstream** - [WinUAE GitHub](https://github.com/tonioni/WinUAE)

### Cloning with Submodules

```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/CrownParkComputing/uae4arm_2026.git

# Or initialize after cloning
git submodule update --init --recursive
```

### Updating Submodules

```bash
# Update to latest upstream
git submodule update --remote amiberry-upstream
git submodule update --remote winuae-upstream
```

## Highlights

- Android app module under `android/` (arm64-v8a only)
- Release tooling under `release_dashboard/` (AAB/APK exports, Play Store assets)
- Native emulator sources under `amiberry-src/` with CMake build
- Upstream sources as git submodules for easy tracking

## Quickstart (Windows)

Build a debug APK:

```powershell
android\gradlew.bat -p android --no-daemon --console=plain assembleDebug
```

Install on a connected device:

```cmd
adb install -r "android\build\outputs\apk\debug\uae4arm_2026-debug.apk"
```

## Documentation

- Source code organization: `amiberry-src/README.md`
- Android module details: `android/README.md`
- Release dashboard: `release_dashboard/README.md`
- Additional docs: `docs/README.md`

## GitHub Actions CI

Automated Android release builds are configured via GitHub Actions. The workflow file is at `.github/workflows/android-release.yml`.

## Source Code Organization

### amiberry-src/
Contains the active source code that is compiled into the UAE4ARM 2026 executable. See `amiberry-src/README.md` for detailed documentation on:
- What is imported from Amiberry
- What is excluded and why
- Android-specific modifications

### amiberry-upstream/ (Git Submodule)
Complete upstream Amiberry source code. Used as reference for:
- Comparing local modifications
- Merging upstream updates
- Understanding original implementation

### winuae-upstream/ (Git Submodule)
Complete upstream WinUAE source code. Used as reference for:
- Original Windows implementation
- Core emulation code
- Understanding Amiga hardware emulation

## What Changed from Amiberry to Native Android

UAE4ARM 2026 starts from the [Amiberry](https://github.com/BlitterStudio/amiberry) source tree and makes the following structural changes to produce a true Android-native build.

### 1. Android-native entry point

| Amiberry (upstream) | UAE4ARM 2026 |
|---------------------|--------------|
| `main()` / `amiberry_run()` desktop entrypoint | `amiberry_android_sdlmain.cpp` exports `SDL_main()` via `libmain.so` |
| Expects desktop OS (Linux/macOS/Windows) | Runs inside `AmiberryActivity extends SDLActivity` |
| Uses desktop file paths | Uses Android app-private storage + Storage Access Framework |

### 2. Desktop GUI replaced with Android UI

- **Removed**: All of Amiberry's SDL/ImGui desktop GUI panels (`PanelCPU`, `PanelChipset`, `PanelDisplay`, `PanelPaths`, etc.).
- **Added**: `amiberry_gui_stub.cpp` — lightweight stubs that satisfy linker dependencies without importing the full GUI stack.
- **Android side**: Configuration, file selection, and settings are handled by the native Java/Kotlin activity.

### 3. Virtual keyboard removed

- **Removed**: `src/osdep/vkbd/vkbd.cpp` and the SDL-rendered virtual keyboard.
- **Android side**: The on-screen keyboard is an Android overlay view, and a virtual joystick is rendered in Java.

### 4. Minimal-dependency build (`AMIBERRY_ANDROID_MINIMAL=ON`)

Enabled by default for all Android CMake builds. This removes:

| Removed dependency | Why |
|--------------------|-----|
| `SDL2_image`, `SDL2_ttf` | Not needed without the desktop GUI |
| `libpng` | Pulled in only by GUI image loading |
| `mpg123`, `FLAC` | Module audio optional; keeps APK small |
| CHD archiver (`src/archivers/chd/`) | Uncommon on Android; large dependency |
| PCEm x86 (`src/pcem/`) | PC bridgeboard emulation not useful on mobile |

### 5. Build system: CMake + Android NDK

| Amiberry | UAE4ARM 2026 |
|----------|--------------|
| autotools / `Makefile` | CMake 3.22.1 |
| GCC/Clang on Linux/macOS | Android NDK r27 (`ndk;27.0.12077973`) |
| Installed system SDL2 | SDL2 vendored in `external/SDL` (built from source) |
| Desktop platform targets | `arm64-v8a` only |

### 6. AROS ROM provisioning

- **Amiberry**: Requires ROMs to be manually placed in the filesystem before launch.
- **UAE4ARM 2026**: Bundles `aros-rom.bin` / `aros-ext.bin` as Android assets and automatically copies them to app-private storage on first launch.

### 7. Storage & permissions

- `android/src/main/java/…/AmiberryActivity.java` integrates with the Android Storage Access Framework (SAF) for accessing ADF, HDF, and game archives from external storage.

### 8. Excluded Amiberry components

| Component | Upstream path | Reason excluded |
|-----------|---------------|-----------------|
| Desktop GUI | `src/osdep/gui/` | Replaced by Android UI |
| Virtual keyboard | `src/osdep/vkbd/` | Android input stack used |
| CHD archiver | `src/archivers/chd/` | Size; uncommon use on mobile |
| PCEm x86 | `src/pcem/` | Not applicable on mobile |
| FloppyBridge | `src/floppybridge/` | Requires dedicated hardware |
| Libretro core | `src/libretro/` | Separate RetroArch project |
| Linux packaging | `packaging/`, `debian/` | Desktop only |

See [`amiberry-src/README.md`](amiberry-src/README.md) for the full file-level inventory.

## What's Imported vs Excluded

### Imported from Amiberry
- Core CPU emulation (68000-68060)
- Custom chip emulation (Agnus, Denise, Paula)
- Memory and expansion handling
- Graphics rendering (line to screen, RTG)
- Audio output
- File system and hard drive support
- Archive handling (LHA, ZIP, DMS)

### Excluded from Android Build
- **Desktop GUI** - Android uses native Java UI
- **Virtual Keyboard** - Android has its own input methods
- **CHD archiver** - Excluded for minimal build size
- **PCEm x86** - Disabled in Android build
- **FloppyBridge** - Real floppy requires hardware
- **Libretro** - Separate RetroArch project

See `amiberry-src/README.md` for complete details.