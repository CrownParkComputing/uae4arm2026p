package com.uae4arm2026;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Runs in the dedicated ":emu" process and terminates that process on request.
 *
 * This lets the Quickstart UI (main process) restart the emulator cleanly without
 * overlapping SDL teardown/startup in the same process.
 */
public final class EmulatorProcessKillReceiver extends BroadcastReceiver {

    private static final String TAG = "EmuProcKill";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) return;
            String action = intent.getAction();
            if (!EmulatorProcessControl.ACTION_KILL_EMULATOR_PROCESS.equals(action)) return;

            Log.i(TAG, "Killing emulator process for restart");

            // Best-effort acknowledgement so the main process can avoid relaunching too early.
            try {
                String token = intent.getStringExtra(EmulatorProcessControl.EXTRA_KILL_TOKEN);
                if (token != null && !token.trim().isEmpty() && context != null) {
                    java.io.File ack = new java.io.File(context.getFilesDir(), EmulatorProcessControl.KILL_ACK_FILENAME);
                    java.io.FileOutputStream fos = null;
                    try {
                        fos = new java.io.FileOutputStream(ack, false);
                        fos.write(token.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        fos.flush();
                    } finally {
                        try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable ignored) {
            }

            try {
                android.os.Process.killProcess(android.os.Process.myPid());
            } catch (Throwable ignored) {
            }

            try {
                System.exit(0);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to kill emulator process: " + t);
        }
    }
}
