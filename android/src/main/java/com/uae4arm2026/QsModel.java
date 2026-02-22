package com.uae4arm2026;

public final class QsModel {
    public final String prefsId;
    public final String cliId;
    public final String label;
    public final String[] configs;
    public final boolean rtg;
    public final boolean rtgJit;
    public final int rtgVramMb;
    public final int rtgZ3Mb;

    public QsModel(String prefsId, String cliId, String label, String[] configs, boolean rtg, boolean rtgJit, int rtgVramMb, int rtgZ3Mb) {
        this.prefsId = prefsId;
        this.cliId = cliId;
        this.label = label;
        this.configs = configs;
        this.rtg = rtg;
        this.rtgJit = rtgJit;
        this.rtgVramMb = rtgVramMb;
        this.rtgZ3Mb = rtgZ3Mb;
    }
}
