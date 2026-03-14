package com.uae4arm2026;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class LogsActivity extends Activity {
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Logs");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refreshLogs());

        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> {
            AppLog.clear(this);
            refreshLogs();
        });

        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> finish());

        actions.addView(refresh);
        actions.addView(clear);
        actions.addView(close);

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12f);
        scroll.addView(logView, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(actions);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        setContentView(root);
        refreshLogs();
    }

    private void refreshLogs() {
        String logs = AppLog.readAll(this);
        if (logs == null || logs.trim().isEmpty()) {
            logView.setText("(no logs)");
        } else {
            logView.setText(logs);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
