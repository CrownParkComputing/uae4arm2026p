#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// Runs Amiberry using the provided argv-style arguments.
//
// Typical usage for AROS ROM replacement:
// - Ensure `aros-rom.bin` and `aros-ext.bin` are present in Amiberry's ROMs directory.
// - Use a config that sets `kickstart_rom_file=:AROS`.
int amiberry_core_run(int argc, const char** argv);

#ifdef __cplusplus
}
#endif
