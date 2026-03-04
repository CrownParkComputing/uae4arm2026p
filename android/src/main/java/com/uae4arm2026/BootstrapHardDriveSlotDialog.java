package com.uae4arm2026;

import android.app.Activity;
import android.app.AlertDialog;

final class BootstrapHardDriveSlotDialog {
    private BootstrapHardDriveSlotDialog() {
    }

    interface Actions {
        void pickHdf(int slot);

        void pickFolder(int slot);

        void openAgsSetup();

        void setLabel(int slot);

        void clearSlot(int slot);
    }

    static void show(Activity activity, int slot, boolean includeAgsSetup, Actions actions) {
        if (activity == null || actions == null) return;

        final String[] options = includeAgsSetup
            ? new String[]{"Choose HDF file", "Choose folder", "AGS auto-mount setup", "Set label", "Clear slot"}
            : new String[]{"Choose HDF file", "Choose folder", "Set label", "Clear slot"};

        new AlertDialog.Builder(activity)
            .setTitle("DH" + slot)
            .setItems(options, (d, which) -> handleSelection(slot, includeAgsSetup, which, actions))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void handleSelection(int slot, boolean includeAgsSetup, int which, Actions actions) {
        if (which == 0) {
            actions.pickHdf(slot);
            return;
        }

        if (which == 1) {
            actions.pickFolder(slot);
            return;
        }

        if (includeAgsSetup && which == 2) {
            actions.openAgsSetup();
            return;
        }

        int labelIndex = includeAgsSetup ? 3 : 2;
        if (which == labelIndex) {
            actions.setLabel(slot);
            return;
        }

        actions.clearSlot(slot);
    }
}
