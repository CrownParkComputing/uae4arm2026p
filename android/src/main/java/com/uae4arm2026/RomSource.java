package com.uae4arm2026;

import java.io.File;
import android.net.Uri;

public final class RomSource {
    public final String displayName;
    public final File file;
    public final Uri uri;
    public final boolean isInternal;

    public RomSource(String displayName, File file, Uri uri, boolean isInternal) {
        this.displayName = displayName;
        this.file = file;
        this.uri = uri;
        this.isInternal = isInternal;
    }
}
