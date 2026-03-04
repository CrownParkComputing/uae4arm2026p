package com.uae4arm2026;

import android.content.SharedPreferences;

import java.util.Locale;

final class BootstrapQuickstartDefaults {

    private BootstrapQuickstartDefaults() {
    }

    static boolean is24BitAddressing(String cpuModel) {
        if (cpuModel == null) return false;
        return "68000".equals(cpuModel) || "68010".equals(cpuModel);
    }

    static String inferCpuModel(String modelId, String cfgLabel) {
        if (cfgLabel != null) {
            String lc = cfgLabel.toLowerCase(Locale.ROOT);
            if (lc.contains("68060")) return "68060";
            if (lc.contains("68040")) return "68040";
            if (lc.contains("68030")) return "68030";
            if (lc.contains("68020")) return "68020";
            if (lc.contains("68010")) return "68010";
            if (lc.contains("68000")) return "68000";
        }

        String upperModel = modelId == null ? "" : modelId.toUpperCase(Locale.ROOT);
        switch (upperModel) {
            case "A1200":
            case "CD32":
                return "68020";
            case "A3000":
                return "68030";
            case "A4000":
                return "68030";
            default:
                return "68000";
        }
    }

    static String inferCycleExactDefault(String modelId, String cpuModel, String cfgLabel) {
        if (modelId == null) return null;

        String m = modelId.trim().toUpperCase(Locale.ROOT);
        if ("A3000".equals(m) || "A4000".equals(m)) {
            return "false";
        }

        if ("A1200".equals(m) || "CD32".equals(m)) {
            if ("68040".equals(cpuModel) || "68060".equals(cpuModel)) {
                return "false";
            }
            return "true";
        }

        return "true";
    }

    static boolean inferCpuCompatibleDefault(String modelId) {
        if (modelId == null) return true;
        String m = modelId.trim().toUpperCase(Locale.ROOT);
        return !(("A3000".equals(m) || "A4000".equals(m)));
    }

    static String inferCpuSpeedDefault(String modelId) {
        if (modelId == null) return null;
        String m = modelId.trim().toUpperCase(Locale.ROOT);
        if ("A3000".equals(m) || "A4000".equals(m)) {
            return "max";
        }
        return "real";
    }

    static String inferFpuModel(String cpuModel, String cfgLabel) {
        if (cfgLabel != null) {
            String lc = cfgLabel.toLowerCase(Locale.ROOT);
            if (lc.contains("fpu")) {
                if (lc.contains("68060")) return "68060";
                if (lc.contains("68040")) return "68040";
                if (lc.contains("68882")) return "68882";
                if (lc.contains("68881")) return "68881";
            }
        }

        if (cpuModel == null) return null;
        if ("68040".equals(cpuModel)) return "68040";
        if ("68060".equals(cpuModel)) return "68060";
        return "0";
    }

    static void applyQuickstartMemoryDefaults(SharedPreferences.Editor e, String modelId, String cfgLabel) {
        String upperModel = modelId == null ? "" : modelId.toUpperCase(Locale.ROOT);

        int chip;
        int bogo;
        int fastBytes;

        switch (upperModel) {
            case "A1200":
            case "A4000":
            case "CD32":
                chip = 4;
                bogo = 0;
                fastBytes = 0;
                break;
            case "A3000":
                chip = 4;
                bogo = 0;
                fastBytes = 8 * 1024 * 1024;
                break;
            case "A500":
            case "A500P":
            case "A600":
            case "A1000":
            case "A2000":
            default:
                chip = 1;
                bogo = 0;
                fastBytes = 0;
                break;
        }

        if (cfgLabel != null) {
            String lc = cfgLabel.toLowerCase(Locale.ROOT);

            if (lc.contains("256 kb chip")) chip = 0;
            else if (lc.contains("512 kb chip")) chip = 1;
            else if (lc.contains("1.5 mb chip") || lc.contains("1,5 mb chip")) chip = 3;
            else if (lc.contains("2 mb chip")) chip = 4;
            else if (lc.contains("4 mb chip")) chip = 8;
            else if (lc.contains("8 mb chip")) chip = 16;
            else if (lc.contains("1 mb chip")) chip = 2;

            if (lc.contains("512 kb slow")) bogo = 2;
            else if (lc.contains("1.5 mb slow") || lc.contains("1,5 mb slow")) bogo = 6;
            else if (lc.contains("1.8 mb") && lc.contains("slow")) bogo = 7;
            else if (lc.contains("1 mb slow")) bogo = 4;

            if (lc.contains("64 kb fast")) fastBytes = 64 * 1024;
            else if (lc.contains("128 kb fast")) fastBytes = 128 * 1024;
            else if (lc.contains("256 kb fast")) fastBytes = 256 * 1024;
            else if (lc.contains("512 kb fast")) fastBytes = 512 * 1024;
            else if (lc.contains("1 gb fast")) fastBytes = 1024 * 1024 * 1024;
            else if (lc.contains("1 mb fast")) fastBytes = 1 * 1024 * 1024;
            else if (lc.contains("2 mb fast")) fastBytes = 2 * 1024 * 1024;
            else if (lc.contains("4 mb fast")) fastBytes = 4 * 1024 * 1024;
            else if (lc.contains("8 mb fast")) fastBytes = 8 * 1024 * 1024;
        }

        e.putInt(UaeOptionKeys.UAE_MEM_CHIPMEM_SIZE, chip);
        e.putInt(UaeOptionKeys.UAE_MEM_BOGOMEM_SIZE, bogo);
        e.putInt(UaeOptionKeys.UAE_MEM_FASTMEM_BYTES, fastBytes);
        e.putInt(UaeOptionKeys.UAE_MEM_Z3MEM_SIZE_MB, 0);
        e.putInt(UaeOptionKeys.UAE_MEM_MEGACHIPMEM_SIZE_MB, 0);
        e.putInt(UaeOptionKeys.UAE_MEM_A3000MEM_SIZE_MB, 0);
        e.putInt(UaeOptionKeys.UAE_MEM_MBRESMEM_SIZE_MB, 0);
        e.putString(UaeOptionKeys.UAE_MEM_Z3MAPPING, "auto");
    }
}
