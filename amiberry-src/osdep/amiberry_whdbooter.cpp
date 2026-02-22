/*
 * UAE - The Un*x Amiga Emulator
 *
 * Amiberry interface
 *
 */
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <sstream>
#include <vector>
#include <sys/types.h>
#include <sys/stat.h>
#include "sysdeps.h"
#ifdef __ANDROID__
#include <SDL_system.h>
#endif
#include "uae.h"
#include "options.h"
#include "rommgr.h"
#include "fsdb.h"
#include "tinyxml2.h"
#include "custom.h"
#include "inputdevice.h"
#include "xwin.h"
#include "drawing.h"
#include "midiemu.h"
#include "registry.h"
#include "zfile.h"
#include "zarchive.h"
#include "archivers/zip/unzip.h"

extern void set_last_active_config(const char* filename);
extern std::string current_dir;
extern char last_loaded_config[MAX_DPATH];

// Use configs with 8MB Fast RAM, to make it likely
// that WHDLoad preload will cache everything.
enum
{
	A600_CONFIG = 3, // 8MB fast ram
	A1200_CONFIG = 1 // 8MB fast ram
};

struct game_hardware_options
{
	std::string port0 = "nul";
	std::string port1 = "nul";
	std::string control = "nul";
	std::string control2 = "nul";
	std::string cpu = "nul";
	std::string blitter = "nul";
	std::string clock = "nul";
	std::string chipset = "nul";
	std::string jit = "nul";
	std::string cpu_comp = "nul";
	std::string cpu_24bit = "nul";
	std::string cpu_exact = "nul";
	std::string sprites = "nul";
	std::string scr_width = "nul";
	std::string scr_height = "nul";
	std::string scr_autoheight = "nul";
	std::string scr_centerh = "nul";
	std::string scr_centerv = "nul";
	std::string scr_offseth = "nul";
	std::string scr_offsetv = "nul";
	std::string ntsc = "nul";
	std::string chip = "nul";
	std::string fast = "nul";
	std::string z3 = "nul";
};

std::filesystem::path whdbooter_path;
std::filesystem::path boot_path;
std::filesystem::path save_path;
std::filesystem::path conf_path;
std::filesystem::path whd_path;
std::filesystem::path kickstart_path;

std::string uae_config;
std::string whd_config;
std::string whd_startup;

static bool is_safe_relative_zip_path(std::string_view rel)
{
	if (rel.empty())
		return false;
	// Normalize: reject absolute paths and parent traversal.
	if (rel[0] == '/' || rel[0] == '\\')
		return false;
	if (rel.find(":") != std::string_view::npos)
		return false;
	if (rel.find("..") != std::string_view::npos)
		return false;
	return true;
}

static std::filesystem::path strip_boot_data_prefix(const std::string& in)
{
	std::string name = in;
	std::replace(name.begin(), name.end(), '\\', '/');
	while (!name.empty() && name.front() == '/')
		name.erase(name.begin());
	if (name.rfind("boot-data/", 0) == 0)
		name.erase(0, std::strlen("boot-data/"));
	return std::filesystem::path(name);
}

static std::string sanitize_whd_sub_path(std::string value)
{
	std::replace(value.begin(), value.end(), '\\', '/');

	while (!value.empty() && std::isspace(static_cast<unsigned char>(value.front())))
		value.erase(value.begin());
	while (!value.empty() && std::isspace(static_cast<unsigned char>(value.back())))
		value.pop_back();

	while (!value.empty() && (value.front() == '/' || value.front() == '.'))
	{
		if (value.rfind("../", 0) == 0)
			value.erase(0, 3);
		else if (value.rfind("./", 0) == 0)
			value.erase(0, 2);
		else if (value.front() == '/')
			value.erase(value.begin());
		else
			break;
	}

	std::vector<std::string> parts;
	std::stringstream stream(value);
	std::string segment;
	while (std::getline(stream, segment, '/'))
	{
		if (segment.empty() || segment == "." || segment == "..")
			continue;

		segment.erase(std::remove_if(segment.begin(), segment.end(), [](unsigned char ch)
		{
			return std::iscntrl(ch) || ch == '"' || ch == ':';
		}), segment.end());

		while (!segment.empty() && std::isspace(static_cast<unsigned char>(segment.front())))
			segment.erase(segment.begin());
		while (!segment.empty() && std::isspace(static_cast<unsigned char>(segment.back())))
			segment.pop_back();

		if (!segment.empty())
			parts.emplace_back(segment);
	}

	std::string sanitized;
	for (size_t i = 0; i < parts.size(); ++i)
	{
		if (i > 0)
			sanitized += "/";
		sanitized += parts[i];
	}

	return sanitized;
}

static bool extract_zip_to_dir(const std::filesystem::path& zip_path, const std::filesystem::path& out_dir)
{
	try {
		std::filesystem::create_directories(out_dir);
	}
	catch (...) {
		write_log("ZIP: ERROR creating output directory\n");
		return false;
	}

	struct zfile* zf = zfile_fopen(zip_path.string().c_str(), _T("rb"), ZFD_NORMAL);
	if (!zf) {
		write_log("ZIP: ERROR opening zip file\n");
		return false;
	}

	unzFile uf = unzOpen(zf);
	if (!uf) {
		write_log("ZIP: ERROR unzOpen failed\n");
		zfile_fclose(zf);
		return false;
	}

	bool ok = true;
	int err = unzGoToFirstFile(uf);
	int file_count = 0;
	int success_count = 0;
	while (err == UNZ_OK) {
		file_count++;
		unz_file_info info{};
		char filename[1024] = { 0 };
		if (unzGetCurrentFileInfo(uf, &info, filename, sizeof filename, nullptr, 0, nullptr, 0) != UNZ_OK) {
			ok = false;
			break;
		}

		std::string entry_name(filename);
		auto rel_path = strip_boot_data_prefix(entry_name);
		std::string rel_string = rel_path.generic_string();
		if (!rel_string.empty() && rel_string.back() == '/')
			rel_string.pop_back();

		if (!rel_string.empty() && is_safe_relative_zip_path(rel_string)) {
			std::filesystem::path dest = out_dir / std::filesystem::path(rel_string);
			// Directory entry
			bool is_dir = !entry_name.empty() && (entry_name.back() == '/' || entry_name.back() == '\\');
			if (is_dir) {
				try {
					std::filesystem::create_directories(dest);
					success_count++;
				}
				catch (...) {
					write_log("ZIP: ERROR creating directory: %s\n", dest.c_str());
				}
			} else {
				try {
					std::filesystem::create_directories(dest.parent_path());
				}
				catch (...) {
					write_log("ZIP: ERROR creating parent directory for: %s\n", dest.c_str());
				}

				if (unzOpenCurrentFile(uf) != UNZ_OK) {
					write_log("ZIP: ERROR opening file in archive: %s\n", entry_name.c_str());
					err = unzGoToNextFile(uf);
					continue;
				}

				std::ofstream out(dest, std::ios::binary);
				if (!out.is_open()) {
					write_log("ZIP: ERROR creating output file: %s\n", dest.c_str());
					unzCloseCurrentFile(uf);
					err = unzGoToNextFile(uf);
					continue;
				}

				char buf[64 * 1024];
				bool write_ok = true;
				for (;;) {
					int r = unzReadCurrentFile(uf, buf, sizeof buf);
					if (r < 0) {
						write_log("ZIP: ERROR reading from archive: %s\n", entry_name.c_str());
						write_ok = false;
						break;
					}
					if (r == 0)
						break;
					out.write(buf, r);
				}

				out.close();
				unzCloseCurrentFile(uf);
				if (write_ok) {
					success_count++;
				}
			}
		}

		err = unzGoToNextFile(uf);
	}

	unzClose(uf);
	zfile_fclose(zf);
	
	write_log("ZIP: Extraction complete - %d/%d files successful\n", success_count, file_count);
	return success_count > 0; // Return success if we extracted at least some files
}

static std::filesystem::path resolve_boot_data_dir()
{
	const auto dir = whdbooter_path / "boot-data";
	const auto zip = whdbooter_path / "boot-data.zip";

	// Prefer an already-extracted directory.
	if (std::filesystem::exists(dir) && std::filesystem::is_directory(dir))
		return dir;

	// If only the zip exists, extract it once.
	if (std::filesystem::exists(zip) && std::filesystem::is_regular_file(zip))
	{
		write_log("WHDBooter - boot-data directory missing; extracting %s to %s\n", zip.c_str(), dir.c_str());
		if (extract_zip_to_dir(zip, dir)) {
			write_log("WHDBooter - Extracted boot-data.zip successfully\n");
			if (std::filesystem::exists(dir) && std::filesystem::is_directory(dir))
				return dir;
		} else {
			write_log("WHDBooter - ERROR extracting boot-data.zip\n");
		}
	}

	return dir;
}

// Extract boot-data.zip directly to whdboot root (for simplified mode)
static void ensure_boot_data_extracted()
{
	const auto zip = whdbooter_path / "boot-data.zip";
	const auto c_dir = whdbooter_path / "C";
	
	// If C directory already exists, assume boot-data has been extracted
	if (std::filesystem::exists(c_dir) && std::filesystem::is_directory(c_dir)) {
		write_log("WHDBooter - C directory exists, boot-data already extracted\n");
		
		// Ensure WHDLoad and JST are in C directory
		const auto whdload_root = whdbooter_path / "WHDLoad";
		const auto whdload_c = c_dir / "WHDLoad";
		const auto jst_root = whdbooter_path / "JST";
		const auto jst_c = c_dir / "JST";
		
		try {
			if (std::filesystem::exists(whdload_root) && !std::filesystem::exists(whdload_c)) {
				std::filesystem::copy_file(whdload_root, whdload_c, std::filesystem::copy_options::overwrite_existing);
				write_log("WHDBooter - Copied WHDLoad to C directory\n");
			}
			if (std::filesystem::exists(jst_root) && !std::filesystem::exists(jst_c)) {
				std::filesystem::copy_file(jst_root, jst_c, std::filesystem::copy_options::overwrite_existing);
				write_log("WHDBooter - Copied JST to C directory\n");
			}
		} catch (const std::filesystem::filesystem_error& e) {
			write_log("WHDBooter - ERROR copying loaders to C: %s\n", e.what());
		}
		return;
	}
	
	// Extract boot-data.zip to a temp location first
	const auto temp_extract = whdbooter_path / "boot-data";
	if (std::filesystem::exists(zip) && std::filesystem::is_regular_file(zip))
	{
		// Remove old boot-data directory if it exists
		try {
			if (std::filesystem::exists(temp_extract)) {
				std::filesystem::remove_all(temp_extract);
				write_log("WHDBooter - Removed old boot-data directory\n");
			}
		} catch (const std::filesystem::filesystem_error& e) {
			write_log("WHDBooter - ERROR removing old boot-data: %s\n", e.what());
		}
		
		write_log("WHDBooter - Extracting boot-data.zip to temp location: %s\n", temp_extract.c_str());
		if (extract_zip_to_dir(zip, temp_extract)) {
			write_log("WHDBooter - Extracted boot-data.zip successfully, now copying to root...\n");
			
			// Copy all contents from boot-data/ to whdboot/ root
			try {
				for (const auto& entry : std::filesystem::directory_iterator(temp_extract)) {
					const auto src = entry.path();
					const auto dst = whdbooter_path / src.filename();
					
					std::error_code ec;
					std::filesystem::copy(src, dst,
						std::filesystem::copy_options::recursive |
						std::filesystem::copy_options::overwrite_existing,
						ec);
					
					if (ec) {
						write_log("WHDBooter - Failed copying %s to %s: %s\n", 
							src.c_str(), dst.c_str(), ec.message().c_str());
					} else {
						write_log("WHDBooter - Copied %s to root\n", src.filename().c_str());
					}
				}
				
				// Also copy WHDLoad and JST to C directory
				const auto whdload_root = whdbooter_path / "WHDLoad";
				const auto whdload_c = c_dir / "WHDLoad";
				const auto jst_root = whdbooter_path / "JST";
				const auto jst_c = c_dir / "JST";
				
				if (std::filesystem::exists(whdload_root)) {
					std::filesystem::copy_file(whdload_root, whdload_c, std::filesystem::copy_options::overwrite_existing);
					write_log("WHDBooter - Copied WHDLoad to C directory\n");
				}
				if (std::filesystem::exists(jst_root)) {
					std::filesystem::copy_file(jst_root, jst_c, std::filesystem::copy_options::overwrite_existing);
					write_log("WHDBooter - Copied JST to C directory\n");
				}
			} catch (const std::filesystem::filesystem_error& e) {
				write_log("WHDBooter - ERROR copying boot-data to root: %s\n", e.what());
			}
		} else {
			write_log("WHDBooter - ERROR extracting boot-data.zip\n");
		}
	} else {
		write_log("WHDBooter - WARNING: boot-data.zip not found at %s\n", zip.c_str());
	}
}

static std::string sanitize_mount_name(const std::string& in)
{
	if (in.empty())
		return "default";

	std::string out;
	out.reserve(in.size());
	for (unsigned char ch : in) {
		if (std::isalnum(ch) || ch == '-' || ch == '_') {
			out.push_back(static_cast<char>(ch));
		} else {
			out.push_back('_');
		}
	}

	// collapse repeated underscores
	std::string collapsed;
	collapsed.reserve(out.size());
	bool prev_us = false;
	for (char ch : out) {
		if (ch == '_') {
			if (!prev_us)
				collapsed.push_back(ch);
			prev_us = true;
		} else {
			collapsed.push_back(ch);
			prev_us = false;
		}
	}

	while (!collapsed.empty() && collapsed.front() == '_')
		collapsed.erase(collapsed.begin());
	while (!collapsed.empty() && collapsed.back() == '_')
		collapsed.pop_back();

	if (collapsed.empty())
		return "default";
	return collapsed;
}

static int hex_to_int(const char c)
{
	if (c >= '0' && c <= '9')
		return c - '0';
	if (c >= 'a' && c <= 'f')
		return 10 + (c - 'a');
	if (c >= 'A' && c <= 'F')
		return 10 + (c - 'A');
	return -1;
}

static std::string percent_decode(const std::string& in)
{
	std::string out;
	out.reserve(in.size());

	for (size_t i = 0; i < in.size(); ++i) {
		if (in[i] == '%' && i + 2 < in.size()) {
			const int hi = hex_to_int(in[i + 1]);
			const int lo = hex_to_int(in[i + 2]);
			if (hi >= 0 && lo >= 0) {
				out.push_back(static_cast<char>((hi << 4) | lo));
				i += 2;
				continue;
			}
		}

		if (in[i] == '+') {
			out.push_back(' ');
		} else {
			out.push_back(in[i]);
		}
	}

	return out;
}

static std::string game_name_from_content_uri(const std::string& uri)
{
	if (uri.rfind("content://", 0) != 0)
		return "";

	const auto slash_pos = uri.find_last_of('/');
	std::string last_segment = slash_pos == std::string::npos ? uri : uri.substr(slash_pos + 1);
	last_segment = percent_decode(last_segment);

	// SAF document IDs are usually like: primary:Folder/SubFolder/Game.lha
	const auto colon_pos = last_segment.find(':');
	if (colon_pos != std::string::npos)
		last_segment = last_segment.substr(colon_pos + 1);

	const auto base_pos = last_segment.find_last_of("\\/");
	if (base_pos != std::string::npos)
		last_segment = last_segment.substr(base_pos + 1);

	return remove_file_extension(last_segment);
}

static TCHAR* parse_text(const TCHAR* s)
{
	if (*s == '"' || *s == '\'')
	{
		const auto c = *s++;
		auto* const d = my_strdup(s);
		for (unsigned int i = 0; i < _tcslen(d); i++)
		{
			if (d[i] == c)
			{
				d[i] = 0;
				break;
			}
		}
		return d;
	}
	return my_strdup(s);
}

std::string trim_full_line(std::string full_line)
{
	const std::string tab = "\t";

	auto found = full_line.find(tab);
	while (found != std::string::npos)
	{
		full_line.replace(found, tab.size(), "");
		found = full_line.find(tab);
	}

	return full_line;
}

std::string find_substring(const std::string& search_string, const std::string& whole_string)
{
	const std::string lf = "\n";
	const auto check = string(search_string);
	auto full_line = trim_full_line(string(whole_string));

	auto lf_found = full_line.find(lf);
	while (lf_found != std::string::npos)
	{
		const auto start = full_line.find_first_of(lf, lf_found);
		auto end = full_line.find_first_of(lf, start + 1);
		if (end == std::string::npos) end = full_line.size();

		const auto found = full_line.find(check);
		if (found != std::string::npos)
		{
			const auto found_end = full_line.find_first_of(lf, found);
			auto result = full_line.substr(found + check.size() + 1, found_end - found - check.size() - 1);
			return result;
		}

		if (end < full_line.size())
			lf_found = end;
		else
			lf_found = std::string::npos;
	}

	return "nul";
}

void parse_cfg_line(uae_prefs* prefs, const std::string& line_string)
{
	TCHAR* line = my_strdup(line_string.c_str());
	cfgfile_parse_line(prefs, line, 0);
	xfree(line);
}

void parse_custom_settings(uae_prefs* p, const std::string& settings)
{
	const std::string lf = "\n";
	const std::string check = "amiberry_custom";
	auto full_line = trim_full_line(settings);

	auto lf_found = full_line.find(lf);
	while (lf_found != std::string::npos)
	{
		const auto start = full_line.find_first_of(lf, lf_found);
		auto end = full_line.find_first_of(lf, start + 1);
		if (end == std::string::npos) end = full_line.size();

		std::string line = full_line.substr(start + 1, end - start - 1);
		if (line.find(check) != std::string::npos)
		{
			parse_cfg_line(p, line);
		}

		if (end < full_line.size())
			lf_found = end;
		else
			lf_found = std::string::npos;
	}
}

std::string find_whdload_game_option(const std::string& find_setting, const std::string& whd_options)
{
	return find_substring(find_setting, whd_options);
}

game_hardware_options get_game_hardware_settings(const std::string& hardware)
{
	game_hardware_options output_detail;
	output_detail.port0 = find_whdload_game_option("PORT0", hardware);
	output_detail.port1 = find_whdload_game_option("PORT1", hardware);
	output_detail.control = find_whdload_game_option("PRIMARY_CONTROL", hardware);
	output_detail.control2 = find_whdload_game_option("SECONDARY_CONTROL", hardware);
	output_detail.cpu = find_whdload_game_option("CPU", hardware);
	output_detail.blitter = find_whdload_game_option("BLITTER", hardware);
	output_detail.clock = find_whdload_game_option("CLOCK", hardware);
	output_detail.chipset = find_whdload_game_option("CHIPSET", hardware);
	output_detail.jit = find_whdload_game_option("JIT", hardware);
	output_detail.cpu_24bit = find_whdload_game_option("CPU_24BITADDRESSING", hardware);
	output_detail.cpu_comp = find_whdload_game_option("CPU_COMPATIBLE", hardware);
	output_detail.sprites = find_whdload_game_option("SPRITES", hardware);
	output_detail.scr_height = find_whdload_game_option("SCREEN_HEIGHT", hardware);
	output_detail.scr_width = find_whdload_game_option("SCREEN_WIDTH", hardware);
	output_detail.scr_autoheight = find_whdload_game_option("SCREEN_AUTOHEIGHT", hardware);
	output_detail.scr_centerh = find_whdload_game_option("SCREEN_CENTERH", hardware);
	output_detail.scr_centerv = find_whdload_game_option("SCREEN_CENTERV", hardware);
	output_detail.scr_offseth = find_whdload_game_option("SCREEN_OFFSETH", hardware);
	output_detail.scr_offsetv = find_whdload_game_option("SCREEN_OFFSETV", hardware);
	output_detail.ntsc = find_whdload_game_option("NTSC", hardware);
	output_detail.fast = find_whdload_game_option("FAST_RAM", hardware);
	output_detail.z3 = find_whdload_game_option("Z3_RAM", hardware);
	output_detail.cpu_exact = find_whdload_game_option("CPU_EXACT", hardware);

	return output_detail;
}

void make_rom_symlink(const std::string& kickstart_short_name, const int kickstart_number, struct uae_prefs* prefs)
{
	std::filesystem::path kickstart_long_path = kickstart_path;
	kickstart_long_path /= kickstart_short_name;

	// Remove the symlink if it already exists
	if (std::filesystem::is_symlink(kickstart_long_path)) {
		std::filesystem::remove(kickstart_long_path);
	}

	if (!std::filesystem::exists(kickstart_long_path))
	{
		const int roms[2] = { kickstart_number, -1 };
		// copy the existing prefs->romfile to a backup variable, so we can restore it afterward
		const std::string old_romfile = prefs->romfile;
		if (configure_rom(prefs, roms, 0) == 1)
		{
			try {
				std::filesystem::create_symlink(prefs->romfile, kickstart_long_path);
				write_log("Making SymLink for Kickstart ROM: %s  [Ok]\n", kickstart_long_path.c_str());
			}
			catch (std::filesystem::filesystem_error& e) {
				if (e.code() == std::errc::operation_not_permitted) {
					// Fallback to copying file if filesystem does not support the generation of symlinks
					std::filesystem::copy(prefs->romfile, kickstart_long_path);
					write_log("Copying Kickstart ROM: %s  [Ok]\n", kickstart_long_path.c_str());
				}
				else {
					write_log("Error creating SymLink for Kickstart ROM: %s  [Fail]\n", kickstart_long_path.c_str());
				}
			}
		}
		// restore the original prefs->romfile
        strcpy(prefs->romfile, old_romfile.c_str());
	}
}

void symlink_roms(struct uae_prefs* prefs)
{
	write_log("SymLink Kickstart ROMs for Booter\n");

	// here we can do some checks for Kickstarts we might need to make symlinks for
	current_dir = home_dir;

	// are we using save-data/ ?
	kickstart_path = get_savedatapath(true);
	kickstart_path /= "Kickstarts";
	if (!std::filesystem::exists(kickstart_path)) {
		std::error_code ec;
		std::filesystem::create_directories(kickstart_path, ec);
	}

	// Ensure RTB files are available in save-data/Kickstarts by copying from bundled boot-data
	whdbooter_path = get_whdbootpath();
	const std::filesystem::path bundled_kickstarts = resolve_boot_data_dir() / "Devs" / "Kickstarts";
	if (std::filesystem::exists(bundled_kickstarts) && std::filesystem::is_directory(bundled_kickstarts)) {
		for (const auto& entry : std::filesystem::directory_iterator(bundled_kickstarts)) {
			if (!entry.is_regular_file())
				continue;
			const auto src = entry.path();
			const auto dst = kickstart_path / src.filename();
			if (!std::filesystem::exists(dst)) {
				std::error_code ec;
				std::filesystem::copy_file(src, dst, std::filesystem::copy_options::overwrite_existing, ec);
			}
		}
	}
	write_log("WHDBoot - using kickstarts from %s\n", kickstart_path.c_str());

	// Fallback: ensure at least the active A1200 ROM alias exists for WHDLoad.
	// Some environments fail to generate symlinked aliases via configure_rom(),
	// leaving only .RTB files and causing WHDLoad to drop to DOS prompt.
	std::filesystem::path active_rom_path = prefs->romfile;
	std::filesystem::path a1200_alias_path = kickstart_path / "kick40068.A1200";
	if (std::filesystem::exists(active_rom_path) && !std::filesystem::exists(a1200_alias_path)) {
		try {
			std::filesystem::copy_file(active_rom_path, a1200_alias_path, std::filesystem::copy_options::overwrite_existing);
			write_log("WHDBooter - Seeded Kickstarts alias from active ROM: %s -> %s\n", active_rom_path.c_str(), a1200_alias_path.c_str());
		}
		catch (const std::filesystem::filesystem_error& e) {
			write_log("WHDBooter - Failed seeding active ROM alias: %s\n", e.what());
		}
	}

	// These are all the kickstart rom files found in skick346.lha
	//   http://aminet.net/package/util/boot/skick346

	make_rom_symlink("kick33180.A500", 5, prefs);
	make_rom_symlink("kick34005.A500", 6, prefs);
	make_rom_symlink("kick37175.A500", 7, prefs);
	make_rom_symlink("kick39106.A1200", 11, prefs);
	make_rom_symlink("kick40063.A600", 14, prefs);
	make_rom_symlink("kick40068.A1200", 15, prefs);
	make_rom_symlink("kick40068.A4000", 16, prefs);

	// Symlink rom.key also
	// source file
	std::filesystem::path rom_key_source_path = get_rom_path();
	rom_key_source_path /= "rom.key";

	// destination file (symlink)
	std::filesystem::path rom_key_destination_path = kickstart_path;
	rom_key_destination_path /= "rom.key";

	if (std::filesystem::exists(rom_key_source_path) && !std::filesystem::exists(rom_key_destination_path)) {
		write_log("Making SymLink for rom.key\n");
		try {
			std::filesystem::create_symlink(rom_key_source_path, rom_key_destination_path);
		}
		catch (std::filesystem::filesystem_error& e) {
			if (e.code() == std::errc::operation_not_permitted) {
				// Fallback to copying file if filesystem does not support the generation of symlinks
				std::filesystem::copy(rom_key_source_path, rom_key_destination_path);
			}
		}
	}
}

std::string get_game_filename(const char* filepath)
{
	if (!filepath)
		return "";

	std::string game_name;
	const std::string source(filepath);
	if (source.rfind("content://", 0) == 0)
		game_name = game_name_from_content_uri(source);

	if (game_name.empty()) {
		const std::string file_name = extract_filename(filepath);
		game_name = remove_file_extension(file_name);
	}

	extract_filename(game_name.c_str(), last_loaded_config);
	return game_name;
}

void set_jport_modes(uae_prefs* prefs, const bool is_cd32)
{
	if (is_cd32)
	{
		prefs->jports[0].mode = 7;
		prefs->jports[1].mode = 7;
	}
	else
	{
		// JOY
		prefs->jports[1].mode = 3;
		// MOUSE
		prefs->jports[0].mode = 2;
	}
}
void clear_jports(uae_prefs* prefs)
{
	for (auto& jport : prefs->jports)
	{
		jport.id = JPORT_NONE;
		jport.idc.configname[0] = 0;
		jport.idc.name[0] = 0;
		jport.idc.shortid[0] = 0;
	}
}

void build_uae_config_filename(const std::string& game_name)
{
	uae_config = (conf_path / (game_name + ".uae")).string();
}

void cd_auto_prefs(uae_prefs* prefs, char* filepath)
{
	TCHAR tmp[MAX_DPATH];

	write_log("\nCD Autoload: %s  \n\n", filepath);

	conf_path = get_configuration_path();
	whdload_prefs.filename = get_game_filename(filepath);

	// LOAD GAME SPECIFICS FOR EXISTING .UAE - USE SHA1 IF AVAILABLE
	//  CONFIG LOAD IF .UAE IS IN CONFIG PATH
	build_uae_config_filename(whdload_prefs.filename);

	if (std::filesystem::exists(uae_config))
	{
		target_cfgfile_load(prefs, uae_config.c_str(), CONFIG_TYPE_DEFAULT, 0);
		config_loaded = true;
		return;
	}

	prefs->start_gui = false;

    const auto is_mt32 = whdload_prefs.filename.find("MT32") != std::string::npos || whdload_prefs.filename.find("mt32") != std::string::npos;
    const auto is_cdtv = whdload_prefs.filename.find("CDTV") != std::string::npos || whdload_prefs.filename.find("cdtv") != std::string::npos;
    const auto is_cd32 = whdload_prefs.filename.find("CD32") != std::string::npos || whdload_prefs.filename.find("cd32") != std::string::npos;

	if (is_cdtv && !is_cd32)
	{
		_tcscpy(prefs->description, _T("AutoBoot Configuration [CDTV]"));
		// SET THE BASE AMIGA (CDTV)
		built_in_prefs(prefs, 9, 0, 0, 0);
	}
	else
	{
		_tcscpy(prefs->description, _T("AutoBoot Configuration [CD32]"));
		// SET THE BASE AMIGA (CD32)
		built_in_prefs(prefs, 8, 3, 0, 0);
	}

	if (is_mt32)
	{
		// Check if we have the MT32 ROMs
		auto mt32_available = midi_emu_available(_T("MT-32"));
		auto cm32_available = midi_emu_available(_T("CM-32L"));
		if (!mt32_available && !cm32_available)
		{
			write_log("MT32/CM32L MIDI Emulation not available (ROMs missing)\n");
		}
		else
		{
			// Enable MIDI output
			_tcscpy(prefs->midioutdev, mt32_available ? "Munt MT-32" : "Munt CM-32L");
		}
	}

	// enable CD
	_sntprintf(tmp, MAX_DPATH, "cd32cd=1");
	cfgfile_parse_line(prefs, parse_text(tmp), 0);

	// mount the image
	_sntprintf(tmp, MAX_DPATH, "cdimage0=%s,image", filepath);
	cfgfile_parse_line(prefs, parse_text(tmp), 0);

	// Set joystick port MODES only (CD32 pad / mouse / joy).
	// Do NOT override the actual device assignments — those come from
	// the user's main GUI Input settings (passed via -s joyport0/1=...).
	set_jport_modes(prefs, is_cd32);
}

void set_input_settings(uae_prefs* prefs, const game_hardware_options& game_detail, const bool is_cd32)
{
	// Set joystick port MODES based on game requirements (CD32/mouse/joy)
	// but do NOT override the actual device assignments.
	// The user's main GUI Input settings (joyport0/1 device) are applied
	// via command-line args (-s joyport0=... -s joyport1=...) which are
	// processed AFTER this function, so they take priority.

	//  CD32
	if (is_cd32 || strcmpi(game_detail.port0.c_str(), "cd32") == 0)
		prefs->jports[0].mode = 7;

	if (is_cd32	|| strcmpi(game_detail.port1.c_str(), "cd32") == 0)
		prefs->jports[1].mode = 7;

	// JOY
	if (strcmpi(game_detail.port0.c_str(), "joy") == 0)
		prefs->jports[0].mode = JSEM_MODE_JOYSTICK;
	if (strcmpi(game_detail.port1.c_str(), "joy") == 0)
		prefs->jports[1].mode = JSEM_MODE_JOYSTICK;

	// MOUSE
	if (strcmpi(game_detail.port0.c_str(), "mouse") == 0)
		prefs->jports[0].mode = 2;
	if (strcmpi(game_detail.port1.c_str(), "mouse") == 0)
		prefs->jports[1].mode = 2;
}

void set_gfx_settings(uae_prefs* prefs, const game_hardware_options& game_detail)
{
	std::string line_string;
	// SCREEN AUTO-HEIGHT
	if (strcmpi(game_detail.scr_autoheight.c_str(), "true") == 0)
	{
		prefs->gfx_auto_crop = true;
	}
	else if (strcmpi(game_detail.scr_autoheight.c_str(), "false") == 0)
	{
		prefs->gfx_auto_crop = false;
	}

	// SCREEN CENTER/HEIGHT/WIDTH
	if (strcmpi(game_detail.scr_centerh.c_str(), "smart") == 0)
	{
		if (prefs->gfx_auto_crop)
		{
			// Disable if using Auto-Crop, otherwise the output won't be correct
			prefs->gfx_xcenter = 0;
		}
		else
		{
			prefs->gfx_xcenter = 2;
		}
	}
	else if (strcmpi(game_detail.scr_centerh.c_str(), "none") == 0)
	{
		prefs->gfx_xcenter = 0;
	}

	if (strcmpi(game_detail.scr_centerv.c_str(), "smart") == 0)
	{
		if (prefs->gfx_auto_crop)
		{
			// Disable if using Auto-Crop, otherwise the output won't be correct
			prefs->gfx_ycenter = 0;
		}
		else
		{
			prefs->gfx_ycenter = 2;
		}
	}
	else if (strcmpi(game_detail.scr_centerv.c_str(), "none") == 0)
	{
		prefs->gfx_ycenter = 0;
	}

	if (strcmpi(game_detail.scr_height.c_str(), "nul") != 0)
	{
		if (!prefs->gfx_auto_crop)
		{
			prefs->gfx_manual_crop = true;
			prefs->gfx_manual_crop_height = std::stoi(game_detail.scr_height);
			prefs->gfx_vertical_offset = ((AMIGA_HEIGHT_MAX << prefs->gfx_vresolution) - prefs->gfx_manual_crop_height) / 2;
		}
	}

	if (strcmpi(game_detail.scr_width.c_str(), "nul") != 0)
	{
		if (!prefs->gfx_auto_crop)
		{
			prefs->gfx_manual_crop = true;
			prefs->gfx_manual_crop_width = std::stoi(game_detail.scr_width);
			prefs->gfx_horizontal_offset = ((AMIGA_WIDTH_MAX << prefs->gfx_resolution) - prefs->gfx_manual_crop_width) / 2;
		}
	}

	if (strcmpi(game_detail.scr_offseth.c_str(), "nul") != 0)
	{
		if (!prefs->gfx_auto_crop)
		{
			prefs->gfx_horizontal_offset = std::stoi(game_detail.scr_offseth);
		}
	}

	if (strcmpi(game_detail.scr_offsetv.c_str(), "nul") != 0)
	{
		if (!prefs->gfx_auto_crop)
		{
			prefs->gfx_vertical_offset = std::stoi(game_detail.scr_offsetv);
		}
	}
}

void set_compatibility_settings(uae_prefs* prefs, const game_hardware_options& game_detail, const bool a600_available, const bool use_aga)
{
	write_log("WHDBooter - COMPAT: Starting, use_aga=%d, game_cpu=%s\n", use_aga, game_detail.cpu.c_str());
	std::string line_string;
	// CPU 68020/040 or no A600 ROM available
	if (strcmpi(game_detail.cpu.c_str(), "68020") == 0 || strcmpi(game_detail.cpu.c_str(), "68040") == 0 || use_aga)
	{
		line_string = "cpu_type=";
		line_string.append(use_aga ? "68020" : game_detail.cpu);
		write_log("WHDBooter - COMPAT: Setting CPU via parse_cfg_line: %s\n", line_string.c_str());
		parse_cfg_line(prefs, line_string);
	}

	// CPU 68000/010 (requires a600 rom)
	else if ((strcmpi(game_detail.cpu.c_str(), "68000") == 0 || strcmpi(game_detail.cpu.c_str(), "68010") == 0) && a600_available)
	{
		line_string = "cpu_type=";
		line_string.append(game_detail.cpu);
		parse_cfg_line(prefs, line_string);

		line_string = "chipmem_size=4";
		parse_cfg_line(prefs, line_string);
	}

	// Invalid or no CPU value specified, but A600 ROM is available? Use 68000
	else if (a600_available)
	{
		write_log("Invalid or no CPU value, A600 ROM available, using CPU: 68000\n");
		line_string = "cpu_type=68000";
		parse_cfg_line(prefs, line_string);
	}
	// Fallback for any invalid values - 68020 CPU
	else
	{
		write_log("Invalid or no CPU value, A600 ROM NOT found, falling back to CPU: 68020\n");
		line_string = "cpu_type=68020";
		parse_cfg_line(prefs, line_string);
	}

	// CPU SPEED
	if (strcmpi(game_detail.clock.c_str(), "7") == 0)
	{
		line_string = "cpu_multiplier=2";
		parse_cfg_line(prefs, line_string);
	}
	else if (strcmpi(game_detail.clock.c_str(), "14") == 0)
	{
		line_string = "cpu_multiplier=4";
		parse_cfg_line(prefs, line_string);
	}
	else if (strcmpi(game_detail.clock.c_str(), "28") == 0 || strcmpi(game_detail.clock.c_str(), "25") == 0)
	{
		line_string = "cpu_multiplier=8";
		parse_cfg_line(prefs, line_string);
	}
	else if (strcmpi(game_detail.clock.c_str(), "max") == 0)
	{
		line_string = "cpu_speed=max";
		parse_cfg_line(prefs, line_string);
	}

	// COMPATIBLE CPU
	if (strcmpi(game_detail.cpu_comp.c_str(), "true") == 0)
	{
		line_string = "cpu_compatible=true";
		parse_cfg_line(prefs, line_string);
	}
	else if (strcmpi(game_detail.cpu_comp.c_str(), "false") == 0)
	{
		line_string = "cpu_compatible=false";
		parse_cfg_line(prefs, line_string);
	}

	// COMPATIBLE CPU
	if (strcmpi(game_detail.cpu_24bit.c_str(), "false") == 0 || strcmpi(game_detail.z3.c_str(), "nul") != 0)
	{
		line_string = "cpu_24bit_addressing=false";
		parse_cfg_line(prefs, line_string);
	}

	// CYCLE-EXACT CPU
	if (strcmpi(game_detail.cpu_exact.c_str(), "true") == 0)
	{
		write_log("WHDBooter - COMPAT: Game requires cycle_exact=true\n");
		line_string = "cpu_cycle_exact=true";
		parse_cfg_line(prefs, line_string);
	}
	else
	{
		// Explicitly disable cycle-exact for WHDLoad games unless required
		write_log("WHDBooter - COMPAT: Disabling cycle_exact and cpu_compatible for WHDLoad\n");
		line_string = "cpu_cycle_exact=false";
		parse_cfg_line(prefs, line_string);
		
		line_string = "cpu_memory_cycle_exact=false";
		parse_cfg_line(prefs, line_string);
		
		line_string = "cpu_compatible=false";
		parse_cfg_line(prefs, line_string);
	}

	// FAST / Z3 MEMORY REQUIREMENTS
	if (strcmpi(game_detail.fast.c_str(), "nul") != 0)
	{
		line_string = "fastmem_size=";
		line_string.append(game_detail.fast);
		parse_cfg_line(prefs, line_string);
	}
	if (strcmpi(game_detail.z3.c_str(), "nul") != 0)
	{
		line_string = "z3mem_size=";
		line_string.append(game_detail.z3);
		parse_cfg_line(prefs, line_string);
	}

	// BLITTER=IMMEDIATE/WAIT/NORMAL
	if (strcmpi(game_detail.blitter.c_str(), "immediate") == 0)
	{
		line_string = "immediate_blits=true";
		parse_cfg_line(prefs, line_string);
	}
	else if (strcmpi(game_detail.blitter.c_str(), "normal") == 0)
	{
		line_string = "waiting_blits=automatic";
		parse_cfg_line(prefs, line_string);
	}

	// JIT
	if (strcmpi(game_detail.jit.c_str(), "true") == 0)
	{
		line_string = "cachesize=16384";
		parse_cfg_line(prefs, line_string);

		line_string = "cpu_compatible=false";
		parse_cfg_line(prefs, line_string);

		line_string = "cpu_cycle_exact=false";
		parse_cfg_line(prefs, line_string);

		line_string = "cpu_memory_cycle_exact=false";
		parse_cfg_line(prefs, line_string);

		line_string = "address_space_24=false";
		parse_cfg_line(prefs, line_string);
	}

	// NTSC
	if (strcmpi(game_detail.ntsc.c_str(), "true") == 0)
	{
		line_string = "ntsc=true";
		parse_cfg_line(prefs, line_string);
	}

	// SPRITE COLLISION
	if (strcmpi(game_detail.sprites.c_str(), "nul") != 0)
	{
		line_string = "collision_level=";
		line_string.append(game_detail.sprites);
		parse_cfg_line(prefs, line_string);
	}

	// Screen settings, only if allowed to override the defaults from amiberry.conf
	if (amiberry_options.allow_display_settings_from_xml)
	{
		set_gfx_settings(prefs, game_detail);
	}
}

void parse_slave_custom_fields(whdload_slave& slave, const std::string& custom)
{
	std::istringstream stream(custom);
	std::string line;

	while (std::getline(stream, line)) {
		if (line.find("C1") != std::string::npos || line.find("C2") != std::string::npos ||
			line.find("C3") != std::string::npos || line.find("C4") != std::string::npos ||
			line.find("C5") != std::string::npos) {

			std::istringstream lineStream(line);
			std::string segment;
			std::vector<std::string> seglist;

			while (std::getline(lineStream, segment, ':')) {
				segment.erase(std::remove(segment.begin(), segment.end(), '\t'), segment.end());
				seglist.push_back(segment);
			}

			// Process seglist as needed
			if (seglist[0] == "C1")
			{
				if (seglist[1] == "B")
				{
					slave.custom1.type = bool_type;
					slave.custom1.caption = seglist[2];
					slave.custom1.value = 0;
				}
				else if (seglist[1] == "X")
				{
					slave.custom1.type = bit_type;
					slave.custom1.value = 0;
					slave.custom1.label_bit_pairs.insert(slave.custom1.label_bit_pairs.end(), { seglist[2], stoi(seglist[3]) });
				}
				else if (seglist[1] == "L")
				{
					slave.custom1.type = list_type;
					slave.custom1.caption = seglist[2];
					slave.custom1.value = 0;
					std::string token;
					std::istringstream token_stream(seglist[3]);
					while (std::getline(token_stream, token, ',')) {
						slave.custom1.labels.push_back(token);
					}
				}
			}
			else if (seglist[0] == "C2")
			{
				if (seglist[1] == "B")
				{
					slave.custom2.type = bool_type;
					slave.custom2.caption = seglist[2];
					slave.custom2.value = 0;
				}
				else if (seglist[1] == "X")
				{
					slave.custom2.type = bit_type;
					slave.custom2.value = 0;
					slave.custom2.label_bit_pairs.insert(slave.custom2.label_bit_pairs.end(), { seglist[2], stoi(seglist[3]) });
				}
				else if (seglist[1] == "L")
				{
					slave.custom2.type = list_type;
					slave.custom2.caption = seglist[2];
					slave.custom2.value = 0;
					std::string token;
					std::istringstream token_stream(seglist[3]);
					while (std::getline(token_stream, token, ',')) {
						slave.custom2.labels.push_back(token);
					}
				}
			}
			else if (seglist[0] == "C3")
			{
				if (seglist[1] == "B")
				{
					slave.custom3.type = bool_type;
					slave.custom3.caption = seglist[2];
					slave.custom3.value = 0;
				}
				else if (seglist[1] == "X")
				{
					slave.custom3.type = bit_type;
					slave.custom3.value = 0;
					slave.custom3.label_bit_pairs.insert(slave.custom3.label_bit_pairs.end(), { seglist[2], stoi(seglist[3]) });
				}
				else if (seglist[1] == "L")
				{
					slave.custom3.type = list_type;
					slave.custom3.caption = seglist[2];
					slave.custom3.value = 0;
					std::string token;
					std::istringstream token_stream(seglist[3]);
					while (std::getline(token_stream, token, ',')) {
						slave.custom3.labels.push_back(token);
					}
				}
			}
			else if (seglist[0] == "C4")
			{
				if (seglist[1] == "B")
				{
					slave.custom4.type = bool_type;
					slave.custom4.caption = seglist[2];
					slave.custom4.value = 0;
				}
				else if (seglist[1] == "X")
				{
					slave.custom4.type = bit_type;
					slave.custom4.value = 0;
					slave.custom4.label_bit_pairs.insert(slave.custom4.label_bit_pairs.end(), { seglist[2], stoi(seglist[3]) });
				}
				else if (seglist[1] == "L")
				{
					slave.custom4.type = list_type;
					slave.custom4.caption = seglist[2];
					slave.custom4.value = 0;
					std::string token;
					std::istringstream token_stream(seglist[3]);
					while (std::getline(token_stream, token, ',')) {
						slave.custom4.labels.push_back(token);
					}
				}
			}
			else if (seglist[0] == "C5")
			{
				if (seglist[1] == "B")
				{
					slave.custom5.type = bool_type;
					slave.custom5.caption = seglist[2];
					slave.custom5.value = 0;
				}
				else if (seglist[1] == "X")
				{
					slave.custom5.type = bit_type;
					slave.custom5.value = 0;
					slave.custom5.label_bit_pairs.insert(slave.custom5.label_bit_pairs.end(), { seglist[2], stoi(seglist[3]) });
				}
				else if (seglist[1] == "L")
				{
					slave.custom5.type = list_type;
					slave.custom5.caption = seglist[2];
					slave.custom5.value = 0;
					std::string token;
					std::istringstream token_stream(seglist[3]);
					while (std::getline(token_stream, token, ',')) {
						slave.custom5.labels.push_back(token);
					}
				}
			}
		}
	}
}

game_hardware_options parse_settings_from_xml(uae_prefs* prefs, const char* filepath)
{
	tinyxml2::XMLDocument doc;
	write_log(_T("WHDBooter - Searching whdload_db.xml for %s\n"), whdload_prefs.filename.c_str());

	FILE* f = fopen(whd_config.c_str(), _T("rb"));
	if (!f)
	{
		write_log(_T("Failed to open '%s'\n"), whd_config.c_str());
		return {};
	}

	tinyxml2::XMLError err = doc.LoadFile(f);
	fclose(f);
	if (err != tinyxml2::XML_SUCCESS)
	{
		write_log(_T("Failed to parse '%s':  %d\n"), whd_config.c_str(), err);
		return {};
	}

	game_hardware_options game_detail{};
	auto sha1 = my_get_sha1_of_file(filepath);
	std::transform(sha1.begin(), sha1.end(), sha1.begin(), ::tolower);

	tinyxml2::XMLElement* game_node = doc.FirstChildElement("whdbooter")->FirstChildElement("game");
	while (game_node != nullptr)
	{
		// Ideally we'd just match by sha1, but filename has worked up until now, so try that first
		// then fall back to sha1 if a user has renamed the file!
		//
		if (game_node->Attribute("filename", whdload_prefs.filename.c_str()) || 
			game_node->Attribute("sha1", sha1.c_str()))
		{
			// Name
			auto xml_element = game_node->FirstChildElement("name");
			if (xml_element)
			{
				whdload_prefs.game_name.assign(xml_element->GetText());
			}

			// Sub Path
			xml_element = game_node->FirstChildElement("subpath");
			if (xml_element)
			{
				whdload_prefs.sub_path.assign(xml_element->GetText());
			}

			// Variant UUID
			xml_element = game_node->FirstChildElement("variant_uuid");
			if (xml_element)
			{
				whdload_prefs.variant_uuid.assign(xml_element->GetText());
			}

			// Slave count
			xml_element = game_node->FirstChildElement("slave_count");
			if (xml_element)
			{
				whdload_prefs.slave_count = xml_element->IntText(0);
			}

			// Default slave
			xml_element = game_node->FirstChildElement("slave_default");
			if (xml_element)
			{
				whdload_prefs.slave_default.assign(xml_element->GetText());
				write_log("WHDBooter - Selected Slave: %s \n", whdload_prefs.slave_default.c_str());
			}

			// Slave_libraries
			xml_element = game_node->FirstChildElement("slave_libraries");
			if (xml_element->GetText() != nullptr)
			{
				if (strcmpi(xml_element->GetText(), "true") == 0)
					whdload_prefs.slave_libraries = true;
			}

			// Get slaves and settings
			xml_element = game_node->FirstChildElement("slave");
			whdload_prefs.slaves.clear();

			for (int i = 0; i < whdload_prefs.slave_count && xml_element; ++i)
			{
				whdload_slave slave;
				const char* slave_text = nullptr;

				slave_text = xml_element->FirstChildElement("filename")->GetText();
				if (slave_text)
					slave.filename.assign(slave_text);

				slave_text = xml_element->FirstChildElement("datapath")->GetText();
				if (slave_text)
					slave.data_path.assign(slave_text);

				auto customElement = xml_element->FirstChildElement("custom");
				if (customElement && ((slave_text = customElement->GetText())))
				{
					auto custom = std::string(slave_text);
					parse_slave_custom_fields(slave, custom);
				}

				whdload_prefs.slaves.emplace_back(slave);

				// Set the default slave as the selected one
				if (slave.filename == whdload_prefs.slave_default)
					whdload_prefs.selected_slave = slave;

				xml_element = xml_element->NextSiblingElement("slave");
			}

			// get hardware
			xml_element = game_node->FirstChildElement("hardware");
			if (xml_element)
			{
				std::string hardware;
				hardware.assign(xml_element->GetText());
				if (!hardware.empty())
				{
					game_detail = get_game_hardware_settings(hardware);
					write_log("WHDBooter - Game H/W Settings: \n%s\n", hardware.c_str());
				}
			}

			// get custom controls
			xml_element = game_node->FirstChildElement("custom_controls");
			if (xml_element)
			{
				std::string custom_settings;
				custom_settings.assign(xml_element->GetText());
				if (!custom_settings.empty())
				{
					parse_custom_settings(prefs, custom_settings);
					write_log("WHDBooter - Game Custom Settings: \n%s\n", custom_settings.c_str());
				}
			}

			break;
		}
		game_node = game_node->NextSiblingElement();
	}

	return game_detail;
}

void create_startup_sequence()
{
	const std::string original_sub_path = whdload_prefs.sub_path;
	const std::string safe_sub_path = sanitize_whd_sub_path(original_sub_path);
	if (safe_sub_path != original_sub_path)
	{
		write_log("WHDBooter - Normalized sub_path: '%s' -> '%s'\n",
			original_sub_path.c_str(), safe_sub_path.c_str());
	}
	whdload_prefs.sub_path = safe_sub_path;

	{
		std::error_code ec;
		const std::filesystem::path savegames_root = save_path / "Savegames";
		std::filesystem::create_directories(savegames_root, ec);
		if (ec)
		{
			write_log("WHDBooter - Failed to create Savegames root (%s): %s\n",
				savegames_root.string().c_str(), ec.message().c_str());
		}
		else if (!safe_sub_path.empty())
		{
			ec.clear();
			const std::filesystem::path savegames_game_dir = savegames_root / std::filesystem::path(safe_sub_path);
			std::filesystem::create_directories(savegames_game_dir, ec);
			if (ec)
			{
				write_log("WHDBooter - Failed to create Savegames game dir (%s): %s\n",
					savegames_game_dir.string().c_str(), ec.message().c_str());
			}
		}
	}

	std::ostringstream whd_bootscript;
	whd_bootscript << "FAILAT 999\n";

	if (whdload_prefs.slave_libraries)
	{
		whd_bootscript << "DH3:C/Assign LIBS: DH3:LIBS/ ADD\n";
	}
	if (amiberry_options.use_jst_instead_of_whd)
		whd_bootscript << "IF NOT EXISTS JST\n";
	else
		whd_bootscript << "IF NOT EXISTS WHDLoad\n";

	whd_bootscript << "DH3:C/Assign C: DH3:C/ ADD\n";
	whd_bootscript << "ENDIF\n";

	whd_bootscript << "CD \"Games:" << safe_sub_path << "\"\n";
	if (amiberry_options.use_jst_instead_of_whd)
		whd_bootscript << "JST SLAVE=\"Games:" << safe_sub_path << "/" << whdload_prefs.selected_slave.filename << "\"";
	else
		whd_bootscript << "WHDLoad SLAVE=\"Games:" << safe_sub_path << "/" << whdload_prefs.selected_slave.filename << "\"";

	// Write Cache
	if (amiberry_options.use_jst_instead_of_whd)
		whd_bootscript << " PRELOAD ";
	else
		whd_bootscript << " PRELOAD NOREQ";
	if (!whdload_prefs.write_cache)
	{
		if (amiberry_options.use_jst_instead_of_whd)
			whd_bootscript << " NOCACHE";
		else
			whd_bootscript << " NOWRITECACHE";
	}

	// CUSTOM options
	for (int i = 1; i <= 5; ++i) {
		auto& custom = whdload_prefs.selected_slave.get_custom(i);
		if (custom.value != 0) {
			whd_bootscript << " CUSTOM" << i << "=" << custom.value;
		}
	}
	if (!whdload_prefs.custom.empty())
	{
		whd_bootscript << " CUSTOM=\"" << whdload_prefs.custom << "\"";
	}

	// BUTTONWAIT
	if (whdload_prefs.button_wait)
	{
		whd_bootscript << " BUTTONWAIT";
	}

	// SPLASH
	if (!whdload_prefs.show_splash)
	{
		whd_bootscript << " SPLASHDELAY=0";
	}

	// CONFIGDELAY
	if (whdload_prefs.config_delay != 0)
	{
		whd_bootscript << " CONFIGDELAY=" << whdload_prefs.config_delay;
	}

	// SPECIAL SAVE PATH
	whd_bootscript << " SAVEPATH=Saves:Savegames/ SAVEDIR=\"" << safe_sub_path << "\"";

	// DATA PATH
	if (!whdload_prefs.selected_slave.data_path.empty())
	{
		whd_bootscript << " DATA=\"" << whdload_prefs.selected_slave.data_path << "\"";
		write_log("  Data path: %s\n", whdload_prefs.selected_slave.data_path.c_str());
	}

	whd_bootscript << '\n';

	// Launches utility program to quit the emulator (via a UAE trap in RTAREA)
	if (whdload_prefs.quit_on_exit)
	{
		whd_bootscript << "DH0:C/AmiQuit\n";
	}

	write_log("WHDBooter - Created Startup-Sequence  \n\n%s\n", whd_bootscript.str().c_str());
	write_log("WHDBooter - Saved Auto-Startup to %s\n", whd_startup.c_str());

	std::ofstream myfile(whd_startup);
	if (myfile.is_open())
	{
		myfile << whd_bootscript.str();
		myfile.close();
	}
}

bool is_a600_available(uae_prefs* prefs)
{
	int roms[3] = { 10, 9, -1 }; // kickstart 2.05 A600HD
	const auto rom_test = configure_rom(prefs, roms, 0);
	return rom_test == 1;
}

// Get the temp path for the current platform
static std::filesystem::path get_tmp_path()
{
#ifdef __ANDROID__
	return std::filesystem::path(get_whdbootpath()) / "tmp";
#else
	return "/tmp/amiberry";
#endif
}

// Returns the temporary directory for WHDBooter boot files
// Uses whdboot/tmp/ which is always user-writable on all platforms
std::filesystem::path get_whdboot_temp_path()
{
	std::filesystem::path temp_path = get_whdbootpath();
	temp_path /= "tmp";
	return temp_path;
}

// Get the temp path for games extraction
static std::filesystem::path get_games_tmp_path()
{
	return get_tmp_path() / "games";
}

static void prepare_tmp_boot_volume(const std::filesystem::path& tmp_path)
{
	const std::filesystem::path boot_data = resolve_boot_data_dir();
	if (!std::filesystem::exists(boot_data) || !std::filesystem::is_directory(boot_data)) {
		write_log("WHDBooter - Boot-data directory missing, cannot seed DH0: %s\n", boot_data.c_str());
		return;
	}

	std::error_code ec;
	std::filesystem::create_directories(tmp_path, ec);

	for (const auto& entry : std::filesystem::directory_iterator(boot_data)) {
		const auto src = entry.path();
		const auto dst = tmp_path / src.filename();
		ec.clear();
		std::filesystem::copy(src, dst,
			std::filesystem::copy_options::recursive |
			std::filesystem::copy_options::overwrite_existing,
			ec);
		if (ec) {
			write_log("WHDBooter - Failed copying boot-data entry (%s -> %s): %s\n", src.c_str(), dst.c_str(), ec.message().c_str());
		}
	}
}

static bool directory_contains_regular_files(const std::filesystem::path& dir)
{
	if (!std::filesystem::exists(dir) || !std::filesystem::is_directory(dir))
		return false;

	for (const auto& entry : std::filesystem::recursive_directory_iterator(dir)) {
		if (entry.is_regular_file())
			return true;
	}

	return false;
}

static int extract_lha_tree(struct znode* node, const std::filesystem::path& extract_dir)
{
	int extracted_count = 0;

	while (node) {
		if (node->type == ZNODE_FILE) {
			std::filesystem::path out_path = extract_dir;

			const TCHAR* rel_path = node->fullname;
			if (rel_path && rel_path[0]) {
				std::string rel_str(rel_path);
				
				// Strip archive path prefix if present (e.g., "archive.lha/entry")
				auto lha_marker = rel_str.find(".lha/");
				if (lha_marker == std::string::npos)
					lha_marker = rel_str.find(".lzh/");
				if (lha_marker != std::string::npos)
					rel_str = rel_str.substr(lha_marker + 5);
				
				// Remove leading slashes from relative path
				while (!rel_str.empty() && (rel_str.front() == '/' || rel_str.front() == '\\'))
					rel_str.erase(rel_str.begin());
				
				if (!rel_str.empty())
					out_path /= rel_str;
				else
					out_path /= node->name;
			} else {
				out_path /= node->name;
			}

			std::filesystem::path parent_dir = out_path.parent_path();
			if (!parent_dir.empty() && !std::filesystem::exists(parent_dir)) {
				try {
					std::filesystem::create_directories(parent_dir);
				}
				catch (const std::filesystem::filesystem_error& e) {
					write_log("WHDBooter - Failed to create directory: %s\n", e.what());
					node = node->next;
					continue;
				}
			}

			struct zfile* out_zf = archive_access_lha(node);
			if (out_zf) {
				FILE* out_file = fopen(out_path.c_str(), "wb");
				if (out_file) {
					fwrite(out_zf->data, 1, out_zf->size, out_file);
					fclose(out_file);
					extracted_count++;
				}
				else {
					write_log("WHDBooter - Failed to create file: %s\n", out_path.c_str());
				}
				zfile_fclose(out_zf);
			}
		}

		if (node->child)
			extracted_count += extract_lha_tree(node->child, extract_dir);

		node = node->next;
	}

	return extracted_count;
}

static int extract_archive_vfs_recursive(const std::string& archive_path,
	const std::string& root_source,
	const std::filesystem::path& extract_dir,
	const int depth)
{
	if (depth > 32)
		return 0;

	int extracted_count = 0;
	struct zdirectory* zd = zfile_opendir_archive(archive_path.c_str());
	if (!zd)
		return 0;

	TCHAR out[MAX_DPATH] = { 0 };
	while (zfile_readdir_archive(zd, out, true)) {
		int isdir = 0;
		int flags = 0;
		if (!zfile_fill_file_attrs_archive(out, &isdir, &flags, nullptr))
			continue;

		std::string full_entry(out);
		if (isdir) {
			if (full_entry != archive_path)
				extracted_count += extract_archive_vfs_recursive(full_entry, root_source, extract_dir, depth + 1);
			continue;
		}

		std::string rel = full_entry;
		const auto root_pos = full_entry.find(root_source);
		if (root_pos != std::string::npos) {
			rel = full_entry.substr(root_pos + root_source.size());
		}

		// Some backends may return paths prefixed as <archive>.lha/<entry>
		if (rel == full_entry) {
			auto marker = full_entry.find(".lha/");
			if (marker == std::string::npos)
				marker = full_entry.find(".lzh/");
			if (marker != std::string::npos)
				rel = full_entry.substr(marker + 5);
		}

		while (!rel.empty() && (rel.front() == '/' || rel.front() == '\\'))
			rel.erase(rel.begin());

		if (rel.empty())
			continue;

		// Guard against accidental absolute/archive-prefixed outputs
		if (rel.find(":/") != std::string::npos || rel.find(":\\") != std::string::npos)
			continue;

		std::filesystem::path out_path = extract_dir / std::filesystem::path(rel);
		try {
			std::filesystem::create_directories(out_path.parent_path());
		}
		catch (...) {
			continue;
		}

		struct zfile* in = zfile_fopen(out, _T("rb"), ZFD_NORMAL);
		if (!in)
			continue;

		zfile_fseek(in, 0, SEEK_END);
		const auto size = zfile_ftell(in);
		zfile_fseek(in, 0, SEEK_SET);

		if (size > 0) {
			std::vector<unsigned char> buf(static_cast<size_t>(size));
			if (zfile_fread(buf.data(), static_cast<unsigned int>(size), 1, in) == 1) {
				FILE* out_file = fopen(out_path.c_str(), "wb");
				if (out_file) {
					fwrite(buf.data(), 1, static_cast<size_t>(size), out_file);
					fclose(out_file);
					extracted_count++;
				}
			}
		}

		zfile_fclose(in);
	}

	zfile_closedir_archive(zd);
	return extracted_count;
}

static int extract_lha_via_archive_vfs(const char* filepath, const std::filesystem::path& extract_dir)
{
	if (!filepath)
		return 0;
	const std::string source(filepath);
	return extract_archive_vfs_recursive(source, source, extract_dir, 0);
}

// Extract LHA archive to a temporary directory
// Returns the path to the extracted directory, or empty string on failure
static std::string extract_lha_to_temp(const char* filepath)
{
	if (!filepath || !*filepath) {
		write_log("WHDBooter - ERROR: extract_lha_to_temp called with null/empty filepath\n");
		return "";
	}
	
	std::filesystem::path lha_path(filepath);
	std::string filename = lha_path.filename().string();
	
	write_log("\n=============== LHA EXTRACTION ===============\n");
	write_log("  Source: %s\n", filepath);
	write_log("  Filename: %s\n", filename.c_str());
	
	// Remove .lha or .lzh extension for directory name
	size_t last_dot = filename.find_last_of('.');
	if (last_dot != std::string::npos) {
		filename = filename.substr(0, last_dot);
	}
	
	// Create extraction directory
	std::filesystem::path tmp_base = get_tmp_path();
	write_log("  Tmp base path: %s\n", tmp_base.string().c_str());
	write_log("  Tmp exists: %s\n", std::filesystem::exists(tmp_base) ? "YES" : "NO");
	
	// Ensure tmp directory exists
	try {
		if (!std::filesystem::exists(tmp_base)) {
			std::filesystem::create_directories(tmp_base);
			write_log("  Created tmp directory\n");
		}
	} catch (const std::filesystem::filesystem_error& e) {
		write_log("  ERROR: Cannot create tmp directory: %s\n", e.what());
		write_log("===============================================\n\n");
		return "";
	}
	
	std::filesystem::path extract_dir = tmp_base / "games" / filename;
	write_log("  Extract target: %s\n", extract_dir.string().c_str());
	
	write_log("  Extract target: %s\n", extract_dir.string().c_str());
	
	// If directory already exists and has files, assume it's already extracted
	if (std::filesystem::exists(extract_dir)) {
		write_log("  Extract dir already exists\n");
		if (directory_contains_regular_files(extract_dir)) {
			write_log("  Already extracted, reusing\n");
			write_log("===============================================\n\n");
			return extract_dir.string();
		}

		// stale/incomplete extraction dir
		std::error_code ec;
		write_log("  Removing stale extraction dir\n");
		std::filesystem::remove_all(extract_dir, ec);
		if (ec) {
			write_log("  WARNING: Remove failed: %s\n", ec.message().c_str());
		}
	}
	
	// Create the extraction directory
	try {
		write_log("  Creating extraction directory...\n");
		std::filesystem::create_directories(extract_dir);
		write_log("  Created successfully\n");
	}
	catch (const std::filesystem::filesystem_error& e) {
		write_log("  ERROR: Failed to create: %s\n", e.what());
		write_log("===============================================\n\n");
		return "";
	}
	
	// Open the LHA archive
	write_log("  Opening LHA archive...\n");
	struct zfile* zf = zfile_fopen(filepath, _T("rb"), ZFD_NORMAL);
	if (!zf) {
		write_log("  ERROR: Failed to open LHA file\n");
		write_log("===============================================\n\n");
		return "";
	}
	write_log("  Archive opened successfully\n");
	
	// Get the archive directory
	write_log("  Reading archive directory...\n");
	struct zvolume* zv = archive_directory_lha(zf);
	if (!zv) {
		write_log("  ERROR: Failed to read LHA archive directory\n");
		zfile_fclose(zf);
		write_log("===============================================\n\n");
		return "";
	}
	write_log("  Archive directory read successfully\n");
	
	write_log("  Extracting files...\n");
	
	// Extract all files recursively
	int extracted_count = 0;
	try {
		extracted_count = extract_lha_tree(zv->root.child, extract_dir);
		write_log("  Direct extraction: %d files\n", extracted_count);
	} catch (const std::exception& e) {
		write_log("  ERROR during direct extraction: %s\n", e.what());
	} catch (...) {
		write_log("  ERROR: Unknown exception during direct extraction\n");
	}

	// Fallback for archives where direct LHA node traversal yields no files
	if (extracted_count == 0) {
		write_log("  Trying VFS fallback...\n");
		try {
			extracted_count = extract_lha_via_archive_vfs(filepath, extract_dir);
			write_log("  VFS extraction: %d files\n", extracted_count);
		} catch (const std::exception& e) {
			write_log("  ERROR during VFS extraction: %s\n", e.what());
		} catch (...) {
			write_log("  ERROR: Unknown exception during VFS extraction\n");
		}
	}
	
	// Clean up
	write_log("  Cleaning up...\n");
	zfile_fclose_archive(zv);
	zfile_fclose(zf);
	
	write_log("  Result: %d files extracted\n", extracted_count);
	write_log("===============================================\n\n");
	
	if (extracted_count > 0) {
		return extract_dir.string();
	}
	
	write_log("WHDBooter - ERROR: No files were extracted from LHA\n");
	return "";
}

// Check if a file is an LHA archive
static bool is_lha_file(const char* filepath)
{
	if (!filepath)
		return false;
	
	size_t len = strlen(filepath);
	if (len < 4)
		return false;
	
	const char* ext = filepath + len - 4;
	if (strcasecmp(ext, ".lha") == 0 || strcasecmp(ext, ".lzh") == 0)
		return true;
	
	// Also check for .lha with different case
	if (len >= 4) {
		char ext_lower[5];
		for (int i = 0; i < 4; i++) {
			ext_lower[i] = tolower((unsigned char)filepath[len - 4 + i]);
		}
		ext_lower[4] = '\0';
		if (strcmp(ext_lower, ".lha") == 0 || strcmp(ext_lower, ".lzh") == 0)
			return true;
	}
	
	return false;
}

static bool auto_select_slave_from_lha(const char* filepath)
{
	write_log("\n=============== SLAVE AUTO-DETECTION ===============\n");
	write_log("  Input file: %s\n", filepath);
	write_log("  Is LHA: %s\n", is_lha_file(filepath) ? "YES" : "NO");
	
	if (!is_lha_file(filepath))
	{
		write_log("  Result: Not an LHA file, skipping\n");
		write_log("=====================================================\n\n");
		return false;
	}

	const std::string extracted_path = extract_lha_to_temp(filepath);
	if (extracted_path.empty())
	{
		write_log("  Result: Extraction failed\n");
		write_log("=====================================================\n\n");
		return false;
	}
	write_log("  Extracted to: %s\n", extracted_path.c_str());

	std::error_code ec;
	const std::filesystem::path root(extracted_path);
	
	// Verify the root path exists and is accessible
	if (!std::filesystem::exists(root, ec) || ec) {
		write_log("  ERROR: Extracted path does not exist or is not accessible\n");
		if (ec) write_log("  Error code: %s\n", ec.message().c_str());
		write_log("=====================================================\n\n");
		return false;
	}
	
	write_log("  Searching for .slave files...\n");
	
	// Use try-catch to prevent crashes during directory iteration
	try {
		for (const auto& entry : std::filesystem::recursive_directory_iterator(root, ec)) {
			if (ec)
			{
				write_log("  ERROR iterating directory: %s\n", ec.message().c_str());
				break;
			}
			
			// Skip directories
			try {
				if (!entry.is_regular_file())
					continue;
			} catch (...) {
				// Skip entries we can't check
				continue;
			}

			const std::string ext = entry.path().extension().string();
			if (strcasecmp(ext.c_str(), ".slave") != 0)
				continue;

			write_log("  Found slave file: %s\n", entry.path().string().c_str());

			std::filesystem::path rel = std::filesystem::relative(entry.path(), root, ec);
			if (ec || rel.empty())
				rel = entry.path().filename();

			whdload_prefs.selected_slave.filename = rel.filename().string();
			whdload_prefs.slave_default = whdload_prefs.selected_slave.filename;

			auto parent = rel.parent_path().generic_string();
			if (parent == ".")
				parent.clear();
			whdload_prefs.sub_path = parent;

			write_log("  Result: SUCCESS\n");
			write_log("    Slave filename: %s\n", whdload_prefs.selected_slave.filename.c_str());
			write_log("    Sub-path: %s\n", whdload_prefs.sub_path.empty() ? "(none)" : whdload_prefs.sub_path.c_str());
			write_log("    Will be accessed as: DH1:%s%s\n",
				whdload_prefs.sub_path.empty() ? "" : (whdload_prefs.sub_path + "/").c_str(),
				whdload_prefs.selected_slave.filename.c_str());
			write_log("=====================================================\n\n");
			return true;
		}
	} catch (const std::filesystem::filesystem_error& e) {
		write_log("  ERROR: Filesystem exception during search: %s\n", e.what());
		write_log("=====================================================\n\n");
		return false;
	} catch (const std::exception& e) {
		write_log("  ERROR: Exception during search: %s\n", e.what());
		write_log("=====================================================\n\n");
		return false;
	} catch (...) {
		write_log("  ERROR: Unknown exception during search\n");
		write_log("=====================================================\n\n");
		return false;
	}

	write_log("  Result: No .slave file found\n");
	write_log("=====================================================\n\n");
	return false;
}

void set_booter_drives(uae_prefs* prefs, const char* filepath)
{
	std::string tmp;

	{
		std::error_code ec;
		std::filesystem::create_directories(save_path, ec);
		if (ec)
		{
			write_log("WHDBooter - Failed to create save-data path (%s): %s\n",
				save_path.string().c_str(), ec.message().c_str());
		}
	}

	if (!whdload_prefs.selected_slave.filename.empty()) // new booter solution
	{
		boot_path = get_whdboot_temp_path();

		tmp = "filesystem2=rw,DH0:DH0:" + boot_path.string() + ",10";
		cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);

		tmp = "uaehf0=dir,rw,DH0:DH0::" + boot_path.string() + ",10";
		cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);

		boot_path = whdbooter_path / "boot-data.zip";
		if (!std::filesystem::exists(boot_path))
			boot_path = whdbooter_path / "boot-data";

		tmp = "filesystem2=rw,DH3:DH3:" + boot_path.string() + ",-10";
		cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);

		tmp = "uaehf0=dir,rw,DH3:DH3::" + boot_path.string() + ",-10";
		cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);
	}
	else // revert to original booter if no slave was set
	{
		boot_path = whdbooter_path / "boot-data.zip";
		if (!std::filesystem::exists(boot_path))
			boot_path = whdbooter_path / "boot-data";

		tmp = "filesystem2=rw,DH0:DH0:" + boot_path.string() + ",10";
		cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);

		tmp = "uaehf0=dir,rw,DH0:DH0::" + boot_path.string() + ",10";
		cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);
	}

	//set the Second (game data) drive
	tmp = "filesystem2=rw,DH1:Games:\"" + std::string(filepath) + "\",0";
	cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);

	tmp = "uaehf1=dir,rw,DH1:Games:\"" + std::string(filepath) + "\",0";
	cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);

	//set the third (save data) drive
	whd_path = save_path / "";

	if (std::filesystem::exists(save_path))
	{
		tmp = "filesystem2=rw,DH2:Saves:" + save_path.string() + ",0";
		cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);

		tmp = "uaehf2=dir,rw,DH2:Saves:" + save_path.string() + ",0";
		cfgfile_parse_line(prefs, parse_text(tmp.c_str()), 0);
	}

	write_log("WHDBooter - Drive setup complete:\n");
	write_log("  DH0: %s (boot, pri=10)\n", get_whdboot_temp_path().string().c_str());
	write_log("  DH1: %s (game)\n", filepath);
	write_log("  DH2: %s (saves)\n", save_path.string().c_str());
	write_log("  DH3: %s (boot-data, pri=-10)\n", boot_path.string().c_str());
}

void whdload_auto_prefs(uae_prefs* prefs, const char* filepath)
{
	write_log("WHDBooter Launched\n");
	if (amiberry_options.use_jst_instead_of_whd)
		write_log("WHDBooter - Using JST instead of WHDLoad\n");

	conf_path = get_configuration_path();
	whdbooter_path = get_whdbootpath();
	save_path = get_savedatapath(false);

	symlink_roms(prefs);

	// this allows A600HD to be used to slow games down
	const auto a600_available = is_a600_available(prefs);
	if (a600_available)
	{
		write_log("WHDBooter - Host: A600 ROM available, will use it for non-AGA titles\n");
	}
	else
	{
		write_log("WHDBooter - Host: A600 ROM not found, falling back to A1200 config for all titles\n");
	}

	// REMOVE THE FILE PATH AND EXTENSION
	const auto* filename = my_getfilepart(filepath);
	const std::string filename_no_extension = get_game_filename(filepath);
	whdload_prefs.filename = filename_no_extension;

	// setup for tmp folder.
	std::filesystem::path temp_base = get_whdboot_temp_path();
	// Clean up any stale files from previous runs to ensure a fresh tmp
	// This helps avoid stale/broken symlinks causing WHDLoad not found
	try {
		if (std::filesystem::exists(temp_base)) {
			write_log("WHDBooter - Cleaning existing tmp directory %s\n", temp_base.string().c_str());
			std::filesystem::remove_all(temp_base);
		}
	}
	catch (std::filesystem::filesystem_error &e) {
		write_log("WHDBooter - Failed to clean tmp directory %s: %s\n", temp_base.string().c_str(), e.what());
	}

	std::filesystem::create_directories(temp_base / "s");
	std::filesystem::create_directories(temp_base / "c");
	std::filesystem::create_directories(temp_base / "devs");
	whd_startup = (temp_base / "s" / "startup-sequence").string();
	std::filesystem::remove(whd_startup);

	// are we using save-data/ ?
	kickstart_path = std::filesystem::path(get_savedatapath(true)) / "Kickstarts";

	// LOAD GAME SPECIFICS
	whd_path = whdbooter_path / "game-data";
	game_hardware_options game_detail;
	whd_config = whd_path / "whdload_db.xml";

	if (std::filesystem::exists(whd_config))
	{
		game_detail = parse_settings_from_xml(prefs, filepath);
	}
	else
	{
		write_log("WHDBooter - Could not load whdload_db.xml - does not exist?\n");
	}

	// If the XML lookup didn't find a slave (game not in database),
	// try auto-detecting the .slave file from inside the LHA archive
	if (whdload_prefs.selected_slave.filename.empty())
	{
		write_log("WHDBooter - Game not found in XML database, attempting slave auto-detection from LHA...\n");
		auto_select_slave_from_lha(filepath);
	}

	// LOAD CUSTOM CONFIG
	build_uae_config_filename(whdload_prefs.filename);
	// If we have a config file, we will load that on top of the XML settings
	if (std::filesystem::exists(uae_config))
	{
		write_log("WHDBooter - %s found. Loading Config for WHDLoad options.\n", uae_config.c_str());
		target_cfgfile_load(prefs, uae_config.c_str(), CONFIG_TYPE_DEFAULT, 0);
		config_loaded = true;
	}

	// If we have a slave, create a startup-sequence
	if (!whdload_prefs.selected_slave.filename.empty())
	{
		create_startup_sequence();
	}

	// now we should have a startup-sequence file (if we don't, we are going to use the original booter)
	if (std::filesystem::exists(whd_startup))
	{
		if (amiberry_options.use_jst_instead_of_whd)
		{
			// create a link/copy to JST
			whd_path = whdbooter_path / "JST";
			std::filesystem::path jst_link = temp_base / "c" / "JST";
			// Remove stale link if it exists
			if (std::filesystem::is_symlink(jst_link) || std::filesystem::exists(jst_link)) {
				try { std::filesystem::remove_all(jst_link); } catch (std::filesystem::filesystem_error &e) { write_log("WHDBooter - Failed to remove existing JST link/tree %s: %s\n", jst_link.string().c_str(), e.what()); }
			}
			if (std::filesystem::exists(whd_path) && !std::filesystem::exists(jst_link)) {
				write_log("WHDBooter - Creating link/copy to JST in %s\n", (temp_base / "c").string().c_str());
				try {
#ifdef __ANDROID__
				// Android's external storage often does not support symlinks; copy instead
				std::filesystem::copy(whd_path, jst_link, std::filesystem::copy_options::recursive);
#else
				std::filesystem::create_symlink(whd_path, jst_link);
#endif
				}
				catch (std::filesystem::filesystem_error& e) {
					write_log("WHDBooter - JST link creation failed (%s). Falling back to copy: %s\n", jst_link.string().c_str(), e.what());
					try {
						std::filesystem::copy(whd_path, jst_link, std::filesystem::copy_options::recursive);
					}
					catch (std::filesystem::filesystem_error &e2) {
						write_log("WHDBooter - JST copy also failed: %s\n", e2.what());
					}
				}
			}
		}
		else
		{
			// create a link/copy to WHDLoad
			whd_path = whdbooter_path / "WHDLoad";
			std::filesystem::path whdload_link = temp_base / "c" / "WHDLoad";
			// Remove stale link/file if present
			if (std::filesystem::is_symlink(whdload_link) || std::filesystem::exists(whdload_link)) {
				try { std::filesystem::remove_all(whdload_link); } catch (std::filesystem::filesystem_error &e) { write_log("WHDBooter - Failed to remove existing WHDLoad link/tree %s: %s\n", whdload_link.string().c_str(), e.what()); }
			}
			if (std::filesystem::exists(whd_path) && !std::filesystem::exists(whdload_link)) {
				write_log("WHDBooter - Creating link/copy to WHDLoad in %s\n", (temp_base / "c").string().c_str());
				try {
#ifdef __ANDROID__
				std::filesystem::copy(whd_path, whdload_link, std::filesystem::copy_options::recursive);
#else
				std::filesystem::create_symlink(whd_path, whdload_link);
#endif
				}
				catch (std::filesystem::filesystem_error& e) {
					write_log("WHDBooter - WHDLoad link creation failed (%s). Falling back to copy: %s\n", whdload_link.string().c_str(), e.what());
					try {
						std::filesystem::copy(whd_path, whdload_link, std::filesystem::copy_options::recursive);
					}
					catch (std::filesystem::filesystem_error &e2) {
						write_log("WHDBooter - WHDLoad copy also failed: %s\n", e2.what());
					}
				}
			}
		}

		// Create a link/copy to AmiQuit
		whd_path = whdbooter_path / "AmiQuit";
		std::filesystem::path amiquit_link = temp_base / "c" / "AmiQuit";
		if (std::filesystem::is_symlink(amiquit_link) || std::filesystem::exists(amiquit_link)) {
			try { std::filesystem::remove_all(amiquit_link); } catch (std::filesystem::filesystem_error &e) { write_log("WHDBooter - Failed to remove existing AmiQuit link/tree %s: %s\n", amiquit_link.string().c_str(), e.what()); }
		}
		if (std::filesystem::exists(whd_path) && !std::filesystem::exists(amiquit_link)) {
			write_log("WHDBooter - Creating link/copy to AmiQuit in %s\n", (temp_base / "c").string().c_str());
			try {
#ifdef __ANDROID__
			std::filesystem::copy(whd_path, amiquit_link, std::filesystem::copy_options::recursive);
#else
			std::filesystem::create_symlink(whd_path, amiquit_link);
#endif
			}
			catch (std::filesystem::filesystem_error& e) {
				write_log("WHDBooter - AmiQuit link creation failed (%s). Falling back to copy: %s\n", amiquit_link.string().c_str(), e.what());
				try {
					std::filesystem::copy(whd_path, amiquit_link, std::filesystem::copy_options::recursive);
				}
				catch (std::filesystem::filesystem_error &e2) {
					write_log("WHDBooter - AmiQuit copy also failed: %s\n", e2.what());
				}
			}
		}

		// create a symlink/copy for DEVS/Kickstarts
		std::filesystem::path kickstarts_link = temp_base / "devs" / "Kickstarts";
		if (std::filesystem::is_symlink(kickstarts_link) || std::filesystem::exists(kickstarts_link)) {
			try { std::filesystem::remove_all(kickstarts_link); } catch (std::filesystem::filesystem_error &e) { write_log("WHDBooter - Failed to remove existing Kickstarts link/tree %s: %s\n", kickstarts_link.string().c_str(), e.what()); }
		}
		if (!std::filesystem::exists(kickstarts_link)) {
			write_log("WHDBooter - Creating link/copy to Kickstarts in %s\n", (temp_base / "devs").string().c_str());
			try {
#ifdef __ANDROID__
				std::filesystem::copy(kickstart_path, kickstarts_link, std::filesystem::copy_options::recursive);
#else
				std::filesystem::create_symlink(kickstart_path, kickstarts_link);
#endif
			}
			catch (std::filesystem::filesystem_error& e) {
				write_log("WHDBooter - Kickstarts link creation failed (%s). Falling back to copy: %s\n", kickstarts_link.string().c_str(), e.what());
				try {
					std::filesystem::copy(kickstart_path, kickstarts_link, std::filesystem::copy_options::recursive);
				}
				catch (std::filesystem::filesystem_error &e2) {
					write_log("WHDBooter - Kickstarts copy also failed: %s\n", e2.what());
				}
			}
		}
	}
#if DEBUG
	// debugging code!
	write_log("WHDBooter - Game: Port 0     : %s  \n", game_detail.port0.c_str());
	write_log("WHDBooter - Game: Port 1     : %s  \n", game_detail.port1.c_str());
	write_log("WHDBooter - Game: Control    : %s  \n", game_detail.control.c_str());
	write_log("WHDBooter - Game: CPU        : %s  \n", game_detail.cpu.c_str());
	write_log("WHDBooter - Game: Blitter    : %s  \n", game_detail.blitter.c_str());
	write_log("WHDBooter - Game: CPU Clock  : %s  \n", game_detail.clock.c_str());
	write_log("WHDBooter - Game: Chipset    : %s  \n", game_detail.chipset.c_str());
	write_log("WHDBooter - Game: JIT        : %s  \n", game_detail.jit.c_str());
	write_log("WHDBooter - Game: CPU Compat : %s  \n", game_detail.cpu_comp.c_str());
	write_log("WHDBooter - Game: Sprite Col : %s  \n", game_detail.sprites.c_str());
	write_log("WHDBooter - Game: Scr Height : %s  \n", game_detail.scr_height.c_str());
	write_log("WHDBooter - Game: Scr Width  : %s  \n", game_detail.scr_width.c_str());
	write_log("WHDBooter - Game: Scr AutoHgt: %s  \n", game_detail.scr_autoheight.c_str());
	write_log("WHDBooter - Game: Scr CentrH : %s  \n", game_detail.scr_centerh.c_str());
	write_log("WHDBooter - Game: Scr CentrV : %s  \n", game_detail.scr_centerv.c_str());
	write_log("WHDBooter - Game: Scr OffsetH: %s  \n", game_detail.scr_offseth.c_str());
	write_log("WHDBooter - Game: Scr OffsetV: %s  \n", game_detail.scr_offsetv.c_str());
	write_log("WHDBooter - Game: NTSC       : %s  \n", game_detail.ntsc.c_str());
	write_log("WHDBooter - Game: Fast Ram   : %s  \n", game_detail.fast.c_str());
	write_log("WHDBooter - Game: Z3 Ram     : %s  \n", game_detail.z3.c_str());
	write_log("WHDBooter - Game: CPU Exact  : %s  \n", game_detail.cpu_exact.c_str());

	write_log("WHDBooter - Host: Controller 1   : %s  \n", amiberry_options.default_controller1);
	write_log("WHDBooter - Host: Controller 2   : %s  \n", amiberry_options.default_controller2);
	write_log("WHDBooter - Host: Controller 3   : %s  \n", amiberry_options.default_controller3);
	write_log("WHDBooter - Host: Controller 4   : %s  \n", amiberry_options.default_controller4);
	write_log("WHDBooter - Host: Mouse 1        : %s  \n", amiberry_options.default_mouse1);
	write_log("WHDBooter - Host: Mouse 2        : %s  \n", amiberry_options.default_mouse2);
#endif

	// if we already loaded a .uae config, we don't need to do the below manual setup for hardware
	if (config_loaded)
	{
		write_log("WHDBooter - %s found; ignoring WHD Quickstart setup.\n", uae_config.c_str());
		return;
	}

	//    *** EMULATED HARDWARE ***
	//    SET UNIVERSAL DEFAULTS
	prefs->start_gui = false;

	// DO CHECKS FOR AGA / CD32
	const auto is_aga = _tcsstr(filename, "AGA") != nullptr || strcmpi(game_detail.chipset.c_str(), "AGA") == 0;
	const auto is_cd32 = _tcsstr(filename, "CD32") != nullptr || strcmpi(game_detail.chipset.c_str(), "CD32") == 0;
	const auto is_mt32 = _tcsstr(filename, _T("MT32")) != nullptr || _tcsstr(filename, _T("mt32")) != nullptr;

	if (is_aga || is_cd32 || !a600_available)
	{
		// SET THE BASE AMIGA (Expanded A1200)
		write_log("WHDBooter - Host: A1200 ROM selected\n");
		built_in_prefs(prefs, 4, A1200_CONFIG, 0, 0);
		// set 8MB Fast RAM
		prefs->fastmem[0].size = 0x800000;
		_tcscpy(prefs->description, _T("AutoBoot Configuration [WHDLoad] [AGA]"));
	}
	else
	{
		// SET THE BASE AMIGA (Expanded A600)
		write_log("WHDBooter - Host: A600 ROM selected\n");
		built_in_prefs(prefs, 2, A600_CONFIG, 0, 0);
		_tcscpy(prefs->description, _T("AutoBoot Configuration [WHDLoad]"));
	}

	if (is_mt32)
	{
		// Check if we have the MT32 ROMs
		auto mt32_available = midi_emu_available(_T("MT-32"));
		auto cm32_available = midi_emu_available(_T("CM-32L"));
		if (!mt32_available && !cm32_available)
		{
			write_log("MT32/CM32L MIDI Emulation not available (ROMs missing)\n");
		}
		else
		{
			// Enable MIDI output
			_tcscpy(prefs->midioutdev, mt32_available ? "Munt MT-32" : "Munt CM-32L");
		}
	}

	// SET THE WHD BOOTER AND GAME DATA
	write_log("WHDBooter - Host: setting up drives\n");
	set_booter_drives(prefs, filepath);

	// APPLY THE SETTINGS FOR MOUSE/JOYSTICK ETC
	write_log("WHDBooter - Host: setting up controllers\n");
	set_input_settings(prefs, game_detail, is_cd32);

	//  SET THE GAME COMPATIBILITY SETTINGS
	// BLITTER, SPRITES, MEMORY, JIT, BIG CPU ETC
	write_log("WHDBooter - Host: setting up game compatibility settings\n");
	set_compatibility_settings(prefs, game_detail, a600_available, is_aga || is_cd32);

	write_log("WHDBooter - Host: settings applied\n\n");
}
