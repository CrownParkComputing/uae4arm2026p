package com.uae4arm2026;

import android.widget.AdapterView;

final class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {

    interface Callback {
        void run();
    }

    private final Callback callback;

    SimpleItemSelectedListener(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
        if (callback != null) callback.run();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        if (callback != null) callback.run();
    }
}
