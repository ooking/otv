package com.xiaomitv.dtmb;

import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

final class XiaomiTvAudio {
    private static final String TAG = "DtmbPlayback";
    private static final String TV_MUTE = "TV-Mute";

    private final AudioManager audio;
    private boolean enabled;
    private boolean unmuteRequested;
    private String lastRequest = "尚未发送";

    XiaomiTvAudio(AudioManager audio) {
        this.audio = audio;
    }

    boolean supported() {
        return "xiaomi".equalsIgnoreCase(Build.MANUFACTURER)
                && Build.MODEL.startsWith("MiTV-");
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
            lastRequest = "系统拒绝 " + parameter;
            Log.w(TAG, "xiaomi_audio rejected " + parameter, exception);
        }
    }

    String diagnostics() {
        return "小米音频兼容  " + (enabled ? "已开启" : "已关闭")
                + "\n最近的厂商音频请求  " + lastRequest;
    }
}
