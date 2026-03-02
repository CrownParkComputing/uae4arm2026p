package com.uae4arm2026;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

public class SoundOptionsActivity extends Activity {

    private CheckBox mUsePreset;

    private RadioGroup mSoundOutput;
    private CheckBox mAutoSwitch;

    private Spinner mChannelMode;
    private Spinner mFrequency;
    private Spinner mSwapChannels;
    private Spinner mStereoSeparation;
    private Spinner mStereoDelay;
    private Spinner mInterpolation;
    private Spinner mFilter;

    private SeekBar mPaulaVol;
    private SeekBar mCdVol;
    private SeekBar mAhiVol;
    private SeekBar mMidiVol;

    private TextView mPaulaVolInfo;
    private TextView mCdVolInfo;
    private TextView mAhiVolInfo;
    private TextView mMidiVolInfo;

    private CheckBox mFloppySound;
    private SeekBar mFloppyEmpty;
    private SeekBar mFloppyDisk;
    private TextView mFloppyEmptyInfo;
    private TextView mFloppyDiskInfo;

    private Spinner mSoundMaxBuff;
    private RadioGroup mPullPush;

    private SharedPreferences prefs() {
        return getSharedPreferences(UaeOptionKeys.PREFS_NAME, MODE_PRIVATE);
    }

    private static final List<String> CHANNEL_MODE_LABELS = Arrays.asList(
        "Mono",
        "Stereo",
        "Cloned stereo (4 channels)",
        "4 Channels",
        "Cloned stereo (5.1)",
        "5.1 Channels",
        "Cloned stereo (7.1)",
        "7.1 channels"
    );

    private static final List<String> CHANNEL_MODE_VALUES = Arrays.asList(
        "mono",
        "stereo",
        "clonedstereo",
        "4ch",
        "clonedstereo6ch",
        "6ch",
        "clonedstereo8ch",
        "8ch"
    );

    private static final List<String> FREQUENCY_VALUES = Arrays.asList(
        "11025",
        "22050",
        "32000",
        "44100",
        "48000"
    );

    private static final List<String> SWAP_CHANNELS_LABELS = Arrays.asList(
        "-",
        "Paula only",
        "AHI only",
        "Both"
    );

    private static final List<String> STEREO_SEPARATION_LABELS = Arrays.asList(
        "100%",
        "90%",
        "80%",
        "70%",
        "60%",
        "50%",
        "40%",
        "30%",
        "20%",
        "10%",
        "0%"
    );

    private static final List<String> STEREO_DELAY_LABELS = Arrays.asList(
        "-",
        "1",
        "2",
        "3",
        "4",
        "5",
        "6",
        "7",
        "8",
        "9",
        "10"
    );

    private static final List<String> INTERPOLATION_LABELS = Arrays.asList(
        "Disabled",
        "Anti",
        "Sinc",
        "RH",
        "Crux"
    );

    private static final List<String> INTERPOLATION_VALUES = Arrays.asList(
        "none",
        "anti",
        "sinc",
        "rh",
        "crux"
    );

    private static final List<String> FILTER_LABELS = Arrays.asList(
        "Always off",
        "Emulated (A500)",
        "Emulated (A1200)",
        "Always on (A500)",
        "Always on (A1200)"
    );

    private static final List<String> SOUND_MAX_BUFF_LABELS = Arrays.asList(
        "Auto (0)",
        "1024",
        "2048",
        "3072",
        "4096",
        "6144",
        "8192",
        "12288",
        "16384",
        "32768",
        "65536"
    );

    private static final int[] SOUND_MAX_BUFF_VALUES = new int[]{
        0, 1024, 2048, 3072, 4096, 6144, 8192, 12288, 16384, 32768, 65536
    };

    private static int indexOfIgnoreCase(List<String> values, String needle, int defIdx) {
        if (needle == null) return defIdx;
        for (int i = 0; i < values.size(); i++) {
            if (needle.equalsIgnoreCase(values.get(i))) return i;
        }
        return defIdx;
    }

    private static void updatePercentLabel(TextView label, int percent) {
        if (label == null) return;
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        label.setText(percent + "%");
    }

    private boolean hasSoundOverrides(SharedPreferences p) {
        return p.contains(UaeOptionKeys.UAE_SOUND_OUTPUT)
            || p.contains(UaeOptionKeys.UAE_SOUND_AUTO)
            || p.contains(UaeOptionKeys.UAE_SOUND_CHANNELS)
            || p.contains(UaeOptionKeys.UAE_SOUND_FREQUENCY)
            || p.contains(UaeOptionKeys.UAE_SOUND_INTERPOL)
            || p.contains(UaeOptionKeys.UAE_SOUND_FILTER)
            || p.contains(UaeOptionKeys.UAE_SOUND_FILTER_TYPE)
            || p.contains(UaeOptionKeys.UAE_SOUND_STEREO_SEPARATION)
            || p.contains(UaeOptionKeys.UAE_SOUND_STEREO_DELAY)
            || p.contains(UaeOptionKeys.UAE_SOUND_SWAP_PAULA)
            || p.contains(UaeOptionKeys.UAE_SOUND_SWAP_AHI)
            || p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_PAULA)
            || p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_CD)
            || p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_AHI)
            || p.contains(UaeOptionKeys.UAE_SOUND_VOLUME_MIDI)
            || p.contains(UaeOptionKeys.UAE_SOUND_MAX_BUFF)
            || p.contains(UaeOptionKeys.UAE_SOUND_PULLMODE)
            || p.contains(UaeOptionKeys.UAE_FLOPPY_SOUND_ENABLED)
            || p.contains(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_EMPTY)
            || p.contains(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_DISK);
    }

    private void bindSpinners() {
        mChannelMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, CHANNEL_MODE_LABELS));
        ((ArrayAdapter<?>) mChannelMode.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mFrequency.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, FREQUENCY_VALUES));
        ((ArrayAdapter<?>) mFrequency.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mSwapChannels.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, SWAP_CHANNELS_LABELS));
        ((ArrayAdapter<?>) mSwapChannels.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mStereoSeparation.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, STEREO_SEPARATION_LABELS));
        ((ArrayAdapter<?>) mStereoSeparation.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mStereoDelay.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, STEREO_DELAY_LABELS));
        ((ArrayAdapter<?>) mStereoDelay.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mInterpolation.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, INTERPOLATION_LABELS));
        ((ArrayAdapter<?>) mInterpolation.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mFilter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, FILTER_LABELS));
        ((ArrayAdapter<?>) mFilter.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mSoundMaxBuff.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, SOUND_MAX_BUFF_LABELS));
        ((ArrayAdapter<?>) mSoundMaxBuff.getAdapter()).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    private void setControlsEnabled(boolean enabled) {
        mSoundOutput.setEnabled(enabled);
        for (int i = 0; i < mSoundOutput.getChildCount(); i++) {
            View v = mSoundOutput.getChildAt(i);
            if (v != null) v.setEnabled(enabled);
        }

        mAutoSwitch.setEnabled(enabled);
        mChannelMode.setEnabled(enabled);
        mFrequency.setEnabled(enabled);
        mSwapChannels.setEnabled(enabled);
        mStereoSeparation.setEnabled(enabled);
        mStereoDelay.setEnabled(enabled);
        mInterpolation.setEnabled(enabled);
        mFilter.setEnabled(enabled);

        mPaulaVol.setEnabled(enabled);
        mCdVol.setEnabled(enabled);
        mAhiVol.setEnabled(enabled);
        mMidiVol.setEnabled(enabled);

        mFloppySound.setEnabled(enabled);
        mFloppyEmpty.setEnabled(enabled);
        mFloppyDisk.setEnabled(enabled);

        mSoundMaxBuff.setEnabled(enabled);
        mPullPush.setEnabled(enabled);
        for (int i = 0; i < mPullPush.getChildCount(); i++) {
            View v = mPullPush.getChildAt(i);
            if (v != null) v.setEnabled(enabled);
        }
    }

    private void updateStereoRelatedEnabled() {
        boolean enabled = !mUsePreset.isChecked();
        int cmIdx = mChannelMode.getSelectedItemPosition();
        boolean stereo = enabled && cmIdx > 0; // anything except mono
        mStereoSeparation.setEnabled(stereo);
        mStereoDelay.setEnabled(stereo);
    }

    private void load() {
        SharedPreferences p = prefs();

        boolean usePreset = !hasSoundOverrides(p);
        mUsePreset.setChecked(usePreset);

        // Sound output: default to "normal".
        String out = p.getString(UaeOptionKeys.UAE_SOUND_OUTPUT, "normal");
        if ("none".equalsIgnoreCase(out)) {
            mSoundOutput.check(R.id.radioSoundDisabled);
        } else if ("interrupts".equalsIgnoreCase(out)) {
            mSoundOutput.check(R.id.radioSoundDisabledEmu);
        } else {
            mSoundOutput.check(R.id.radioSoundEnabled);
        }

        mAutoSwitch.setChecked(p.getBoolean(UaeOptionKeys.UAE_SOUND_AUTO, false));

        String ch = p.getString(UaeOptionKeys.UAE_SOUND_CHANNELS, "stereo");
        mChannelMode.setSelection(indexOfIgnoreCase(CHANNEL_MODE_VALUES, ch, 1));

        int freq = p.getInt(UaeOptionKeys.UAE_SOUND_FREQUENCY, 44100);
        mFrequency.setSelection(indexOfIgnoreCase(FREQUENCY_VALUES, String.valueOf(freq), 3));

        boolean swapPaula = p.getBoolean(UaeOptionKeys.UAE_SOUND_SWAP_PAULA, false);
        boolean swapAhi = p.getBoolean(UaeOptionKeys.UAE_SOUND_SWAP_AHI, false);
        int swapSel;
        if (swapPaula && swapAhi) swapSel = 3;
        else if (swapPaula) swapSel = 1;
        else if (swapAhi) swapSel = 2;
        else swapSel = 0;
        mSwapChannels.setSelection(swapSel);

        int sep = p.getInt(UaeOptionKeys.UAE_SOUND_STEREO_SEPARATION, 7);
        int sepIdx = 10 - sep;
        if (sepIdx < 0) sepIdx = 0;
        if (sepIdx > 10) sepIdx = 10;
        mStereoSeparation.setSelection(sepIdx);

        int delay = p.getInt(UaeOptionKeys.UAE_SOUND_STEREO_DELAY, 0);
        int delayIdx = 0;
        if (delay >= 1 && delay <= 10) delayIdx = delay;
        mStereoDelay.setSelection(delayIdx);

        String interpol = p.getString(UaeOptionKeys.UAE_SOUND_INTERPOL, "anti");
        mInterpolation.setSelection(indexOfIgnoreCase(INTERPOLATION_VALUES, interpol, 1));

        String filter = p.getString(UaeOptionKeys.UAE_SOUND_FILTER, "emulated");
        String filterType = p.getString(UaeOptionKeys.UAE_SOUND_FILTER_TYPE, "standard");
        int filterIdx;
        if ("off".equalsIgnoreCase(filter)) filterIdx = 0;
        else if ("emulated".equalsIgnoreCase(filter) && "enhanced".equalsIgnoreCase(filterType)) filterIdx = 2;
        else if ("emulated".equalsIgnoreCase(filter)) filterIdx = 1;
        else if ("on".equalsIgnoreCase(filter) && "enhanced".equalsIgnoreCase(filterType)) filterIdx = 4;
        else filterIdx = 3;
        mFilter.setSelection(filterIdx);

        // Volumes: prefs store attenuation [0..100]. UI is percent [0..100].
        int paulaAtt = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_PAULA, 0);
        int cdAtt = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_CD, 0);
        int ahiAtt = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_AHI, 0);
        int midiAtt = p.getInt(UaeOptionKeys.UAE_SOUND_VOLUME_MIDI, 0);

        int paulaPct = 100 - paulaAtt;
        int cdPct = 100 - cdAtt;
        int ahiPct = 100 - ahiAtt;
        int midiPct = 100 - midiAtt;

        mPaulaVol.setProgress(clampPercent(paulaPct));
        mCdVol.setProgress(clampPercent(cdPct));
        mAhiVol.setProgress(clampPercent(ahiPct));
        mMidiVol.setProgress(clampPercent(midiPct));

        updatePercentLabel(mPaulaVolInfo, mPaulaVol.getProgress());
        updatePercentLabel(mCdVolInfo, mCdVol.getProgress());
        updatePercentLabel(mAhiVolInfo, mAhiVol.getProgress());
        updatePercentLabel(mMidiVolInfo, mMidiVol.getProgress());

        boolean floppySound = p.getBoolean(UaeOptionKeys.UAE_FLOPPY_SOUND_ENABLED, true);
        mFloppySound.setChecked(floppySound);

        int floppyEmptyAtt = p.getInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_EMPTY, 33);
        int floppyDiskAtt = p.getInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_DISK, 33);

        int floppyEmptyPct = 100 - floppyEmptyAtt;
        int floppyDiskPct = 100 - floppyDiskAtt;

        mFloppyEmpty.setProgress(clampPercent(floppyEmptyPct));
        mFloppyDisk.setProgress(clampPercent(floppyDiskPct));
        updatePercentLabel(mFloppyEmptyInfo, mFloppyEmpty.getProgress());
        updatePercentLabel(mFloppyDiskInfo, mFloppyDisk.getProgress());

        int maxb = p.getInt(UaeOptionKeys.UAE_SOUND_MAX_BUFF, 16384);
        int mbIdx = 0;
        for (int i = 0; i < SOUND_MAX_BUFF_VALUES.length; i++) {
            if (SOUND_MAX_BUFF_VALUES[i] == maxb) {
                mbIdx = i;
                break;
            }
        }
        mSoundMaxBuff.setSelection(mbIdx);

        boolean pull = p.getBoolean(UaeOptionKeys.UAE_SOUND_PULLMODE, true);
        mPullPush.check(pull ? R.id.radioPullAudio : R.id.radioPushAudio);

        setControlsEnabled(!usePreset);
        updateStereoRelatedEnabled();
    }

    private static int clampPercent(int v) {
        if (v < 0) return 0;
        if (v > 100) return 100;
        return v;
    }

    private void clearSoundOverrides(SharedPreferences.Editor e) {
        e.remove(UaeOptionKeys.UAE_SOUND_OUTPUT);
        e.remove(UaeOptionKeys.UAE_SOUND_AUTO);
        e.remove(UaeOptionKeys.UAE_SOUND_CHANNELS);
        e.remove(UaeOptionKeys.UAE_SOUND_FREQUENCY);
        e.remove(UaeOptionKeys.UAE_SOUND_INTERPOL);
        e.remove(UaeOptionKeys.UAE_SOUND_FILTER);
        e.remove(UaeOptionKeys.UAE_SOUND_FILTER_TYPE);
        e.remove(UaeOptionKeys.UAE_SOUND_STEREO_SEPARATION);
        e.remove(UaeOptionKeys.UAE_SOUND_STEREO_DELAY);
        e.remove(UaeOptionKeys.UAE_SOUND_SWAP_PAULA);
        e.remove(UaeOptionKeys.UAE_SOUND_SWAP_AHI);
        e.remove(UaeOptionKeys.UAE_SOUND_VOLUME_PAULA);
        e.remove(UaeOptionKeys.UAE_SOUND_VOLUME_CD);
        e.remove(UaeOptionKeys.UAE_SOUND_VOLUME_AHI);
        e.remove(UaeOptionKeys.UAE_SOUND_VOLUME_MIDI);
        e.remove(UaeOptionKeys.UAE_SOUND_MAX_BUFF);
        e.remove(UaeOptionKeys.UAE_SOUND_PULLMODE);
        e.remove(UaeOptionKeys.UAE_FLOPPY_SOUND_ENABLED);
        e.remove(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_EMPTY);
        e.remove(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_DISK);
    }

    private void save() {
        SharedPreferences.Editor e = prefs().edit();

        if (mUsePreset.isChecked()) {
            clearSoundOverrides(e);
            e.apply();
            return;
        }

        // Sound output
        int outChecked = mSoundOutput.getCheckedRadioButtonId();
        if (outChecked == R.id.radioSoundDisabled) {
            e.putString(UaeOptionKeys.UAE_SOUND_OUTPUT, "none");
        } else if (outChecked == R.id.radioSoundDisabledEmu) {
            e.putString(UaeOptionKeys.UAE_SOUND_OUTPUT, "interrupts");
        } else {
            e.putString(UaeOptionKeys.UAE_SOUND_OUTPUT, "normal");
        }

        e.putBoolean(UaeOptionKeys.UAE_SOUND_AUTO, mAutoSwitch.isChecked());

        int cm = mChannelMode.getSelectedItemPosition();
        if (cm < 0 || cm >= CHANNEL_MODE_VALUES.size()) cm = 1;
        e.putString(UaeOptionKeys.UAE_SOUND_CHANNELS, CHANNEL_MODE_VALUES.get(cm));

        int fidx = mFrequency.getSelectedItemPosition();
        if (fidx < 0 || fidx >= FREQUENCY_VALUES.size()) fidx = 3;
        e.putInt(UaeOptionKeys.UAE_SOUND_FREQUENCY, Integer.parseInt(FREQUENCY_VALUES.get(fidx)));

        int swap = mSwapChannels.getSelectedItemPosition();
        boolean swapPaula = swap == 1 || swap == 3;
        boolean swapAhi = swap == 2 || swap == 3;
        e.putBoolean(UaeOptionKeys.UAE_SOUND_SWAP_PAULA, swapPaula);
        e.putBoolean(UaeOptionKeys.UAE_SOUND_SWAP_AHI, swapAhi);

        int sepIdx = mStereoSeparation.getSelectedItemPosition();
        if (sepIdx < 0) sepIdx = 3;
        if (sepIdx > 10) sepIdx = 10;
        e.putInt(UaeOptionKeys.UAE_SOUND_STEREO_SEPARATION, 10 - sepIdx);

        int delayIdx = mStereoDelay.getSelectedItemPosition();
        if (delayIdx < 0) delayIdx = 0;
        if (delayIdx > 10) delayIdx = 0;
        e.putInt(UaeOptionKeys.UAE_SOUND_STEREO_DELAY, delayIdx);

        int ip = mInterpolation.getSelectedItemPosition();
        if (ip < 0 || ip >= INTERPOLATION_VALUES.size()) ip = 1;
        e.putString(UaeOptionKeys.UAE_SOUND_INTERPOL, INTERPOLATION_VALUES.get(ip));

        int filt = mFilter.getSelectedItemPosition();
        // 0=off, 1=emulated+standard, 2=emulated+enhanced, 3=on+standard, 4=on+enhanced
        if (filt == 0) {
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER, "off");
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER_TYPE, "standard");
        } else if (filt == 2) {
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER, "emulated");
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER_TYPE, "enhanced");
        } else if (filt == 1) {
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER, "emulated");
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER_TYPE, "standard");
        } else if (filt == 4) {
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER, "on");
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER_TYPE, "enhanced");
        } else {
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER, "on");
            e.putString(UaeOptionKeys.UAE_SOUND_FILTER_TYPE, "standard");
        }

        // Volumes (store attenuation)
        e.putInt(UaeOptionKeys.UAE_SOUND_VOLUME_PAULA, 100 - clampPercent(mPaulaVol.getProgress()));
        e.putInt(UaeOptionKeys.UAE_SOUND_VOLUME_CD, 100 - clampPercent(mCdVol.getProgress()));
        e.putInt(UaeOptionKeys.UAE_SOUND_VOLUME_AHI, 100 - clampPercent(mAhiVol.getProgress()));
        e.putInt(UaeOptionKeys.UAE_SOUND_VOLUME_MIDI, 100 - clampPercent(mMidiVol.getProgress()));

        e.putBoolean(UaeOptionKeys.UAE_FLOPPY_SOUND_ENABLED, mFloppySound.isChecked());
        e.putInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_EMPTY, 100 - clampPercent(mFloppyEmpty.getProgress()));
        e.putInt(UaeOptionKeys.UAE_FLOPPY_SOUNDVOL_DISK, 100 - clampPercent(mFloppyDisk.getProgress()));

        int mb = mSoundMaxBuff.getSelectedItemPosition();
        if (mb < 0 || mb >= SOUND_MAX_BUFF_VALUES.length) mb = 0;
        e.putInt(UaeOptionKeys.UAE_SOUND_MAX_BUFF, SOUND_MAX_BUFF_VALUES[mb]);

        int pp = mPullPush.getCheckedRadioButtonId();
        e.putBoolean(UaeOptionKeys.UAE_SOUND_PULLMODE, pp == R.id.radioPullAudio);

        e.apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_options);

        mUsePreset = findViewById(R.id.chkSoundUsePreset);
        mSoundOutput = findViewById(R.id.radioSoundOutput);
        mAutoSwitch = findViewById(R.id.chkSoundAutoSwitch);

        mChannelMode = findViewById(R.id.spinnerSoundChannelMode);
        mFrequency = findViewById(R.id.spinnerSoundFrequency);
        mSwapChannels = findViewById(R.id.spinnerSoundSwapChannels);
        mStereoSeparation = findViewById(R.id.spinnerSoundStereoSeparation);
        mStereoDelay = findViewById(R.id.spinnerSoundStereoDelay);
        mInterpolation = findViewById(R.id.spinnerSoundInterpolation);
        mFilter = findViewById(R.id.spinnerSoundFilter);

        mPaulaVol = findViewById(R.id.seekPaulaVol);
        mCdVol = findViewById(R.id.seekCdVol);
        mAhiVol = findViewById(R.id.seekAhiVol);
        mMidiVol = findViewById(R.id.seekMidiVol);

        mPaulaVolInfo = findViewById(R.id.txtPaulaVolInfo);
        mCdVolInfo = findViewById(R.id.txtCdVolInfo);
        mAhiVolInfo = findViewById(R.id.txtAhiVolInfo);
        mMidiVolInfo = findViewById(R.id.txtMidiVolInfo);

        mFloppySound = findViewById(R.id.chkFloppySound);
        mFloppyEmpty = findViewById(R.id.seekFloppyEmpty);
        mFloppyDisk = findViewById(R.id.seekFloppyDisk);
        mFloppyEmptyInfo = findViewById(R.id.txtFloppyEmptyInfo);
        mFloppyDiskInfo = findViewById(R.id.txtFloppyDiskInfo);

        mSoundMaxBuff = findViewById(R.id.spinnerSoundMaxBuff);
        mPullPush = findViewById(R.id.radioPullPush);

        mPaulaVol.setMax(100);
        mCdVol.setMax(100);
        mAhiVol.setMax(100);
        mMidiVol.setMax(100);
        mFloppyEmpty.setMax(100);
        mFloppyDisk.setMax(100);

        bindSpinners();
        load();

        mUsePreset.setOnCheckedChangeListener((b, checked) -> {
            setControlsEnabled(!checked);
            updateStereoRelatedEnabled();
        });

        mChannelMode.setOnItemSelectedListener(new SimpleItemSelectedListener(this::updateStereoRelatedEnabled));

        SeekBar.OnSeekBarChangeListener volListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (seekBar == mPaulaVol) updatePercentLabel(mPaulaVolInfo, progress);
                else if (seekBar == mCdVol) updatePercentLabel(mCdVolInfo, progress);
                else if (seekBar == mAhiVol) updatePercentLabel(mAhiVolInfo, progress);
                else if (seekBar == mMidiVol) updatePercentLabel(mMidiVolInfo, progress);
                else if (seekBar == mFloppyEmpty) updatePercentLabel(mFloppyEmptyInfo, progress);
                else if (seekBar == mFloppyDisk) updatePercentLabel(mFloppyDiskInfo, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        mPaulaVol.setOnSeekBarChangeListener(volListener);
        mCdVol.setOnSeekBarChangeListener(volListener);
        mAhiVol.setOnSeekBarChangeListener(volListener);
        mMidiVol.setOnSeekBarChangeListener(volListener);
        mFloppyEmpty.setOnSeekBarChangeListener(volListener);
        mFloppyDisk.setOnSeekBarChangeListener(volListener);

        Button btnSave = findViewById(R.id.btnSoundSave);
        Button btnBack = findViewById(R.id.btnSoundBack);
        Button btnReset = findViewById(R.id.btnSoundReset);

        btnSave.setOnClickListener(v -> {
            save();
            finish();
        });
        btnBack.setOnClickListener(v -> finish());
        btnReset.setOnClickListener(v -> {
            SharedPreferences.Editor e = prefs().edit();
            clearSoundOverrides(e);
            e.apply();
            load();
        });

        View root = findViewById(android.R.id.content);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }
}
