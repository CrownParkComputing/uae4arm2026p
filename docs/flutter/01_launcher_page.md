# Flutter UI – Page 1 (Launcher)

Goal: replace Amiberry’s built-in GUI with Flutter pages. This first page is the “Launcher/Boot” page that selects:

- Kickstart ROM (optional; otherwise use AROS)
- DF0 disk image (optional)
- Machine preset (A500 non-AGA / A1200 AGA)
- CPU preset (68000 / 68020)
- Then starts the native `AmiberryActivity` headless (no Amiberry GUI)

## Native contract (already implemented)

`AmiberryActivity` accepts these Android intent extras:

- `com.blitterstudio.amiberry.extra.KICKSTART_ROM_FILE` → absolute path to kickstart ROM
- `com.blitterstudio.amiberry.extra.DF0_DISK_FILE` → absolute path to DF0 disk image
- `com.blitterstudio.amiberry.extra.MACHINE_PRESET` → `A500` or `A1200` (maps to `chipset_compatible`)
- `com.blitterstudio.amiberry.extra.CPU_TYPE` → string like `68000` or `68020`
- `com.blitterstudio.amiberry.extra.SHOW_GUI` → boolean, default `false`

It builds Amiberry CLI args like:

- `-s use_gui=no`
- `-s rom_path=<internal>/files/amiberry/roms/`
- `-s kickstart_rom_file=...` (or `:AROS`)
- `-s chipset_compatible=A500|A1200` (optional)
- `-s cpu_type=68000|68020` (optional)
- `-0 <disk>` (optional)

## Storage strategy (important)

Android 11+ blocks direct reads from `/storage/emulated/0/...` unless you use broad permissions.
Recommended approach for Flutter UI:

1. Use SAF (document picker) to let user choose files.
2. Copy them into app-internal storage:
   - Kickstart → `<app>/files/amiberry/roms/kick.rom`
   - DF0 disk → `<app>/files/amiberry/disks/df0.<ext>`
3. Launch `AmiberryActivity` with those internal absolute paths.

Because the emulator runs inside the same Android app package, it can always read its own internal files.

## Flutter page UI (suggested)

- Kickstart section:
  - Status text: “not selected → using AROS” or show picked filename
  - Button: “Pick Kickstart ROM”
- DF0 section:
  - Status text: “not selected” or show picked filename
  - Button: “Pick DF0 disk (adf/zip/hdf…)”
- Machine preset:
  - Dropdown: A500 (non-AGA) / A1200 (AGA)
- CPU preset:
  - Dropdown: Default / 68000 / 68020
- Start button:
  - “Start Emulator”

## Implementation notes

### Picking + copying files
Use a picker that supports SAF URIs and then copy bytes into internal storage.

Options:
- `file_picker` (popular)
- `file_selector` (official-ish)

You’ll also want:
- `path_provider` to get a writable app directory

Pseudo-flow:

1. `final picked = await FilePicker.platform.pickFiles(...);`
2. Read bytes from picked file (or via platform channel if you get only a URI)
3. Write to a target internal file under `amiberry/roms/` or `amiberry/disks/`

### Starting the emulator activity from Flutter
Two common approaches:

A) If you’re using Flutter inside the same Android app, easiest is `android_intent_plus`:

- Package: `com.blitterstudio.amiberry`
- Component: `com.blitterstudio.amiberry.AmiberryActivity`
- Extras as described above

B) Or implement a small Android `MethodChannel` (“amiberry/launcher”) that starts `AmiberryActivity`.

## Minimal Dart skeleton (UI only)

```dart
enum CpuPreset { defaultPreset, m68000, m68020 }

class LauncherState {
  final String? kickPath;
  final String? df0Path;
  final CpuPreset cpu;
  const LauncherState({this.kickPath, this.df0Path, this.cpu = CpuPreset.defaultPreset});
}

String? cpuTypeValue(CpuPreset cpu) {
  switch (cpu) {
    case CpuPreset.m68000: return '68000';
    case CpuPreset.m68020: return '68020';
    case CpuPreset.defaultPreset: return null;
  }
}
```

## Next pages (after this)

- Page 2: “Machine/Chipset/RAM” (maps to additional `-s` config keys)
- Page 3: “Drives / Hardfiles / WHDLoad”
- Page 4: “Input / Controller mapping”
