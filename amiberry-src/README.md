# UAE4ARM Amiberry Source Code

This folder contains the active source code compiled into the UAE4ARM Android executable.

## Origin

This code is derived from [Amiberry](https://github.com/BlitterStudio/amiberry) by Dimitris Panokostas (BlitterStudio), which itself is derived from [WinUAE](https://github.com/tonioni/WinUAE) by Toni Wilen.

See the `amiberry-upstream` and `winuae-upstream` git submodules for the complete upstream source.

## License

**GNU General Public License v3.0** - See `LICENSE` file in the project root.

## Imported from Amiberry

### Core Emulation (`/`)
- **CPU Emulation**: `cpuemu_*.cpp`, `newcpu*.cpp`, `cpummu*.cpp`, `cpustbl.cpp`
- **Custom Chips**: `custom.cpp`, `blitter*.cpp`, `cia.cpp`, `disk.cpp`, `audio.cpp`
- **Memory**: `memory.cpp`, `gayle.cpp`, `akiko.cpp`
- **Graphics**: `drawing.cpp`, `linetoscr_*.cpp`, `gfxboard.cpp`, `picasso96.cpp` (via osdep)
- **I/O**: `inputdevice.cpp`, `keyboard.cpp` (via osdep), `serial.cpp`
- **File Systems**: `filesys*.cpp`, `hardfile.cpp`, `fsdb*.cpp`
- **Expansion**: `expansion.cpp`, `a2065.cpp`, `a2091.cpp`, `ncr*_scsi.cpp`
- **CD/CDTV/CD32**: `cdrom.cpp`, `cdtv*.cpp`, `cd32_fmv*.cpp`
- **Debugger**: `debug.cpp`, `debugmem.cpp`, `enforcer.cpp`

### Platform Support (`/osdep/`)
Android-specific implementations:
- `amiberry_android_sdlmain.cpp` - Main entry point for Android
- `amiberry_gfx.cpp` - Graphics output via SDL
- `amiberry_input.cpp` - Input handling
- `amiberry_gui_stub.cpp` - GUI stubs (GUI handled by Android Java)
- `android_minimal/` - Minimal Android-specific code
- `picasso96.cpp` - RTG support

### Archivers (`/archivers/`)
- `lha/`, `zip/`, `dms/` - Archive handling for compressed Amiga files
- `wrp/` - Warp compression

### External Libraries (`/caps/`, `/slirp/`, etc.)
- `caps/` - CAPS/SPS IPF floppy image support
- `slirp/` - Network emulation
- `jit/` - JIT compiler (x86)
- `qemuvga/` - QEMU VGA emulation for RTG

## Excluded from Amiberry

The following Amiberry components are NOT included in this build:

### Desktop GUI (`amiberry-upstream/src/osdep/gui/`)
- Full desktop GUI with panels (PanelCPU, PanelChipset, etc.)
- File/folder dialogs (SelectFile, SelectFolder)
- Controller mapping UI (ControllerMap)
- Theme support
- **Reason**: Android uses native Java UI instead

### Virtual Keyboard (`amiberry-upstream/src/osdep/vkbd/`)
- SDL-based virtual keyboard
- **Reason**: Android has its own input methods

### CHD Support (`amiberry-upstream/src/archivers/chd/`)
- MAME CHD archive format support
- **Reason**: Excluded for minimal Android build, reduces binary size

### PCEm x86 Emulation (`amiberry-upstream/src/pcem/`)
- PCem x86 emulator for PC bridgeboards
- **Reason**: Disabled in Android build configuration

### Other Exclusions
- `floppybridge/` - Real floppy drive support (requires hardware)
- `libretro/` - RetroArch core (separate project)
- `packaging/`, `debian/` - Linux desktop packaging
- `vcpkg.json` - Desktop dependency management

## Modifications from Upstream

### Android-Specific Changes
1. **Entry Point**: Custom `amiberry_android_sdlmain.cpp` for Android lifecycle
2. **GUI**: Stubs in `amiberry_gui_stub.cpp` - UI handled by Java
3. **Storage**: Android storage access framework integration
4. **Input**: Touch screen and gamepad support
5. **Audio**: OpenSL ES audio backend via SDL

### Build System
- CMake instead of autotools
- Android NDK toolchain
- Minimal dependencies (SDL2, mpg123, FLAC)

## Updating from Upstream

To update from upstream Amiberry:

```bash
# Update the submodule
git submodule update --remote amiberry-upstream

# Compare and merge changes
diff -r amiberry-upstream/src/ amiberry-src/
```

See also `winuae-upstream` for the original WinUAE source.