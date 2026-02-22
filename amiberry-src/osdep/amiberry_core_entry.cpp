#include <vector>
#include <cstring>

// Forward declaration of the embeddable entrypoint implemented in amiberry.cpp
int amiberry_run(int argc, char* argv[]);

extern "C" {

// Minimal C ABI for hosting via FFI.
//
// Notes:
// - This currently runs the full SDL-based frontend loop.
// - It is intended for desktop embedding (e.g., Flutter Desktop via dart:ffi),
//   where Amiberry can create/manage its own window.
// - For true in-process rendering into a Flutter texture/surface, additional
//   platform-specific integration is required.
int amiberry_core_run(int argc, const char** argv)
{
	std::vector<char*> argv_mut;
	argv_mut.reserve(static_cast<size_t>(argc) + 1);
	for (int i = 0; i < argc; i++) {
		// Amiberry treats argv as read-only; cast is safe for that usage.
		argv_mut.push_back(const_cast<char*>(argv[i] ? argv[i] : ""));
	}
	argv_mut.push_back(nullptr);
	return amiberry_run(argc, argv_mut.data());
}

}
