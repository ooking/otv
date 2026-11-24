package com.xiaomitv.dtmb;

import android.content.Context;
import android.media.tv.TvTrackInfo;

import java.util.List;
import java.util.Locale;

final class TrackLabels {
    private TrackLabels() {}

    static String label(Context context, TvTrackInfo track, int index) {
        String name = trackName(context, track);
        String label = context.getString(R.string.track_audio_label, index + 1);
        if (!name.isEmpty()) label += " · " + name;
        int channels = track.getAudioChannelCount();
        if (channels > 0) label += " · " + context.getString(R.string.track_channels, channels);
        return label;
    }

    static String subtitleLabel(Context context, TvTrackInfo track, int index) {
        String name = trackName(context, track);
        String label = context.getString(R.string.track_subtitle_label, index + 1);
        return name.isEmpty() ? label : label + " · " + name;
    }

    static int selectedIndex(List<TvTrackInfo> tracks, String selectedId) {
        if (selectedId == null) return -1;
        for (int index = 0; index < tracks.size(); index++) {
            if (selectedId.equals(tracks.get(index).getId())) return index;
        }
        return -1;
    }

    static String trackName(Context context, TvTrackInfo track) {
        String language = language(context, track.getLanguage());
        CharSequence rawDescription = track.getDescription();
        String description = rawDescription == null ? "" : rawDescription.toString().trim();
        String name = description.isEmpty() ? language : description;
        if (!description.isEmpty() && !language.isEmpty() && !description.equalsIgnoreCase(language)) {
            name += " · " + language;
        }
        return name;
    }

    private static String language(Context context, String value) {
        if (value == null) return "";
        String tag = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (tag.isEmpty() || "und".equals(tag)) return "";
        if ("chi".equals(tag) || "zho".equals(tag) || "zh".equals(tag)) {
            return context.getString(R.string.lang_chinese);
        }
        if ("chs".equals(tag) || "zh-hans".equals(tag)) {
            return context.getString(R.string.lang_zh_hans);
        }
        if ("cht".equals(tag) || "zh-hant".equals(tag)) {
            return context.getString(R.string.lang_zh_hant);
        }
        if ("eng".equals(tag) || "en".equals(tag)) {
            return context.getString(R.string.lang_english);
        }
        if ("yue".equals(tag) || "zh-yue".equals(tag)) {
            return context.getString(R.string.lang_cantonese);
        }
        if ("cmn".equals(tag) || "zh-cmn".equals(tag)) {
            return context.getString(R.string.lang_mandarin);
        }
        return Locale.forLanguageTag(tag).getDisplayLanguage(AppLanguage.activeLocale(context));
    }
}
