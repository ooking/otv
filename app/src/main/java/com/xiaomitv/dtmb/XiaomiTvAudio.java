package com.xiaomitv.dtmb;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

final class XiaomiTvAudio {
    private static final String TAG = "DtmbPlayback";
    private static final String TV_MUTE = "TV-Mute";

    private final Context context;
    private final AudioManager audio;
    private boolean enabled;
    private boolean unmuteRequested;
    private String lastRequest;

    XiaomiTvAudio(Context context, AudioManager audio) {
        this.context = context;
        this.audio = audio;
        this.lastRequest = context.getString(R.string.diag_not_sent);
    }

    // 兼容小米与 Redmi 全系电视机型
    boolean supported() {
        return isXiaomiFamily(Build.MANUFACTURER)
                || isXiaomiFamily(Build.BRAND)
                || (Build.MODEL != null && Build.MODEL.startsWith("MiTV-"));
    }

    private static boolean isXiaomiFamily(String value) {
        return "xiaomi".equalsIgnoreCase(value) || "redmi".equalsIgnoreCase(value);
    }

    boolean enabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled && supported();
    }

    void sync(boolean playingWithFocus, boolean retry) {
        if (!enabled && !unmuteRequested) return;
        boolean unmute = enabled && playingWithFocus
                && !audio.isStreamMute(AudioManager.STREAM_MUSIC)
                && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0;
        if (unmute == unmuteRequested && !(unmute && retry)) return;

        String parameter = TV_MUTE + (unmute ? "=0" : "=1");
        try {
            audio.setParameters(parameter);
            unmuteRequested = unmute;
            lastRequest = parameter;
            Log.i(TAG, "xiaomi_audio request=" + parameter);
        } catch (SecurityException | IllegalArgumentException exception) {
            lastRequest = context.getString(R.string.diag_rejected, parameter);
            Log.w(TAG, "xiaomi_audio rejected " + parameter, exception);
        }
    }

    String diagnostics() {
        String state = context.getString(enabled ? R.string.diag_enabled : R.string.diag_disabled);
        return context.getString(R.string.diag_xiaomi_audio, state, lastRequest);
    }
}
