package com.uae4arm2026;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

final class BootstrapRestartController {
    private static final String TAG = "BootstrapRestart";

    private BootstrapRestartController() {
    }

    static void requestRestartAndRelaunch(Activity activity, Runnable relaunchRunnable) {
        if (activity == null || relaunchRunnable == null) return;

        final String killToken = "kill_" + SystemClock.uptimeMillis();
        try {
            File ack = new File(activity.getFilesDir(), EmulatorProcessControl.KILL_ACK_FILENAME);
            //noinspection ResultOfMethodCallIgnored
            ack.delete();
        } catch (Throwable ignored) {
        }

        EmulatorProcessControl.requestEmulatorProcessExit(activity, killToken);

        final long startMs = SystemClock.uptimeMillis();
        final long timeoutMs = 2500;
        final Handler handler = new Handler(Looper.getMainLooper());

        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            boolean acked = false;
            try {
                File ack = new File(activity.getFilesDir(), EmulatorProcessControl.KILL_ACK_FILENAME);
                if (ack.exists() && ack.length() > 0) {
                    byte[] bytes = new byte[(int) Math.min(256, ack.length())];
                    FileInputStream fis = null;
                    try {
                        fis = new FileInputStream(ack);
                        int n = fis.read(bytes);
                        if (n > 0) {
                            String s = new String(bytes, 0, n, StandardCharsets.UTF_8).trim();
                            acked = killToken.equals(s);
                        }
                    } finally {
                        try {
                            if (fis != null) fis.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            long elapsed = SystemClock.uptimeMillis() - startMs;
            if (acked || elapsed >= timeoutMs) {
                if (!acked) {
                    Log.w(TAG, "Emulator kill ack not observed within timeout; relaunching anyway");
                }

                try {
                    // NOTE: the kill receiver writes the ack immediately before killing the :emu process.
                    // To avoid the singleTask activity receiving this relaunch via onNewIntent() (and then
                    // continuing/closing unexpectedly), wait a short moment so the process actually dies.
                    final long relaunchDelayMs = acked ? 350 : 650;
                    handler.postDelayed(() -> {
                        try {
                            relaunchRunnable.run();
                        } catch (Throwable ignored2) {
                        }
                    }, relaunchDelayMs);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to schedule relaunch", t);
                }
                return;
            }

            try {
                handler.postDelayed(poll[0], 75);
            } catch (Throwable ignored) {
            }
        };

        try {
            handler.postDelayed(poll[0], 150);
        } catch (Throwable ignored) {
        }
    }
}
