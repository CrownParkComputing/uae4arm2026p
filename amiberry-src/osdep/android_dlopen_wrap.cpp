#include "sysconfig.h"
#include "sysdeps.h"

#if defined(__ANDROID__) && defined(AMIBERRY_ANDROID_DLOPEN_TRACE)

#include <android/log.h>
#include <dlfcn.h>

extern "C" void* __real_dlopen(const char* filename, int flags);
extern "C" void* __wrap_dlopen(const char* filename, int flags)
{
	void* caller = __builtin_return_address(0);
	Dl_info info;
	const char* soName = "?";
	const char* symName = "?";
	void* symAddr = nullptr;
	if (dladdr(caller, &info) != 0) {
		if (info.dli_fname) {
			soName = info.dli_fname;
		}
		if (info.dli_sname) {
			symName = info.dli_sname;
		}
		symAddr = info.dli_saddr;
	}

	__android_log_print(
		ANDROID_LOG_ERROR,
		"amiberry",
		"WRAP dlopen('%s', 0x%x) caller=%p (%s:%s @ %p)",
		filename ? filename : "(null)",
		flags,
		caller,
		soName,
		symName,
		symAddr);

	return __real_dlopen(filename, flags);
}

#endif
