Place AROS ROM replacement files here if you want AmiberryActivity to auto-copy them into app-private storage on first run.

Alternative (preferred): put them under android/roms-src/roms/ and they will still be bundled into the APK
(android/build.gradle adds android/roms-src as an extra assets directory). That folder is ignored by git.

Expected filenames:
- aros-rom.bin
- aros-ext.bin

Notes:
- Do not commit proprietary Kickstart ROMs.
- If you prefer, your Flutter app can copy these files into:
  <app internal storage>/files/amiberry/roms/
  before launching com.uae4arm2026.AmiberryActivity.
