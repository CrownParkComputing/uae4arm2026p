#pragma once

// Minimal SDL_image-compatible header used only for Android minimal builds.
// This is NOT full SDL_image; it only provides what Amiberry needs.

#include <SDL.h>

#ifdef __cplusplus
extern "C" {
#endif

// Flags are kept for compatibility; this minimal implementation ignores them.
#ifndef IMG_INIT_PNG
#define IMG_INIT_PNG 0x00000002
#endif

int IMG_Init(int flags);
void IMG_Quit(void);
const char* IMG_GetError(void);

SDL_Surface* IMG_Load(const char* file);
SDL_Texture* IMG_LoadTexture(SDL_Renderer* renderer, const char* file);

#ifdef __cplusplus
}
#endif
