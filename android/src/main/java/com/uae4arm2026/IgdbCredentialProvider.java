package com.uae4arm2026;

import android.content.SharedPreferences;

final class IgdbCredentialProvider {
    private IgdbCredentialProvider() {
    }

    // Paste SimpleSpeccy IGDB credentials here for temporary fallback.
    // Preference values (if set) always take priority over these defaults.
    static final String SIMPLE_SPECCY_CLIENT_ID = "r1xsns8dbmeb0xxualwaotref8o9ti";
    static final String SIMPLE_SPECCY_CLIENT_SECRET = "d30omhdyjuebh9h8c6jvg5cntz75n7";
    static final String SIMPLE_SPECCY_ACCESS_TOKEN = "";

    static String resolveClientId(SharedPreferences prefs, String prefKey) {
        String fromPrefs = null;
        try {
            fromPrefs = prefs == null ? null : prefs.getString(prefKey, null);
        } catch (Throwable ignored) {
        }
        if (fromPrefs != null && !fromPrefs.trim().isEmpty()) return fromPrefs.trim();
        return (SIMPLE_SPECCY_CLIENT_ID == null || SIMPLE_SPECCY_CLIENT_ID.trim().isEmpty())
            ? null
            : SIMPLE_SPECCY_CLIENT_ID.trim();
    }

    static String resolveAccessToken(SharedPreferences prefs, String prefKey) {
        String fromPrefs = null;
        try {
            fromPrefs = prefs == null ? null : prefs.getString(prefKey, null);
        } catch (Throwable ignored) {
        }
        if (fromPrefs != null && !fromPrefs.trim().isEmpty()) return fromPrefs.trim();
        return (SIMPLE_SPECCY_ACCESS_TOKEN == null || SIMPLE_SPECCY_ACCESS_TOKEN.trim().isEmpty())
            ? null
            : SIMPLE_SPECCY_ACCESS_TOKEN.trim();
    }

    static String resolveClientSecret(SharedPreferences prefs, String prefKey) {
        String fromPrefs = null;
        try {
            fromPrefs = prefs == null ? null : prefs.getString(prefKey, null);
        } catch (Throwable ignored) {
        }
        if (fromPrefs != null && !fromPrefs.trim().isEmpty()) return fromPrefs.trim();
        return (SIMPLE_SPECCY_CLIENT_SECRET == null || SIMPLE_SPECCY_CLIENT_SECRET.trim().isEmpty())
            ? null
            : SIMPLE_SPECCY_CLIENT_SECRET.trim();
    }
}
