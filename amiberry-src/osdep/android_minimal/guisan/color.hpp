#pragma once

// Minimal stub for Android "minimal" builds.
//
// The upstream Amiberry codebase uses Guichan (guisan) types like gcn::Color in
// option/theme structures even when the desktop GUI is not compiled.
//
// For AMIBERRY_ANDROID_MINIMAL we intentionally do not build/link libguisan.
// This header provides the tiny subset needed to compile.

namespace gcn {

class Color {
public:
    int r{0};
    int g{0};
    int b{0};
    int a{255};

    Color() = default;

    Color(int red, int green, int blue, int alpha = 255)
        : r(red), g(green), b(blue), a(alpha) {}
};

} // namespace gcn
