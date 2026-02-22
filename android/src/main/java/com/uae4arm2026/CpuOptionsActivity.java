package com.uae4arm2026;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class CpuOptionsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // CPU & FPU settings are unified in CpuFpuOptionsActivity.
        // Keep this activity as a compatibility shim for any legacy entry points.
        startActivity(new Intent(this, CpuFpuOptionsActivity.class));
        finish();
    }
}
