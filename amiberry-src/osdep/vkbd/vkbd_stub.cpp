// vkbd_stub.cpp - Stub implementations for Android minimal build
// The real vkbd.cpp uses SDL_image which isn't available in Android minimal builds

#ifdef AMIBERRY_ANDROID_MINIMAL

#include <string>
#include <SDL_rect.h>
#include "vkbd.h"

// Additional functions called by amiberry.cpp but not in vkbd.h
bool vkbd_handle_pointer(int, int, bool, int*, int*) { return false; }
bool vkbd_get_bounds(SDL_Rect*) { return false; }

void vkbd_set_hires(bool) {}
void vkbd_set_language(VkbdLanguage) {}
void vkbd_set_style(VkbdStyle) {}
void vkbd_set_language(const std::string&) {}
void vkbd_set_style(const std::string&) {}
void vkbd_set_transparency(double) {}
void vkbd_set_keyboard_has_exit_button(bool) {}

void vkbd_init(void) {}
void vkbd_quit(void) {}
void vkbd_redraw(void) {}
void vkbd_toggle(void) {}
bool vkbd_process(int, int*, int*) { return false; }
bool vkbd_is_active(void) { return false; }
void vkbd_update_position_from_texture() {}

#endif // AMIBERRY_ANDROID_MINIMAL