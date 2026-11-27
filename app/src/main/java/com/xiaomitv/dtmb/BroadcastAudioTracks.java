package com.xiaomitv.dtmb;

import android.content.SharedPreferences;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.os.Bundle;
import android.util.Log;

import com.xiaomitv.dtmb.TrackPreferences.AudioPreference;
import com.xiaomitv.dtmb.TrackPreferences.AudioTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * 按频道独立记忆与自动恢复广播音轨。
 * 结合轨道快照签名与有限重试，兼顾切台恢复与节目切换时的自动追随。
 */
final class BroadcastAudioTracks {
    private static final int MAX_ATTEMPTS_PER_SNAPSHOT = 2;

    private final TvView view;
    private final SharedPreferences preferences;
    private long channelId = -1;
    private String lastSignature = "";
    private int attemptsForSnapshot;

    BroadcastAudioTracks(TvView view, SharedPreferences preferences) {
        this.view = view;
        this.preferences = preferences;
    }

    void reset(long channel) {
        channelId = channel;
        lastSignature = "";
        attemptsForSnapshot = 0;
    }

    /**
     * 协调当前音轨与频道偏好：轨道列表变化（切台或换节目）时会重新匹配。
     */
    void reconcile(List<TvTrackInfo> rawTracks, String selectedTrackId) {
        if (channelId == -1 || rawTracks == null || rawTracks.isEmpty()) return;
        AudioPreference pref = loadPreference();
        if (pref == null) return;

        List<AudioTrack> tracks = toAudioTracks(rawTracks);
        String signature = TrackPreferences.audioSnapshotSignature(tracks);
        if (!signature.equals(lastSignature)) {
            lastSignature = signature;
            attemptsForSnapshot = 0;
        }

        AudioTrack match = TrackPreferences.matchAudio(pref, tracks);
        if (match == null) return;
        if (match.id().equals(selectedTrackId)) {
            return;
        }
        if (attemptsForSnapshot >= MAX_ATTEMPTS_PER_SNAPSHOT) return;

        if (apply(match.id())) {
            attemptsForSnapshot++;
            Log.i(Playback.TAG, "audio_track_restored channel=" + channelId
                    + " track=" + match.id() + " lang=" + match.language()
                    + " attempt=" + attemptsForSnapshot);
        }
    }

    /**
     * 用户手动选择音轨时调用：执行切换并持久化该频道的音轨指纹。
     */
    boolean select(long expectedChannel, String trackId, List<TvTrackInfo> rawTracks, String selectedTrackId) {
        if (channelId == -1 || expectedChannel != channelId || trackId == null || rawTracks == null) {
            return false;
        }
        List<AudioTrack> tracks = toAudioTracks(rawTracks);
        AudioTrack target = null;
        for (AudioTrack track : tracks) {
            if (trackId.equals(track.id())) {
                target = track;
                break;
            }
        }
        if (target == null) return false;

        if (!trackId.equals(selectedTrackId) && !apply(trackId)) {
            return false;
        }

        AudioPreference pref = TrackPreferences.createAudioPreference(target, tracks);
        preferences.edit().putString(prefKey(), TrackPreferences.encodeAudio(pref)).apply();
        lastSignature = TrackPreferences.audioSnapshotSignature(tracks);
        attemptsForSnapshot = MAX_ATTEMPTS_PER_SNAPSHOT;
        Log.i(Playback.TAG, "audio_track_saved channel=" + channelId
                + " track=" + target.id() + " lang=" + target.language()
                + " pid=" + target.pid() + " ordinal=" + pref.sameLangOrdinal());
        return true;
    }

    private boolean apply(String trackId) {
        try {
            view.selectTrack(TvTrackInfo.TYPE_AUDIO, trackId);
            Log.i(Playback.TAG, "audio_track_requested channel=" + channelId + " track=" + trackId);
            return true;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException exception) {
            Log.w(Playback.TAG, "Audio track is no longer available", exception);
            return false;
        }
    }

    private AudioPreference loadPreference() {
        return TrackPreferences.decodeAudio(preferences.getString(prefKey(), null));
    }

    private String prefKey() {
        return "audio_pref_" + channelId;
    }

    private static List<AudioTrack> toAudioTracks(List<TvTrackInfo> rawTracks) {
        List<AudioTrack> result = new ArrayList<>(rawTracks.size());
        for (TvTrackInfo track : rawTracks) {
            Bundle extra = track.getExtra();
            String pid = extraString(extra, "key_AudioPid");
            String role = buildRole(extra);
            CharSequence rawDesc = track.getDescription();
            String desc = rawDesc == null ? "" : rawDesc.toString();
            result.add(new AudioTrack(
                    track.getId(),
                    track.getLanguage(),
                    desc,
                    pid,
                    role,
                    track.getAudioChannelCount()
            ));
        }
        return result;
    }

    private static String buildRole(Bundle extra) {
        if (extra == null) return "";
        String audioType = extraString(extra, "key_AudioType");
        String editorial = extraString(extra, "key_AudioEditorialClass");
        String mixedAd = extraString(extra, "key_AudioIsMixedWithAD");
        if (audioType.isEmpty() && editorial.isEmpty() && mixedAd.isEmpty()) return "";
        return audioType + ":" + editorial + ":" + mixedAd;
    }

    private static String extraString(Bundle extra, String key) {
        if (extra == null) return "";
        String value = extra.getString(key);
        return value == null ? "" : value.trim();
    }
}
