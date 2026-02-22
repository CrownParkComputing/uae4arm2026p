#include "sysdeps.h"
#include "gui.h"
#include "options.h"
#include "target.h"

#include <cstdarg>
#include <string>
#include <vector>

#include "ethernet.h"

// Minimal stubs for builds that compile without the desktop GUI (e.g. Android-first).
// Core emulation calls these hooks for LEDs / status updates; no-ops are fine.

unsigned int gui_ledstate;
bool gui_running = false;

int emulating = 0;
bool config_loaded = false;

std::vector<std::string> lstMRUDiskList;
std::vector<std::string> lstMRUCDList;
std::vector<std::string> lstMRUWhdloadList;

std::vector<std::string> midi_in_ports;
std::vector<std::string> midi_out_ports;

bool joystick_refresh_needed = false;
int scsiromselected = 0;

bool isguiactive()
{
	return false;
}

void target_startup_msg(const TCHAR*, const TCHAR*)
{
}

void add_file_to_mru_list(std::vector<std::string>& vec, const std::string& file)
{
	if (std::find(vec.begin(), vec.end(), file) == vec.end()) {
		vec.insert(vec.begin(), file);
	}
	while (vec.size() > MAX_MRU_LIST)
		vec.pop_back();
}

struct netdriverdata **target_ethernet_enumerate(void)
{
	return nullptr;
}

struct cddlg_vals current_cddlg = {};
struct tapedlg_vals current_tapedlg = {};
struct fsvdlg_vals current_fsvdlg = {};
struct hfdlg_vals current_hfdlg = {};

void gui_update_gfx(void)
{
}

void notify_user(int)
{
}

void notify_user_parms(int, const TCHAR*, ...)
{
}

int translate_message(int, TCHAR*)
{
	return 0;
}

void gui_message (const TCHAR *,...)
{
}

bool gui_ask_disk(int, TCHAR*)
{
	return false;
}

void hardfile_testrdb(struct hfdlg_vals*)
{
}

void default_tapedlg(struct tapedlg_vals*)
{
}

void default_fsvdlg(struct fsvdlg_vals*)
{
}

void default_hfdlg(struct hfdlg_vals* f, bool rdb)
{
	if (!f)
		return;
	*f = {};
	f->rdb = rdb;
}

void updatehdfinfo(bool, bool, bool, std::string& txtHdfInfo, std::string& txtHdfInfo2)
{
	txtHdfInfo.clear();
	txtHdfInfo2.clear();
}

int scan_roms(int)
{
	return 0;
}

void load_default_theme()
{
}

void load_default_dark_theme()
{
}

void save_theme(const std::string&)
{
}

int gui_init(void)
{
	return 0;
}

void gui_update(void)
{
}

void gui_exit(void)
{
}

void gui_led(int, int, int)
{
}

void gui_handle_events(void)
{
}

void gui_filename(int, const TCHAR*)
{
}

void gui_fps(int, int, bool, int, int)
{
}

void gui_changesettings(void)
{
}

void gui_lock(void)
{
}

void gui_unlock(void)
{
}

void gui_flicker_led(int, int, int)
{
}

void gui_disk_image_change(int, const TCHAR*, bool)
{
}

void gui_display(int)
{
}

void gui_restart()
{
}
