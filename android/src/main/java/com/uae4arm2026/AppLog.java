package com.uae4arm2026;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AppLog {
    private static final String FILE_NAME = "adf_library_logs.txt";

    private AppLog() {
    }

    static synchronized void append(Context context, String message) {
        if (context == null || message == null) return;
        File file = new File(context.getFilesDir(), FILE_NAME);
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = ts + " | " + message + "\n";
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    static synchronized String readAll(Context context) {
        if (context == null) return "";
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists() || !file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.max(0, file.length())];
            int read = in.read(data);
            if (read <= 0) return "";
            return new String(data, 0, read, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    static synchronized void clear(Context context) {
        if (context == null) return;
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(new byte[0]);
            out.flush();
        } catch (Throwable ignored) {
        }
    }
}
