package com.uae4arm2026;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Cross-process control helpers for the dedicated emulator (SDL) process.
 */
public final class EmulatorProcessControl {
    private EmulatorProcessControl() {}

    private static final String TAG = "EmuProcCtl";

    public static final String ACTION_KILL_EMULATOR_PROCESS = "com.uae4arm2026.action.KILL_EMULATOR_PROCESS";

    public static final String EXTRA_KILL_TOKEN = "com.uae4arm2026.extra.KILL_TOKEN";

    // Written by the :emu process just before it terminates, to acknowledge receipt of the kill request.
    public static final String KILL_ACK_FILENAME = "emu_kill_ack.txt";

    public static void requestEmulatorProcessExit(Context context) {
        requestEmulatorProcessExit(context, null);
    }

    public static void requestEmulatorProcessExit(Context context, String token) {
        if (context == null) return;
        // Use an explicit broadcast so we don't rely on an intent-filter in the manifest.
        Intent i = new Intent(context, EmulatorProcessKillReceiver.class);
        i.setAction(ACTION_KILL_EMULATOR_PROCESS);
        if (token != null && !token.trim().isEmpty()) {
            i.putExtra(EXTRA_KILL_TOKEN, token);
        }
        try {
            Log.i(TAG, "Requesting emulator process exit");
            context.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
