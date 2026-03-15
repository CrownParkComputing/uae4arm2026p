/*
 * gles_renderer.cpp – OpenGL ES 3.0 frame renderer for Android.
 *
 * This module handles:
 *   - Shader compilation for textured-quad and solid-color rendering
 *   - Frame texture upload (full or dirty-rect partial updates)
 *   - Aspect-ratio-preserving or stretch-to-fill viewport computation
 *   - OSD (status line) alpha-blended overlay rendering
 *   - Rotation support (0/90/180/270 degrees)
 */

#if defined(USE_OPENGL) && defined(__ANDROID__)

#include "gles_renderer.h"
#include "sysdeps.h"

#include <android/log.h>
#define GLES_LOG(...) __android_log_print(ANDROID_LOG_INFO, "GLES_RENDERER", __VA_ARGS__)

// ─── Shaders ────────────────────────────────────────────────────────────────

static const char* vs_source =
    "#version 300 es\n"
    "precision mediump float;\n"
    "layout(location = 0) in vec4 a_pos_uv;\n"  // xy = position, zw = texcoord
    "out vec2 v_uv;\n"
    "void main() {\n"
    "    gl_Position = vec4(a_pos_uv.xy, 0.0, 1.0);\n"
    "    v_uv = a_pos_uv.zw;\n"
    "}\n";

static const char* fs_source =
    "#version 300 es\n"
    "precision mediump float;\n"
    "precision mediump int;\n"
    "in vec2 v_uv;\n"
    "uniform sampler2D u_texture;\n"
    "uniform float u_alpha;\n"
    "uniform int u_mode;\n"
    "uniform vec4 u_color;\n"
    "out vec4 fragColor;\n"
    "void main() {\n"
    "    if (u_mode == 1) {\n"
    "        fragColor = u_color;\n"
    "    } else {\n"
    "        vec4 c = texture(u_texture, v_uv);\n"
    "        fragColor = vec4(c.rgb, c.a * u_alpha);\n"
    "    }\n"
    "}\n";

// ─── Helpers ────────────────────────────────────────────────────────────────

static GLuint compile_shader(GLenum type, const char* source)
{
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        GLES_LOG("Shader compile error (%s): %s",
                 type == GL_VERTEX_SHADER ? "vert" : "frag", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static void draw_quad(GlesRenderer* r, const GLfloat* verts)
{
    glBindBuffer(GL_ARRAY_BUFFER, r->vbo);
    glBufferData(GL_ARRAY_BUFFER, 4 * 4 * sizeof(GLfloat), verts, GL_STREAM_DRAW);

    glBindVertexArray(r->vao);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), nullptr);
    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

// ─── Public API ─────────────────────────────────────────────────────────────

bool gles_init(GlesRenderer* r)
{
    if (r->initialized) return true;

    GLuint vsh = compile_shader(GL_VERTEX_SHADER, vs_source);
    if (!vsh) return false;

    GLuint fsh = compile_shader(GL_FRAGMENT_SHADER, fs_source);
    if (!fsh) { glDeleteShader(vsh); return false; }

    r->program = glCreateProgram();
    glAttachShader(r->program, vsh);
    glAttachShader(r->program, fsh);
    glLinkProgram(r->program);

    glDeleteShader(vsh);
    glDeleteShader(fsh);

    GLint linked = 0;
    glGetProgramiv(r->program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512];
        glGetProgramInfoLog(r->program, sizeof(log), nullptr, log);
        GLES_LOG("Program link error: %s", log);
        glDeleteProgram(r->program);
        r->program = 0;
        return false;
    }

    r->u_texture = glGetUniformLocation(r->program, "u_texture");
    r->u_alpha   = glGetUniformLocation(r->program, "u_alpha");
    r->u_mode    = glGetUniformLocation(r->program, "u_mode");
    r->u_color   = glGetUniformLocation(r->program, "u_color");

    glGenVertexArrays(1, &r->vao);
    glGenBuffers(1, &r->vbo);

    r->initialized = true;
    GLES_LOG("GLES3 renderer initialized (program=%u)", r->program);

    const char* renderer = (const char*)glGetString(GL_RENDERER);
    const char* version  = (const char*)glGetString(GL_VERSION);
    GLES_LOG("GPU: %s", renderer ? renderer : "unknown");
    GLES_LOG("GLES: %s", version ? version : "unknown");

    return true;
}

void gles_shutdown(GlesRenderer* r)
{
    if (r->frame_tex) { glDeleteTextures(1, &r->frame_tex); r->frame_tex = 0; }
    if (r->osd_tex)   { glDeleteTextures(1, &r->osd_tex);   r->osd_tex = 0; }
    if (r->vbo)       { glDeleteBuffers(1, &r->vbo);         r->vbo = 0; }
    if (r->vao)       { glDeleteVertexArrays(1, &r->vao);    r->vao = 0; }
    if (r->program)   { glDeleteProgram(r->program);          r->program = 0; }
    r->frame_tex_w = r->frame_tex_h = 0;
    r->osd_tex_w = r->osd_tex_h = 0;
    r->initialized = false;
}

void gles_alloc_frame_texture(GlesRenderer* r, int w, int h)
{
    if (w <= 0 || h <= 0) {
        GLES_LOG("Ignoring invalid texture size: %dx%d", w, h);
        return;
    }
    if (r->frame_tex && r->frame_tex_w == w && r->frame_tex_h == h)
        return;

    if (r->frame_tex) {
        glDeleteTextures(1, &r->frame_tex);
        r->frame_tex = 0;
    }

    glGenTextures(1, &r->frame_tex);
    glBindTexture(GL_TEXTURE_2D, r->frame_tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    // Allocate storage: RGBA8 to match SDL_PIXELFORMAT_ABGR8888 (R,G,B,A byte order)
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);

    r->frame_tex_w = w;
    r->frame_tex_h = h;
    GLES_LOG("Frame texture allocated: %dx%d", w, h);
}

void gles_upload_frame(GlesRenderer* r, const SDL_Surface* surface,
                       const SDL_Rect* dirty_rects, int num_dirty, bool full_update)
{
    if (!r->frame_tex || !surface || !surface->pixels) {
        static int s_skip_log = 0;
        if (s_skip_log < 5) {
            s_skip_log++;
            GLES_LOG("gles_upload_frame SKIP: frame_tex=%u surface=%p pixels=%p",
                     r->frame_tex, (void*)surface, surface ? surface->pixels : nullptr);
        }
        return;
    }

    {
        static int s_upload_log = 0;
        if (s_upload_log < 5) {
            s_upload_log++;
            // Sample first pixel to check if surface has data
            uint32_t first_pixel = *reinterpret_cast<const uint32_t*>(surface->pixels);
            GLES_LOG("gles_upload_frame OK: tex=%u %dx%d pitch=%d first_pixel=0x%08X",
                     r->frame_tex, surface->w, surface->h, surface->pitch, first_pixel);
        }
    }

    glBindTexture(GL_TEXTURE_2D, r->frame_tex);

    // Ensure row alignment matches surface pitch
    glPixelStorei(GL_UNPACK_ROW_LENGTH, surface->pitch / surface->format->BytesPerPixel);

    if (full_update || num_dirty <= 0 || !dirty_rects) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, surface->w, surface->h,
                        GL_RGBA, GL_UNSIGNED_BYTE, surface->pixels);
    } else {
        const int bpp = surface->format->BytesPerPixel;
        for (int i = 0; i < num_dirty; i++) {
            const SDL_Rect& rect = dirty_rects[i];
            const void* ptr = static_cast<const uint8_t*>(surface->pixels)
                              + rect.y * surface->pitch + rect.x * bpp;
            glTexSubImage2D(GL_TEXTURE_2D, 0, rect.x, rect.y, rect.w, rect.h,
                            GL_RGBA, GL_UNSIGNED_BYTE, ptr);
        }
    }

    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
}

void gles_draw_frame(GlesRenderer* r,
                     int drawable_w, int drawable_h,
                     const SDL_Rect* crop, int tex_w, int tex_h,
                     bool stretch_to_fill,
                     bool correct_aspect,
                     int rotation_angle)
{
    if (!r->frame_tex || drawable_w <= 0 || drawable_h <= 0) return;

    // Compute source texture coordinates from crop rect
    float u0 = 0.0f, v0 = 0.0f, u1 = 1.0f, v1 = 1.0f;
    int src_w = tex_w, src_h = tex_h;
    if (crop && tex_w > 0 && tex_h > 0) {
        u0 = static_cast<float>(crop->x) / tex_w;
        v0 = static_cast<float>(crop->y) / tex_h;
        u1 = static_cast<float>(crop->x + crop->w) / tex_w;
        v1 = static_cast<float>(crop->y + crop->h) / tex_h;
        src_w = crop->w;
        src_h = crop->h;
    }

    // Compute viewport for aspect ratio preservation
    int vp_x = 0, vp_y = 0, vp_w = drawable_w, vp_h = drawable_h;
    if (!stretch_to_fill && src_w > 0 && src_h > 0) {
        float desired_aspect;
        if (correct_aspect)
            desired_aspect = 4.0f / 3.0f;
        else
            desired_aspect = static_cast<float>(src_w) / src_h;

        // For 90/270 rotation, invert the aspect ratio
        bool rotated = (rotation_angle == 90 || rotation_angle == 270);
        if (rotated) desired_aspect = 1.0f / desired_aspect;

        int dest_w = drawable_w;
        int dest_h = static_cast<int>(drawable_w / desired_aspect);
        if (dest_h > drawable_h) {
            dest_h = drawable_h;
            dest_w = static_cast<int>(drawable_h * desired_aspect);
        }
        vp_x = (drawable_w - dest_w) / 2;
        vp_y = (drawable_h - dest_h) / 2;
        vp_w = dest_w;
        vp_h = dest_h;
    }

    // Handle texture coordinate rotation
    // Default (0°): BL(u0,v1) BR(u1,v1) TR(u1,v0) TL(u0,v0)
    float bl_u, bl_v, br_u, br_v, tr_u, tr_v, tl_u, tl_v;
    switch (rotation_angle) {
    case 90:
        bl_u = u1; bl_v = v1; br_u = u1; br_v = v0;
        tr_u = u0; tr_v = v0; tl_u = u0; tl_v = v1;
        break;
    case 180:
        bl_u = u1; bl_v = v0; br_u = u0; br_v = v0;
        tr_u = u0; tr_v = v1; tl_u = u1; tl_v = v1;
        break;
    case 270:
        bl_u = u0; bl_v = v0; br_u = u0; br_v = v1;
        tr_u = u1; tr_v = v1; tl_u = u1; tl_v = v0;
        break;
    default: // 0
        bl_u = u0; bl_v = v1; br_u = u1; br_v = v1;
        tr_u = u1; tr_v = v0; tl_u = u0; tl_v = v0;
        break;
    }

    // Fullscreen quad in NDC with rotated texcoords
    GLfloat verts[] = {
        -1.0f, -1.0f,  bl_u, bl_v,   // bottom-left
         1.0f, -1.0f,  br_u, br_v,   // bottom-right
         1.0f,  1.0f,  tr_u, tr_v,   // top-right
        -1.0f,  1.0f,  tl_u, tl_v,   // top-left
    };

    glViewport(vp_x, vp_y, vp_w, vp_h);
    glUseProgram(r->program);
    glUniform1i(r->u_mode, 0);
    glUniform1f(r->u_alpha, 1.0f);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, r->frame_tex);
    glUniform1i(r->u_texture, 0);

    draw_quad(r, verts);
}

void gles_draw_osd(GlesRenderer* r,
                   int drawable_w, int drawable_h,
                   const SDL_Surface* osd_surface,
                   int osd_x, int osd_y)
{
    if (!osd_surface || !osd_surface->pixels || drawable_w <= 0 || drawable_h <= 0) return;

    // Upload OSD surface to texture
    if (!r->osd_tex || r->osd_tex_w != osd_surface->w || r->osd_tex_h != osd_surface->h) {
        if (r->osd_tex) glDeleteTextures(1, &r->osd_tex);
        glGenTextures(1, &r->osd_tex);
        glBindTexture(GL_TEXTURE_2D, r->osd_tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, osd_surface->w, osd_surface->h, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        r->osd_tex_w = osd_surface->w;
        r->osd_tex_h = osd_surface->h;
    } else {
        glBindTexture(GL_TEXTURE_2D, r->osd_tex);
    }

    glPixelStorei(GL_UNPACK_ROW_LENGTH, osd_surface->pitch / osd_surface->format->BytesPerPixel);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, osd_surface->w, osd_surface->h,
                    GL_RGBA, GL_UNSIGNED_BYTE, osd_surface->pixels);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);

    // Compute NDC position for OSD quad (screen coords → NDC, Y-flipped for GL)
    // Full-width, positioned at bottom of screen
    float scale_x = static_cast<float>(drawable_w) / osd_surface->w;
    float scaled_h = osd_surface->h * scale_x;

    float x0 = -1.0f;
    float x1 =  1.0f;
    float y0 = -1.0f;
    float y1 = y0 + (scaled_h / drawable_h) * 2.0f;

    GLfloat verts[] = {
        x0, y0, 0.0f, 1.0f,   // bottom-left  (tex flipped: v=1)
        x1, y0, 1.0f, 1.0f,   // bottom-right
        x1, y1, 1.0f, 0.0f,   // top-right     (tex flipped: v=0)
        x0, y1, 0.0f, 0.0f,   // top-left
    };

    glViewport(0, 0, drawable_w, drawable_h);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glUseProgram(r->program);
    glUniform1i(r->u_mode, 0);
    glUniform1f(r->u_alpha, 1.0f);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, r->osd_tex);
    glUniform1i(r->u_texture, 0);

    draw_quad(r, verts);

    glDisable(GL_BLEND);
}

#endif // USE_OPENGL && __ANDROID__
