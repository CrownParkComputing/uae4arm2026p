package com.uae4arm2026;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class OptionsMenuActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_options_menu);

        // Ensure the Back button and bottom rows aren't obscured by the system navigation bar.
        UiInsets.applySystemBarsPaddingBottom(findViewById(android.R.id.content));

        TextView title = findViewById(R.id.txtOptionsTitle);
        if (title != null) {
            title.setText("Customise (2-column)" + getVersionSuffix());
        }

        if (isDebuggable()) {
            Toast.makeText(this,
                    "Loaded new Customise screen",
                    Toast.LENGTH_SHORT).show();
        }

        Button btnChipset = findViewById(R.id.btnOptionsChipset);
        Button btnCpuFpu = findViewById(R.id.btnOptionsCpuFpu);
        Button btnRom = findViewById(R.id.btnOptionsRom);
        Button btnRam = findViewById(R.id.btnOptionsRam);
        Button btnDrives = findViewById(R.id.btnOptionsDrives);
        Button btnPaths = findViewById(R.id.btnOptionsPaths);
        Button btnConfigs = findViewById(R.id.btnOptionsConfigs);
        Button btnSound = findViewById(R.id.btnOptionsSound);
        Button btnBack = findViewById(R.id.btnOptionsBack);

        btnChipset.setOnClickListener(v -> startActivity(new Intent(this, ChipsetOptionsActivity.class)));
        btnCpuFpu.setOnClickListener(v -> startActivity(new Intent(this, CpuFpuOptionsActivity.class)));
        // ROM import UI is intentionally hidden; Kickstart selection is driven via Kickstart Map.
        if (btnRom != null) btnRom.setVisibility(android.view.View.GONE);
        btnRam.setOnClickListener(v -> startActivity(new Intent(this, MemoryOptionsActivity.class)));
        btnDrives.setOnClickListener(v -> startActivity(new Intent(this, DrivesOptionsActivity.class)));
        btnPaths.setOnClickListener(v -> startActivity(new Intent(this, PathsSimpleActivity.class)));
        btnConfigs.setOnClickListener(v -> startActivity(new Intent(this, ConfigManagerActivity.class)));
        btnSound.setOnClickListener(v -> startActivity(new Intent(this, SoundOptionsActivity.class)));
        btnBack.setOnClickListener(v -> finish());
    }

    private String getVersionSuffix() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                versionCode = packageInfo.getLongVersionCode();
            } else {
                versionCode = packageInfo.versionCode;
            }
            String versionName = packageInfo.versionName;
            return " v" + versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
