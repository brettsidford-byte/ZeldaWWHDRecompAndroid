// Exercise real allocation/rescale code without a device, using a mock Vulkan backend.
#include <cassert>
#include <cstdlib>
#include <iostream>
#include "../../../runtime/src/vk/vk_surfaces.cpp"

static uint32_t copiedLayers = 0;
static uintptr_t nextImage = 1;
static void VKAPI_CALL formats(VkPhysicalDevice, VkFormat f, VkFormatProperties* p) {
    *p = {};
    if (f != VK_FORMAT_D16_UNORM)
        p->optimalTilingFeatures = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
}
static void VKAPI_CALL blit(VkCommandBuffer, VkImage, VkImageLayout, VkImage, VkImageLayout,
                           uint32_t count, const VkImageBlit* b, VkFilter filter) {
    assert(count == 1);
    assert(b->srcSubresource.layerCount == b->dstSubresource.layerCount);
    assert(filter == VK_FILTER_NEAREST);
    copiedLayers = b->srcSubresource.layerCount;
}
PFN_vkGetPhysicalDeviceFormatProperties vkGetPhysicalDeviceFormatProperties = formats;
namespace gfx::rec {
void CmdBlitImage(VkCommandBuffer c, VkImage a, VkImageLayout al, VkImage b, VkImageLayout bl,
                  uint32_t n, const VkImageBlit* regions, VkFilter filter) {
    blit(c, a, al, b, bl, n, regions, filter);
}
}
void log_msg(const char*, ...) {}
[[noreturn]] void fatal(const char*, ...) { std::abort(); }
namespace gfx {
Renderer R;
void prepare(Image&, Use) {}
void retire_image(const Image&) {}
VkCommandBuffer command_buffer() { return VK_NULL_HANDLE; }
bool create_image(Image& i, VkImageType type, VkImageViewType view, VkFormat format,
                  uint32_t w, uint32_t h, uint32_t depth, uint32_t layers, uint32_t mips,
                  VkImageUsageFlags, bool cube, bool isDepth, bool stencil) {
    i.image = (VkImage)nextImage++;
    i.type = type; i.viewType = view; i.format = format;
    i.width = w; i.height = h; i.depth = depth; i.layers = layers; i.mips = mips;
    i.cube = cube;
    i.aspect = isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
    if (stencil) i.aspect |= VK_IMAGE_ASPECT_STENCIL_BIT;
    return true;
}
}
int main() {
    using namespace gfx;
    Surface cascade;
    cascade.width = cascade.height = 1024; cascade.slices = 3; cascade.dim = 5;
    cascade.isDepth = cascade.fmt.depth = cascade.fmt.renderable = true;
    cascade.fmt.format = VK_FORMAT_D32_SFLOAT;
    assert(create_surface_image(&cascade, true));
    assert(cascade.img.width == 1024 && cascade.img.layers == 3);
    set_shadow_scale(0.5f); latch_resolution_scale(); rescaled(&cascade);
    assert(cascade.img.width == 512 && cascade.img.height == 512);
    assert(cascade.img.layers == 3 && copiedLayers == 3);
    set_shadow_scale(1); latch_resolution_scale(); rescaled(&cascade);
    assert(cascade.img.width == 1024 && copiedLayers == 3);
    set_shadow_scale(0.5f); latch_resolution_scale();
    Surface unsupported = cascade;
    unsupported.fmt.format = VK_FORMAT_D16_UNORM;
    assert(create_surface_image(&unsupported, true));
    assert(unsupported.img.width == 1024); // no depth blits: fall back safely
    Surface ordinary = cascade;
    ordinary.slices = 1; ordinary.dim = 1; ordinary.shadowSource = false;
    assert(create_surface_image(&ordinary, true));
    assert(ordinary.img.width == 1024); // unrelated square depth stays original
    ordinary.shadowSource = true;
    rescaled(&ordinary);
    assert(ordinary.img.width == 512); // only a detected cascade scratch source scales
    Surface stencil = cascade;
    stencil.fmt.stencil = true;
    assert(create_surface_image(&stencil, true));
    assert(stencil.img.width == 1024);
    Surface screen;
    screen.width = 1280; screen.height = 720; screen.fmt.renderable = true;
    screen.fmt.format = VK_FORMAT_R8G8B8A8_UNORM;
    set_resolution_scale(0.5f); latch_resolution_scale();
    set_frame_aspect(4.0f / 3.0f);
    assert(create_surface_image(&screen, true));
    assert(screen.img.width == 640 && screen.img.height == 480);
    set_resolution_scale(0.4f); latch_resolution_scale();
    assert(create_surface_image(&screen, true));
    assert(screen.img.width == 512 && screen.img.height == 384);
    std::cout << "Shadow scaling: array preservation, restore, unsupported-format fallback, scratch selection and independent screen scale passed\n";
}
