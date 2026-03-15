/*
 * vk_renderer.cpp – Vulkan frame renderer for Android.
 *
 * A self-contained Vulkan backend that mirrors the gles_renderer API.
 * Uses inline SPIR-V shaders (compiled from equivalent GLSL) to avoid
 * any external shader file dependencies.
 */

#if defined(USE_VULKAN) && defined(__ANDROID__)

#include "vk_renderer.h"
#include <SDL_vulkan.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <algorithm>
#include <vector>

#define VK_LOG(...) __android_log_print(ANDROID_LOG_INFO, "VK_RENDERER", __VA_ARGS__)
#define VK_ERR(...) __android_log_print(ANDROID_LOG_ERROR, "VK_RENDERER", __VA_ARGS__)

// ─── Pre-compiled SPIR-V shaders (generated offline with glslc) ──────────────
#include "shaders/vert_spv.inc"
#include "shaders/frag_spv.inc"

// ─── Helper: find memory type ────────────────────────────────────────────────
static uint32_t find_memory_type(VkPhysicalDevice pd, uint32_t type_filter, VkMemoryPropertyFlags props)
{
    VkPhysicalDeviceMemoryProperties mem_props;
    vkGetPhysicalDeviceMemoryProperties(pd, &mem_props);
    for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
        if ((type_filter & (1 << i)) && (mem_props.memoryTypes[i].propertyFlags & props) == props)
            return i;
    }
    VK_ERR("Failed to find suitable memory type!");
    return 0;
}

// ─── Helper: create buffer ───────────────────────────────────────────────────
static bool create_buffer(VkRenderer* r, VkDeviceSize size, VkBufferUsageFlags usage,
                          VkMemoryPropertyFlags props, VkBuffer& buffer, VkDeviceMemory& memory)
{
    VkBufferCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    ci.size = size;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(r->device, &ci, nullptr, &buffer) != VK_SUCCESS) return false;

    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(r->device, buffer, &req);

    VkMemoryAllocateInfo ai = {};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = find_memory_type(r->physical_device, req.memoryTypeBits, props);

    if (vkAllocateMemory(r->device, &ai, nullptr, &memory) != VK_SUCCESS) return false;
    vkBindBufferMemory(r->device, buffer, memory, 0);
    return true;
}

// ─── Helper: create image ────────────────────────────────────────────────────
static bool create_image(VkRenderer* r, uint32_t w, uint32_t h, VkFormat format,
                         VkImageUsageFlags usage, VkImage& image, VkDeviceMemory& memory)
{
    VkImageCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = format;
    ci.extent = {w, h, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(r->device, &ci, nullptr, &image) != VK_SUCCESS) return false;

    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(r->device, image, &req);

    VkMemoryAllocateInfo ai = {};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = find_memory_type(r->physical_device, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    if (vkAllocateMemory(r->device, &ai, nullptr, &memory) != VK_SUCCESS) return false;
    vkBindImageMemory(r->device, image, memory, 0);
    return true;
}

// ─── Helper: create image view ───────────────────────────────────────────────
static VkImageView create_image_view(VkDevice device, VkImage image, VkFormat format)
{
    VkImageViewCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    ci.image = image;
    ci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    ci.format = format;
    ci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    ci.subresourceRange.levelCount = 1;
    ci.subresourceRange.layerCount = 1;

    VkImageView view = VK_NULL_HANDLE;
    vkCreateImageView(device, &ci, nullptr, &view);
    return view;
}

// ─── Helper: single-shot command buffer ──────────────────────────────────────
static VkCommandBuffer begin_single_cmd(VkRenderer* r)
{
    VkCommandBufferAllocateInfo ai = {};
    ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool = r->cmd_pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkResult res = vkAllocateCommandBuffers(r->device, &ai, &cmd);
    if (res != VK_SUCCESS) {
        VK_ERR("begin_single_cmd: vkAllocateCommandBuffers failed (%d)", res);
        return VK_NULL_HANDLE;
    }

    VkCommandBufferBeginInfo bi = {};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = 0;
    bi.pNext = nullptr;
    bi.pInheritanceInfo = nullptr;
    res = r->pfnBeginCommandBuffer(cmd, &bi);
    if (res != VK_SUCCESS) {
        VK_ERR("begin_single_cmd: vkBeginCommandBuffer failed (%d)", res);
        vkFreeCommandBuffers(r->device, r->cmd_pool, 1, &cmd);
        return VK_NULL_HANDLE;
    }
    return cmd;
}

static void end_single_cmd(VkRenderer* r, VkCommandBuffer cmd)
{
    if (cmd == VK_NULL_HANDLE) return;
    vkEndCommandBuffer(cmd);

    VkSubmitInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    vkQueueSubmit(r->graphics_queue, 1, &si, VK_NULL_HANDLE);
    vkQueueWaitIdle(r->graphics_queue);
    vkFreeCommandBuffers(r->device, r->cmd_pool, 1, &cmd);
}

// ─── Helper: transition image layout ─────────────────────────────────────────
static void transition_image(VkRenderer* r, VkImage image,
                             VkImageLayout old_layout, VkImageLayout new_layout,
                             VkAccessFlags src_access, VkAccessFlags dst_access,
                             VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage)
{
    VkCommandBuffer cmd = begin_single_cmd(r);
    if (cmd == VK_NULL_HANDLE) return;

    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;

    vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    end_single_cmd(r, cmd);
}

// ─── Helper: transition image layout (in existing command buffer) ────────────
static void cmd_transition_image(VkCommandBuffer cmd, VkImage image,
                                 VkImageLayout old_layout, VkImageLayout new_layout,
                                 VkAccessFlags src_access, VkAccessFlags dst_access,
                                 VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage)
{
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;

    vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

// ─── Create shader module ────────────────────────────────────────────────────
static VkShaderModule create_shader_module(VkDevice device, const uint32_t* code, size_t code_size)
{
    VkShaderModuleCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = code_size;
    ci.pCode = code;

    VkShaderModule mod = VK_NULL_HANDLE;
    if (vkCreateShaderModule(device, &ci, nullptr, &mod) != VK_SUCCESS) {
        VK_ERR("Failed to create shader module");
    }
    return mod;
}

// ─── Create swapchain ────────────────────────────────────────────────────────
static bool create_swapchain(VkRenderer* r, SDL_Window* window)
{
    VkSurfaceCapabilitiesKHR caps;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(r->physical_device, r->surface, &caps);

    // Choose format
    uint32_t format_count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(r->physical_device, r->surface, &format_count, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(format_count);
    vkGetPhysicalDeviceSurfaceFormatsKHR(r->physical_device, r->surface, &format_count, formats.data());

    VkSurfaceFormatKHR chosen = formats[0];
    for (auto& f : formats) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = f;
            break;
        }
        if (f.format == VK_FORMAT_B8G8R8A8_UNORM && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = f;
        }
    }
    r->swapchain_format = chosen.format;

    // Choose present mode
    uint32_t pm_count = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(r->physical_device, r->surface, &pm_count, nullptr);
    std::vector<VkPresentModeKHR> modes(pm_count);
    vkGetPhysicalDeviceSurfacePresentModesKHR(r->physical_device, r->surface, &pm_count, modes.data());

    VkPresentModeKHR present_mode = VK_PRESENT_MODE_FIFO_KHR; // always available
    for (auto m : modes) {
        if (m == VK_PRESENT_MODE_MAILBOX_KHR) { present_mode = m; break; }
    }

    // Extent — prefer SDL drawable size so we always get landscape dimensions.
    // On some Android drivers, caps.currentExtent may reflect pre-rotated
    // (portrait) dimensions when currentTransform includes ROTATE_90.
    {
        int w, h;
        SDL_Vulkan_GetDrawableSize(window, &w, &h);
        VK_LOG("Extent: caps.currentExtent=%ux%u  SDL_drawable=%dx%d",
               caps.currentExtent.width, caps.currentExtent.height, w, h);

        if (caps.currentExtent.width != UINT32_MAX) {
            r->swapchain_extent = caps.currentExtent;
        } else {
            r->swapchain_extent.width = std::max(caps.minImageExtent.width,
                                                 std::min(caps.maxImageExtent.width, (uint32_t)w));
            r->swapchain_extent.height = std::max(caps.minImageExtent.height,
                                                  std::min(caps.maxImageExtent.height, (uint32_t)h));
        }

        // Safety: if extent is portrait-shaped but SDL says landscape, use SDL dims.
        if (r->swapchain_extent.width < r->swapchain_extent.height && w > h) {
            VK_LOG("Extent is portrait but drawable is landscape — swapping");
            std::swap(r->swapchain_extent.width, r->swapchain_extent.height);
        }
    }

    uint32_t img_count = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && img_count > caps.maxImageCount)
        img_count = caps.maxImageCount;
    if (img_count > VkRenderer::MAX_SWAPCHAIN_IMAGES)
        img_count = VkRenderer::MAX_SWAPCHAIN_IMAGES;

    VkSwapchainCreateInfoKHR ci = {};
    ci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    ci.surface = r->surface;
    ci.minImageCount = img_count;
    ci.imageFormat = chosen.format;
    ci.imageColorSpace = chosen.colorSpace;
    ci.imageExtent = r->swapchain_extent;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;

    // Let the compositor handle orientation rotation instead of pre-rotating.
    // On portrait-natural Android devices in landscape mode, currentTransform
    // is ROTATE_90 — passing that as preTransform would produce portrait output
    // because the app does not pre-rotate its rendering.
    ci.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    VK_LOG("Surface currentTransform=0x%x, using IDENTITY preTransform",
           caps.currentTransform);

    // Pick a supported composite alpha mode
    VkCompositeAlphaFlagBitsKHR composite = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    const VkCompositeAlphaFlagBitsKHR preferred[] = {
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
    };
    for (auto ca : preferred) {
        if (caps.supportedCompositeAlpha & ca) { composite = ca; break; }
    }
    ci.compositeAlpha = composite;
    ci.presentMode = present_mode;
    ci.clipped = VK_TRUE;
    ci.oldSwapchain = r->swapchain; // for recreation

    if (vkCreateSwapchainKHR(r->device, &ci, nullptr, &r->swapchain) != VK_SUCCESS) {
        VK_ERR("Failed to create swapchain");
        return false;
    }

    // Get images
    vkGetSwapchainImagesKHR(r->device, r->swapchain, &r->image_count, nullptr);
    if (r->image_count > VkRenderer::MAX_SWAPCHAIN_IMAGES)
        r->image_count = VkRenderer::MAX_SWAPCHAIN_IMAGES;
    vkGetSwapchainImagesKHR(r->device, r->swapchain, &r->image_count, r->swapchain_images);

    // Create image views
    for (uint32_t i = 0; i < r->image_count; i++) {
        r->swapchain_views[i] = create_image_view(r->device, r->swapchain_images[i], r->swapchain_format);
    }

    VK_LOG("Swapchain created: %ux%u, %u images, format=%d",
           r->swapchain_extent.width, r->swapchain_extent.height, r->image_count, r->swapchain_format);
    return true;
}

// ─── Create render pass ──────────────────────────────────────────────────────
static bool create_render_pass(VkRenderer* r)
{
    VkAttachmentDescription color_attach = {};
    color_attach.format = r->swapchain_format;
    color_attach.samples = VK_SAMPLE_COUNT_1_BIT;
    color_attach.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color_attach.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color_attach.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color_attach.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color_attach.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    color_attach.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference color_ref = {};
    color_ref.attachment = 0;
    color_ref.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass = {};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &color_ref;

    VkSubpassDependency dep = {};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.srcAccessMask = 0;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount = 1;
    ci.pAttachments = &color_attach;
    ci.subpassCount = 1;
    ci.pSubpasses = &subpass;
    ci.dependencyCount = 1;
    ci.pDependencies = &dep;

    if (vkCreateRenderPass(r->device, &ci, nullptr, &r->render_pass) != VK_SUCCESS) {
        VK_ERR("Failed to create render pass");
        return false;
    }
    return true;
}

// ─── Create framebuffers ─────────────────────────────────────────────────────
static bool create_framebuffers(VkRenderer* r)
{
    for (uint32_t i = 0; i < r->image_count; i++) {
        VkFramebufferCreateInfo ci = {};
        ci.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        ci.renderPass = r->render_pass;
        ci.attachmentCount = 1;
        ci.pAttachments = &r->swapchain_views[i];
        ci.width = r->swapchain_extent.width;
        ci.height = r->swapchain_extent.height;
        ci.layers = 1;

        if (vkCreateFramebuffer(r->device, &ci, nullptr, &r->framebuffers[i]) != VK_SUCCESS) {
            VK_ERR("Failed to create framebuffer %u", i);
            return false;
        }
    }
    return true;
}

// ─── Create graphics pipeline ────────────────────────────────────────────────
static bool create_pipeline(VkRenderer* r)
{
    // Create shader modules from pre-compiled SPIR-V
    VkShaderModule vert_mod = create_shader_module(r->device, vert_spv, vert_spv_size);
    VkShaderModule frag_mod = create_shader_module(r->device, frag_spv, frag_spv_size);
    if (!vert_mod || !frag_mod) {
        if (vert_mod) vkDestroyShaderModule(r->device, vert_mod, nullptr);
        if (frag_mod) vkDestroyShaderModule(r->device, frag_mod, nullptr);
        return false;
    }

    VkPipelineShaderStageCreateInfo stages[2] = {};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert_mod;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag_mod;
    stages[1].pName = "main";

    // Vertex input: vec2 position
    VkVertexInputBindingDescription binding = {};
    binding.binding = 0;
    binding.stride = sizeof(float) * 2;
    binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    VkVertexInputAttributeDescription attr = {};
    attr.location = 0;
    attr.binding = 0;
    attr.format = VK_FORMAT_R32G32_SFLOAT;
    attr.offset = 0;

    VkPipelineVertexInputStateCreateInfo vertex_input = {};
    vertex_input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vertex_input.vertexBindingDescriptionCount = 1;
    vertex_input.pVertexBindingDescriptions = &binding;
    vertex_input.vertexAttributeDescriptionCount = 1;
    vertex_input.pVertexAttributeDescriptions = &attr;

    VkPipelineInputAssemblyStateCreateInfo input_asm = {};
    input_asm.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    input_asm.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    // Dynamic viewport and scissor
    VkDynamicState dyn_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic_state = {};
    dynamic_state.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamic_state.dynamicStateCount = 2;
    dynamic_state.pDynamicStates = dyn_states;

    VkPipelineViewportStateCreateInfo viewport_state = {};
    viewport_state.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewport_state.viewportCount = 1;
    viewport_state.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo raster = {};
    raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo multisample = {};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    // No alpha blending for the opaque Amiga frame quad.
    // (The Amiga surface has alpha=0 which would make all pixels invisible
    //  if blended.  The GL path also draws the frame without blending.)
    VkPipelineColorBlendAttachmentState blend_attach = {};
    blend_attach.blendEnable = VK_FALSE;
    blend_attach.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                  VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    VkPipelineColorBlendStateCreateInfo blend = {};
    blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    blend.attachmentCount = 1;
    blend.pAttachments = &blend_attach;

    // Descriptor set layout: one combined image sampler
    VkDescriptorSetLayoutBinding sampler_binding = {};
    sampler_binding.binding = 0;
    sampler_binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    sampler_binding.descriptorCount = 1;
    sampler_binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo dsl_ci = {};
    dsl_ci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dsl_ci.bindingCount = 1;
    dsl_ci.pBindings = &sampler_binding;
    vkCreateDescriptorSetLayout(r->device, &dsl_ci, nullptr, &r->desc_set_layout);

    // Push constant range
    VkPushConstantRange pc_range = {};
    pc_range.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    pc_range.offset = 0;
    pc_range.size = sizeof(VkRenderer::PushConstants);

    VkPipelineLayoutCreateInfo layout_ci = {};
    layout_ci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layout_ci.setLayoutCount = 1;
    layout_ci.pSetLayouts = &r->desc_set_layout;
    layout_ci.pushConstantRangeCount = 1;
    layout_ci.pPushConstantRanges = &pc_range;
    vkCreatePipelineLayout(r->device, &layout_ci, nullptr, &r->pipeline_layout);

    VkGraphicsPipelineCreateInfo pipe_ci = {};
    pipe_ci.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipe_ci.stageCount = 2;
    pipe_ci.pStages = stages;
    pipe_ci.pVertexInputState = &vertex_input;
    pipe_ci.pInputAssemblyState = &input_asm;
    pipe_ci.pViewportState = &viewport_state;
    pipe_ci.pRasterizationState = &raster;
    pipe_ci.pMultisampleState = &multisample;
    pipe_ci.pColorBlendState = &blend;
    pipe_ci.pDynamicState = &dynamic_state;
    pipe_ci.layout = r->pipeline_layout;
    pipe_ci.renderPass = r->render_pass;
    pipe_ci.subpass = 0;

    VkResult result = vkCreateGraphicsPipelines(r->device, VK_NULL_HANDLE, 1, &pipe_ci, nullptr, &r->pipeline);

    vkDestroyShaderModule(r->device, vert_mod, nullptr);
    vkDestroyShaderModule(r->device, frag_mod, nullptr);

    if (result != VK_SUCCESS) {
        VK_ERR("Failed to create graphics pipeline");
        return false;
    }

    VK_LOG("Vulkan graphics pipeline created");
    return true;
}

// ─── Create descriptor pool and sets ─────────────────────────────────────────
static bool create_descriptors(VkRenderer* r)
{
    // Pool: 2 combined image samplers (frame + OSD)
    VkDescriptorPoolSize pool_size = {};
    pool_size.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    pool_size.descriptorCount = 2;

    VkDescriptorPoolCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.maxSets = 2;
    ci.poolSizeCount = 1;
    ci.pPoolSizes = &pool_size;

    if (vkCreateDescriptorPool(r->device, &ci, nullptr, &r->desc_pool) != VK_SUCCESS)
        return false;

    // Allocate frame descriptor set
    VkDescriptorSetAllocateInfo ai = {};
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = r->desc_pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &r->desc_set_layout;
    vkAllocateDescriptorSets(r->device, &ai, &r->desc_set);

    // Allocate OSD descriptor set
    vkAllocateDescriptorSets(r->device, &ai, &r->osd_desc_set);

    // Create sampler
    VkSamplerCreateInfo sci = {};
    sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    vkCreateSampler(r->device, &sci, nullptr, &r->sampler);

    return true;
}

// ─── Update descriptor set to point to an image view ─────────────────────────
static void update_descriptor(VkRenderer* r, VkDescriptorSet set, VkImageView view)
{
    VkDescriptorImageInfo img_info = {};
    img_info.sampler = r->sampler;
    img_info.imageView = view;
    img_info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    VkWriteDescriptorSet write = {};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = set;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &img_info;

    vkUpdateDescriptorSets(r->device, 1, &write, 0, nullptr);
}

// ─── Create vertex buffer (fullscreen quad) ──────────────────────────────────
static bool create_vertex_buffer(VkRenderer* r)
{
    // Fullscreen quad: 6 vertices (two triangles), just 2D positions
    float verts[] = {
        -1.0f, -1.0f,  // BL
         1.0f, -1.0f,  // BR
         1.0f,  1.0f,  // TR
        -1.0f, -1.0f,  // BL
         1.0f,  1.0f,  // TR
        -1.0f,  1.0f,  // TL
    };

    VkDeviceSize size = sizeof(verts);
    if (!create_buffer(r, size, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       r->vertex_buffer, r->vertex_memory))
        return false;

    void* data;
    vkMapMemory(r->device, r->vertex_memory, 0, size, 0, &data);
    memcpy(data, verts, size);
    vkUnmapMemory(r->device, r->vertex_memory);
    return true;
}

// ═════════════════════════════════════════════════════════════════════════════
// Public API
// ═════════════════════════════════════════════════════════════════════════════

bool vk_init(VkRenderer* r, SDL_Window* window)
{
    if (r->initialized) return true;

    // ── 1. Create Vulkan instance ──
    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "uae4arm";
    app_info.applicationVersion = VK_MAKE_VERSION(3, 8, 0);
    app_info.pEngineName = "amiberry";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = VK_API_VERSION_1_0;

    // Get required extensions from SDL
    unsigned int ext_count = 0;
    SDL_Vulkan_GetInstanceExtensions(window, &ext_count, nullptr);
    std::vector<const char*> extensions(ext_count);
    SDL_Vulkan_GetInstanceExtensions(window, &ext_count, extensions.data());

    VkInstanceCreateInfo inst_ci = {};
    inst_ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    inst_ci.pApplicationInfo = &app_info;
    inst_ci.enabledExtensionCount = ext_count;
    inst_ci.ppEnabledExtensionNames = extensions.data();

    if (vkCreateInstance(&inst_ci, nullptr, &r->instance) != VK_SUCCESS) {
        VK_ERR("Failed to create Vulkan instance");
        return false;
    }
    VK_LOG("Vulkan instance created");

    // ── 2. Create surface ──
    if (!SDL_Vulkan_CreateSurface(window, r->instance, &r->surface)) {
        VK_ERR("Failed to create Vulkan surface: %s", SDL_GetError());
        return false;
    }

    // ── 3. Pick physical device ──
    uint32_t dev_count = 0;
    vkEnumeratePhysicalDevices(r->instance, &dev_count, nullptr);
    if (dev_count == 0) {
        VK_ERR("No Vulkan physical devices found");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(dev_count);
    vkEnumeratePhysicalDevices(r->instance, &dev_count, devices.data());
    r->physical_device = devices[0]; // pick first

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(r->physical_device, &props);
    strncpy(r->device_name, props.deviceName, sizeof(r->device_name) - 1);
    VK_LOG("Using GPU: %s (Vulkan %u.%u.%u)",
           props.deviceName,
           VK_VERSION_MAJOR(props.apiVersion),
           VK_VERSION_MINOR(props.apiVersion),
           VK_VERSION_PATCH(props.apiVersion));

    // ── 4. Find graphics queue family with present support ──
    uint32_t qf_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(r->physical_device, &qf_count, nullptr);
    std::vector<VkQueueFamilyProperties> qf_props(qf_count);
    vkGetPhysicalDeviceQueueFamilyProperties(r->physical_device, &qf_count, qf_props.data());

    bool found_queue = false;
    for (uint32_t i = 0; i < qf_count; i++) {
        VkBool32 present_support = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(r->physical_device, i, r->surface, &present_support);
        if ((qf_props[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present_support) {
            r->queue_family = i;
            found_queue = true;
            break;
        }
    }
    if (!found_queue) {
        VK_ERR("No suitable graphics queue family found");
        return false;
    }

    // ── 5. Create logical device ──
    float queue_priority = 1.0f;
    VkDeviceQueueCreateInfo queue_ci = {};
    queue_ci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_ci.queueFamilyIndex = r->queue_family;
    queue_ci.queueCount = 1;
    queue_ci.pQueuePriorities = &queue_priority;

    const char* dev_extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};

    VkDeviceCreateInfo dev_ci = {};
    dev_ci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dev_ci.queueCreateInfoCount = 1;
    dev_ci.pQueueCreateInfos = &queue_ci;
    dev_ci.enabledExtensionCount = 1;
    dev_ci.ppEnabledExtensionNames = dev_extensions;

    if (vkCreateDevice(r->physical_device, &dev_ci, nullptr, &r->device) != VK_SUCCESS) {
        VK_ERR("Failed to create Vulkan logical device");
        return false;
    }
    vkGetDeviceQueue(r->device, r->queue_family, 0, &r->graphics_queue);

    // ── 6a. Load device-level function pointers ──
    // Bypass the Vulkan loader trampoline to work around dispatch bugs on
    // some Adreno drivers that crash inside the loader's vkBeginCommandBuffer.
    r->pfnBeginCommandBuffer = (PFN_vkBeginCommandBuffer)
        vkGetDeviceProcAddr(r->device, "vkBeginCommandBuffer");
    if (!r->pfnBeginCommandBuffer) {
        VK_ERR("Failed to load vkBeginCommandBuffer via vkGetDeviceProcAddr");
        return false;
    }
    VK_LOG("Device-level vkBeginCommandBuffer loaded: %p", (void*)r->pfnBeginCommandBuffer);

    // ── 6b. Command pool ──
    VkCommandPoolCreateInfo pool_ci = {};
    pool_ci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_ci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_ci.queueFamilyIndex = r->queue_family;
    if (vkCreateCommandPool(r->device, &pool_ci, nullptr, &r->cmd_pool) != VK_SUCCESS) {
        VK_ERR("Failed to create command pool");
        return false;
    }
    VK_LOG("Command pool created");

    // ── 7. Swapchain ──
    if (!create_swapchain(r, window)) return false;

    // ── 8. Render pass ──
    if (!create_render_pass(r)) return false;

    // ── 9. Framebuffers ──
    if (!create_framebuffers(r)) return false;

    // ── 10. Pipeline ──
    if (!create_pipeline(r)) return false;

    // ── 11. Descriptors ──
    if (!create_descriptors(r)) return false;

    // ── 12. Vertex buffer ──
    if (!create_vertex_buffer(r)) return false;

    // ── 13. Command buffers ──
    VkCommandBufferAllocateInfo cmd_ai = {};
    cmd_ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmd_ai.commandPool = r->cmd_pool;
    cmd_ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmd_ai.commandBufferCount = r->image_count;
    if (vkAllocateCommandBuffers(r->device, &cmd_ai, r->cmd_buffers) != VK_SUCCESS) {
        VK_ERR("Failed to allocate command buffers");
        return false;
    }
    VK_LOG("Allocated %u command buffers", r->image_count);

    // ── 13b. Warm-up: record → end → reset each buffer ──
    // Exercises the driver's internal lazy-init paths so vkBeginCommandBuffer
    // works reliably on the first real frame.
    for (uint32_t i = 0; i < r->image_count; i++) {
        VkCommandBufferBeginInfo warmup_bi = {};
        warmup_bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        warmup_bi.pNext = nullptr;
        warmup_bi.flags = 0;
        warmup_bi.pInheritanceInfo = nullptr;
        VkResult wr = r->pfnBeginCommandBuffer(r->cmd_buffers[i], &warmup_bi);
        if (wr != VK_SUCCESS) {
            VK_ERR("Warm-up vkBeginCommandBuffer[%u] failed (%d)", i, wr);
            return false;
        }
        vkEndCommandBuffer(r->cmd_buffers[i]);
        vkResetCommandBuffer(r->cmd_buffers[i], 0);
    }
    VK_LOG("Command buffer warm-up OK");

    // ── 14. Synchronization ──
    VkSemaphoreCreateInfo sem_ci = {};
    sem_ci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    if (vkCreateSemaphore(r->device, &sem_ci, nullptr, &r->image_available) != VK_SUCCESS ||
        vkCreateSemaphore(r->device, &sem_ci, nullptr, &r->render_finished) != VK_SUCCESS) {
        VK_ERR("Failed to create semaphores");
        return false;
    }

    VkFenceCreateInfo fence_ci = {};
    fence_ci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fence_ci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vkCreateFence(r->device, &fence_ci, nullptr, &r->in_flight_fence) != VK_SUCCESS) {
        VK_ERR("Failed to create fence");
        return false;
    }

    r->initialized = true;
    VK_LOG("Vulkan renderer fully initialized");
    return true;
}

void vk_shutdown(VkRenderer* r)
{
    if (!r->device) return;
    vkDeviceWaitIdle(r->device);

    auto destroy_if = [&](auto& h, auto fn) {
        if (h != VK_NULL_HANDLE) { fn(r->device, h, nullptr); h = VK_NULL_HANDLE; }
    };

    destroy_if(r->in_flight_fence, vkDestroyFence);
    destroy_if(r->image_available, vkDestroySemaphore);
    destroy_if(r->render_finished, vkDestroySemaphore);

    if (r->staging_mapped && r->staging_memory) {
        vkUnmapMemory(r->device, r->staging_memory);
        r->staging_mapped = nullptr;
    }
    destroy_if(r->staging_buffer, vkDestroyBuffer);
    destroy_if(r->staging_memory, vkFreeMemory);

    destroy_if(r->vertex_buffer, vkDestroyBuffer);
    destroy_if(r->vertex_memory, vkFreeMemory);

    if (r->frame_view)   { vkDestroyImageView(r->device, r->frame_view, nullptr);  r->frame_view = VK_NULL_HANDLE; }
    if (r->frame_image)  { vkDestroyImage(r->device, r->frame_image, nullptr);      r->frame_image = VK_NULL_HANDLE; }
    if (r->frame_memory) { vkFreeMemory(r->device, r->frame_memory, nullptr);       r->frame_memory = VK_NULL_HANDLE; }

    if (r->osd_view)     { vkDestroyImageView(r->device, r->osd_view, nullptr);     r->osd_view = VK_NULL_HANDLE; }
    if (r->osd_image)    { vkDestroyImage(r->device, r->osd_image, nullptr);        r->osd_image = VK_NULL_HANDLE; }
    if (r->osd_memory)   { vkFreeMemory(r->device, r->osd_memory, nullptr);         r->osd_memory = VK_NULL_HANDLE; }

    destroy_if(r->sampler, vkDestroySampler);
    destroy_if(r->desc_pool, vkDestroyDescriptorPool);
    destroy_if(r->desc_set_layout, vkDestroyDescriptorSetLayout);

    destroy_if(r->pipeline, vkDestroyPipeline);
    destroy_if(r->pipeline_layout, vkDestroyPipelineLayout);
    destroy_if(r->render_pass, vkDestroyRenderPass);

    for (uint32_t i = 0; i < r->image_count; i++) {
        if (r->framebuffers[i]) { vkDestroyFramebuffer(r->device, r->framebuffers[i], nullptr); r->framebuffers[i] = VK_NULL_HANDLE; }
        if (r->swapchain_views[i]) { vkDestroyImageView(r->device, r->swapchain_views[i], nullptr); r->swapchain_views[i] = VK_NULL_HANDLE; }
    }

    destroy_if(r->swapchain, vkDestroySwapchainKHR);
    destroy_if(r->cmd_pool, vkDestroyCommandPool);

    if (r->surface && r->instance) {
        vkDestroySurfaceKHR(r->instance, r->surface, nullptr);
        r->surface = VK_NULL_HANDLE;
    }

    vkDestroyDevice(r->device, nullptr);
    r->device = VK_NULL_HANDLE;

    if (r->instance) {
        vkDestroyInstance(r->instance, nullptr);
        r->instance = VK_NULL_HANDLE;
    }

    r->frame_tex_w = r->frame_tex_h = 0;
    r->osd_tex_w = r->osd_tex_h = 0;
    r->initialized = false;
    VK_LOG("Vulkan renderer shut down");
}

void vk_alloc_frame_texture(VkRenderer* r, int w, int h)
{
    if (w <= 0 || h <= 0) {
        VK_LOG("Ignoring invalid frame texture size: %dx%d", w, h);
        return;
    }
    if (r->frame_image && r->frame_tex_w == w && r->frame_tex_h == h)
        return;

    vkDeviceWaitIdle(r->device);

    // Clean up old resources
    if (r->frame_view)   { vkDestroyImageView(r->device, r->frame_view, nullptr);  r->frame_view = VK_NULL_HANDLE; }
    if (r->frame_image)  { vkDestroyImage(r->device, r->frame_image, nullptr);      r->frame_image = VK_NULL_HANDLE; }
    if (r->frame_memory) { vkFreeMemory(r->device, r->frame_memory, nullptr);       r->frame_memory = VK_NULL_HANDLE; }
    if (r->staging_mapped && r->staging_memory) {
        vkUnmapMemory(r->device, r->staging_memory);
        r->staging_mapped = nullptr;
    }
    if (r->staging_buffer) { vkDestroyBuffer(r->device, r->staging_buffer, nullptr); r->staging_buffer = VK_NULL_HANDLE; }
    if (r->staging_memory) { vkFreeMemory(r->device, r->staging_memory, nullptr);    r->staging_memory = VK_NULL_HANDLE; }

    // Create frame image (RGBA8)
    VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
    if (!create_image(r, w, h, format,
                      VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                      r->frame_image, r->frame_memory)) {
        VK_ERR("Failed to create frame image %dx%d", w, h);
        return;
    }

    r->frame_view = create_image_view(r->device, r->frame_image, format);

    // Transition to shader read initially
    transition_image(r, r->frame_image,
                     VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                     0, VK_ACCESS_SHADER_READ_BIT,
                     VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

    // Create staging buffer
    VkDeviceSize staging_size = (VkDeviceSize)w * h * 4;
    if (!create_buffer(r, staging_size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       r->staging_buffer, r->staging_memory)) {
        VK_ERR("Failed to create staging buffer for %dx%d", w, h);
        return;
    }
    r->staging_size = staging_size;
    vkMapMemory(r->device, r->staging_memory, 0, staging_size, 0, &r->staging_mapped);

    // Update descriptor
    update_descriptor(r, r->desc_set, r->frame_view);

    r->frame_tex_w = w;
    r->frame_tex_h = h;
    VK_LOG("Frame texture allocated: %dx%d", w, h);
}

void vk_upload_frame(VkRenderer* r, const SDL_Surface* surface,
                     const SDL_Rect* /*dirty_rects*/, int /*num_dirty*/, bool /*full_update*/)
{
    if (!r->frame_image || !surface || !surface->pixels || !r->staging_mapped) {
        static int s_skip_log = 0;
        if (s_skip_log < 5) {
            s_skip_log++;
            VK_LOG("vk_upload_frame SKIP: frame_image=%p surface=%p pixels=%p staging_mapped=%p",
                   (void*)r->frame_image, (void*)surface,
                   surface ? surface->pixels : nullptr, r->staging_mapped);
        }
        return;
    }

    {
        static int s_upload_log = 0;
        if (s_upload_log < 5) {
            s_upload_log++;
            uint32_t first_pixel = *reinterpret_cast<const uint32_t*>(surface->pixels);
            VK_LOG("vk_upload_frame OK: %dx%d pitch=%d first_pixel=0x%08X",
                   surface->w, surface->h, surface->pitch, first_pixel);
        }
    }

    // CPU-only: copy pixels to staging buffer (GPU transfer in vk_draw_frame)
    const int src_pitch = surface->pitch;
    const int dst_pitch = r->frame_tex_w * 4;

    if (src_pitch == dst_pitch) {
        memcpy(r->staging_mapped, surface->pixels, (size_t)dst_pitch * r->frame_tex_h);
    } else {
        const uint8_t* src = static_cast<const uint8_t*>(surface->pixels);
        uint8_t* dst = static_cast<uint8_t*>(r->staging_mapped);
        int copy_w = std::min(src_pitch, dst_pitch);
        for (int y = 0; y < r->frame_tex_h && y < surface->h; y++) {
            memcpy(dst + y * dst_pitch, src + y * src_pitch, copy_w);
        }
    }

    r->staging_dirty = true;
}

bool vk_begin_frame(VkRenderer* r, uint32_t* image_index)
{
    VkResult res;
    res = vkWaitForFences(r->device, 1, &r->in_flight_fence, VK_TRUE, UINT64_MAX);
    if (res != VK_SUCCESS) {
        VK_ERR("vk_begin_frame: vkWaitForFences failed (%d)", res);
        return false;
    }
    // NOTE: fence is NOT reset here — it's reset in vk_draw_frame just before
    // vkQueueSubmit. This prevents a deadlock if we bail out early (the fence
    // stays signaled so the next WaitForFences won't block forever).

    res = vkAcquireNextImageKHR(r->device, r->swapchain, UINT64_MAX,
                                r->image_available, VK_NULL_HANDLE, image_index);
    if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_ERROR_SURFACE_LOST_KHR) return false;
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
        VK_ERR("vk_begin_frame: vkAcquireNextImageKHR failed (%d)", res);
        return false;
    }

    if (*image_index >= r->image_count) {
        VK_ERR("vk_begin_frame: image_index %u >= image_count %u", *image_index, r->image_count);
        return false;
    }

    VkCommandBuffer cmd = r->cmd_buffers[*image_index];

    res = vkResetCommandBuffer(cmd, 0);
    if (res != VK_SUCCESS) {
        VK_ERR("vk_begin_frame: vkResetCommandBuffer[%u] failed (%d)", *image_index, res);
        return false;
    }

    VkCommandBufferBeginInfo bi = {};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.pNext = nullptr;
    bi.flags = 0;
    bi.pInheritanceInfo = nullptr;
    res = r->pfnBeginCommandBuffer(cmd, &bi);
    if (res != VK_SUCCESS) {
        VK_ERR("vk_begin_frame: vkBeginCommandBuffer[%u] failed (%d)", *image_index, res);
        return false;
    }
    VK_LOG("vk_begin_frame: cmd buffer recording");

    return true;
}

void vk_end_frame(VkRenderer* r, uint32_t image_index)
{
    VkCommandBuffer cmd = r->cmd_buffers[image_index];
    vkCmdEndRenderPass(cmd);
    vkEndCommandBuffer(cmd);

    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &r->image_available;
    si.pWaitDstStageMask = &wait_stage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &r->render_finished;

    vkQueueSubmit(r->graphics_queue, 1, &si, r->in_flight_fence);

    VkPresentInfoKHR pi = {};
    pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &r->render_finished;
    pi.swapchainCount = 1;
    pi.pSwapchains = &r->swapchain;
    pi.pImageIndices = &image_index;

    vkQueuePresentKHR(r->graphics_queue, &pi);
}

void vk_draw_frame(VkRenderer* r,
                   int drawable_w, int drawable_h,
                   const SDL_Rect* crop, int tex_w, int tex_h,
                   bool stretch_to_fill,
                   bool correct_aspect,
                   int rotation_angle)
{
    if (!r->frame_image || !r->initialized) return;

    uint32_t img_idx;
    if (!vk_begin_frame(r, &img_idx)) return;

    VkCommandBuffer cmd = r->cmd_buffers[img_idx];

    // ── Staging buffer → GPU image transfer (if new data) ──
    if (r->staging_dirty && r->staging_buffer) {
        cmd_transition_image(cmd, r->frame_image,
                             VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                             VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

        VkBufferImageCopy region = {};
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.layerCount = 1;
        region.imageExtent = {(uint32_t)r->frame_tex_w, (uint32_t)r->frame_tex_h, 1};
        vkCmdCopyBufferToImage(cmd, r->staging_buffer, r->frame_image,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

        cmd_transition_image(cmd, r->frame_image,
                             VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                             VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

        r->staging_dirty = false;
    }

    // ── Begin render pass ──
    VkClearValue clear = {};
    clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};

    VkRenderPassBeginInfo rp_bi = {};
    rp_bi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rp_bi.renderPass = r->render_pass;
    rp_bi.framebuffer = r->framebuffers[img_idx];
    rp_bi.renderArea.extent = r->swapchain_extent;
    rp_bi.clearValueCount = 1;
    rp_bi.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &rp_bi, VK_SUBPASS_CONTENTS_INLINE);

    // Compute UV coordinates from crop rect
    float u0 = 0.0f, v0 = 0.0f, u1 = 1.0f, v1 = 1.0f;
    int src_w = tex_w, src_h = tex_h;
    if (crop && tex_w > 0 && tex_h > 0) {
        u0 = (float)crop->x / tex_w;
        v0 = (float)crop->y / tex_h;
        u1 = (float)(crop->x + crop->w) / tex_w;
        v1 = (float)(crop->y + crop->h) / tex_h;
        src_w = crop->w;
        src_h = crop->h;
    }

    // Handle rotation in UV coords
    float fu0, fv0, fu1, fv1;
    switch (rotation_angle) {
    case 90:  fu0 = u1; fv0 = v0; fu1 = u0; fv1 = v1; break;
    case 180: fu0 = u1; fv0 = v1; fu1 = u0; fv1 = v0; break;
    case 270: fu0 = u0; fv0 = v1; fu1 = u1; fv1 = v0; break;
    default:  fu0 = u0; fv0 = v0; fu1 = u1; fv1 = v1; break;
    }

    // Compute viewport for aspect ratio
    int vp_x = 0, vp_y = 0, vp_w = drawable_w, vp_h = drawable_h;
    if (!stretch_to_fill && src_w > 0 && src_h > 0) {
        float desired_aspect = correct_aspect ? (4.0f / 3.0f) : ((float)src_w / src_h);
        bool rotated = (rotation_angle == 90 || rotation_angle == 270);
        if (rotated) desired_aspect = 1.0f / desired_aspect;

        int dest_w = drawable_w;
        int dest_h = (int)(drawable_w / desired_aspect);
        if (dest_h > drawable_h) {
            dest_h = drawable_h;
            dest_w = (int)(drawable_h * desired_aspect);
        }
        vp_x = (drawable_w - dest_w) / 2;
        vp_y = (drawable_h - dest_h) / 2;
        vp_w = dest_w;
        vp_h = dest_h;
    }

    // Set viewport and scissor
    VkViewport viewport = {};
    viewport.x = (float)vp_x;
    viewport.y = (float)vp_y;
    viewport.width = (float)vp_w;
    viewport.height = (float)vp_h;
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;
    vkCmdSetViewport(cmd, 0, 1, &viewport);

    VkRect2D scissor = {};
    scissor.offset = {vp_x, vp_y};
    scissor.extent = {(uint32_t)vp_w, (uint32_t)vp_h};
    vkCmdSetScissor(cmd, 0, 1, &scissor);

    // Bind pipeline and vertex buffer
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, r->pipeline);
    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &r->vertex_buffer, &offset);

    // Bind frame texture descriptor
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            r->pipeline_layout, 0, 1, &r->desc_set, 0, nullptr);

    // Push constants
    VkRenderer::PushConstants pc = {};
    pc.viewport[0] = 0.0f; // identity (NDC quad covers full viewport)
    pc.viewport[1] = 0.0f;
    pc.viewport[2] = 1.0f;
    pc.viewport[3] = 1.0f;
    pc.uv[0] = fu0;
    pc.uv[1] = fv0;
    pc.uv[2] = fu1;
    pc.uv[3] = fv1;
    pc.alpha = 1.0f;
    pc.mode = 0; // textured

    vkCmdPushConstants(cmd, r->pipeline_layout,
                       VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                       0, sizeof(pc), &pc);

    vkCmdDraw(cmd, 6, 1, 0, 0);

    // Don't end render pass here - OSD might follow
    // vk_end_frame handles ending the render pass
    vkCmdEndRenderPass(cmd);
    vkEndCommandBuffer(cmd);

    // Submit
    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &r->image_available;
    si.pWaitDstStageMask = &wait_stage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &r->render_finished;

    vkResetFences(r->device, 1, &r->in_flight_fence);
    vkQueueSubmit(r->graphics_queue, 1, &si, r->in_flight_fence);

    // Present
    VkPresentInfoKHR pi = {};
    pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &r->render_finished;
    pi.swapchainCount = 1;
    pi.pSwapchains = &r->swapchain;
    pi.pImageIndices = &img_idx;

    vkQueuePresentKHR(r->graphics_queue, &pi);
}

void vk_draw_osd(VkRenderer* r,
                 int drawable_w, int drawable_h,
                 const SDL_Surface* osd_surface,
                 int osd_x, int osd_y)
{
    if (!osd_surface || !osd_surface->pixels || !r->initialized) return;

    // Allocate or resize OSD image
    if (!r->osd_image || r->osd_tex_w != osd_surface->w || r->osd_tex_h != osd_surface->h) {
        vkDeviceWaitIdle(r->device);

        if (r->osd_view)   { vkDestroyImageView(r->device, r->osd_view, nullptr);  r->osd_view = VK_NULL_HANDLE; }
        if (r->osd_image)  { vkDestroyImage(r->device, r->osd_image, nullptr);      r->osd_image = VK_NULL_HANDLE; }
        if (r->osd_memory) { vkFreeMemory(r->device, r->osd_memory, nullptr);       r->osd_memory = VK_NULL_HANDLE; }

        VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
        if (!create_image(r, osd_surface->w, osd_surface->h, format,
                          VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                          r->osd_image, r->osd_memory)) return;

        r->osd_view = create_image_view(r->device, r->osd_image, format);

        transition_image(r, r->osd_image,
                         VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                         0, VK_ACCESS_SHADER_READ_BIT,
                         VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

        update_descriptor(r, r->osd_desc_set, r->osd_view);
        r->osd_tex_w = osd_surface->w;
        r->osd_tex_h = osd_surface->h;
    }

    // Upload OSD pixels using a temporary staging buffer
    VkDeviceSize osd_size = (VkDeviceSize)osd_surface->w * osd_surface->h * 4;
    VkBuffer osd_staging = VK_NULL_HANDLE;
    VkDeviceMemory osd_staging_mem = VK_NULL_HANDLE;

    if (!create_buffer(r, osd_size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                       osd_staging, osd_staging_mem)) return;

    void* mapped;
    vkMapMemory(r->device, osd_staging_mem, 0, osd_size, 0, &mapped);
    const int dst_pitch = osd_surface->w * 4;
    if (osd_surface->pitch == dst_pitch) {
        memcpy(mapped, osd_surface->pixels, osd_size);
    } else {
        const uint8_t* src = static_cast<const uint8_t*>(osd_surface->pixels);
        uint8_t* dst = static_cast<uint8_t*>(mapped);
        for (int y = 0; y < osd_surface->h; y++)
            memcpy(dst + y * dst_pitch, src + y * osd_surface->pitch, dst_pitch);
    }
    vkUnmapMemory(r->device, osd_staging_mem);

    // Copy to OSD image
    VkCommandBuffer cmd = begin_single_cmd(r);
    if (cmd == VK_NULL_HANDLE) {
        vkDestroyBuffer(r->device, osd_staging, nullptr);
        vkFreeMemory(r->device, osd_staging_mem, nullptr);
        return;
    }
    cmd_transition_image(cmd, r->osd_image,
                         VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkBufferImageCopy region = {};
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {(uint32_t)osd_surface->w, (uint32_t)osd_surface->h, 1};
    vkCmdCopyBufferToImage(cmd, osd_staging, r->osd_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    cmd_transition_image(cmd, r->osd_image,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                         VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    end_single_cmd(r, cmd);

    vkDestroyBuffer(r->device, osd_staging, nullptr);
    vkFreeMemory(r->device, osd_staging_mem, nullptr);

    // Note: OSD drawing within the frame pass would need to be integrated into
    // vk_draw_frame. For now, the OSD texture is uploaded and the descriptor
    // is ready for use in a future combined render pass.
}

bool vk_recreate_swapchain(VkRenderer* r, SDL_Window* window)
{
    vkDeviceWaitIdle(r->device);

    // Destroy old framebuffers and views
    for (uint32_t i = 0; i < r->image_count; i++) {
        if (r->framebuffers[i])    { vkDestroyFramebuffer(r->device, r->framebuffers[i], nullptr); r->framebuffers[i] = VK_NULL_HANDLE; }
        if (r->swapchain_views[i]) { vkDestroyImageView(r->device, r->swapchain_views[i], nullptr); r->swapchain_views[i] = VK_NULL_HANDLE; }
    }

    VkSwapchainKHR old = r->swapchain;
    if (!create_swapchain(r, window)) return false;
    if (old != VK_NULL_HANDLE && old != r->swapchain) {
        vkDestroySwapchainKHR(r->device, old, nullptr);
    }

    if (!create_framebuffers(r)) return false;
    return true;
}

#endif // USE_VULKAN && __ANDROID__
