package com.uae4arm2026;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity for configuring custom input mappings.
 * Allows users to map keyboard keys or joystick directions/buttons to other inputs.
 * Mappings are saved per-game and loaded automatically when the game starts.
 */
public class InputMappingActivity extends AppCompatActivity {

    // Mapping types
    public static final int MAPPING_TYPE_KEYBOARD = 0;
    public static final int MAPPING_TYPE_CONTROLLER_BUTTON = 1;
    public static final int MAPPING_TYPE_CONTROLLER_AXIS = 2;

    // Amiga key codes (matching AK_* in keyboard.h)
    public static final int AK_SPACE = 0x40;
    public static final int AK_RETURN = 0x44;
    public static final int AK_ESCAPE = 0x45;
    public static final int AK_UP = 0x4C;
    public static final int AK_DOWN = 0x4D;
    public static final int AK_LEFT = 0x4F;
    public static final int AK_RIGHT = 0x4E;
    public static final int AK_FIRE = 0x60; // Joystick fire button

    // Controller button IDs
    public static final int CONTROLLER_BUTTON_A = 0;
    public static final int CONTROLLER_BUTTON_B = 1;
    public static final int CONTROLLER_BUTTON_X = 2;
    public static final int CONTROLLER_BUTTON_Y = 3;
    public static final int CONTROLLER_BUTTON_DPAD_UP = 11;
    public static final int CONTROLLER_BUTTON_DPAD_DOWN = 12;
    public static final int CONTROLLER_BUTTON_DPAD_LEFT = 13;
    public static final int CONTROLLER_BUTTON_DPAD_RIGHT = 14;

    // UI elements
    private ListView mappingListView;
    private Button addButton;
    private Button saveButton;
    private Button loadButton;
    private Button clearButton;
    private TextView statusText;

    // Current mappings
    private List<InputMapping> mappings;
    private InputMappingAdapter adapter;

    // Game identifier for per-game config
    private String gameIdentifier;

    // Waiting for input capture
    private boolean waitingForSource = false;
    private boolean waitingForTarget = false;
    private InputMapping currentMapping;

    // Mapping name lookup
    private static final Map<Integer, String> AMIGA_KEY_NAMES = new HashMap<>();
    private static final Map<Integer, String> CONTROLLER_BUTTON_NAMES = new HashMap<>();

    static {
        // Amiga key names
        AMIGA_KEY_NAMES.put(AK_SPACE, "Space");
        AMIGA_KEY_NAMES.put(AK_RETURN, "Return");
        AMIGA_KEY_NAMES.put(AK_ESCAPE, "Escape");
        AMIGA_KEY_NAMES.put(AK_UP, "Up");
        AMIGA_KEY_NAMES.put(AK_DOWN, "Down");
        AMIGA_KEY_NAMES.put(AK_LEFT, "Left");
        AMIGA_KEY_NAMES.put(AK_RIGHT, "Right");
        AMIGA_KEY_NAMES.put(AK_FIRE, "Fire");

        // Controller button names
        CONTROLLER_BUTTON_NAMES.put(CONTROLLER_BUTTON_A, "A Button");
        CONTROLLER_BUTTON_NAMES.put(CONTROLLER_BUTTON_B, "B Button");
        CONTROLLER_BUTTON_NAMES.put(CONTROLLER_BUTTON_X, "X Button");
        CONTROLLER_BUTTON_NAMES.put(CONTROLLER_BUTTON_Y, "Y Button");
        CONTROLLER_BUTTON_NAMES.put(CONTROLLER_BUTTON_DPAD_UP, "D-Pad Up");
        CONTROLLER_BUTTON_NAMES.put(CONTROLLER_BUTTON_DPAD_DOWN, "D-Pad Down");
        CONTROLLER_BUTTON_NAMES.put(CONTROLLER_BUTTON_DPAD_LEFT, "D-Pad Left");
        CONTROLLER_BUTTON_NAMES.put(CONTROLLER_BUTTON_DPAD_RIGHT, "D-Pad Right");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_input_mapping);

        // Get game identifier from intent (passed from AmiberryActivity)
        gameIdentifier = getIntent().getStringExtra("game_identifier");
        if (gameIdentifier == null) {
            gameIdentifier = "default";
        }

        // Initialize UI
        mappingListView = findViewById(R.id.mapping_list);
        addButton = findViewById(R.id.add_mapping_button);
        saveButton = findViewById(R.id.save_mappings_button);
        loadButton = findViewById(R.id.load_mappings_button);
        clearButton = findViewById(R.id.clear_mappings_button);
        statusText = findViewById(R.id.status_text);

        // Initialize mappings list
        mappings = new ArrayList<>();
        adapter = new InputMappingAdapter(this, mappings);
        mappingListView.setAdapter(adapter);

        // Load existing mappings
        loadMappings();

        // Set up button handlers
        addButton.setOnClickListener(v -> startAddMapping());
        saveButton.setOnClickListener(v -> saveMappings());
        loadButton.setOnClickListener(v -> loadMappings());
        clearButton.setOnClickListener(v -> clearMappings());

        // Set up list item click for editing/deleting
        mappingListView.setOnItemClickListener((parent, view, position, id) -> {
            InputMapping mapping = mappings.get(position);
            showMappingOptionsDialog(mapping, position);
        });

        updateStatus();
    }

    private void startAddMapping() {
        currentMapping = new InputMapping();
        waitingForSource = true;
        waitingForTarget = false;
        statusText.setText("Press a key or controller button to map FROM...");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (waitingForSource) {
            // Capture keyboard key as source
            currentMapping.sourceType = MAPPING_TYPE_KEYBOARD;
            currentMapping.sourceKeyCode = keyCode;
            currentMapping.sourceAmigaKey = sdlKeyCodeToAmigaKey(keyCode);

            waitingForSource = false;
            waitingForTarget = true;
            statusText.setText("Now press a key or button to map TO...");
            return true;
        } else if (waitingForTarget) {
            // Capture keyboard key as target
            currentMapping.targetType = MAPPING_TYPE_KEYBOARD;
            currentMapping.targetKeyCode = keyCode;
            currentMapping.targetAmigaKey = sdlKeyCodeToAmigaKey(keyCode);

            addMapping(currentMapping);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Handle keyboard input for mapping
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (waitingForSource || waitingForTarget) {
                return onKeyDown(event.getKeyCode(), event);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Handle controller input for mapping
        if (event.getSource() == InputDevice.SOURCE_GAMEPAD ||
            event.getSource() == InputDevice.SOURCE_JOYSTICK) {

            if (waitingForSource || waitingForTarget) {
                // Check controller buttons
                for (int i = 0; i < 15; i++) { // SDL_CONTROLLER_BUTTON_MAX
                    float axisValue = event.getAxisValue(i);
                    if (Math.abs(axisValue) > 0.5f) {
                        handleControllerInput(i, axisValue > 0);
                        return true;
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void handleControllerInput(int buttonOrAxis, boolean positive) {
        if (waitingForSource) {
            currentMapping.sourceType = MAPPING_TYPE_CONTROLLER_BUTTON;
            currentMapping.sourceButton = buttonOrAxis;

            waitingForSource = false;
            waitingForTarget = true;
            statusText.setText("Now press a key or button to map TO...");
        } else if (waitingForTarget) {
            currentMapping.targetType = MAPPING_TYPE_CONTROLLER_BUTTON;
            currentMapping.targetButton = buttonOrAxis;

            addMapping(currentMapping);
        }
    }

    private void addMapping(InputMapping mapping) {
        mappings.add(mapping);
        adapter.notifyDataSetChanged();
        waitingForSource = false;
        waitingForTarget = false;
        updateStatus();
    }

    private void showMappingOptionsDialog(InputMapping mapping, int position) {
        String[] options = {"Edit", "Delete", "Cancel"};

        new AlertDialog.Builder(this)
            .setTitle("Mapping: " + mapping.getDisplayName())
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: // Edit - restart capture
                        mappings.remove(position);
                        startAddMapping();
                        break;
                    case 1: // Delete
                        mappings.remove(position);
                        adapter.notifyDataSetChanged();
                        updateStatus();
                        break;
                    case 2: // Cancel
                        break;
                }
            })
            .show();
    }

    private void saveMappings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();

        // Save mappings as JSON-like string
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mappings.size(); i++) {
            InputMapping m = mappings.get(i);
            if (i > 0) sb.append(";");
            sb.append(m.toString());
        }

        editor.putString("input_mappings_" + gameIdentifier, sb.toString());
        editor.apply();

        Toast.makeText(this, "Mappings saved", Toast.LENGTH_SHORT).show();
    }

    private void loadMappings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String saved = prefs.getString("input_mappings_" + gameIdentifier, "");

        mappings.clear();
        if (!saved.isEmpty()) {
            String[] parts = saved.split(";");
            for (String part : parts) {
                InputMapping m = InputMapping.fromString(part);
                if (m != null) {
                    mappings.add(m);
                }
            }
        }

        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void clearMappings() {
        new AlertDialog.Builder(this)
            .setTitle("Clear all mappings?")
            .setMessage("This will delete all custom input mappings for this game.")
            .setPositiveButton("Clear", (dialog, which) -> {
                mappings.clear();
                adapter.notifyDataSetChanged();
                updateStatus();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateStatus() {
        String status = "Game: " + gameIdentifier + "\nMappings: " + mappings.size();
        if (!waitingForSource && !waitingForTarget) {
            status += "\n\nTap 'Add Mapping' to create a new input mapping";
        }
        statusText.setText(status);
    }

    /**
     * Convert SDL key code to Amiga key code
     */
    private int sdlKeyCodeToAmigaKey(int sdlKeyCode) {
        switch (sdlKeyCode) {
            case KeyEvent.KEYCODE_SPACE: return AK_SPACE;
            case KeyEvent.KEYCODE_ENTER: return AK_RETURN;
            case KeyEvent.KEYCODE_ESCAPE: return AK_ESCAPE;
            case KeyEvent.KEYCODE_DPAD_UP: return AK_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return AK_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return AK_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return AK_RIGHT;
            default: return sdlKeyCode; // Pass through for other keys
        }
    }

    /**
     * Get human-readable name for an Amiga key
     */
    public static String getAmigaKeyName(int amigaKey) {
        return AMIGA_KEY_NAMES.getOrDefault(amigaKey, "Key " + amigaKey);
    }

    /**
     * Get human-readable name for a controller button
     */
    public static String getControllerButtonName(int button) {
        return CONTROLLER_BUTTON_NAMES.getOrDefault(button, "Button " + button);
    }

    /**
     * Class representing a single input mapping
     */
    public static class InputMapping {
        public int sourceType;
        public int sourceKeyCode;
        public int sourceAmigaKey;
        public int sourceButton;

        public int targetType;
        public int targetKeyCode;
        public int targetAmigaKey;
        public int targetButton;

        public InputMapping() {}

        public String getDisplayName() {
            String source = getSourceName();
            String target = getTargetName();
            return source + " → " + target;
        }

        public String getSourceName() {
            if (sourceType == MAPPING_TYPE_KEYBOARD) {
                return getAmigaKeyName(sourceAmigaKey);
            } else {
                return getControllerButtonName(sourceButton);
            }
        }

        public String getTargetName() {
            if (targetType == MAPPING_TYPE_KEYBOARD) {
                return getAmigaKeyName(targetAmigaKey);
            } else {
                return getControllerButtonName(targetButton);
            }
        }

        @Override
        public String toString() {
            // Format: type,code,type,code
            return sourceType + "," + sourceAmigaKey + "," + targetType + "," + targetAmigaKey;
        }

        public static InputMapping fromString(String s) {
            try {
                String[] parts = s.split(",");
                InputMapping m = new InputMapping();
                m.sourceType = Integer.parseInt(parts[0]);
                m.sourceAmigaKey = Integer.parseInt(parts[1]);
                m.targetType = Integer.parseInt(parts[2]);
                m.targetAmigaKey = Integer.parseInt(parts[3]);
                return m;
            } catch (Exception e) {
                return null;
            }
        }
    }
}