package com.uae4arm2026;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class AboutActivity extends Activity {

    private static String buildAboutBody() {
        return "Copyright 2026 Whitty Apps\n\n" +
            "Thanks to the Amiberry and WinUAE projects; this port would not be possible without them.";
    }

    private static String getVersionString(Activity activity) {
        try {
            PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            String name = pi.versionName == null ? "" : pi.versionName;
            long code;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                code = pi.getLongVersionCode();
            } else {
                //noinspection deprecation
                code = pi.versionCode;
            }
            String suffix = (name.trim().isEmpty() ? "" : (" " + name.trim()));
            return "Version" + suffix + " (" + code + ")";
        } catch (Throwable ignored) {
            return "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView version = findViewById(R.id.txtAboutVersion);
        if (version != null) {
            version.setText(getVersionString(this));
        }

        TextView body = findViewById(R.id.txtAboutBody);
        if (body != null) {
            body.setText(buildAboutBody());
        }

        Button close = findViewById(R.id.btnAboutClose);
        if (close != null) {
            close.setOnClickListener(v -> finish());
        }
    }
}
