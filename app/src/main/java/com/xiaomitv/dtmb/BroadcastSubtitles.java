package com.xiaomitv.dtmb;

import android.content.SharedPreferences;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.util.Log;

import java.util.List;
import java.util.Locale;

final class BroadcastSubtitles {
    private final TvView view;
    private final SharedPreferences preferences;
    private long channelId = -1;
    private boolean applied;
    private boolean clearedMissingTrack;

    BroadcastSubtitles(TvView view, SharedPreferences preferences) {
        this.view = view;
        this.preferences = preferences;
    }

    void reset(long channel) {
        channelId = channel;
        applied = false;
        clearedMissingTrack = false;
    }

    void restore(List<TvTrackInfo> tracks) {
        if (applied || channelId == -1) return;
        String id = preferences.getString(idKey(), null);
        if (id == null) {
            applied = true;
            return;
        }
        if (id.isEmpty()) {
            applied = apply(null);
            return;
        }
        String language = preferences.getString(languageKey(), "");
        TvTrackInfo sameLanguage = null;
        boolean multipleMatches = false;
        for (TvTrackInfo track : tracks) {
            if (!language.equals(language(track))) continue;
            if (id.equals(track.getId())) {
                applied = apply(id);
                return;
            }
            if (!language.isEmpty()) {
                multipleMatches |= sameLanguage != null;
                sameLanguage = track;
            }
        }
        if (sameLanguage != null && !multipleMatches) {
            applied = apply(sameLanguage.getId());
        } else if (!clearedMissingTrack) {
            clearedMissingTrack = apply(null);
        }
    }

    boolean select(long expectedChannel, TvTrackInfo track, List<TvTrackInfo> tracks) {
        if (channelId == -1 || expectedChannel != channelId) return false;
        if (track != null && tracks.stream().noneMatch(candidate ->
                track.getId().equals(candidate.getId()) && language(track).equals(language(candidate)))) {
            return false;
        }
        String id = track == null ? "" : track.getId();
        if (!apply(track == null ? null : id)) return false;
        applied = true;
        preferences.edit().putString(idKey(), id)
                .putString(languageKey(), track == null ? "" : language(track)).apply();
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

    private String idKey() {
        return "subtitle_id_" + channelId;
    }

    private String languageKey() {
        return "subtitle_language_" + channelId;
    }

    private static String language(TvTrackInfo track) {
        String language = track.getLanguage();
        return language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    }
}
