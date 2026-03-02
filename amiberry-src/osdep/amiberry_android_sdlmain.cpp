// Android entrypoint for SDLActivity.
//
// SDL2 Android expects a shared library named libmain.so that exports SDL_main().
// This wrapper forwards to Amiberry's embeddable entrypoint.

#if defined(__ANDROID__)
#include <jni.h>

#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

#include <SDL.h>
#include <SDL_hints.h>
#ifdef USE_VULKAN
#include <SDL_vulkan.h>
#include <vulkan/vulkan.h>
#endif

#ifdef USE_OPENGL
#include <SDL_opengl.h>
#endif

#include "sysdeps.h"
#include "gui.h"
#include "inputdevice.h"
#include "keyboard.h"
#include "options.h"
#include "vkbd/vkbd.h"

// Android-only runtime toggle: stretch emulation output to fill the full surface.
// 1 = stretch-to-fill, 0 = preserve aspect (letterbox/pillarbox).
std::atomic<int> g_amiberry_android_stretch_to_fill{1};

// Android-only runtime video aspect mode.
// 0 = standard 4:3, 1 = widescreen 16:9.
std::atomic<int> g_amiberry_android_video_aspect_mode{1};

// Flag to enable/disable virtual joystick processing in native code
std::atomic<int> g_amiberry_virtual_joy_enabled{0};

// Custom SDL event type used to deliver Android MotionEvent coordinates to the emulation thread.
// Initialized lazily from the UI thread via SDL_RegisterEvents().
extern "C" Uint32 g_amiberry_vkbd_touch_event_type = 0;

// Custom SDL event type used to deliver media hot-swap requests to the emulation thread.
extern "C" Uint32 g_amiberry_android_media_event_type = 0;

// Custom SDL event type used to deliver save state requests to the emulation thread.
extern "C" Uint32 g_amiberry_android_savestate_event_type = 0;

// Custom SDL event type used to deliver virtual joystick events to the emulation thread.
extern "C" Uint32 g_amiberry_virtual_joy_event_type = 0;

struct AmiberryVkbdTouchPayload {
	int action; // MotionEvent.ACTION_* value
	int x;
	int y;
	int viewW;
	int viewH;
};

struct AmiberryMediaSwapPayload {
	int drive; // 0=DF0, 1=DF1
	char* path; // malloc'd UTF-8 path
};

struct AmiberrySaveStatePayload {
    int slot;
    int mode; // 1=save, 0=load
};

struct AmiberryVirtualJoyPayload {
    int axis;
    int value;
    int button;
    int pressed;
};

static Uint32 ensure_savestate_event_type()
{
	if (g_amiberry_android_savestate_event_type != 0)
		return g_amiberry_android_savestate_event_type;
	const Uint32 t = SDL_RegisterEvents(1);
	if (t != ((Uint32)-1))
		g_amiberry_android_savestate_event_type = t;
	return g_amiberry_android_savestate_event_type;
}

static Uint32 ensure_virtual_joy_event_type()
{
    if (g_amiberry_virtual_joy_event_type != 0)
        return g_amiberry_virtual_joy_event_type;
    const Uint32 t = SDL_RegisterEvents(1);
    if (t != ((Uint32)-1))
        g_amiberry_virtual_joy_event_type = t;
    return g_amiberry_virtual_joy_event_type;
}

static Uint32 ensure_vkbd_touch_event_type()
{
	if (g_amiberry_vkbd_touch_event_type != 0)
		return g_amiberry_vkbd_touch_event_type;
	const Uint32 t = SDL_RegisterEvents(1);
	if (t != ((Uint32)-1))
		g_amiberry_vkbd_touch_event_type = t;
	return g_amiberry_vkbd_touch_event_type;
}

static Uint32 ensure_media_event_type()
{
	if (g_amiberry_android_media_event_type != 0)
		return g_amiberry_android_media_event_type;
	const Uint32 t = SDL_RegisterEvents(1);
	if (t != ((Uint32)-1))
		g_amiberry_android_media_event_type = t;
	return g_amiberry_android_media_event_type;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeToggleVkbd(JNIEnv*, jclass)
{
	// Queue the standard action key for OSK/VKBD.
	// The core will execute it in the emulation thread.
	inputdevice_add_inputcode(AKS_OSK, 1, nullptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeIsVkbdActive(JNIEnv*, jclass)
{
	return vkbd_is_active() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSendVkbdTouch(JNIEnv*, jclass, jint action, jint x, jint y, jint viewW, jint viewH)
{
	const Uint32 t = ensure_vkbd_touch_event_type();
	if (t == 0 || t == ((Uint32)-1))
		return;

	auto* payload = static_cast<AmiberryVkbdTouchPayload*>(SDL_malloc(sizeof(AmiberryVkbdTouchPayload)));
	if (!payload)
		return;
	payload->action = (int)action;
	payload->x = (int)x;
	payload->y = (int)y;
	payload->viewW = (int)viewW;
	payload->viewH = (int)viewH;

	SDL_Event ev;
	SDL_zero(ev);
	ev.type = t;
	ev.user.code = 0;
	ev.user.data1 = payload;
	ev.user.data2 = nullptr;

	if (SDL_PushEvent(&ev) != 1)
	{
		SDL_free(payload);
	}
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeInsertFloppy(JNIEnv* env, jclass, jint drive, jstring path)
{
	if (!env || !path)
		return;
	const Uint32 t = ensure_media_event_type();
	if (t == 0 || t == ((Uint32)-1))
		return;

	const char* utf = env->GetStringUTFChars(path, nullptr);
	if (!utf)
		return;

	auto* payload = static_cast<AmiberryMediaSwapPayload*>(SDL_malloc(sizeof(AmiberryMediaSwapPayload)));
	if (!payload)
	{
		env->ReleaseStringUTFChars(path, utf);
		return;
	}
	payload->drive = (int)drive;
	payload->path = SDL_strdup(utf);
	// release JVM string now
	env->ReleaseStringUTFChars(path, utf);

	if (!payload->path)
	{
		SDL_free(payload);
		return;
	}

	SDL_Event ev;
	SDL_zero(ev);
	ev.type = t;
	ev.user.code = 0;
	ev.user.data1 = payload;
	ev.user.data2 = nullptr;

	if (SDL_PushEvent(&ev) != 1)
	{
		SDL_free(payload->path);
		SDL_free(payload);
	}
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSaveState(JNIEnv* env, jclass, jint slot, jint mode)
{
    const Uint32 t = ensure_savestate_event_type();
    if (t == 0 || t == ((Uint32)-1))
        return;

    auto* payload = static_cast<AmiberrySaveStatePayload*>(SDL_malloc(sizeof(AmiberrySaveStatePayload)));
    if (!payload)
        return;
    payload->slot = (int)slot;
    payload->mode = (int)mode;

    SDL_Event ev;
    SDL_zero(ev);
    ev.type = t;
    ev.user.code = 0;
    ev.user.data1 = payload;
    ev.user.data2 = nullptr;

    if (SDL_PushEvent(&ev) != 1)
    {
        SDL_free(payload);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSendVirtualJoystick(JNIEnv* env, jclass, jint axis, jint value, jint button, jint pressed)
{
    const Uint32 t = ensure_virtual_joy_event_type();
    if (t == 0 || t == ((Uint32)-1))
        return;

    auto* payload = static_cast<AmiberryVirtualJoyPayload*>(SDL_malloc(sizeof(AmiberryVirtualJoyPayload)));
    if (!payload)
        return;
    payload->axis = (int)axis;
    payload->value = (int)value;
    payload->button = (int)button;
    payload->pressed = (int)pressed;

    SDL_Event ev;
    SDL_zero(ev);
    ev.type = t;
    ev.user.code = 0;
    ev.user.data1 = payload;
    ev.user.data2 = nullptr;

    if (SDL_PushEvent(&ev) != 1)
    {
        SDL_free(payload);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSetStretchToFill(JNIEnv*, jclass, jboolean enabled)
{
	g_amiberry_android_stretch_to_fill.store(enabled == JNI_TRUE ? 1 : 0, std::memory_order_relaxed);
	// Best-effort: update SDL hint immediately. The render path also consults the flag.
	SDL_SetHint(SDL_HINT_RENDER_LOGICAL_SIZE_MODE, enabled == JNI_TRUE ? "stretch" : "letterbox");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeGetStretchToFill(JNIEnv*, jclass)
{
	return g_amiberry_android_stretch_to_fill.load(std::memory_order_relaxed) != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSetVideoAspectMode(JNIEnv*, jclass, jint mode)
{
	const int normalized = (mode == 0) ? 0 : 1;
	g_amiberry_android_video_aspect_mode.store(normalized, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeGetVideoAspectMode(JNIEnv*, jclass)
{
	return g_amiberry_android_video_aspect_mode.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeGetRendererDebugInfo(JNIEnv* env, jclass)
{
	if (!env)
		return nullptr;

	auto gfx_api_name = [](int v) -> const char* {
		switch (v) {
			case 0: return "directdraw";
			case 1: return "direct3d";
			case 2: return "direct3d11";
			case 3: return "direct3d11-hdr";
			case 4: return "sdl2";
			default: return "unknown";
		}
	};

	const char* sdl_video = SDL_GetCurrentVideoDriver();
	if (!sdl_video)
		sdl_video = "unknown";

	const char* sdl_render_hint = SDL_GetHint(SDL_HINT_RENDER_DRIVER);
	if (!sdl_render_hint)
		sdl_render_hint = "(default)";

	std::string gl_renderer = "n/a";
	std::string gl_version = "n/a";
#ifdef USE_OPENGL
	if (SDL_GL_GetCurrentContext()) {
		const char* r = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
		const char* v = reinterpret_cast<const char*>(glGetString(GL_VERSION));
		if (r && *r)
			gl_renderer = r;
		if (v && *v)
			gl_version = v;
	}
#endif

	std::string out;
	out.reserve(512);
	out += "build.use_vulkan=";
#ifdef USE_VULKAN
	out += "1";
#else
	out += "0";
#endif
	out += "\n";
	out += "build.use_opengl=";
#ifdef USE_OPENGL
	out += "1";
#else
	out += "0";
#endif
	out += "\n";
	out += "gfx_api=";
	out += gfx_api_name(currprefs.gfx_api);
	out += "\n";
	out += "gfx_api_options=";
	out += (currprefs.gfx_api_options == 1 ? "software" : "hardware");
	out += "\n";
	out += "sdl.video_driver=";
	out += sdl_video;
	out += "\n";
	out += "sdl.render_driver_hint=";
	out += sdl_render_hint;
	out += "\n";
	out += "gl.renderer=";
	out += gl_renderer;
	out += "\n";
	out += "gl.version=";
	out += gl_version;

#ifdef USE_VULKAN
	std::string vk_device_name = "n/a";
	if (SDL_Vulkan_LoadLibrary(nullptr) == 0) {
		using VkGetInstanceProcAddrFn = PFN_vkVoidFunction (*)(VkInstance, const char*);
		auto vkGetInstanceProcAddr = reinterpret_cast<VkGetInstanceProcAddrFn>(SDL_Vulkan_GetVkGetInstanceProcAddr());
		if (vkGetInstanceProcAddr) {
			auto vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
			auto vkDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkDestroyInstance"));
			auto vkEnumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumeratePhysicalDevices"));
			auto vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkGetPhysicalDeviceProperties"));

			if (vkCreateInstance && vkDestroyInstance && vkEnumeratePhysicalDevices && vkGetPhysicalDeviceProperties) {
				VkApplicationInfo app_info{};
				app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
				app_info.pApplicationName = "uae4arm";
				app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
				app_info.pEngineName = "amiberry";
				app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
				app_info.apiVersion = VK_API_VERSION_1_0;

				VkInstanceCreateInfo instance_info{};
				instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
				instance_info.pApplicationInfo = &app_info;

				VkInstance instance = VK_NULL_HANDLE;
				if (vkCreateInstance(&instance_info, nullptr, &instance) == VK_SUCCESS && instance != VK_NULL_HANDLE) {
					auto vkEnumeratePhysicalDevicesInst = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
					auto vkGetPhysicalDevicePropertiesInst = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
					if (vkEnumeratePhysicalDevicesInst && vkGetPhysicalDevicePropertiesInst) {
						uint32_t device_count = 0;
						if (vkEnumeratePhysicalDevicesInst(instance, &device_count, nullptr) == VK_SUCCESS && device_count > 0) {
							std::vector<VkPhysicalDevice> devices(device_count);
							if (vkEnumeratePhysicalDevicesInst(instance, &device_count, devices.data()) == VK_SUCCESS && !devices.empty() && devices[0] != VK_NULL_HANDLE) {
								VkPhysicalDeviceProperties props{};
								vkGetPhysicalDevicePropertiesInst(devices[0], &props);
								if (props.deviceName[0] != '\0') {
									vk_device_name = props.deviceName;
								}
							}
						}
					}
					vkDestroyInstance(instance, nullptr);
				}
			}
		}
		SDL_Vulkan_UnloadLibrary();
	}
	out += "\n";
	out += "vk.device_name=";
	out += vk_device_name;
#else
	out += "\n";
	out += "vk.device_name=n/a";
#endif

	return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeGetCurrentFps(JNIEnv*, jclass)
{
	// gui_data.fps is tracked as 10x FPS in core statusline code.
	const int raw = gui_data.fps;
	if (raw <= 0)
		return 0;
	return (raw + 5) / 10;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSetNtscMode(JNIEnv*, jclass, jboolean ntsc)
{
	const bool enabled = ntsc == JNI_TRUE;
	changed_prefs.ntscmode = enabled;
	currprefs.ntscmode = enabled;
	set_config_changed();
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSetFloppySoundVolumePercent(JNIEnv*, jclass, jint percent)
{
	int p = (int)percent;
	if (p < 0)
		p = 0;
	if (p > 100)
		p = 100;

	const int attenuation = 100 - p;
	const int enabledClick = p > 0 ? 1 : 0;

	for (int i = 0; i < 4; ++i)
	{
		changed_prefs.dfxclickvolume_disk[i] = attenuation;
		changed_prefs.dfxclickvolume_empty[i] = attenuation;
		changed_prefs.floppyslots[i].dfxclick = enabledClick;
		if (enabledClick && currprefs.floppyslots[i].dfxclick == 0)
		{
			currprefs.floppyslots[i].dfxclick = 1;
		}
	}

	set_config_changed();
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSetEmulatorSoundVolumePercent(JNIEnv*, jclass, jint percent)
{
	int p = (int)percent;
	if (p < 0)
		p = 0;
	if (p > 100)
		p = 100;

	const int attenuation = 100 - p;
	changed_prefs.sound_volume_master = attenuation;
	set_config_changed();
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeSetVirtualJoystickEnabled(JNIEnv*, jclass, jboolean enabled)
{
	g_amiberry_virtual_joy_enabled.store(enabled == JNI_TRUE ? 1 : 0, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeIsVirtualJoystickEnabled(JNIEnv*, jclass)
{
	return g_amiberry_virtual_joy_enabled.load(std::memory_order_relaxed) != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uae4arm2026_AmiberryActivity_nativeResetEmulator(JNIEnv*, jclass)
{
	// Queue a soft reset action to be executed on the emulation thread.
	// This is equivalent to the user pressing the reset key combination.
	inputdevice_add_inputcode(AKS_SOFTRESET, 1, nullptr);
}
#endif

int amiberry_run(int argc, char* argv[]);

extern "C" int SDL_main(int argc, char* argv[])
{
	return amiberry_run(argc, argv);
}
