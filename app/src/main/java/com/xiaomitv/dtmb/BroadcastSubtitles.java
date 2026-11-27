package com.xiaomitv.dtmb;

import android.content.SharedPreferences;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.os.Bundle;
import android.util.Log;

import com.xiaomitv.dtmb.TrackPreferences.SubtitlePreference;
import com.xiaomitv.dtmb.TrackPreferences.SubtitleTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * 按频道独立记忆与自动恢复广播字幕。
 * 支持同语言多字幕轨消歧、切台防覆盖重试与节目切换自动追随，并兼容旧版存储格式。
 */
final class BroadcastSubtitles {
    private static final int MAX_ATTEMPTS_PER_SNAPSHOT = 2;

    private final TvView view;
    private final SharedPreferences preferences;
    private long channelId = -1;
    private String lastSignature = "";
    private int attemptsForSnapshot;
    private boolean clearedMissingForSnapshot;

    BroadcastSubtitles(TvView view, SharedPreferences preferences) {
        this.view = view;
        this.preferences = preferences;
    }

    void reset(long channel) {
        channelId = channel;
        lastSignature = "";
        attemptsForSnapshot = 0;
        clearedMissingForSnapshot = false;
    }

    /**
     * 协调当前字幕状态与频道偏好：轨道列表变化（切台或换节目）时会重新匹配。
     */
    void reconcile(List<TvTrackInfo> rawTracks, String selectedTrackId) {
        if (channelId == -1) return;
        SubtitlePreference pref = loadPreference();
        if (pref == null) return;

        List<SubtitleTrack> tracks = rawTracks == null ? List.of() : toSubtitleTracks(rawTracks);
        String signature = TrackPreferences.subtitleSnapshotSignature(tracks);
        if (!signature.equals(lastSignature)) {
            lastSignature = signature;
            attemptsForSnapshot = 0;
            clearedMissingForSnapshot = false;
        }

        if (pref.disabled()) {
            boolean hasActiveSubtitle = selectedTrackId != null && !selectedTrackId.isEmpty();
            if ((hasActiveSubtitle || attemptsForSnapshot == 0)
                    && attemptsForSnapshot < MAX_ATTEMPTS_PER_SNAPSHOT) {
                if (apply(null)) attemptsForSnapshot++;
            }
            return;
        }

        if (tracks.isEmpty()) return;

        SubtitleTrack match = TrackPreferences.matchSubtitle(pref, tracks);
        if (match != null) {
            if (match.id().equals(selectedTrackId)) {
                return;
            }
            if (attemptsForSnapshot < MAX_ATTEMPTS_PER_SNAPSHOT && apply(match.id())) {
                attemptsForSnapshot++;
                Log.i(Playback.TAG, "subtitle_restored channel=" + channelId
                        + " track=" + match.id() + " lang=" + match.language()
                        + " attempt=" + attemptsForSnapshot);
            }
        } else if (!clearedMissingForSnapshot && selectedTrackId != null && !selectedTrackId.isEmpty()) {
            // 新节目无匹配字幕时关闭不匹配的字幕，但保留用户偏好不删除
            clearedMissingForSnapshot = apply(null);
        }
    }

    /**
     * 用户手动选择或关闭字幕时调用：执行切换并持久化该频道的字幕指纹。
     */
    boolean select(long expectedChannel, TvTrackInfo track, List<TvTrackInfo> rawTracks) {
        if (channelId == -1 || expectedChannel != channelId) return false;
        List<SubtitleTrack> tracks = rawTracks == null ? List.of() : toSubtitleTracks(rawTracks);
        SubtitleTrack target = null;
        if (track != null) {
            for (SubtitleTrack candidate : tracks) {
                if (track.getId().equals(candidate.id())) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) return false;
        }

        if (!apply(target == null ? null : target.id())) return false;

        SubtitlePreference pref = TrackPreferences.createSubtitlePreference(target, tracks);
        preferences.edit()
                .putString(prefKey(), TrackPreferences.encodeSubtitle(pref))
                .remove(legacyIdKey())
                .remove(legacyLanguageKey())
                .apply();
        lastSignature = TrackPreferences.subtitleSnapshotSignature(tracks);
        attemptsForSnapshot = MAX_ATTEMPTS_PER_SNAPSHOT;
        clearedMissingForSnapshot = target == null;
        Log.i(Playback.TAG, "subtitle_saved channel=" + channelId
                + " disabled=" + pref.disabled()
                + " track=" + pref.trackId()
                + " lang=" + pref.language()
                + " ordinal=" + pref.sameLangOrdinal());
        return true;
    }

    private boolean apply(String id) {
        try {
            if (id == null) {
                view.selectTrack(TvTrackInfo.TYPE_SUBTITLE, null);
                view.setCaptionEnabled(false);
            } else {
                view.setCaptionEnabled(true);
                view.selectTrack(TvTrackInfo.TYPE_SUBTITLE, id);
            }
            Log.i(Playback.TAG, "subtitle_requested channel=" + channelId + " track=" + id);
            return true;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException exception) {
            Log.w(Playback.TAG, "Subtitle request rejected", exception);
            return false;
        }
    }

    private SubtitlePreference loadPreference() {
        SubtitlePreference pref = TrackPreferences.decodeSubtitle(preferences.getString(prefKey(), null));
        if (pref != null) return pref;
        String legacyId = preferences.getString(legacyIdKey(), null);
        if (legacyId == null) return null;
        return TrackPreferences.fromLegacySubtitle(legacyId, preferences.getString(legacyLanguageKey(), ""));
    }

    private String prefKey() {
        return "subtitle_pref_" + channelId;
    }

    private String legacyIdKey() {
        return "subtitle_id_" + channelId;
    }

    private String legacyLanguageKey() {
        return "subtitle_language_" + channelId;
    }

    private static List<SubtitleTrack> toSubtitleTracks(List<TvTrackInfo> rawTracks) {
        List<SubtitleTrack> result = new ArrayList<>(rawTracks.size());
        for (TvTrackInfo track : rawTracks) {
            Bundle extra = track.getExtra();
            CharSequence rawDesc = track.getDescription();
            String desc = rawDesc == null ? "" : rawDesc.toString();
            result.add(new SubtitleTrack(
                    track.getId(),
                    track.getLanguage(),
                    desc,
                    extraString(extra, "key_SubtitleType"),
                    extraString(extra, "key_HearingImpaired")
            ));
        }
        return result;
    }

    private static String extraString(Bundle extra, String key) {
        if (extra == null) return "";
        String value = extra.getString(key);
        return value == null ? "" : value.trim();
    }
}
