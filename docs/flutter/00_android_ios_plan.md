# Flutter UI Replacement Plan (Android + iOS)

Target: Replace Amiberry’s built-in GUI with Flutter pages, while keeping the SDL-based emulator core.

## Current state (Android)

- Emulator runs in `com.blitterstudio.amiberry.AmiberryActivity` (SDLActivity).
- Amiberry GUI is **disabled by default** (`use_gui=no`).
- `AmiberryActivity` accepts intent extras for Kickstart / DF0 / CPU preset.

This means Flutter can be a pure UI layer that only:
- selects files/settings
- copies selected files into app-internal storage
- launches `AmiberryActivity` with extras

## Recommended architecture

### Android

Option A (recommended now): **Flutter Launcher screens + start native SDL activity**

- App starts in Flutter (a Flutter activity)
- Page 1 (Launcher) selects Kickstart/DF0/CPU
- Pressing Start launches `AmiberryActivity`

Pros: simplest, stable SDL lifecycle.

Option B (later): **Flutter overlay on top of SDL surface**

- Keep `AmiberryActivity` as the single Activity
- Embed a Flutter view into the overlay container (existing `amiberry_gui_container`)

Pros: seamless UI over emulator.
Cons: more complex input focus, lifecycle, and performance tuning.

### iOS

- Flutter UI runs in the main iOS app
- Pressing Start presents an SDL-backed `UIViewController` that runs Amiberry

Note: iOS requires platform work for:
- SDL view/controller lifecycle
- audio session
- controller/keyboard input mapping

## Flutter Page 1 (Launcher)

See `docs/flutter/01_launcher_page.md`.

## Native launch contract (Android)

Launch `com.blitterstudio.amiberry.AmiberryActivity` with extras:

- `com.blitterstudio.amiberry.extra.KICKSTART_ROM_FILE` → `/data/user/0/<pkg>/files/amiberry/roms/kick.rom`
- `com.blitterstudio.amiberry.extra.DF0_DISK_FILE` → `/data/user/0/<pkg>/files/amiberry/disks/df0.zip` (or `.adf`)
- `com.blitterstudio.amiberry.extra.CPU_TYPE` → `68000` or `68020`
- `com.blitterstudio.amiberry.extra.SHOW_GUI` → `false`

## File picking (important)

Do not rely on raw `/storage/emulated/0/...` paths on Android 11+.
Use a document picker (SAF) and copy bytes into app-internal files.

## Repo scaffolding

A starter Flutter plugin scaffold is provided at:

- `flutter/amiberry_launcher/`

It implements:
- `getAmiberryPaths()` (Android + iOS)
- `startEmulator()` (Android; iOS stub)

This is intended as the first bridge for Page 1.
