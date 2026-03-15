package com.uae4arm2026;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;

/**
 * Maps Android gamepad buttons to Amiga joystick / keyboard input events
 * and emits the corresponding UAE config arguments.
 * Extracted from AmiberryActivity to reduce module size.
 */
final class JoystickEventMapper {

    private static final String TAG = "JoystickEventMapper";

    private JoystickEventMapper() { }

    static String joyEventForAction(int port, String action, boolean mouseMode) {
        if (action == null || action.trim().isEmpty()) return null;
        String keyboardEvent = keyboardEventForAction(action);
        if (keyboardEvent != null) return keyboardEvent;
        final String jn = (port == 0) ? "Joy1" : "Joy2";
        final String mn = (port == 0) ? "Mouse1" : "Mouse2";
        switch (action.trim().toUpperCase()) {
            case "MOUSE_UP":            return mn + " Up";
            case "MOUSE_DOWN":          return mn + " Down";
            case "MOUSE_LEFT":          return mn + " Left";
            case "MOUSE_RIGHT":         return mn + " Right";
            case "MOUSE_LEFT_BUTTON":   return mn + " Left Button";
            case "MOUSE_RIGHT_BUTTON":  return mn + " Right Button";
            case "MOUSE_MIDDLE_BUTTON": return mn + " Middle Button";
            default: break;
        }
        if (mouseMode) {
            switch (action.trim().toUpperCase()) {
                case "UP":          return mn + " Up";
                case "DOWN":        return mn + " Down";
                case "LEFT":        return mn + " Left";
                case "RIGHT":       return mn + " Right";
                case "FIRE1":       return mn + " Left Button";
                case "FIRE2":       return mn + " Right Button";
                case "FIRE3":       return mn + " Middle Button";
                default: return null;
            }
        }
        switch (action.trim().toUpperCase()) {
            case "UP":          return jn + " Up";
            case "DOWN":        return jn + " Down";
            case "LEFT":        return jn + " Left";
            case "RIGHT":       return jn + " Right";
            case "FIRE1":       return jn + " Fire/" + mn + " Left Button";
            case "FIRE2":       return jn + " 2nd Button/" + mn + " Right Button";
            case "FIRE3":       return jn + " 3rd Button/" + mn + " Middle Button";
            case "CD32_RED":    return jn + " CD32 Red";
            case "CD32_BLUE":   return jn + " CD32 Blue";
            case "CD32_GREEN":  return jn + " CD32 Green";
            case "CD32_YELLOW": return jn + " CD32 Yellow";
            case "CD32_PLAY":   return jn + " CD32 Play";
            case "CD32_RWD":    return jn + " CD32 RWD";
            case "CD32_FFW":    return jn + " CD32 FFW";
            default: return null;
        }
    }

    static String keyboardEventForAction(String action) {
        if (action == null || action.trim().isEmpty()) return null;
        switch (action.trim().toUpperCase()) {
            case "KEY_SPACE": return "Space";
            case "KEY_RETURN": return "Return";
            case "KEY_ESC": return "ESC";
            case "KEY_TAB": return "Tab";
            case "KEY_BACKSPACE": return "Backspace";
            case "KEY_DEL": return "Del";
            case "KEY_CURSOR_UP": return "Cursor Up";
            case "KEY_CURSOR_DOWN": return "Cursor Down";
            case "KEY_CURSOR_LEFT": return "Cursor Left";
            case "KEY_CURSOR_RIGHT": return "Cursor Right";
            case "KEY_F1": return "F1";
            case "KEY_F2": return "F2";
            case "KEY_F3": return "F3";
            case "KEY_F4": return "F4";
            case "KEY_F5": return "F5";
            case "KEY_F6": return "F6";
            case "KEY_F7": return "F7";
            case "KEY_F8": return "F8";
            case "KEY_F9": return "F9";
            case "KEY_F10": return "F10";
            case "KEY_A": return "A";
            case "KEY_B": return "B";
            case "KEY_C": return "C";
            case "KEY_D": return "D";
            case "KEY_E": return "E";
            case "KEY_F": return "F";
            case "KEY_G": return "G";
            case "KEY_H": return "H";
            case "KEY_I": return "I";
            case "KEY_J": return "J";
            case "KEY_K": return "K";
            case "KEY_L": return "L";
            case "KEY_M": return "M";
            case "KEY_N": return "N";
            case "KEY_O": return "O";
            case "KEY_P": return "P";
            case "KEY_Q": return "Q";
            case "KEY_R": return "R";
            case "KEY_S": return "S";
            case "KEY_T": return "T";
            case "KEY_U": return "U";
            case "KEY_V": return "V";
            case "KEY_W": return "W";
            case "KEY_X": return "X";
            case "KEY_Y": return "Y";
            case "KEY_Z": return "Z";
            case "KEY_0": return "0";
            case "KEY_1": return "1";
            case "KEY_2": return "2";
            case "KEY_3": return "3";
            case "KEY_4": return "4";
            case "KEY_5": return "5";
            case "KEY_6": return "6";
            case "KEY_7": return "7";
            case "KEY_8": return "8";
            case "KEY_9": return "9";
            default: return null;
        }
    }

    static void addJoyMappingOption(List<String> args, int port, String buttonSuffix, String actionValue, boolean mouseMode) {
        String eventName = joyEventForAction(port, actionValue, mouseMode);
        if (eventName == null) return;
        String opt = "joyport" + port + "_amiberry_custom_none_" + buttonSuffix + "=" + eventName;
        Log.i(TAG, "Joy map arg: -s " + opt);
        args.add("-s");
        args.add(opt);
    }

    static void addAndroidJoyMappingsFromPrefs(List<String> args,
                                               SharedPreferences globalPrefs,
                                               SharedPreferences perGamePrefs,
                                               String gameId) {
        String mapA     = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_A);
        String mapB     = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_B);
        String mapX     = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_X);
        String mapY     = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_Y);
        String mapL1    = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_L1);
        String mapR1    = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_R1);
        String mapBack  = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_BACK);
        String mapStart = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_START);
        String mapDpadUp    = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_UP);
        String mapDpadDown  = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_DOWN);
        String mapDpadLeft  = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_LEFT);
        String mapDpadRight = JoyMappingActivity.getEffectiveMapping(globalPrefs, perGamePrefs, gameId, UaeOptionKeys.UAE_INPUT_MAP_BTN_DPAD_RIGHT);

        if (gameId != null) {
            Log.i(TAG, "Joy mapping: using per-game overrides for \"" + gameId + "\"");
        }

        boolean controllerMouseRemap = false;
        try {
            controllerMouseRemap = globalPrefs.getBoolean(UaeOptionKeys.UAE_INPUT_CONTROLLER_MOUSE_REMAP, false);
        } catch (Throwable ignored) {
        }

        ArrayList<Integer> targetPorts = new ArrayList<>();
        HashSet<Integer> mousePorts = new HashSet<>();
        try {
            String p0 = globalPrefs.getString(UaeOptionKeys.UAE_INPUT_PORT0_MODE, "mouse");
            if (p0 != null && p0.trim().equalsIgnoreCase("mouse")) {
                mousePorts.add(0);
            }
            if (p0 != null && p0.trim().toLowerCase(Locale.ROOT).startsWith("joy")) {
                targetPorts.add(0);
            }
        } catch (Throwable ignored) {
        }
        try {
            String p1 = globalPrefs.getString(UaeOptionKeys.UAE_INPUT_PORT1_MODE, "joy0");
            if (p1 != null && p1.trim().equalsIgnoreCase("mouse")) {
                mousePorts.add(1);
            }
            if (p1 != null && p1.trim().toLowerCase(Locale.ROOT).startsWith("joy")) {
                targetPorts.add(1);
            }
        } catch (Throwable ignored) {
        }

        if (controllerMouseRemap) {
            for (Integer mp : mousePorts) {
                if (mp != null && !targetPorts.contains(mp)) {
                    targetPorts.add(mp);
                }
            }
        }

        if (targetPorts.isEmpty()) {
            targetPorts.add(1);
        }

        for (int port : targetPorts) {
            boolean mouseMode = controllerMouseRemap && mousePorts.contains(port);
            addJoyMappingOption(args, port, "a", mapA, mouseMode);
            addJoyMappingOption(args, port, "b", mapB, mouseMode);
            addJoyMappingOption(args, port, "x", mapX, mouseMode);
            addJoyMappingOption(args, port, "y", mapY, mouseMode);
            addJoyMappingOption(args, port, "leftshoulder", mapL1, mouseMode);
            addJoyMappingOption(args, port, "rightshoulder", mapR1, mouseMode);
            addJoyMappingOption(args, port, "back", mapBack, mouseMode);
            addJoyMappingOption(args, port, "start", mapStart, mouseMode);
            addJoyMappingOption(args, port, "dpup", mapDpadUp, mouseMode);
            addJoyMappingOption(args, port, "dpdown", mapDpadDown, mouseMode);
            addJoyMappingOption(args, port, "dpleft", mapDpadLeft, mouseMode);
            addJoyMappingOption(args, port, "dpright", mapDpadRight, mouseMode);
        }
    }
}
