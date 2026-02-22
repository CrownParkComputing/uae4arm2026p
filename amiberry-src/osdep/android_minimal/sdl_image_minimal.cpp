#include "SDL_image.h"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <fcntl.h>
#include <unistd.h>

#include <dlfcn.h>

#include <android/log.h>

#include <jni.h>

#include <SDL_system.h>

// Android platform image decoder (API 30+). We load it via dlsym so the
// binary can still start on older Android versions (image load will
// gracefully fail there instead of crashing at load time).
#include <android/bitmap.h>
#include <android/imagedecoder.h>

static char g_img_last_error[256] = {0};

static void log_img_error(const char* file)
{
    __android_log_print(ANDROID_LOG_ERROR, "amiberry", "IMG_Load failed for '%s': %s", file ? file : "(null)",
                        IMG_GetError());
}

static void set_img_error(const char* msg)
{
    if (!msg) msg = "unknown";
    std::snprintf(g_img_last_error, sizeof(g_img_last_error), "%s", msg);
}

static void set_img_errno(const char* context)
{
    const int err = errno;
    std::snprintf(g_img_last_error, sizeof(g_img_last_error), "%s: %s", context ? context : "error",
                  std::strerror(err));
}

struct ImageDecoderApi
{
    void* handle = nullptr;
    int (*createFromFd)(int, AImageDecoder**) = nullptr;
    void (*destroy)(AImageDecoder*) = nullptr;
    const AImageDecoderHeaderInfo* (*getHeaderInfo)(const AImageDecoder*) = nullptr;
    int32_t (*getWidth)(const AImageDecoderHeaderInfo*) = nullptr;
    int32_t (*getHeight)(const AImageDecoderHeaderInfo*) = nullptr;
    int (*setAndroidBitmapFormat)(AImageDecoder*, int32_t) = nullptr;
    size_t (*getMinimumStride)(AImageDecoder*) = nullptr;
    int (*decodeImage)(AImageDecoder*, void*, size_t, size_t) = nullptr;
};

static bool load_image_decoder_api(ImageDecoderApi& api)
{
    static ImageDecoderApi cached;
    static bool initialized = false;
    static bool ok = false;

    if (initialized) {
        api = cached;
        return ok;
    }
    initialized = true;

    void* handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        set_img_error("dlopen(libandroid.so) failed");
        __android_log_print(ANDROID_LOG_ERROR, "amiberry", "%s", IMG_GetError());
        return false;
    }

    cached.handle = handle;
    cached.createFromFd = reinterpret_cast<int (*)(int, AImageDecoder**)>(dlsym(handle, "AImageDecoder_createFromFd"));
    cached.destroy = reinterpret_cast<void (*)(AImageDecoder*)>(dlsym(handle, "AImageDecoder_delete"));
    cached.getHeaderInfo = reinterpret_cast<const AImageDecoderHeaderInfo* (*)(const AImageDecoder*)>(dlsym(handle, "AImageDecoder_getHeaderInfo"));
    cached.getWidth = reinterpret_cast<int32_t (*)(const AImageDecoderHeaderInfo*)>(dlsym(handle, "AImageDecoderHeaderInfo_getWidth"));
    cached.getHeight = reinterpret_cast<int32_t (*)(const AImageDecoderHeaderInfo*)>(dlsym(handle, "AImageDecoderHeaderInfo_getHeight"));
    cached.setAndroidBitmapFormat = reinterpret_cast<int (*)(AImageDecoder*, int32_t)>(dlsym(handle, "AImageDecoder_setAndroidBitmapFormat"));
    cached.getMinimumStride = reinterpret_cast<size_t (*)(AImageDecoder*)>(dlsym(handle, "AImageDecoder_getMinimumStride"));
    cached.decodeImage = reinterpret_cast<int (*)(AImageDecoder*, void*, size_t, size_t)>(dlsym(handle, "AImageDecoder_decodeImage"));

    if (!cached.createFromFd || !cached.destroy || !cached.getHeaderInfo || !cached.getWidth || !cached.getHeight ||
        !cached.setAndroidBitmapFormat || !cached.getMinimumStride || !cached.decodeImage) {
        set_img_error("AImageDecoder symbols not found");
        api = cached;
        return false;
    }

    ok = true;
    api = cached;
    return true;
}

extern "C" {

int IMG_Init(int flags)
{
    (void)flags;
    g_img_last_error[0] = 0;
    return IMG_INIT_PNG;
}

void IMG_Quit(void)
{
}

const char* IMG_GetError(void)
{
    return g_img_last_error[0] ? g_img_last_error : "";
}

static SDL_Surface* img_load_via_java_abgr(const char* file)
{
    JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
    if (!env) {
        set_img_error("JNI env not available");
        return nullptr;
    }

    jclass cls = env->FindClass("com/uae4arm2026/SafFileBridge");
    if (!cls) {
        env->ExceptionClear();
        set_img_error("SafFileBridge class not found");
        return nullptr;
    }

    jmethodID mid = env->GetStaticMethodID(cls, "decodePngFileToAbgr", "(Ljava/lang/String;[I)[B");
    if (!mid) {
        env->ExceptionClear();
        set_img_error("decodePngFileToAbgr method not found");
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    jstring jpath = env->NewStringUTF(file);
    if (!jpath) {
        set_img_error("NewStringUTF failed");
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    jintArray jwh = env->NewIntArray(2);
    if (!jwh) {
        set_img_error("NewIntArray failed");
        env->DeleteLocalRef(jpath);
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    jbyteArray jpixels = static_cast<jbyteArray>(env->CallStaticObjectMethod(cls, mid, jpath, jwh));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        set_img_error("Java decode threw exception");
        jpixels = nullptr;
    }

    jint wh[2] = {0, 0};
    env->GetIntArrayRegion(jwh, 0, 2, wh);

    SDL_Surface* surface = nullptr;
    if (jpixels && wh[0] > 0 && wh[1] > 0) {
        const jsize len = env->GetArrayLength(jpixels);
        const size_t expected = static_cast<size_t>(wh[0]) * static_cast<size_t>(wh[1]) * 4u;
        if (static_cast<size_t>(len) != expected) {
            set_img_error("Java decode returned unexpected size");
        } else {
            surface = SDL_CreateRGBSurfaceWithFormat(0, wh[0], wh[1], 32, SDL_PIXELFORMAT_ABGR8888);
            if (!surface) {
                set_img_error(SDL_GetError());
            } else {
                jboolean isCopy = JNI_FALSE;
                jbyte* src = env->GetByteArrayElements(jpixels, &isCopy);
                if (!src) {
                    SDL_FreeSurface(surface);
                    surface = nullptr;
                    set_img_error("GetByteArrayElements failed");
                } else {
                    std::memcpy(surface->pixels, src, expected);
                    env->ReleaseByteArrayElements(jpixels, src, JNI_ABORT);
                    #if defined(AMIBERRY_ANDROID) && defined(AMIBERRY_VKBD_DEBUG_LOG)
                    __android_log_print(ANDROID_LOG_INFO, "amiberry",
                                        "IMG_Load ok (Java) '%s' (%dx%d)", file, static_cast<int>(wh[0]),
                                        static_cast<int>(wh[1]));
                    #endif
                }
            }
        }
    }

    if (jpixels) env->DeleteLocalRef(jpixels);
    env->DeleteLocalRef(jwh);
    env->DeleteLocalRef(jpath);
    env->DeleteLocalRef(cls);
    return surface;
}

SDL_Surface* IMG_Load(const char* file)
{
    if (!file || !*file) {
        set_img_error("invalid filename");
        log_img_error(file);
        return nullptr;
    }

    ImageDecoderApi api;
    if (!load_image_decoder_api(api)) {
        // Some devices/ROMs don't export the NDK AImageDecoder symbols even on newer
        // Android versions. Fall back to a Java decode path for small UI assets.
        SDL_Surface* surface = img_load_via_java_abgr(file);
        if (surface) return surface;
        log_img_error(file);
        return nullptr;
    }

    const int fd = open(file, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        set_img_errno("open failed");
        log_img_error(file);
        return nullptr;
    }

    AImageDecoder* decoder = nullptr;
    int result = api.createFromFd(fd, &decoder);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS || !decoder) {
        close(fd);
        set_img_error("AImageDecoder_createFromFd failed");
        log_img_error(file);
        return nullptr;
    }

    const AImageDecoderHeaderInfo* info = api.getHeaderInfo(decoder);
    const int32_t w = info ? api.getWidth(info) : 0;
    const int32_t h = info ? api.getHeight(info) : 0;
    if (w <= 0 || h <= 0) {
        api.destroy(decoder);
        close(fd);
        set_img_error("invalid image dimensions");
        log_img_error(file);
        return nullptr;
    }

    result = api.setAndroidBitmapFormat(decoder, ANDROID_BITMAP_FORMAT_RGBA_8888);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        api.destroy(decoder);
        close(fd);
        set_img_error("AImageDecoder_setAndroidBitmapFormat failed");
        log_img_error(file);
        return nullptr;
    }

    SDL_Surface* surface = SDL_CreateRGBSurfaceWithFormat(0, w, h, 32, SDL_PIXELFORMAT_ABGR8888);
    if (!surface) {
        api.destroy(decoder);
        close(fd);
        set_img_error(SDL_GetError());
        log_img_error(file);
        return nullptr;
    }

    const size_t min_stride = api.getMinimumStride(decoder);
    const size_t decode_stride = (min_stride != 0) ? min_stride : (static_cast<size_t>(w) * 4);
    const size_t decode_size = decode_stride * static_cast<size_t>(h);

    // AImageDecoder may require a stride larger than width*4 due to alignment.
    // Decode into a temporary buffer with the required stride, then copy rows
    // into the SDL surface.
    void* decode_buf = malloc(decode_size);
    if (!decode_buf) {
        SDL_FreeSurface(surface);
        api.destroy(decoder);
        close(fd);
        set_img_error("out of memory");
        log_img_error(file);
        return nullptr;
    }

    result = api.decodeImage(decoder, decode_buf, decode_stride, decode_size);
    api.destroy(decoder);
    close(fd);

    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        free(decode_buf);
        SDL_FreeSurface(surface);
        set_img_error("AImageDecoder_decodeImage failed");
        log_img_error(file);
        return nullptr;
    }

    const uint8_t* src = static_cast<const uint8_t*>(decode_buf);
    uint8_t* dst = static_cast<uint8_t*>(surface->pixels);
    const size_t row_bytes = static_cast<size_t>(w) * 4;
    for (int32_t y = 0; y < h; y++) {
        std::memcpy(dst + static_cast<size_t>(y) * static_cast<size_t>(surface->pitch),
                    src + static_cast<size_t>(y) * decode_stride, row_bytes);
    }
    free(decode_buf);

    #if defined(AMIBERRY_ANDROID) && defined(AMIBERRY_VKBD_DEBUG_LOG)
    __android_log_print(ANDROID_LOG_INFO, "amiberry",
                        "IMG_Load ok '%s' (%dx%d) minStride=%zu pitch=%d", file, static_cast<int>(w),
                        static_cast<int>(h), min_stride, surface->pitch);
    #endif

    return surface;
}

SDL_Texture* IMG_LoadTexture(SDL_Renderer* renderer, const char* file)
{
    if (!renderer) {
        set_img_error("invalid renderer");
        return nullptr;
    }

    SDL_Surface* surf = IMG_Load(file);
    if (!surf) return nullptr;

    SDL_Texture* tex = SDL_CreateTextureFromSurface(renderer, surf);
    if (!tex) {
        set_img_error(SDL_GetError());
    }

    SDL_FreeSurface(surf);
    return tex;
}

} // extern "C"
