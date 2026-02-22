package com.uae4arm2026;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.TextView;

public class HelpActivity extends Activity {

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
        setContentView(R.layout.activity_help);

        TextView version = findViewById(R.id.txtAboutVersion);
        if (version != null) {
            version.setText(getVersionString(this));
        }

        Button btnClose = findViewById(R.id.btnClose);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        Button btnRerunWalkthrough = findViewById(R.id.btnRerunWalkthrough);
        if (btnRerunWalkthrough != null) {
            btnRerunWalkthrough.setOnClickListener(v -> {
                // Reset the walkthrough completed flag and launch the walkthrough
                SharedPreferences prefs = getSharedPreferences("bootstrap", MODE_PRIVATE);
                prefs.edit().putBoolean("walkthrough_completed", false).apply();
                startActivity(new Intent(this, WalkthroughActivity.class));
                finish();
            });
        }
    }
}
