# Android Flutter Overlay Setup (SDLActivity host)

This repo’s Android app (`android/`) hosts the emulator in `AmiberryActivity` (SDLActivity).
We now have an overlay container (`amiberry_gui_container`) where Flutter UI can be embedded.

## What is already implemented

- `AmiberryActivity` builds and runs without Flutter.
- If Flutter embedding classes are present at runtime, `AmiberryActivity` will attach a FlutterView overlay.
  - Implementation is reflection-based in `android/src/main/java/com/blitterstudio/amiberry/FlutterOverlay.java`.
  - Without Flutter, it logs `Flutter classes not found; overlay disabled`.

## What you must add (to actually see Flutter UI)

Because Flutter isn’t installed in this environment yet, the repo does **not** include generated Flutter AARs.
To embed Flutter as an overlay, you need to:

1) Install Flutter SDK on your dev machine

- https://docs.flutter.dev/get-started/install

2) Create a Flutter module that will be embedded

Example (run from repo root):

- `flutter create -t module flutter_overlay_ui`

This creates `flutter_overlay_ui/` with Android and iOS host artifacts.

3) Build AARs for Android

From the module directory:

- `cd flutter_overlay_ui`
- `flutter build aar`

Flutter will generate a local Maven repository containing:
- Flutter engine AARs
- Your module AAR(s)

4) Wire the generated Maven repo into this Android app

In `android/build.gradle` add the generated Maven repo path to `repositories {}` and add the module dependency.
Flutter prints the exact Gradle snippets after `flutter build aar`.

5) Confirm the overlay attaches

Run the Android app. In logcat you should see:

- `FlutterOverlay: Flutter overlay attached`

…and your Flutter UI should render on top of the SDL surface.

## Notes

- This overlay approach is **single-activity** and enables a slick Flutter UI on top of the emulator.
- For a real product, you’ll likely want a channel to:
  - show/hide the overlay
  - pass inputs (virtual keyboard, UI buttons) to the emulator
  - apply settings (Kickstart/DF0/CPU/etc) by restarting or writing config files

Those pieces can be built incrementally page-by-page.
