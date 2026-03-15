/*
 * gles_renderer.h – Lightweight OpenGL ES 3.0 frame renderer for Android.
 *
 * Replaces SDL_Renderer with direct GLES3 calls:
 *   CPU emulation -> SDL_Surface -> glTexSubImage2D -> shader quad -> swapchain
 *
 * Benefits over SDL_Renderer path:
 *   - No SDL abstraction overhead per frame
 *   - Direct texture streaming (no intermediate format checks)
 *   - Foundation for CRT / scanline shaders
 */

#pragma once

#if defined(USE_OPENGL) && defined(__ANDROID__)

#include <GLES3/gl3.h>
#include <SDL.h>

struct GlesRenderer {
    GLuint program    = 0;
    GLint  u_texture  = -1;
    GLint  u_alpha    = -1;
    GLint  u_mode     = -1;   // 0 = textured, 1 = solid color fill
    GLint  u_color    = -1;   // solid color for mode 1

    GLuint frame_tex  = 0;    // Amiga frame texture
    int    frame_tex_w = 0;
    int    frame_tex_h = 0;

    GLuint osd_tex    = 0;    // Status line texture
    int    osd_tex_w  = 0;
    int    osd_tex_h  = 0;

    GLuint vao = 0;
    GLuint vbo = 0;

    bool   initialized = false;
};

// Initialize the GLES3 renderer (compile shaders, create buffers).
bool gles_init(GlesRenderer* r);

// Release all GPU resources.
void gles_shutdown(GlesRenderer* r);

// Allocate or resize the frame texture.
void gles_alloc_frame_texture(GlesRenderer* r, int w, int h);

// Upload amiga_surface pixels to the frame texture.
// If full_update is false and dirty_rects/num_dirty are provided, only those rects are updated.
void gles_upload_frame(GlesRenderer* r, const SDL_Surface* surface,
                       const SDL_Rect* dirty_rects, int num_dirty, bool full_update);

// Render the frame texture to screen with aspect ratio and rotation.
void gles_draw_frame(GlesRenderer* r,
                     int drawable_w, int drawable_h,
                     const SDL_Rect* crop, int tex_w, int tex_h,
                     bool stretch_to_fill,
                     bool correct_aspect,
                     int rotation_angle);

// Render the OSD (status line) overlay at the given position.
void gles_draw_osd(GlesRenderer* r,
                   int drawable_w, int drawable_h,
                   const SDL_Surface* osd_surface,
                   int osd_x, int osd_y);

#endif // USE_OPENGL && __ANDROID__
