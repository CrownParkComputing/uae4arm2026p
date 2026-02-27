#pragma once

#include <string>
#include <vector>
#include <guisan.hpp>
#include <guisan/sdl.hpp>
#include <guisan/sdl/sdl2graphics.hpp>
#include <guisan/sdl/sdlinput.hpp>
#include <guisan/sdl/sdltruetypefont.hpp>
#include "options.h"
#include "osdep/amiberry_gfx.h"       // defines GUI_WIDTH, GUI_HEIGHT
#include "osdep/gui/SelectorEntry.hpp"

// ── Derived layout constants ──────────────────────────────────────────────────
#define SELECTOR_WIDTH  160
#define PANEL_WIDTH   (GUI_WIDTH  - SELECTOR_WIDTH)
#define PANEL_HEIGHT  (GUI_HEIGHT - 60)

// ── Global GUI state ─────────────────────────────────────────────────────────
extern bool                   gui_running;
extern gcn::SDLTrueTypeFont*  gui_font;
extern gcn::Color             gui_base_color;
extern gcn::Color             gui_foreground_color;
extern gcn::Color             gui_background_color;
extern gcn::Color             gui_selection_color;
extern gcn::Color             gui_selector_inactive_color;
extern gcn::Color             gui_selector_active_color;
extern gcn::Color             gui_font_color;

// gui_theme is declared in options.h / defined in amiberry.cpp
extern amiberry_gui_theme     gui_theme;

// ── Panel/category structure ─────────────────────────────────────────────────
struct Category {
    const char*      category;   ///< Display name shown in the selector
    SelectorEntry*   selector;   ///< Left-side navigation button
    gcn::Container*  panel;      ///< Settings panel shown on the right
};

#define MAX_CATEGORIES 16
extern Category     categories[];
extern int          num_categories;

extern gcn::Container*   selectors;
extern gcn::ScrollArea*  selectorsScrollArea;

// ── Types used by amiberry_gui.cpp ──────────────────────────────────────────
/// HD-controller combo entry (index + human-readable name)
struct controller_map {
    int         firstid;
    std::string name;
};

/// Config-file entry in the config-file browser list
struct ConfigFileInfo {
    char FullPath[MAX_DPATH];
    char Name[MAX_DPATH];
    char Description[MAX_DPATH];
};

// ── Constants used by amiberry_gui.cpp ───────────────────────────────────────
/// Maximum number of hard-drive devices shown in the GUI (7 slots total)
#define MAX_HD_DEVICES 7

// ── Variables declared in amiberry.cpp ───────────────────────────────────────
extern char last_active_config[];

// ── Variables declared in MainWindow.cpp ─────────────────────────────────────
extern int current_state_num;

// ── Functions declared in amiberry_gui.cpp (forward declarations) ─────────────
void FilterFiles(std::vector<std::string>* files, const char* filter[]);
void load_default_theme();
void load_default_dark_theme();
void load_theme(const std::string& theme_filename);
void save_theme(const std::string& theme_filename);

// ── Functions declared in MainWindow.cpp ─────────────────────────────────────
bool is_hdf_rdb();

// ── File filters ─────────────────────────────────────────────────────────────
extern const char* diskfile_filter[];
extern const char* cdfile_filter[];
extern const char* statefile_filter[];
extern const char* romfile_filter[];

// ── Helpers declared in amiberry.cpp ─────────────────────────────────────────
void read_directory(const std::string& path,
                    std::vector<std::string>* dirs,
                    std::vector<std::string>* files);

// ── Main lifecycle functions (implemented in MainWindow.cpp) ─────────────────
void amiberry_gui_init();
void gui_widgets_init();
void run_gui();
void gui_widgets_halt();
void amiberry_gui_halt();

// ── Helper dialogs ───────────────────────────────────────────────────────────
void ShowMessage(const char* title,
                 const char* body, const char* body2, const char* body3,
                 const char* button1, const char* button2);

std::string SelectFile(const char* title,
                       const std::string& current_path,
                       const char* filter[],
                       bool save = false);

void ShowDiskInfo(const char* title, const std::vector<std::string>& info);

// ── Theme helpers (parse_color_string used by load_theme() in amiberry_gui) ──
std::vector<int> parse_color_string(const std::string& value);
void apply_theme();
void apply_theme_extras();

