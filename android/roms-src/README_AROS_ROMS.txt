Optional local AROS ROM drop folder (not committed)

Put these files here to have them bundled into the APK as assets/roms/*:

  android/roms-src/roms/aros-rom.bin
  android/roms-src/roms/aros-ext.bin

Why:
- android/build.gradle adds android/roms-src as an additional Android assets source directory.
- On first run, AmiberryActivity copies assets/roms/{aros-rom.bin,aros-ext.bin} into:
  /data/user/0/<appId>/files/amiberry/roms/

Notes:
- Keep ROM binaries out of git (.gitignore already ignores them).
- This README is intentionally not under roms/ to avoid duplicating the existing assets/roms/README.txt.
