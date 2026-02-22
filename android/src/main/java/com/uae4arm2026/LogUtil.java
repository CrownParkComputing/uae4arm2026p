package com.uae4arm2026;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import org.libsdl.app.SDLActivity;

/**
 * Lightweight logging helper.
 *
 * Goal: keep release builds quiet while still allowing verbose logs in debug builds.
 */
public final class LogUtil {

    private static volatile Boolean sDebuggable;

    private LogUtil() {
    }

    private static boolean isDebuggable() {
        Boolean cached = sDebuggable;
        if (cached != null) return cached;

        boolean debuggable = false;
        try {
            Context ctx = SDLActivity.getContext();
            if (ctx != null) {
                ApplicationInfo ai = ctx.getApplicationInfo();
                debuggable = (ai != null) && ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            }
        } catch (Throwable ignored) {
        }

        sDebuggable = debuggable;
        return debuggable;
    }

    public static void v(String tag, String msg) {
        if (isDebuggable()) Log.v(tag, msg);
    }

    public static void v(String tag, String msg, Throwable tr) {
        if (isDebuggable()) Log.v(tag, msg, tr);
    }

    public static void d(String tag, String msg) {
        if (isDebuggable()) Log.d(tag, msg);
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (isDebuggable()) Log.d(tag, msg, tr);
    }

    public static void i(String tag, String msg) {
        if (isDebuggable()) Log.i(tag, msg);
    }

    public static void i(String tag, String msg, Throwable tr) {
        if (isDebuggable()) Log.i(tag, msg, tr);
    }
}
