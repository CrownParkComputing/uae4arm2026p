package com.uae4arm2026;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewConfiguration;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import android.content.SharedPreferences;

public class ConfigManagerActivity extends Activity {

    private TextView tvFolder;
    private ListView lv;

    private ArrayAdapter<String> adapter;
    private final List<ConfigStorage.ConfigEntry> entries = new ArrayList<>();
    private int selectedIndex = -1;

    private int lastClickIndex = -1;
    private long lastClickUptimeMs = 0L;

    private boolean mFromEmulatorMenu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config_manager);

        // Keep bottom action rows visible above system navigation/gesture UI.
        UiInsets.applySystemBarsPaddingBottom(findViewById(android.R.id.content));

        try {
            Intent it = getIntent();
            mFromEmulatorMenu = it != null && it.getBooleanExtra(BootstrapActivity.EXTRA_FROM_EMULATOR_MENU, false);
        } catch (Throwable ignored) {
            mFromEmulatorMenu = false;
        }

        tvFolder = findViewById(R.id.tvConfigFolder);
        lv = findViewById(R.id.lvConfigs);

        Button btnRefresh = findViewById(R.id.btnConfigsRefresh);
        Button btnLoad = findViewById(R.id.btnConfigsLoad);
        Button btnSave = findViewById(R.id.btnConfigsSave);
        Button btnRename = findViewById(R.id.btnConfigsRename);
        Button btnDelete = findViewById(R.id.btnConfigsDelete);
        Button btnViewText = findViewById(R.id.btnConfigsViewText);
        Button btnBack = findViewById(R.id.btnConfigsBack);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, new ArrayList<>());
        lv.setAdapter(adapter);
        lv.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lv.setOnItemClickListener((parent, view, position, id) -> {
            selectedIndex = position;
            lv.setItemChecked(position, true);

            final long now = SystemClock.uptimeMillis();
            final int timeout = ViewConfiguration.getDoubleTapTimeout();
            if (position == lastClickIndex && (now - lastClickUptimeMs) <= timeout) {
                lastClickIndex = -1;
                lastClickUptimeMs = 0L;
                launchSelectedConfig();
                return;
            }
            lastClickIndex = position;
            lastClickUptimeMs = now;
        });

        btnRefresh.setOnClickListener(v -> refreshList());

        btnLoad.setOnClickListener(v -> {
            ConfigStorage.ConfigEntry e = selectedEntry();
            if (e == null) {
                toast("Select a config first");
                return;
            }
            boolean ok = ConfigStorage.loadConfig(this, e);
            if (ok) {
                try {
                    ConfigStorage.saveLastRan(this);
                } catch (Throwable ignored) {
                }
                toast(e.isLastRan ? "Loaded Last Ran" : "Loaded config");
            } else {
                String folder = ConfigStorage.getConfigDirString(this);
                String err = ConfigStorage.getLastError();
                String msg = "Failed to load config from:\n" + folder;
                if (err != null && !err.trim().isEmpty()) msg += "\n\nDetails: " + err.trim();
                if (ConfigStorage.isSafJoinedPath(folder)) {
                    msg += "\n\nFor SAF folders, re-select the Config path in Paths to refresh Android permissions.";
                }
                toast(msg);
            }
            if (ok) {
                if (mFromEmulatorMenu) {
                    requestEmulatorRestart();
                }
                finish();
            }
        });

        btnViewText.setOnClickListener(v -> {
            ConfigStorage.ConfigEntry e = selectedEntry();
            if (e == null) {
                toast("Select a config first");
                return;
            }
            String text = ConfigStorage.readConfigText(this, e);
            if (text == null) {
                String err = ConfigStorage.getLastError();
                String msg = "Failed to open config text";
                if (err != null && !err.trim().isEmpty()) msg += "\n\nDetails: " + err.trim();
                toast(msg);
                return;
            }
            showConfigTextDialog(e.displayName(), text);
        });

        btnSave.setOnClickListener(v -> promptForName("Save config as", "config", name -> {
            boolean ok = ConfigStorage.saveConfig(this, name);
            if (ok) {
                toast("Saved");
            } else {
                String folder = ConfigStorage.getConfigDirString(this);
                String err = ConfigStorage.getLastError();
                String msg = "Failed to save to:\n" + folder;
                if (err != null && !err.trim().isEmpty()) msg += "\n\nDetails: " + err.trim();
                toast(msg);
            }
            refreshList();
        }));

        btnRename.setOnClickListener(v -> {
            ConfigStorage.ConfigEntry e = selectedEntry();
            if (e == null) {
                toast("Select a config first");
                return;
            }
            if (e.isLastRan) {
                toast("Last Ran cannot be renamed");
                return;
            }
            promptForName("Rename config", e.displayName(), name -> {
                boolean ok = ConfigStorage.renameConfig(this, e, name);
                toast(ok ? "Renamed" : "Failed to rename (name may exist)");
                refreshList();
            });
        });

        btnDelete.setOnClickListener(v -> {
            ConfigStorage.ConfigEntry e = selectedEntry();
            if (e == null) {
                toast("Select a config first");
                return;
            }
            if (e.isLastRan) {
                toast("Last Ran cannot be deleted");
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("Delete config")
                .setMessage("Delete '" + e.displayName() + "'?")
                .setPositiveButton("Delete", (d, which) -> {
                    boolean ok = ConfigStorage.deleteConfig(this, e);
                    toast(ok ? "Deleted" : "Failed to delete");
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        btnBack.setOnClickListener(v -> finish());

        refreshList();
    }

    private void refreshList() {
        String folder = ConfigStorage.getConfigDirString(this);
        tvFolder.setText(folder);

        entries.clear();
        entries.addAll(ConfigStorage.listConfigs(this));

        adapter.clear();
        for (ConfigStorage.ConfigEntry e : entries) {
            adapter.add(e.displayName());
        }
        adapter.notifyDataSetChanged();

        selectedIndex = -1;
        lv.clearChoices();

        if (entries.isEmpty()) {
            String err = ConfigStorage.getLastError();
            if (ConfigStorage.isSafJoinedPath(folder)) {
                if (err != null && !err.trim().isEmpty()) {
                    toast("No configs found. " + err.trim() + "\n\nRe-select Configs path in Paths to refresh SAF permission.");
                } else {
                    toast("No configs found. For SAF, ensure the 'conf' folder exists.");
                }
            } else {
                if (err != null && !err.trim().isEmpty()) {
                    toast("No configs found. " + err.trim());
                } else {
                    toast("No configs found.");
                }
            }
        }
    }

    private ConfigStorage.ConfigEntry selectedEntry() {
        if (selectedIndex < 0 || selectedIndex >= entries.size()) return null;
        return entries.get(selectedIndex);
    }

    private void launchSelectedConfig() {
        ConfigStorage.ConfigEntry e = selectedEntry();
        if (e == null) {
            toast("Select a config first");
            return;
        }
        boolean ok = ConfigStorage.loadConfig(this, e);
        if (!ok) {
            String folder = ConfigStorage.getConfigDirString(this);
            String err = ConfigStorage.getLastError();
            String msg = "Failed to load config from:\n" + folder;
            if (err != null && !err.trim().isEmpty()) msg += "\n\nDetails: " + err.trim();
            if (ConfigStorage.isSafJoinedPath(folder)) {
                msg += "\n\nFor SAF folders, re-select the Config path in Paths to refresh Android permissions.";
            }
            toast(msg);
            return;
        }

        if (mFromEmulatorMenu) {
            // We're coming from an already-running (paused) emulator instance.
            // Do NOT start a second SDL activity. Instead, request a restart of the existing one.
            try {
                ConfigStorage.saveLastRan(this);
            } catch (Throwable ignored) {
            }
            requestEmulatorRestart();
            finish();
            return;
        }

        Intent i = new Intent(this, AmiberryActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        // Avoid unexpected DF0 insertion (e.g., disk.zip) when launching from the native UI.
        i.putExtra(AmiberryActivity.EXTRA_ENABLE_AUTO_DF0, false);

        // AmiberryActivity is singleTask. If an existing emulator instance is alive,
        // this intent arrives in onNewIntent(); request a restart and force DF0-DF3
        // from the loaded config prefs so prior in-memory media cannot win.
        i.putExtra(AmiberryActivity.EXTRA_REQUEST_RESTART, true);
        try {
            SharedPreferences prefs = getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
            i.putExtra(AmiberryActivity.EXTRA_DF0_DISK_FILE, prefs.getString(UaeOptionKeys.UAE_DRIVE_DF0_PATH, ""));
            i.putExtra(AmiberryActivity.EXTRA_DF1_DISK_FILE, prefs.getString(UaeOptionKeys.UAE_DRIVE_DF1_PATH, ""));
            i.putExtra(AmiberryActivity.EXTRA_DF2_DISK_FILE, prefs.getString(UaeOptionKeys.UAE_DRIVE_DF2_PATH, ""));
            i.putExtra(AmiberryActivity.EXTRA_DF3_DISK_FILE, prefs.getString(UaeOptionKeys.UAE_DRIVE_DF3_PATH, ""));
        } catch (Throwable ignored) {
        }

        // Enable core logging in debug builds so we can diagnose boot issues.
        final boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable) {
            i.putExtra(AmiberryActivity.EXTRA_ENABLE_LOGFILE, true);
        }

        // Native menu replaces Amiberry's GUI.
        i.putExtra(AmiberryActivity.EXTRA_SHOW_GUI, false);

        // Best-effort "Last Ran" snapshot so the launcher menu matches.
        try {
            ConfigStorage.saveLastRan(this);
        } catch (Throwable ignored) {
        }

        startActivity(i);
        finish();
    }

    private void requestEmulatorRestart() {
        // Kill the dedicated emulator process, then relaunch it so it picks up new prefs.
        EmulatorProcessControl.requestEmulatorProcessExit(this);

        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Intent i = new Intent(this, AmiberryActivity.class);

                    // Avoid unexpected DF0 insertion (e.g., disk.zip) when launching from the native UI.
                    i.putExtra(AmiberryActivity.EXTRA_ENABLE_AUTO_DF0, false);

                    // Enable core logging in debug builds so we can diagnose boot issues.
                    final boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                    if (debuggable) {
                        i.putExtra(AmiberryActivity.EXTRA_ENABLE_LOGFILE, true);
                    }

                    // Native menu replaces Amiberry's GUI.
                    i.putExtra(AmiberryActivity.EXTRA_SHOW_GUI, false);

                    startActivity(i);
                } catch (Throwable ignored) {
                }
            }, 300);
        } catch (Throwable ignored) {
        }
    }

    private void showConfigTextDialog(String title, String text) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextIsSelectable(true);
        tv.setPadding(pad, pad, pad, pad);
        scroll.addView(tv);

        new AlertDialog.Builder(this)
            .setTitle(title == null || title.trim().isEmpty() ? "Config Text" : title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void promptForName(String title, String initial, NameCallback cb) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial);
        et.setSelection(et.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("OK", (d, which) -> {
                String name = et.getText() != null ? et.getText().toString() : "";
                if (name.trim().isEmpty()) {
                    toast("Name required");
                    return;
                }
                cb.onName(name);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
