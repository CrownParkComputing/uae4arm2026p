This folder is an optional, git-safe place to put the AROS ROM replacement files so they get bundled into the APK.

Expected files (DO NOT COMMIT ROM BINARIES):
- aros-rom.bin
- aros-ext.bin

Why this exists:
- android/build.gradle adds android/roms-src as an extra Android assets source directory.
- AmiberryActivity will copy assets/roms/{aros-rom.bin,aros-ext.bin} into:
  /data/user/0/<appId>/files/amiberry/roms/
  on first run.

If you prefer, you can also put the files directly into:
- android/src/main/assets/roms/
(but keep them ignored by git)
