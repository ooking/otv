package com.xiaomitv.dtmb;

import android.content.Context;
import android.media.tv.TvTrackInfo;
import android.os.Bundle;

import java.util.List;
import java.util.Locale;

final class TrackLabels {
    private TrackLabels() {}

    static String label(Context context, TvTrackInfo track, int index) {
        StringBuilder label = new StringBuilder(context.getString(R.string.track_audio_label, index + 1));
        appendPart(label, trackName(context, track));
        appendPart(label, audioRole(context, track.getExtra()));
        appendPart(label, audioFormat(context, track));
        appendPart(label, audioCodec(track.getExtra()));
        return label.toString();
    }

    static String subtitleLabel(Context context, TvTrackInfo track, int index) {
        StringBuilder label = new StringBuilder(context.getString(R.string.track_subtitle_label, index + 1));
        appendPart(label, trackName(context, track));
        if ("true".equalsIgnoreCase(extraString(track.getExtra(), "key_HearingImpaired"))) {
            appendPart(label, context.getString(R.string.track_role_hi));
        }
        return label.toString();
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

    private static String audioRole(Context context, Bundle extra) {
        if (extra == null) return "";
        int editorial = extraInt(extra, "key_AudioEditorialClass");
        int audioType = extraInt(extra, "key_AudioType");
        int mixedAd = extraInt(extra, "key_AudioIsMixedWithAD");
        if (mixedAd == 1 || editorial == 2 || editorial == 4 || editorial == 5 || audioType == 3) {
            return context.getString(R.string.track_role_ad);
        }
        if (editorial == 3 || audioType == 2) {
            return context.getString(R.string.track_role_hi);
        }
        if (audioType == 8) {
            return context.getString(R.string.track_role_commentary);
        }
        return "";
    }

    private static String audioFormat(Context context, TvTrackInfo track) {
        Bundle extra = track.getExtra();
        int format = extraInt(extra, "key_AudioFormt");
        if (format > 0) {
            return switch (format) {
                case 1 -> context.getString(R.string.track_format_mono);
                case 2 -> context.getString(R.string.track_format_dual_mono);
                case 3 -> context.getString(R.string.track_format_stereo);
                case 4 -> context.getString(R.string.track_format_surround);
                default -> "";
            };
        }
        // 非 MTK 固件缺少 key_AudioFormt 时，仅对标准声道数兜底，避免展示固件内部枚举值
        if (extra == null) {
            int channels = track.getAudioChannelCount();
            if (channels == 1) return context.getString(R.string.track_format_mono);
            if (channels == 2) return context.getString(R.string.track_format_stereo);
            if (channels == 6) return context.getString(R.string.track_format_surround);
            if (channels > 0) return context.getString(R.string.track_channels, channels);
        }
        return "";
    }

    private static String audioCodec(Bundle extra) {
        if (extra == null) return "";
        if (extraInt(extra, "key_AudioAtmos") == 1) return "Dolby Atmos";
        int encodeType = extraInt(extra, "key_AudioEncodeType");
        return switch (encodeType) {
            case 1 -> "AC-3";
            case 2, 3 -> "MPEG";
            case 6 -> "DTS";
            case 7 -> "AAC";
            case 12 -> "E-AC-3";
            case 16 -> "DRA";
            case 25 -> "Opus";
            case 26 -> "AC-4";
            case 27 -> "MPEG-H";
            default -> "";
        };
    }

    private static void appendPart(StringBuilder builder, String part) {
        if (part != null && !part.isEmpty()) {
            builder.append(" · ").append(part);
        }
    }

    private static String extraString(Bundle extra, String key) {
        if (extra == null) return "";
        String value = extra.getString(key);
        return value == null ? "" : value.trim();
    }

    private static int extraInt(Bundle extra, String key) {
        String value = extraString(extra, key);
        if (value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
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
        if ("jpn".equals(tag) || "ja".equals(tag)) {
            return context.getString(R.string.lang_japanese);
        }
        if ("kor".equals(tag) || "ko".equals(tag)) {
            return context.getString(R.string.lang_korean);
        }
        if ("qaa".equals(tag)) {
            return context.getString(R.string.lang_original);
        }
        if ("mul".equals(tag)) {
            return context.getString(R.string.lang_multiple);
        }
        return Locale.forLanguageTag(tag).getDisplayLanguage(AppLanguage.activeLocale(context));
    }
}
