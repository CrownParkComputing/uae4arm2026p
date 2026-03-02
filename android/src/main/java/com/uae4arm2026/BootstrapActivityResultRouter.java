package com.uae4arm2026;

import android.content.Intent;
import android.net.Uri;

final class BootstrapActivityResultRouter {
    interface Callbacks {
        void onPrimaryUriResolved(Uri uri, int takeFlags);
        void handleFirstRunFolder(int resultCode, Uri uri);
        void handleWhdloadImport(Uri uri, Intent data);
        boolean handleDhDirImport(int requestCode, Uri uri);
        boolean handleDhHdfImport(int requestCode, Intent data, Uri uri);
        void handleKickstartImport(Uri uri);
        void handleExtRomImport(Uri uri);
        boolean handleCdImageImport(int requestCode, Intent data, int takeFlags, Uri uri);
        boolean handleDfImport(int requestCode, Uri uri);
    }

    static final class ParsedResult {
        final boolean valid;
        final int takeFlags;
        final Uri uri;

        ParsedResult(boolean valid, int takeFlags, Uri uri) {
            this.valid = valid;
            this.takeFlags = takeFlags;
            this.uri = uri;
        }
    }

    private BootstrapActivityResultRouter() {
    }

    static ParsedResult parse(int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null ||
            (data.getData() == null && data.getClipData() == null)) {
            return new ParsedResult(false, 0, null);
        }

        final int takeFlags = data.getFlags() &
            (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        Uri uri = data.getData();
        if (uri == null) {
            try {
                android.content.ClipData cd = data.getClipData();
                if (cd != null && cd.getItemCount() > 0) {
                    uri = cd.getItemAt(0).getUri();
                }
            } catch (Throwable ignored) {
            }
        }

        return new ParsedResult(true, takeFlags, uri);
    }

    static void route(int requestCode, int resultCode, Intent data,
                      int reqFirstRunFolder,
                      int reqImportWHDLoad,
                      int reqImportKickstart,
                      int reqImportExtRom,
                      Callbacks callbacks) {
        ParsedResult parsed = parse(resultCode, data);
        if (!parsed.valid) {
            return;
        }

        if (parsed.uri != null) {
            callbacks.onPrimaryUriResolved(parsed.uri, parsed.takeFlags);
        }

        if (requestCode == reqFirstRunFolder) {
            callbacks.handleFirstRunFolder(resultCode, parsed.uri);
            return;
        }

        if (requestCode == reqImportWHDLoad) {
            callbacks.handleWhdloadImport(parsed.uri, data);
            return;
        }

        if (callbacks.handleDhDirImport(requestCode, parsed.uri)) {
            return;
        }

        if (callbacks.handleDhHdfImport(requestCode, data, parsed.uri)) {
            return;
        }

        if (requestCode == reqImportKickstart) {
            callbacks.handleKickstartImport(parsed.uri);
            return;
        }

        if (requestCode == reqImportExtRom) {
            callbacks.handleExtRomImport(parsed.uri);
            return;
        }

        if (callbacks.handleCdImageImport(requestCode, data, parsed.takeFlags, parsed.uri)) {
            return;
        }

        callbacks.handleDfImport(requestCode, parsed.uri);
    }
}
