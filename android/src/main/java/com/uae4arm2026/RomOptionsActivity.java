package com.uae4arm2026;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class RomOptionsActivity extends Activity {

    private static final int REQ_PICK_MAIN = 2001;
    private static final int REQ_PICK_EXT = 2002;
    private static final int REQ_PICK_CART = 2003;

    private static final String PREFIX_MAIN = "rom_main";
    private static final String PREFIX_EXT = "rom_ext";
    private static final String PREFIX_CART = "rom_cart";

    private TextView mMainPath;
    private TextView mExtPath;
    private TextView mCartPath;
    private Spinner mUaeBoardSpinner;
    private CheckBox mMapRom;
    private CheckBox mKickShifter;

    private SharedPreferences prefs() {
        return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
    }

    private File getRomsDir() {
        return new File(new File(getFilesDir(), "amiberry"), "roms");
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "rom.bin";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean copyUriToFile(Uri uri, File dest) {
        dest.getParentFile().mkdirs();
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) return false;
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
            return dest.exists() && dest.length() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void clearByPrefix(File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return;
        String marker = prefix + "__";
        for (File f : files) {
            if (f != null && f.getName() != null && f.getName().startsWith(marker)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private File destFor(String prefix, String displayName) {
        String safe = sanitizeFileName(displayName);
        String name = prefix + "__" + safe;
        return new File(getRomsDir(), name);
    }

    private void updateUi() {
        SharedPreferences p = prefs();

        String mainLabel = p.getString(UaeOptionKeys.UAE_ROM_KICKSTART_LABEL, null);
        String extLabel = p.getString(UaeOptionKeys.UAE_ROM_EXT_LABEL, null);
        String cartLabel = p.getString(UaeOptionKeys.UAE_ROM_CART_LABEL, null);

        mMainPath.setText(mainLabel != null && !mainLabel.trim().isEmpty() ? mainLabel : "(default)");
        mExtPath.setText(extLabel != null && !extLabel.trim().isEmpty() ? extLabel : "(none)");
        mCartPath.setText(cartLabel != null && !cartLabel.trim().isEmpty() ? cartLabel : "(none)");
    }

    private static List<String> uaeBoardLabels() {
        List<String> v = new ArrayList<>();
        v.add("ROM disabled");
        v.add("Original UAE (FS + F0 ROM)");
        v.add("New UAE (64k + F0 ROM)");
        v.add("New UAE (128k, ROM, Direct)");
        v.add("New UAE (128k, ROM, Indirect)");
        return v;
    }

    private void bindSpinners() {
        mUaeBoardSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, uaeBoardLabels()));
        ((ArrayAdapter<?>) mUaeBoardSpinner.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    private void load() {
        SharedPreferences p = prefs();

        int idx = p.getInt(UaeOptionKeys.UAE_ROM_UAEBOARD_INDEX, 0);
        if (idx < 0) idx = 0;
        if (idx >= uaeBoardLabels().size()) idx = 0;
        mUaeBoardSpinner.setSelection(idx);

        mMapRom.setChecked(p.getBoolean(UaeOptionKeys.UAE_ROM_MAPROM, false));
        mKickShifter.setChecked(p.getBoolean(UaeOptionKeys.UAE_ROM_KICKSHIFTER, false));

        updateUi();
    }

    private void save() {
        SharedPreferences.Editor e = prefs().edit();
        e.putInt(UaeOptionKeys.UAE_ROM_UAEBOARD_INDEX, mUaeBoardSpinner.getSelectedItemPosition());
        e.putBoolean(UaeOptionKeys.UAE_ROM_MAPROM, mMapRom.isChecked());
        e.putBoolean(UaeOptionKeys.UAE_ROM_KICKSHIFTER, mKickShifter.isChecked());
        e.apply();
    }

    private void pickFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, requestCode);
    }

    private void clearMain() {
        clearByPrefix(getRomsDir(), PREFIX_MAIN);
        prefs().edit()
            .remove(UaeOptionKeys.UAE_ROM_KICKSTART_FILE)
            .remove(UaeOptionKeys.UAE_ROM_KICKSTART_LABEL)
            .apply();
        updateUi();
    }

    private void clearExt() {
        clearByPrefix(getRomsDir(), PREFIX_EXT);
        prefs().edit()
            .remove(UaeOptionKeys.UAE_ROM_EXT_FILE)
            .remove(UaeOptionKeys.UAE_ROM_EXT_LABEL)
            .apply();
        updateUi();
    }

    private void clearCart() {
        clearByPrefix(getRomsDir(), PREFIX_CART);
        prefs().edit()
            .remove(UaeOptionKeys.UAE_ROM_CART_FILE)
            .remove(UaeOptionKeys.UAE_ROM_CART_LABEL)
            .apply();
        updateUi();
    }

    private void resetAll() {
        clearMain();
        clearExt();
        clearCart();
        prefs().edit()
            .remove(UaeOptionKeys.UAE_ROM_UAEBOARD_INDEX)
            .remove(UaeOptionKeys.UAE_ROM_MAPROM)
            .remove(UaeOptionKeys.UAE_ROM_KICKSHIFTER)
            .apply();
        load();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rom_options);

        mMainPath = findViewById(R.id.txtRomMain);
        mExtPath = findViewById(R.id.txtRomExt);
        mCartPath = findViewById(R.id.txtRomCart);
        mUaeBoardSpinner = findViewById(R.id.spinnerUaeBoard);
        mMapRom = findViewById(R.id.chkMapRom);
        mKickShifter = findViewById(R.id.chkKickShifter);

        bindSpinners();
        load();

        Button btnPickMain = findViewById(R.id.btnPickMainRom);
        Button btnClearMain = findViewById(R.id.btnClearMainRom);
        Button btnPickExt = findViewById(R.id.btnPickExtRom);
        Button btnClearExt = findViewById(R.id.btnClearExtRom);
        Button btnPickCart = findViewById(R.id.btnPickCartRom);
        Button btnClearCart = findViewById(R.id.btnClearCartRom);

        btnPickMain.setOnClickListener(v -> pickFile(REQ_PICK_MAIN));
        btnClearMain.setOnClickListener(v -> clearMain());
        btnPickExt.setOnClickListener(v -> pickFile(REQ_PICK_EXT));
        btnClearExt.setOnClickListener(v -> clearExt());
        btnPickCart.setOnClickListener(v -> pickFile(REQ_PICK_CART));
        btnClearCart.setOnClickListener(v -> clearCart());

        Button btnSave = findViewById(R.id.btnRomSave);
        Button btnReset = findViewById(R.id.btnRomReset);
        Button btnBack = findViewById(R.id.btnRomBack);

        btnSave.setOnClickListener(v -> {
            save();
            finish();
        });
        btnReset.setOnClickListener(v -> resetAll());
        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        String displayName = queryDisplayName(uri);
        if (displayName == null) displayName = "rom.bin";

        File romsDir = getRomsDir();
        romsDir.mkdirs();

        if (requestCode == REQ_PICK_MAIN) {
            clearByPrefix(romsDir, PREFIX_MAIN);
            File dest = destFor(PREFIX_MAIN, displayName);
            if (copyUriToFile(uri, dest)) {
                prefs().edit()
                    .putString(UaeOptionKeys.UAE_ROM_KICKSTART_FILE, dest.getAbsolutePath())
                    .putString(UaeOptionKeys.UAE_ROM_KICKSTART_LABEL, displayName)
                    .apply();
                updateUi();
            } else {
                Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_PICK_EXT) {
            clearByPrefix(romsDir, PREFIX_EXT);
            File dest = destFor(PREFIX_EXT, displayName);
            if (copyUriToFile(uri, dest)) {
                prefs().edit()
                    .putString(UaeOptionKeys.UAE_ROM_EXT_FILE, dest.getAbsolutePath())
                    .putString(UaeOptionKeys.UAE_ROM_EXT_LABEL, displayName)
                    .apply();
                updateUi();
            } else {
                Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_PICK_CART) {
            clearByPrefix(romsDir, PREFIX_CART);
            File dest = destFor(PREFIX_CART, displayName);
            if (copyUriToFile(uri, dest)) {
                prefs().edit()
                    .putString(UaeOptionKeys.UAE_ROM_CART_FILE, dest.getAbsolutePath())
                    .putString(UaeOptionKeys.UAE_ROM_CART_LABEL, displayName)
                    .apply();
                updateUi();
            } else {
                Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
