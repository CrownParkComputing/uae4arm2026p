/*
 * vk_renderer.h – Lightweight Vulkan frame renderer for Android.
 *
 * Parallel to gles_renderer: uploads Amiga frame pixels to a VkImage,
 * renders a textured full-screen quad via a simple graphics pipeline,
 * and presents through a VkSwapchainKHR.
 *
 * Lifecycle (called from amiberry_gfx.cpp):
 *   vk_init()                → create instance, device, swapchain, pipeline
 *   vk_alloc_frame_texture() → (re)create staging buffer + VkImage for frame
 *   vk_upload_frame()        → memcpy pixels into staging, blit to image
 *   vk_draw_frame()          → record & submit command buffer, present
 *   vk_draw_osd()            → overlay status line (blended quad)
 *   vk_shutdown()            → destroy everything
 */

#pragma once

#if defined(USE_VULKAN) && defined(__ANDROID__)

#include <vulkan/vulkan.h>
#include <SDL.h>

struct VkRenderer {
    // Core Vulkan objects
    VkInstance               instance        = VK_NULL_HANDLE;
    VkPhysicalDevice         physical_device = VK_NULL_HANDLE;
    VkDevice                 device          = VK_NULL_HANDLE;
    VkQueue                  graphics_queue  = VK_NULL_HANDLE;
    uint32_t                 queue_family    = 0;

    // Surface & Swapchain
    VkSurfaceKHR             surface         = VK_NULL_HANDLE;
    VkSwapchainKHR           swapchain       = VK_NULL_HANDLE;
    VkFormat                 swapchain_format = VK_FORMAT_UNDEFINED;
    VkExtent2D               swapchain_extent = {0, 0};
    uint32_t                 image_count     = 0;
    static constexpr uint32_t MAX_SWAPCHAIN_IMAGES = 8;
    VkImage                  swapchain_images[MAX_SWAPCHAIN_IMAGES] = {};
    VkImageView              swapchain_views[MAX_SWAPCHAIN_IMAGES]  = {};
    VkFramebuffer            framebuffers[MAX_SWAPCHAIN_IMAGES]     = {};

    // Render pass & Pipeline
    VkRenderPass             render_pass     = VK_NULL_HANDLE;
    VkPipelineLayout         pipeline_layout = VK_NULL_HANDLE;
    VkPipeline               pipeline        = VK_NULL_HANDLE;

    // Descriptor for frame texture sampler
    VkDescriptorSetLayout    desc_set_layout = VK_NULL_HANDLE;
    VkDescriptorPool         desc_pool       = VK_NULL_HANDLE;
    VkDescriptorSet          desc_set        = VK_NULL_HANDLE;
    VkSampler                sampler         = VK_NULL_HANDLE;

    // Frame texture (Amiga display)
    VkImage                  frame_image     = VK_NULL_HANDLE;
    VkDeviceMemory           frame_memory    = VK_NULL_HANDLE;
    VkImageView              frame_view      = VK_NULL_HANDLE;
    int                      frame_tex_w     = 0;
    int                      frame_tex_h     = 0;

    // Staging buffer for pixel uploads
    VkBuffer                 staging_buffer  = VK_NULL_HANDLE;
    VkDeviceMemory           staging_memory  = VK_NULL_HANDLE;
    VkDeviceSize             staging_size    = 0;
    void*                    staging_mapped  = nullptr;
    bool                     staging_dirty   = false;

    // OSD texture
    VkImage                  osd_image       = VK_NULL_HANDLE;
    VkDeviceMemory           osd_memory      = VK_NULL_HANDLE;
    VkImageView              osd_view        = VK_NULL_HANDLE;
    VkDescriptorSet          osd_desc_set    = VK_NULL_HANDLE;
    int                      osd_tex_w       = 0;
    int                      osd_tex_h       = 0;

    // Vertex buffer (fullscreen quad)
    VkBuffer                 vertex_buffer   = VK_NULL_HANDLE;
    VkDeviceMemory           vertex_memory   = VK_NULL_HANDLE;

    // Command pool & buffers
    VkCommandPool            cmd_pool        = VK_NULL_HANDLE;
    VkCommandBuffer          cmd_buffers[MAX_SWAPCHAIN_IMAGES] = {};

    // Device-level function pointer: bypasses Vulkan loader trampoline to
    // work around Adreno driver crashes in the loader dispatch path.
    PFN_vkBeginCommandBuffer pfnBeginCommandBuffer = nullptr;

    // Synchronization
    VkSemaphore              image_available = VK_NULL_HANDLE;
    VkSemaphore              render_finished = VK_NULL_HANDLE;
    VkFence                  in_flight_fence = VK_NULL_HANDLE;

    // Push constant data
    struct PushConstants {
        float viewport[4]; // x, y, w, h in NDC
        float uv[4];       // u0, v0, u1, v1
        float alpha;
        int   mode;        // 0 = textured, 1 = solid
        float color[4];
        float pad[2];
    };

    bool initialized = false;

    // Cached device name for renderer info display
    char device_name[256] = {};
};

// Initialize Vulkan: instance, device, swapchain, pipeline, etc.
// window must have been created with SDL_WINDOW_VULKAN.
bool vk_init(VkRenderer* r, SDL_Window* window);

// Release all Vulkan resources.
void vk_shutdown(VkRenderer* r);

// Allocate or resize the frame texture + staging buffer.
void vk_alloc_frame_texture(VkRenderer* r, int w, int h);

// Upload amiga_surface pixels to the frame image via staging buffer.
void vk_upload_frame(VkRenderer* r, const SDL_Surface* surface,
                     const SDL_Rect* dirty_rects, int num_dirty, bool full_update);

// Record, submit, and present a frame.
void vk_draw_frame(VkRenderer* r,
                   int drawable_w, int drawable_h,
                   const SDL_Rect* crop, int tex_w, int tex_h,
                   bool stretch_to_fill,
                   bool correct_aspect,
                   int rotation_angle);

// Draw OSD overlay (status line).
void vk_draw_osd(VkRenderer* r,
                 int drawable_w, int drawable_h,
                 const SDL_Surface* osd_surface,
                 int osd_x, int osd_y);

// Begin a frame (acquire swapchain image, begin command buffer).
// Returns false if swapchain is out of date.
bool vk_begin_frame(VkRenderer* r, uint32_t* image_index);

// End a frame (end command buffer, submit, present).
void vk_end_frame(VkRenderer* r, uint32_t image_index);

// Recreate swapchain (e.g. on resize).
bool vk_recreate_swapchain(VkRenderer* r, SDL_Window* window);

#endif // USE_VULKAN && __ANDROID__
