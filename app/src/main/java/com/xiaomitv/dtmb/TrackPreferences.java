package com.xiaomitv.dtmb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 纯 Java 的频道音轨与字幕偏好模型、序列化工具与多级匹配算法。
 * 不依赖 Android Framework，便于直接运行单元测试。
 */
final class TrackPreferences {
    private static final String VERSION = "v1";

    private TrackPreferences() {}

    record AudioTrack(
            String id,
            String language,
            String description,
            String pid,
            String role,
            int channelCount
    ) {
        AudioTrack {
            id = safe(id);
            language = normalizeLanguage(language);
            description = normalizeText(description);
            pid = safe(pid);
            role = safe(role);
            channelCount = Math.max(0, channelCount);
        }
    }

    record AudioPreference(
            String trackId,
            String language,
            String description,
            String pid,
            String role,
            int channelCount,
            int sameLangOrdinal
    ) {
        AudioPreference {
            trackId = safe(trackId);
            language = normalizeLanguage(language);
            description = normalizeText(description);
            pid = safe(pid);
            role = safe(role);
            channelCount = Math.max(0, channelCount);
            sameLangOrdinal = Math.max(0, sameLangOrdinal);
        }
    }

    record SubtitleTrack(
            String id,
            String language,
            String description,
            String subtitleType,
            String hearingImpaired
    ) {
        SubtitleTrack {
            id = safe(id);
            language = normalizeLanguage(language);
            description = normalizeText(description);
            subtitleType = safe(subtitleType);
            hearingImpaired = safe(hearingImpaired);
        }
    }

    record SubtitlePreference(
            boolean disabled,
            String trackId,
            String language,
            String description,
            String subtitleType,
            String hearingImpaired,
            int sameLangOrdinal
    ) {
        SubtitlePreference {
            trackId = safe(trackId);
            language = normalizeLanguage(language);
            description = normalizeText(description);
            subtitleType = safe(subtitleType);
            hearingImpaired = safe(hearingImpaired);
            sameLangOrdinal = Math.max(0, sameLangOrdinal);
        }

        static SubtitlePreference off() {
            return new SubtitlePreference(true, "", "", "", "", "", 0);
        }
    }

    /**
     * 归一化语言标签：将 chi/zho/zh 统一为 zh，en/eng 统一为 eng，und 视为空。
     */
    static String normalizeLanguage(String raw) {
        if (raw == null) return "";
        String tag = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (tag.isEmpty() || "und".equals(tag)) return "";
        if ("chi".equals(tag) || "zho".equals(tag) || "zh".equals(tag)) return "zh";
        if ("en".equals(tag) || "eng".equals(tag)) return "eng";
        if ("zh-yue".equals(tag)) return "yue";
        if ("zh-cmn".equals(tag)) return "cmn";
        if ("zh-hans".equals(tag)) return "chs";
        if ("zh-hant".equals(tag)) return "cht";
        return tag;
    }

    static AudioPreference createAudioPreference(AudioTrack selected, List<AudioTrack> tracks) {
        if (selected == null) return null;
        int ordinal = 0;
        if (tracks != null) {
            int seen = 0;
            for (AudioTrack track : tracks) {
                if (selected.language().equals(track.language())) {
                    if (selected.id().equals(track.id())) {
                        ordinal = seen;
                        break;
                    }
                    seen++;
                }
            }
        }
        return new AudioPreference(
                selected.id(),
                selected.language(),
                selected.description(),
                selected.pid(),
                selected.role(),
                selected.channelCount(),
                ordinal
        );
    }

    static SubtitlePreference createSubtitlePreference(SubtitleTrack selected, List<SubtitleTrack> tracks) {
        if (selected == null) return SubtitlePreference.off();
        int ordinal = 0;
        if (tracks != null) {
            int seen = 0;
            for (SubtitleTrack track : tracks) {
                if (selected.language().equals(track.language())) {
                    if (selected.id().equals(track.id())) {
                        ordinal = seen;
                        break;
                    }
                    seen++;
                }
            }
        }
        return new SubtitlePreference(
                false,
                selected.id(),
                selected.language(),
                selected.description(),
                selected.subtitleType(),
                selected.hearingImpaired(),
                ordinal
        );
    }

    /**
     * 按多级优先级匹配最佳音轨；若无合适匹配或当前仅有单音轨，返回 null（保持默认且不删偏好）。
     */
    static AudioTrack matchAudio(AudioPreference pref, List<AudioTrack> tracks) {
        if (pref == null || tracks == null || tracks.size() <= 1) return null;

        // 1. 精确锚点匹配（同节目切回或 PID 稳定的同一路音频流）
        if (!pref.pid().isEmpty()) {
            List<AudioTrack> pidMatches = new ArrayList<>();
            for (AudioTrack track : tracks) {
                if (pref.language().equals(track.language())
                        && pref.role().equals(track.role())
                        && pref.pid().equals(track.pid())) {
                    pidMatches.add(track);
                }
            }
            if (pidMatches.size() == 1) return pidMatches.get(0);
            for (AudioTrack track : pidMatches) {
                if (pref.trackId().equals(track.id())) return track;
            }
        }

        if (!pref.trackId().isEmpty()) {
            for (AudioTrack track : tracks) {
                if (pref.trackId().equals(track.id())
                        && pref.language().equals(track.language())
                        && pref.role().equals(track.role())
                        && descriptionsCompatible(pref.description(), track.description())) {
                    return track;
                }
            }
        }

        // 2. 语义匹配：语言明确非空时，只在同语言轨道内消歧，绝不跨语言乱切
        if (!pref.language().isEmpty()) {
            List<AudioTrack> sameLang = new ArrayList<>();
            for (AudioTrack track : tracks) {
                if (pref.language().equals(track.language())) {
                    sameLang.add(track);
                }
            }
            if (sameLang.isEmpty()) return null;

            List<AudioTrack> candidates = filterAudioByRole(sameLang, pref.role());
            if (candidates.size() == 1) return candidates.get(0);

            AudioTrack byDesc = uniqueAudioByDescription(candidates, pref.description());
            if (byDesc != null) return byDesc;

            AudioTrack byChannels = uniqueAudioByChannelCount(candidates, pref.channelCount());
            if (byChannels != null) return byChannels;

            if (pref.sameLangOrdinal() < sameLang.size()) {
                AudioTrack ordinalCandidate = sameLang.get(pref.sameLangOrdinal());
                if (candidates.contains(ordinalCandidate)) return ordinalCandidate;
            }
            if (pref.sameLangOrdinal() < candidates.size()) {
                return candidates.get(pref.sameLangOrdinal());
            }
            return null;
        }

        // 3. 无语言标签多轨兜底（典型如全标 und 或空的 DTMB 双语台）
        List<AudioTrack> unlabeled = new ArrayList<>();
        for (AudioTrack track : tracks) {
            if (track.language().isEmpty()) unlabeled.add(track);
        }
        if (unlabeled.isEmpty()) return null;

        AudioTrack byDesc = uniqueAudioByDescription(unlabeled, pref.description());
        if (byDesc != null) return byDesc;

        if (!pref.trackId().isEmpty()) {
            for (AudioTrack track : unlabeled) {
                if (pref.trackId().equals(track.id()) && pref.role().equals(track.role())) {
                    return track;
                }
            }
        }
        if (pref.sameLangOrdinal() < unlabeled.size()) {
            return unlabeled.get(pref.sameLangOrdinal());
        }
        return null;
    }

    /**
     * 按多级优先级匹配最佳字幕轨；偏好为关闭或无匹配时返回 null。
     */
    static SubtitleTrack matchSubtitle(SubtitlePreference pref, List<SubtitleTrack> tracks) {
        if (pref == null || pref.disabled() || tracks == null || tracks.isEmpty()) return null;

        // 1. 精确锚点匹配
        if (!pref.trackId().isEmpty()) {
            for (SubtitleTrack track : tracks) {
                if (pref.trackId().equals(track.id())
                        && pref.language().equals(track.language())
                        && pref.hearingImpaired().equals(track.hearingImpaired())
                        && pref.subtitleType().equals(track.subtitleType())
                        && descriptionsCompatible(pref.description(), track.description())) {
                    return track;
                }
            }
        }

        // 2. 同语言语义匹配（修复同语言多字幕轨直接被关闭的问题）
        if (!pref.language().isEmpty()) {
            List<SubtitleTrack> sameLang = new ArrayList<>();
            for (SubtitleTrack track : tracks) {
                if (pref.language().equals(track.language())) {
                    sameLang.add(track);
                }
            }
            if (sameLang.isEmpty()) return null;

            List<SubtitleTrack> candidates = filterSubtitleByAttrs(sameLang, pref.hearingImpaired(), pref.subtitleType());
            if (candidates.size() == 1) return candidates.get(0);

            SubtitleTrack byDesc = uniqueSubtitleByDescription(candidates, pref.description());
            if (byDesc != null) return byDesc;

            if (pref.sameLangOrdinal() < sameLang.size()) {
                SubtitleTrack ordinalCandidate = sameLang.get(pref.sameLangOrdinal());
                if (candidates.contains(ordinalCandidate)) return ordinalCandidate;
            }
            if (pref.sameLangOrdinal() < candidates.size()) {
                return candidates.get(pref.sameLangOrdinal());
            }
            return null;
        }

        // 3. 无语言字幕兜底
        List<SubtitleTrack> unlabeled = new ArrayList<>();
        for (SubtitleTrack track : tracks) {
            if (track.language().isEmpty()) unlabeled.add(track);
        }
        if (unlabeled.isEmpty()) return null;

        if (!pref.trackId().isEmpty()) {
            for (SubtitleTrack track : unlabeled) {
                if (pref.trackId().equals(track.id())) return track;
            }
        }
        SubtitleTrack byDesc = uniqueSubtitleByDescription(unlabeled, pref.description());
        if (byDesc != null) return byDesc;

        if (pref.sameLangOrdinal() < unlabeled.size()) {
            return unlabeled.get(pref.sameLangOrdinal());
        }
        return null;
    }

    static String encodeAudio(AudioPreference pref) {
        if (pref == null) return "";
        return String.join("|",
                VERSION,
                escape(pref.trackId()),
                escape(pref.language()),
                escape(pref.description()),
                escape(pref.pid()),
                escape(pref.role()),
                Integer.toString(pref.channelCount()),
                Integer.toString(pref.sameLangOrdinal())
        );
    }

    static AudioPreference decodeAudio(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 8 || !VERSION.equals(parts[0])) return null;
        return new AudioPreference(
                unescape(parts[1]),
                unescape(parts[2]),
                unescape(parts[3]),
                unescape(parts[4]),
                unescape(parts[5]),
                parseInt(parts[6]),
                parseInt(parts[7])
        );
    }

    static String encodeSubtitle(SubtitlePreference pref) {
        if (pref == null) return "";
        return String.join("|",
                VERSION,
                pref.disabled() ? "1" : "0",
                escape(pref.trackId()),
                escape(pref.language()),
                escape(pref.description()),
                escape(pref.subtitleType()),
                escape(pref.hearingImpaired()),
                Integer.toString(pref.sameLangOrdinal())
        );
    }

    static SubtitlePreference decodeSubtitle(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 8 || !VERSION.equals(parts[0])) return null;
        return new SubtitlePreference(
                "1".equals(parts[1]),
                unescape(parts[2]),
                unescape(parts[3]),
                unescape(parts[4]),
                unescape(parts[5]),
                unescape(parts[6]),
                parseInt(parts[7])
        );
    }

    /**
     * 兼容旧版仅保存 subtitle_id_<ch> 与 subtitle_language_<ch> 的数据。
     */
    static SubtitlePreference fromLegacySubtitle(String legacyId, String legacyLanguage) {
        if (legacyId == null) return null;
        if (legacyId.isEmpty()) return SubtitlePreference.off();
        return new SubtitlePreference(false, legacyId, legacyLanguage, "", "", "", 0);
    }

    static String audioSnapshotSignature(List<AudioTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AudioTrack track : tracks) {
            sb.append(escape(track.id())).append(',')
                    .append(escape(track.language())).append(',')
                    .append(escape(track.pid())).append(',')
                    .append(escape(track.role())).append(',')
                    .append(track.channelCount()).append(';');
        }
        return sb.toString();
    }

    static String subtitleSnapshotSignature(List<SubtitleTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (SubtitleTrack track : tracks) {
            sb.append(escape(track.id())).append(',')
                    .append(escape(track.language())).append(',')
                    .append(escape(track.subtitleType())).append(',')
                    .append(escape(track.hearingImpaired())).append(';');
        }
        return sb.toString();
    }

    private static List<AudioTrack> filterAudioByRole(List<AudioTrack> tracks, String role) {
        List<AudioTrack> filtered = new ArrayList<>();
        for (AudioTrack track : tracks) {
            if (role.equals(track.role())) filtered.add(track);
        }
        return filtered.isEmpty() ? tracks : filtered;
    }

    private static List<SubtitleTrack> filterSubtitleByAttrs(
            List<SubtitleTrack> tracks, String hearingImpaired, String subtitleType) {
        List<SubtitleTrack> exact = new ArrayList<>();
        for (SubtitleTrack track : tracks) {
            if (hearingImpaired.equals(track.hearingImpaired())
                    && subtitleType.equals(track.subtitleType())) {
                exact.add(track);
            }
        }
        if (!exact.isEmpty()) return exact;

        List<SubtitleTrack> byHearing = new ArrayList<>();
        for (SubtitleTrack track : tracks) {
            if (hearingImpaired.equals(track.hearingImpaired())) {
                byHearing.add(track);
            }
        }
        return byHearing.isEmpty() ? tracks : byHearing;
    }

    private static AudioTrack uniqueAudioByDescription(List<AudioTrack> tracks, String description) {
        if (description.isEmpty()) return null;
        AudioTrack match = null;
        for (AudioTrack track : tracks) {
            if (description.equalsIgnoreCase(track.description())) {
                if (match != null) return null;
                match = track;
            }
        }
        return match;
    }

    private static AudioTrack uniqueAudioByChannelCount(List<AudioTrack> tracks, int channelCount) {
        if (channelCount <= 0) return null;
        AudioTrack match = null;
        for (AudioTrack track : tracks) {
            if (track.channelCount() == channelCount) {
                if (match != null) return null;
                match = track;
            }
        }
        return match;
    }

    private static SubtitleTrack uniqueSubtitleByDescription(List<SubtitleTrack> tracks, String description) {
        if (description.isEmpty()) return null;
        SubtitleTrack match = null;
        for (SubtitleTrack track : tracks) {
            if (description.equalsIgnoreCase(track.description())) {
                if (match != null) return null;
                match = track;
            }
        }
        return match;
    }

    private static boolean descriptionsCompatible(String a, String b) {
        return a.isEmpty() || b.isEmpty() || a.equalsIgnoreCase(b);
    }

    private static String normalizeText(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("%", "%25").replace("|", "%7C");
    }

    private static String unescape(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("%7C", "|").replace("%25", "%");
    }
}
