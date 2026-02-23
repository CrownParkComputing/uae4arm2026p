package com.uae4arm2026;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity for configuring custom input mappings.
 * 
 * Left column: Game Controls (Up, Down, Left, Right, Fire)
 * Right column: Device Controls (tap to change)
 * 
 * The user taps a device control on the right column to select what device input
 * should trigger that game control.
 */
public class InputMappingActivity extends Activity {

    // Amiga key codes (matching AK_* in keyboard.h)
    public static final int AK_SPACE = 0x40;
    public static final int AK_RETURN = 0x44;
    public static final int AK_ESCAPE = 0x45;
    public static final int AK_UP = 0x4C;
    public static final int AK_DOWN = 0x4D;
    public static final int AK_LEFT = 0x4F;
    public static final int AK_RIGHT = 0x4E;
    public static final int AK_FIRE = 0x60; // Joystick fire button

    // Game controls (fixed list)
    private static final int[] GAME_CONTROLS = {
        AK_UP, AK_DOWN, AK_LEFT, AK_RIGHT, AK_FIRE
    };
    
    // Mapping storage keys
    private static final String PREFS_KEY_GAME_CONTROL = "game_control_";
    private static final String PREFS_KEY_DEVICE_BUTTON = "device_button_";

    // UI elements
    private ListView mappingListView;
    private Button resetButton;
    private TextView statusText;

    // Current mappings (game control -> device button)
    private Map<Integer, Integer> mappings = new HashMap<>();

    // Game identifier for per-game config
    private String gameIdentifier;

    // Waiting for device button capture
    private boolean waitingForButton = false;
    private int currentGameControl = -1;

    // Button names
    private static final Map<Integer, String> GAME_CONTROL_NAMES = new HashMap<>();
    private static final Map<Integer, String> DEVICE_BUTTON_NAMES = new HashMap<>();

    static {
        // Game control names
        GAME_CONTROL_NAMES.put(AK_UP, "Up");
        GAME_CONTROL_NAMES.put(AK_DOWN, "Down");
        GAME_CONTROL_NAMES.put(AK_LEFT, "Left");
        GAME_CONTROL_NAMES.put(AK_RIGHT, "Right");
        GAME_CONTROL_NAMES.put(AK_FIRE, "Fire");

        // Device button names
        DEVICE_BUTTON_NAMES.put(0, "A");
        DEVICE_BUTTON_NAMES.put(1, "B");
        DEVICE_BUTTON_NAMES.put(2, "X");
        DEVICE_BUTTON_NAMES.put(3, "Y");
        DEVICE_BUTTON_NAMES.put(4, "L Shoulder");
        DEVICE_BUTTON_NAMES.put(5, "R Shoulder");
        DEVICE_BUTTON_NAMES.put(11, "D-Pad Up");
        DEVICE_BUTTON_NAMES.put(12, "D-Pad Down");
        DEVICE_BUTTON_NAMES.put(13, "D-Pad Left");
        DEVICE_BUTTON_NAMES.put(14, "D-Pad Right");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_input_mapping);

        // Get game identifier from intent
        gameIdentifier = getIntent().getStringExtra("game_identifier");
        if (gameIdentifier == null) {
            gameIdentifier = "default";
        }

        // Initialize UI
        mappingListView = findViewById(R.id.mapping_list);
        resetButton = findViewById(R.id.reset_mappings_button);
        statusText = findViewById(R.id.status_text);

        // Set up adapter
        InputMappingAdapter adapter = new InputMappingAdapter(this, GAME_CONTROLS, mappings);
        mappingListView.setAdapter(adapter);

        // Load existing mappings
        loadMappings();

        // Set up button handlers
        resetButton.setOnClickListener(v -> resetMappings());

        // Set up list item click for editing
        mappingListView.setOnItemClickListener((parent, view, position, id) -> {
            // User tapped a device control - wait for new button press
            currentGameControl = GAME_CONTROLS[position];
            waitingForButton = true;
            updateStatus("Press a device button to map to " + GAME_CONTROL_NAMES.get(currentGameControl));
        });

        updateStatus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (waitingForButton) {
            // Check if this is a gamepad button
            int deviceButton = gamepadButtonToIndex(keyCode);
            if (deviceButton >= 0) {
                mappings.put(currentGameControl, deviceButton);
                waitingForButton = false;
                ((InputMappingAdapter) mappingListView.getAdapter()).notifyDataSetChanged();
                saveMappings();
                updateStatus("Mapped " + GAME_CONTROL_NAMES.get(currentGameControl) + " to " + DEVICE_BUTTON_NAMES.get(deviceButton));
                return true;
            }
            
            // Also check keyboard keys
            deviceButton = sdlKeyCodeToDeviceButton(keyCode);
            if (deviceButton >= 0) {
                mappings.put(currentGameControl, deviceButton);
                waitingForButton = false;
                ((InputMappingAdapter) mappingListView.getAdapter()).notifyDataSetChanged();
                saveMappings();
                updateStatus("Mapped " + GAME_CONTROL_NAMES.get(currentGameControl) + " to " + DEVICE_BUTTON_NAMES.get(deviceButton));
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
    
    /**
     * Convert Android gamepad button keycode to device button index
     */
    private int gamepadButtonToIndex(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0;
            case KeyEvent.KEYCODE_BUTTON_B: return 1;
            case KeyEvent.KEYCODE_BUTTON_X: return 2;
            case KeyEvent.KEYCODE_BUTTON_Y: return 3;
            case KeyEvent.KEYCODE_BUTTON_L1: return 4;
            case KeyEvent.KEYCODE_BUTTON_R1: return 5;
            case KeyEvent.KEYCODE_BUTTON_L2: return 6;
            case KeyEvent.KEYCODE_BUTTON_R2: return 7;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 8;
            case KeyEvent.KEYCODE_BUTTON_START: return 9;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 10;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 11;
            case KeyEvent.KEYCODE_DPAD_UP: return 11;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 12;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 13;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 14;
            default: return -1;
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Handle controller input for mapping
        if (event.getSource() == InputDevice.SOURCE_GAMEPAD ||
            event.getSource() == InputDevice.SOURCE_JOYSTICK) {

            if (waitingForButton) {
                // Check controller buttons
                for (int i = 0; i < 15; i++) { // SDL_CONTROLLER_BUTTON_MAX
                    float axisValue = event.getAxisValue(i);
                    if (Math.abs(axisValue) > 0.5f) {
                        // Map this button to the current game control
                        mappings.put(currentGameControl, i);
                        waitingForButton = false;
                        ((InputMappingAdapter) mappingListView.getAdapter()).notifyDataSetChanged();
                        saveMappings();
                        updateStatus("Mapped " + GAME_CONTROL_NAMES.get(currentGameControl) + " to " + DEVICE_BUTTON_NAMES.get(i));
                        return true;
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private int sdlKeyCodeToDeviceButton(int keyCode) {
        // Map keyboard keys to device buttons for testing
        switch (keyCode) {
            case KeyEvent.KEYCODE_A: return 0; // A button
            case KeyEvent.KEYCODE_B: return 1; // B button
            case KeyEvent.KEYCODE_X: return 2; // X button
            case KeyEvent.KEYCODE_Y: return 3; // Y button
            case KeyEvent.KEYCODE_L: return 4; // L shoulder
            case KeyEvent.KEYCODE_R: return 5; // R shoulder
            case KeyEvent.KEYCODE_DPAD_UP: return 11; // D-Pad Up
            case KeyEvent.KEYCODE_DPAD_DOWN: return 12; // D-Pad Down
            case KeyEvent.KEYCODE_DPAD_LEFT: return 13; // D-Pad Left
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 14; // D-Pad Right
            default: return -1;
        }
    }

    private void saveMappings() {
        SharedPreferences prefs = getSharedPreferences("input_mappings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Save each mapping
        for (Map.Entry<Integer, Integer> entry : mappings.entrySet()) {
                String key = PREFS_KEY_GAME_CONTROL + "_" + gameIdentifier + "_" + entry.getKey();
                editor.putInt(key, entry.getValue());
        }

        editor.apply();
        Toast.makeText(this, "Mappings saved", Toast.LENGTH_SHORT).show();
    }

    private void loadMappings() {
        SharedPreferences prefs = getSharedPreferences("input_mappings", MODE_PRIVATE);

        mappings.clear();
        for (int gameControl : GAME_CONTROLS) {
            String key = PREFS_KEY_GAME_CONTROL + "_" + gameIdentifier + "_" + gameControl;
            int deviceButton = prefs.getInt(key, -1);
            if (deviceButton >= 0) {
                mappings.put(gameControl, deviceButton);
            }
        }

        ((InputMappingAdapter) mappingListView.getAdapter()).notifyDataSetChanged();
        updateStatus();
    }

    private void resetMappings() {
        // Reset to default mappings
        mappings.clear();
        mappings.put(AK_UP, 11);      // D-Pad Up
        mappings.put(AK_DOWN, 12);    // D-Pad Down
        mappings.put(AK_LEFT, 13);    // D-Pad Left
        mappings.put(AK_RIGHT, 14);   // D-Pad Right
        mappings.put(AK_FIRE, 0);    // A button

        ((InputMappingAdapter) mappingListView.getAdapter()).notifyDataSetChanged();
        saveMappings();
        updateStatus("Reset to default mappings");
    }

    private void updateStatus(String message) {
        if (message != null) {
            statusText.setText(message);
        } else {
            String status = "Game: " + gameIdentifier + "\nTap a device control to change its mapping";
            statusText.setText(status);
        }
    }

    private void updateStatus() {
        updateStatus(null);
    }

    /**
     * Get human-readable name for a game control
     */
    public static String getGameControlName(int gameControl) {
        return GAME_CONTROL_NAMES.getOrDefault(gameControl, "Control " + gameControl);
    }

    /**
     * Get human-readable name for a device button
     */
    public static String getDeviceButtonName(int button) {
        return DEVICE_BUTTON_NAMES.getOrDefault(button, "Button " + button);
    }
}