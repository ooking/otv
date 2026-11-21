package com.xiaomitv.dtmb;

import android.media.tv.TvTrackInfo;

import java.util.Locale;

final class TrackLabels {
    private TrackLabels() {}

    static String label(TvTrackInfo track, int index) {
        String name = trackName(track);
        String label = "音轨 " + (index + 1);
        if (!name.isEmpty()) label += " · " + name;
        int channels = track.getAudioChannelCount();
        if (channels > 0) label += " · " + channels + " 声道";
        return label;
    }

    static String trackName(TvTrackInfo track) {
        String language = language(track.getLanguage());
        CharSequence rawDescription = track.getDescription();
        String description = rawDescription == null ? "" : rawDescription.toString().trim();
        String name = description.isEmpty() ? language : description;
        if (!description.isEmpty() && !language.isEmpty() && !description.equalsIgnoreCase(language)) {
            name += " · " + language;
        }
        return name;
    }

    private static String language(String value) {
        if (value == null) return "";
        String tag = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (tag.isEmpty() || "und".equals(tag)) return "";
        if ("chi".equals(tag) || "zho".equals(tag) || "zh".equals(tag)) return "中文";
        if ("chs".equals(tag) || "zh-hans".equals(tag)) return "简体中文";
        if ("cht".equals(tag) || "zh-hant".equals(tag)) return "繁體中文";
        if ("eng".equals(tag) || "en".equals(tag)) return "英语";
        if ("yue".equals(tag) || "zh-yue".equals(tag)) return "粤语";
        if ("cmn".equals(tag) || "zh-cmn".equals(tag)) return "普通话";
        return Locale.forLanguageTag(tag).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
    }
}
