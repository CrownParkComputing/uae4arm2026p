package com.uae4arm2026;

/**
 * Shared preference keys for the native Android options UI.
 *
 * These map directly to UAE/Amiberry config keys used with "-s key=value".
 */
public final class UaeOptionKeys {
    private UaeOptionKeys() {}

    public static final String PREFS_NAME = "bootstrap";

    // Android host-only UI options (not passed as UAE config keys).
    public static final String UAE_VIDEO_ASPECT_MODE = "uae_video_aspect_mode"; // int: 0=4:3, 1=16:9

    // Overrides
    public static final String UAE_OVERRIDE_CPUFPU = "uae_override_cpu_fpu"; // boolean

    public static final String UAE_OVERRIDE_CHIPSET = "uae_override_chipset"; // boolean

    // Sound (mirrors the desktop GUI "Sound" tab)
    // These map directly to UAE config keys unless noted.
    public static final String UAE_SOUND_OUTPUT = "uae_sound_output"; // string: none, interrupts, normal
    public static final String UAE_SOUND_AUTO = "uae_sound_auto"; // boolean (sound_auto)
    public static final String UAE_SOUND_CHANNELS = "uae_sound_channels"; // string (sound_channels)
    public static final String UAE_SOUND_FREQUENCY = "uae_sound_frequency"; // int (sound_frequency)
    public static final String UAE_SOUND_INTERPOL = "uae_sound_interpol"; // string (sound_interpol)

    // Sound filter is modeled by two keys.
    public static final String UAE_SOUND_FILTER = "uae_sound_filter"; // string: off, emulated, on
    public static final String UAE_SOUND_FILTER_TYPE = "uae_sound_filter_type"; // string: standard, enhanced

    public static final String UAE_SOUND_STEREO_SEPARATION = "uae_sound_stereo_separation"; // int (sound_stereo_separation)
    public static final String UAE_SOUND_STEREO_DELAY = "uae_sound_stereo_delay"; // int (sound_stereo_mixing_delay; 0 = none)

    public static final String UAE_SOUND_SWAP_PAULA = "uae_sound_swap_paula"; // boolean (sound_stereo_swap_paula)
    public static final String UAE_SOUND_SWAP_AHI = "uae_sound_swap_ahi"; // boolean (sound_stereo_swap_ahi)

    public static final String UAE_SOUND_VOLUME_PAULA = "uae_sound_volume_paula"; // int (sound_volume_paula)
    public static final String UAE_SOUND_VOLUME_CD = "uae_sound_volume_cd"; // int (sound_volume_cd)
    public static final String UAE_SOUND_VOLUME_AHI = "uae_sound_volume_ahi"; // int (sound_volume_ahi)
    public static final String UAE_SOUND_VOLUME_MIDI = "uae_sound_volume_midi"; // int (sound_volume_midi)

    public static final String UAE_SOUND_MAX_BUFF = "uae_sound_max_buff"; // int (sound_max_buff)

    // Host/target option (written to amiberry.conf; pass via -o)
    public static final String UAE_SOUND_PULLMODE = "uae_sound_pullmode"; // boolean (sound_pullmode: pull=1, push=0)

    // Floppy drive sound emulation.
    public static final String UAE_FLOPPY_SOUND_ENABLED = "uae_floppy_sound_enabled"; // boolean (floppy0..3sound)
    public static final String UAE_FLOPPY_SOUNDVOL_EMPTY = "uae_floppy_soundvol_empty"; // int (floppy0..3soundvolume_empty)
    public static final String UAE_FLOPPY_SOUNDVOL_DISK = "uae_floppy_soundvol_disk"; // int (floppy0..3soundvolume_disk)

    // Floppy speed (desktop GUI: Turbo/100/200/400/800). Maps to config key "floppy_speed".
    public static final String UAE_FLOPPY_SPEED = "uae_floppy_speed"; // int (floppy_speed)

    // Chipset
    public static final String UAE_CHIPSET = "uae_chipset"; // values: ocs, ecs_agnus, ecs_denise, ecs, aga
    public static final String UAE_CHIPSET_COMPATIBLE = "uae_chipset_compatible"; // values: "-", "A500", "A1200", ...
    public static final String UAE_NTSC = "uae_ntsc"; // boolean
    public static final String UAE_CYCLE_EXACT = "uae_cycle_exact"; // values: false, memory, true
    public static final String UAE_COLLISION_LEVEL = "uae_collision_level"; // values: none, sprites, playfields, full

    // CPU
    public static final String UAE_CPU_MODEL = "uae_cpu_model"; // numeric string: 68000..68060
    public static final String UAE_CPU_COMPATIBLE = "uae_cpu_compatible"; // boolean
    public static final String UAE_CPU_SPEED = "uae_cpu_speed"; // values: real, max, 1..20

    // CPU cycle-exact (PanelCPU "CPU frequency")
    public static final String UAE_CPU_MULTIPLIER = "uae_cpu_multiplier"; // values: 1, 2, 4, 8, 16 (maps to cpu_multiplier)

    public static final String UAE_CPU_24BIT_ADDRESSING = "uae_cpu_24bit_addressing"; // boolean
    public static final String UAE_CPU_DATA_CACHE = "uae_cpu_data_cache"; // boolean

    // MMU
    public static final String UAE_MMU_MODEL = "uae_mmu_model"; // values: 68030/68040/68060 or 68ec030/68ec040/68ec060

    // PowerPC
    public static final String UAE_PPC_ENABLED = "uae_ppc_enabled"; // boolean
    public static final String UAE_PPC_IMPLEMENTATION = "uae_ppc_implementation"; // values: auto, dummy, pearpc, qemu
    public static final String UAE_PPC_CPU_IDLE = "uae_ppc_cpu_idle"; // values: disabled, 1..9, max

    // JIT
    public static final String UAE_JIT_ENABLED = "uae_jit_enabled"; // boolean
    public static final String UAE_CACHESIZE = "uae_cachesize"; // int MB, maps to config key cachesize
    public static final String UAE_COMP_FPU = "uae_comp_fpu"; // boolean, maps to comp_fpu
    public static final String UAE_COMP_CONSTJUMP = "uae_comp_constjump"; // boolean
    public static final String UAE_COMP_FLUSHMODE = "uae_comp_flushmode"; // values: soft, hard
    public static final String UAE_COMP_TRUSTMODE = "uae_comp_trustmode"; // values: direct, indirect
    public static final String UAE_COMP_NF = "uae_comp_nf"; // boolean
    public static final String UAE_COMP_CATCHFAULT = "uae_comp_catchfault"; // boolean

    // FPU
    public static final String UAE_FPU_MODEL = "uae_fpu_model"; // numeric string: 0, 68881, 68882, 68040, 68060
    public static final String UAE_FPU_STRICT = "uae_fpu_strict"; // boolean (maps to fpu_strict)

    // ROM
    // These map to: kickstart_rom_file, kickstart_ext_rom_file, cart_file
    public static final String UAE_ROM_KICKSTART_FILE = "uae_rom_kickstart_file"; // string absolute path
    public static final String UAE_ROM_KICKSTART_LABEL = "uae_rom_kickstart_label"; // original filename for UI
    public static final String UAE_ROM_EXT_FILE = "uae_rom_ext_file"; // string absolute path
    public static final String UAE_ROM_EXT_LABEL = "uae_rom_ext_label"; // original filename for UI
    public static final String UAE_ROM_CART_FILE = "uae_rom_cart_file"; // string absolute path
    public static final String UAE_ROM_CART_LABEL = "uae_rom_cart_label"; // original filename for UI

    // MapROM emulation: config key "maprom" expects either 0 or 0x0f000000.
    public static final String UAE_ROM_MAPROM = "uae_rom_maprom"; // boolean

    // ShapeShifter support: config key "kickshifter".
    public static final String UAE_ROM_KICKSHIFTER = "uae_rom_kickshifter"; // boolean

    // Advanced UAE expansion board / Boot ROM (mirrors PanelROM dropdown)
    // We store a UI index [0..4] to map to config keys: uaeboard + boot_rom_uae.
    public static final String UAE_ROM_UAEBOARD_INDEX = "uae_rom_uaeboard_index"; // int

    // RAM / Memory
    // Note: chipmem_size and bogomem_size are special units in Amiberry/UAE.
    public static final String UAE_MEM_CHIPMEM_SIZE = "uae_mem_chipmem_size"; // int (chipmem_size)
    public static final String UAE_MEM_BOGOMEM_SIZE = "uae_mem_bogomem_size"; // int (bogomem_size, units of 256K)

    // Z2 Fast memory (fastmem). Stored as bytes for UI flexibility.
    public static final String UAE_MEM_FASTMEM_BYTES = "uae_mem_fastmem_bytes"; // int bytes

    // Z3 Fast (z3mem_size) in MB.
    public static final String UAE_MEM_Z3MEM_SIZE_MB = "uae_mem_z3mem_size_mb"; // int MB

    // 32-bit Chip RAM (megachipmem_size) in MB.
    public static final String UAE_MEM_MEGACHIPMEM_SIZE_MB = "uae_mem_megachipmem_size_mb"; // int MB

    // Motherboard + processor slot memory in MB.
    public static final String UAE_MEM_A3000MEM_SIZE_MB = "uae_mem_a3000mem_size_mb"; // int MB
    public static final String UAE_MEM_MBRESMEM_SIZE_MB = "uae_mem_mbresmem_size_mb"; // int MB

    // Z3 mapping mode: auto/uae/real.
    public static final String UAE_MEM_Z3MAPPING = "uae_mem_z3mapping"; // string

    // RTG (Picasso96 / UAEGFX)
    // Mirrors cfgfile.cpp: gfxcard_size (MB) + gfxcard_type (string, e.g. ZorroIII for UAEGFX [Zorro III]).
    public static final String UAE_GFXCARD_SIZE_MB = "uae_gfxcard_size_mb"; // int MB
    public static final String UAE_GFXCARD_TYPE = "uae_gfxcard_type"; // string

    // Drives / CD
    // Directory hard drives (filesystem2)
    public static final String UAE_DRIVE_DIR0_ENABLED = "uae_drive_dir0_enabled"; // boolean
    public static final String UAE_DRIVE_DIR0_PATH = "uae_drive_dir0_path"; // string absolute path
    public static final String UAE_DRIVE_DIR0_DEVNAME = "uae_drive_dir0_devname"; // string (e.g. DH0)
    public static final String UAE_DRIVE_DIR0_VOLNAME = "uae_drive_dir0_volname"; // string (e.g. Work)
    public static final String UAE_DRIVE_DIR0_READONLY = "uae_drive_dir0_readonly"; // boolean
    public static final String UAE_DRIVE_DIR0_BOOTPRI = "uae_drive_dir0_bootpri"; // int

    public static final String UAE_DRIVE_DIR1_ENABLED = "uae_drive_dir1_enabled"; // boolean
    public static final String UAE_DRIVE_DIR1_PATH = "uae_drive_dir1_path"; // string absolute path
    public static final String UAE_DRIVE_DIR1_DEVNAME = "uae_drive_dir1_devname"; // string (e.g. DH1)
    public static final String UAE_DRIVE_DIR1_VOLNAME = "uae_drive_dir1_volname"; // string
    public static final String UAE_DRIVE_DIR1_READONLY = "uae_drive_dir1_readonly"; // boolean
    public static final String UAE_DRIVE_DIR1_BOOTPRI = "uae_drive_dir1_bootpri"; // int

    public static final String UAE_DRIVE_DIR2_ENABLED = "uae_drive_dir2_enabled"; // boolean
    public static final String UAE_DRIVE_DIR2_PATH = "uae_drive_dir2_path"; // string absolute path
    public static final String UAE_DRIVE_DIR2_DEVNAME = "uae_drive_dir2_devname"; // string (e.g. DH2)
    public static final String UAE_DRIVE_DIR2_VOLNAME = "uae_drive_dir2_volname"; // string
    public static final String UAE_DRIVE_DIR2_READONLY = "uae_drive_dir2_readonly"; // boolean
    public static final String UAE_DRIVE_DIR2_BOOTPRI = "uae_drive_dir2_bootpri"; // int

    public static final String UAE_DRIVE_DIR3_ENABLED = "uae_drive_dir3_enabled"; // boolean
    public static final String UAE_DRIVE_DIR3_PATH = "uae_drive_dir3_path"; // string absolute path
    public static final String UAE_DRIVE_DIR3_DEVNAME = "uae_drive_dir3_devname"; // string (e.g. DH3)
    public static final String UAE_DRIVE_DIR3_VOLNAME = "uae_drive_dir3_volname"; // string
    public static final String UAE_DRIVE_DIR3_READONLY = "uae_drive_dir3_readonly"; // boolean
    public static final String UAE_DRIVE_DIR3_BOOTPRI = "uae_drive_dir3_bootpri"; // int

    public static final String UAE_DRIVE_DIR4_ENABLED = "uae_drive_dir4_enabled"; // boolean
    public static final String UAE_DRIVE_DIR4_PATH = "uae_drive_dir4_path"; // string absolute path
    public static final String UAE_DRIVE_DIR4_DEVNAME = "uae_drive_dir4_devname"; // string (e.g. DH4)
    public static final String UAE_DRIVE_DIR4_VOLNAME = "uae_drive_dir4_volname"; // string
    public static final String UAE_DRIVE_DIR4_READONLY = "uae_drive_dir4_readonly"; // boolean
    public static final String UAE_DRIVE_DIR4_BOOTPRI = "uae_drive_dir4_bootpri"; // int

    // Hardfile (HDF) mount (hardfile2). This is implemented as a simple RDB-style mount.
    public static final String UAE_DRIVE_HDF0_ENABLED = "uae_drive_hdf0_enabled"; // boolean
    public static final String UAE_DRIVE_HDF0_PATH = "uae_drive_hdf0_path"; // string absolute path
    public static final String UAE_DRIVE_HDF0_DEVNAME = "uae_drive_hdf0_devname"; // string (e.g. DH0)
    public static final String UAE_DRIVE_HDF0_READONLY = "uae_drive_hdf0_readonly"; // boolean

    public static final String UAE_DRIVE_HDF1_ENABLED = "uae_drive_hdf1_enabled"; // boolean
    public static final String UAE_DRIVE_HDF1_PATH = "uae_drive_hdf1_path"; // string absolute path
    public static final String UAE_DRIVE_HDF1_DEVNAME = "uae_drive_hdf1_devname"; // string (e.g. DH1)
    public static final String UAE_DRIVE_HDF1_READONLY = "uae_drive_hdf1_readonly"; // boolean

    public static final String UAE_DRIVE_HDF2_ENABLED = "uae_drive_hdf2_enabled"; // boolean
    public static final String UAE_DRIVE_HDF2_PATH = "uae_drive_hdf2_path"; // string absolute path
    public static final String UAE_DRIVE_HDF2_DEVNAME = "uae_drive_hdf2_devname"; // string (e.g. DH2)
    public static final String UAE_DRIVE_HDF2_READONLY = "uae_drive_hdf2_readonly"; // boolean

    public static final String UAE_DRIVE_HDF3_ENABLED = "uae_drive_hdf3_enabled"; // boolean
    public static final String UAE_DRIVE_HDF3_PATH = "uae_drive_hdf3_path"; // string absolute path
    public static final String UAE_DRIVE_HDF3_DEVNAME = "uae_drive_hdf3_devname"; // string (e.g. DH3)
    public static final String UAE_DRIVE_HDF3_READONLY = "uae_drive_hdf3_readonly"; // boolean

    public static final String UAE_DRIVE_HDF4_ENABLED = "uae_drive_hdf4_enabled"; // boolean
    public static final String UAE_DRIVE_HDF4_PATH = "uae_drive_hdf4_path"; // string absolute path
    public static final String UAE_DRIVE_HDF4_DEVNAME = "uae_drive_hdf4_devname"; // string (e.g. DH4)
    public static final String UAE_DRIVE_HDF4_READONLY = "uae_drive_hdf4_readonly"; // boolean

    // Optional filesystem module for non-RDB DOS\x hardfiles (e.g. FastFileSystem).
    // Stored as an absolute path in app-internal storage so the core can load it.
    public static final String UAE_DRIVE_DOS_FS_MODULE_PATH = "uae_drive_dos_fs_module_path"; // string absolute path

    // CD image (cdimage0) + CD-related options
    public static final String UAE_DRIVE_CD_IMAGE0_PATH = "uae_drive_cd_image0_path"; // string absolute path

    // Backward-compatible alias (older code used this shorter name).
    public static final String UAE_CD_IMAGE0 = UAE_DRIVE_CD_IMAGE0_PATH;
    public static final String UAE_DRIVE_CD32CD_ENABLED = "uae_drive_cd32cd_enabled"; // boolean
    public static final String UAE_DRIVE_MAP_CD_DRIVES = "uae_drive_map_cd_drives"; // boolean (maps to map_cd_drives)
    public static final String UAE_DRIVE_CD_TURBO = "uae_drive_cd_turbo"; // boolean (maps to cd_speed=0 vs 100)

    // Floppies (launcher mounts these via Amiberry CLI -0/-1; persisted so configs can restore media)
    public static final String UAE_DRIVE_DF0_PATH = "uae_drive_df0_path"; // string absolute path
    public static final String UAE_DRIVE_DF1_PATH = "uae_drive_df1_path"; // string absolute path
    public static final String UAE_DRIVE_DF2_PATH = "uae_drive_df2_path"; // string absolute path
    public static final String UAE_DRIVE_DF3_PATH = "uae_drive_df3_path"; // string absolute path

    // When importing a standard UAE .uae/.cfg file, store the raw key/value pairs so we can apply
    // the full config (including keys not explicitly modeled by the Android UI) as -s overrides.
    // JSON format: { "uae_key": "value", ... }
    public static final String UAE_IMPORTED_CFG_OVERRIDES_JSON = "uae_imported_cfg_overrides_json"; // string JSON

    // Paths (mirrors the desktop GUI "Paths" tab).
    // These are Amiberry/osdep settings (stored in amiberry.conf) and are passed via "-o key=value".
    public static final String UAE_PATH_PARENT_DIR = "uae_path_parent_dir"; // string absolute path (UI convenience)
    public static final String UAE_PATH_PARENT_TREE_URI = "uae_path_parent_tree_uri"; // string SAF tree URI (optional)
    public static final String UAE_PATH_CONF_DIR = "uae_path_conf_dir"; // config_path
    public static final String UAE_PATH_ROMS_DIR = "uae_path_roms_dir"; // rom_path
    public static final String UAE_PATH_FLOPPIES_DIR = "uae_path_floppies_dir"; // floppy_path
    public static final String UAE_PATH_CDROMS_DIR = "uae_path_cdroms_dir"; // cdrom_path
    public static final String UAE_PATH_HARDDRIVES_DIR = "uae_path_harddrives_dir"; // harddrive_path
    public static final String UAE_PATH_LHA_DIR = "uae_path_lha_dir"; // whdload_arch_path
    public static final String UAE_PATH_WHDBOOT_DIR = "uae_path_whdboot_dir"; // whdboot_path
    public static final String UAE_PATH_KICKSTARTS_DIR = "uae_path_kickstarts_dir"; // launcher convenience (default picker folder for Kickstarts)
    public static final String UAE_PATH_SAVESTATES_DIR = "uae_path_savestates_dir"; // savestate_dir
    public static final String UAE_PATH_SCREENS_DIR = "uae_path_screens_dir"; // screenshot_dir

    // Input options
    public static final String UAE_INPUT_CONTROLLER_SOURCE = "uae_input_controller_source"; // string: external|virtual
    public static final String UAE_INPUT_PORT0_MODE = "uae_input_port0_mode"; // string (joyport0)
    public static final String UAE_INPUT_PORT1_MODE = "uae_input_port1_mode"; // string (joyport1, default joy0)
    public static final String UAE_INPUT_MOUSE_SPEED = "uae_input_mouse_speed"; // int (input_mouse_speed)
    public static final String UAE_INPUT_AUTOFIRE = "uae_input_autofire"; // int/boolean (input_autofire_button_X?) - handled via -s autofire=yes/no usually or specific rate
    // Amiberry typically uses "autofire" boolean or "input_autofire_speed"
    public static final String UAE_INPUT_AUTOFIRE_ENABLED = "uae_input_autofire_enabled"; // boolean

    // External gamepad button mapping (Android UI -> joyportX_amiberry_custom_none_*).
    public static final String UAE_INPUT_MAP_BTN_A = "uae_input_map_btn_a";
    public static final String UAE_INPUT_MAP_BTN_B = "uae_input_map_btn_b";
    public static final String UAE_INPUT_MAP_BTN_X = "uae_input_map_btn_x";
    public static final String UAE_INPUT_MAP_BTN_Y = "uae_input_map_btn_y";
    public static final String UAE_INPUT_MAP_BTN_L1 = "uae_input_map_btn_l1";
    public static final String UAE_INPUT_MAP_BTN_R1 = "uae_input_map_btn_r1";
    public static final String UAE_INPUT_MAP_BTN_BACK = "uae_input_map_btn_back";
    public static final String UAE_INPUT_MAP_BTN_START = "uae_input_map_btn_start";
    public static final String UAE_INPUT_MAP_BTN_DPAD_UP = "uae_input_map_btn_dpad_up";
    public static final String UAE_INPUT_MAP_BTN_DPAD_DOWN = "uae_input_map_btn_dpad_down";
    public static final String UAE_INPUT_MAP_BTN_DPAD_LEFT = "uae_input_map_btn_dpad_left";
    public static final String UAE_INPUT_MAP_BTN_DPAD_RIGHT = "uae_input_map_btn_dpad_right";
}
