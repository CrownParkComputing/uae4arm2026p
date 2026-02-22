# UAE4ARM 2026

Android-first Amiga emulator build (arm64-v8a) with an SDL2-based native core and a Gradle Android app wrapper.

This project is derived from the WinUAE lineage and the Amiberry codebase. Amiberry is the original upstream source for much of the non-Windows portability and surrounding tooling.

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