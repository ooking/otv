package com.xiaomitv.dtmb;

import com.xiaomitv.dtmb.TrackPreferences.AudioPreference;
import com.xiaomitv.dtmb.TrackPreferences.AudioTrack;
import com.xiaomitv.dtmb.TrackPreferences.SubtitlePreference;
import com.xiaomitv.dtmb.TrackPreferences.SubtitleTrack;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TrackPreferencesTest {

    public static void main(String[] args) {
        testLanguageNormalization();
        testAudioEncodeDecodeRoundTrip();
        testSubtitleEncodeDecodeAndLegacy();
        testAudioSameProgramReturn();
        testAudioProgramChangeMissingLanguageKeepsDefault();
        testAudioSameLanguageMultiTracks();
        testAudioAdRoleAndChannelDisambiguation();
        testAudioUnlabeledTracks();
        testSubtitleSameLanguageMultiTracks();
        testSubtitleDisabledAndMissingLanguage();
        System.out.println("所有 TrackPreferences 测试用例全部通过！");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError(message + " - 期望: " + expected + ", 实际: " + actual);
    }

    private static void assertNull(Object actual, String message) {
        if (actual != null) throw new AssertionError(message + " - 期望: null, 实际: " + actual);
    }

    private static void assertNotNull(Object actual, String message) {
        if (actual == null) throw new AssertionError(message + " - 期望非 null");
    }

    private static void testLanguageNormalization() {
        assertEquals("zh", TrackPreferences.normalizeLanguage("chi"), "chi 应归一化为 zh");
        assertEquals("zh", TrackPreferences.normalizeLanguage("ZHO"), "ZHO 应归一化为 zh");
        assertEquals("eng", TrackPreferences.normalizeLanguage("en"), "en 应归一化为 eng");
        assertEquals("", TrackPreferences.normalizeLanguage("und"), "und 应归一化为空");
        assertEquals("yue", TrackPreferences.normalizeLanguage("zh_YUE"), "zh_YUE 应归一化为 yue");
    }

    private static void testAudioEncodeDecodeRoundTrip() {
        AudioPreference original = new AudioPreference("2", "eng", "Original|50%", "204", "0:1:0", 6, 1);
        String encoded = TrackPreferences.encodeAudio(original);
        AudioPreference decoded = TrackPreferences.decodeAudio(encoded);
        assertEquals(original, decoded, "音轨偏好编解码往返应一致");
        assertNull(TrackPreferences.decodeAudio(""), "空串应解析为 null");
        assertNull(TrackPreferences.decodeAudio("v99|bad"), "未知版本应解析为 null");
    }

    private static void testSubtitleEncodeDecodeAndLegacy() {
        SubtitlePreference original = new SubtitlePreference(false, "1", "zh", "繁體|中文字幕", "1", "false", 1);
        SubtitlePreference decoded = TrackPreferences.decodeSubtitle(TrackPreferences.encodeSubtitle(original));
        assertEquals(original, decoded, "字幕偏好编解码往返应一致");

        SubtitlePreference off = TrackPreferences.decodeSubtitle(
                TrackPreferences.encodeSubtitle(SubtitlePreference.off()));
        assertNotNull(off, "关闭偏好应成功解析");
        assertEquals(true, off.disabled(), "关闭标志应为 true");

        SubtitlePreference legacyOff = TrackPreferences.fromLegacySubtitle("", "");
        assertNotNull(legacyOff, "旧版空 ID 应视为明确关闭");
        assertEquals(true, legacyOff.disabled(), "旧版空 ID disabled 应为 true");

        SubtitlePreference legacyTrack = TrackPreferences.fromLegacySubtitle("3", "chi");
        assertNotNull(legacyTrack, "旧版字幕应平滑迁移");
        assertEquals("3", legacyTrack.trackId(), "旧版 trackId 应保留");
        assertEquals("zh", legacyTrack.language(), "旧版语言应归一化");
    }

    private static void testAudioSameProgramReturn() {
        AudioTrack cantonese = new AudioTrack("0", "yue", "", "101", "0:0:0", 2);
        AudioTrack english = new AudioTrack("1", "eng", "", "102", "0:0:0", 2);
        List<AudioTrack> program = Arrays.asList(cantonese, english);

        AudioPreference pref = TrackPreferences.createAudioPreference(english, program);
        AudioTrack matched = TrackPreferences.matchAudio(pref, program);
        assertNotNull(matched, "切台回同一节目应命中");
        assertEquals("1", matched.id(), "应恢复英语音轨");
    }

    private static void testAudioProgramChangeMissingLanguageKeepsDefault() {
        AudioTrack yue = new AudioTrack("0", "yue", "", "101", "0:0:0", 2);
        AudioTrack eng = new AudioTrack("1", "eng", "", "102", "0:0:0", 2);
        AudioPreference pref = TrackPreferences.createAudioPreference(eng, Arrays.asList(yue, eng));

        // 换到下一个节目：只有粤语和国语，没有英语
        AudioTrack cmn = new AudioTrack("1", "cmn", "", "103", "0:0:0", 2);
        assertNull(TrackPreferences.matchAudio(pref, Arrays.asList(yue, cmn)),
                "新节目不含偏好语言时不得误切到其他语言");

        // 再换回带英语的节目（即使 trackId 和 PID 都变了）
        AudioTrack engReordered = new AudioTrack("2", "en", "", "205", "0:0:0", 2);
        AudioTrack matched = TrackPreferences.matchAudio(pref, Arrays.asList(yue, cmn, engReordered));
        assertNotNull(matched, "英语再次出现时应自动切回");
        assertEquals("2", matched.id(), "应按语言匹配到新的英语轨道");
    }

    private static void testAudioSameLanguageMultiTracks() {
        // 典型 DTMB：粤语与国语都标为 chi
        AudioTrack track0 = new AudioTrack("0", "chi", "", "301", "0:0:0", 2);
        AudioTrack track1 = new AudioTrack("1", "chi", "", "302", "0:0:0", 2);
        AudioPreference pref = TrackPreferences.createAudioPreference(track1, Arrays.asList(track0, track1));

        // 换到单音轨节目：不应切换
        assertNull(TrackPreferences.matchAudio(pref, Collections.singletonList(track0)),
                "单音轨节目应保持默认");

        // 再换到双语节目（即使 PID 和 ID 变了，且标成 zho）
        AudioTrack next0 = new AudioTrack("10", "zho", "", "401", "0:0:0", 2);
        AudioTrack next1 = new AudioTrack("11", "zho", "", "402", "0:0:0", 2);
        AudioTrack matched = TrackPreferences.matchAudio(pref, Arrays.asList(next0, next1));
        assertNotNull(matched, "同语言多轨应按 sameLangOrdinal 恢复");
        assertEquals("11", matched.id(), "应选中第 2 条中文音轨");
    }

    private static void testAudioAdRoleAndChannelDisambiguation() {
        AudioTrack mainStereo = new AudioTrack("0", "chi", "", "501", "0:0:0", 2);
        AudioTrack mainSurround = new AudioTrack("1", "chi", "", "502", "0:0:0", 6);
        AudioTrack adTrack = new AudioTrack("2", "chi", "", "503", "3:2:1", 2);

        // 用户选了 5.1 环绕声主音轨
        AudioPreference surroundPref = TrackPreferences.createAudioPreference(
                mainSurround, Arrays.asList(mainStereo, mainSurround, adTrack));

        // 换节目后 PID 变了且顺序重排
        AudioTrack newStereo = new AudioTrack("0", "chi", "", "601", "0:0:0", 2);
        AudioTrack newAd = new AudioTrack("1", "chi", "", "602", "3:2:1", 2);
        AudioTrack newSurround = new AudioTrack("2", "chi", "", "603", "0:0:0", 6);

        AudioTrack matched = TrackPreferences.matchAudio(
                surroundPref, Arrays.asList(newStereo, newAd, newSurround));
        assertNotNull(matched, "应优先通过角色与声道数消歧");
        assertEquals("2", matched.id(), "应命中新的 5.1 主音轨而非 AD 轨");
    }

    private static void testAudioUnlabeledTracks() {
        AudioTrack t0 = new AudioTrack("0", "und", "", "", "", 2);
        AudioTrack t1 = new AudioTrack("1", "und", "", "", "", 2);
        AudioPreference pref = TrackPreferences.createAudioPreference(t1, Arrays.asList(t0, t1));

        AudioTrack n0 = new AudioTrack("5", "", "", "", "", 2);
        AudioTrack n1 = new AudioTrack("6", "und", "", "", "", 2);
        AudioTrack matched = TrackPreferences.matchAudio(pref, Arrays.asList(n0, n1));
        assertNotNull(matched, "无语言标签多轨应按顺序兜底");
        assertEquals("6", matched.id(), "应命中第 2 条无语言轨");
    }

    private static void testSubtitleSameLanguageMultiTracks() {
        // 两条同为 chi 的字幕（例如繁体与简体）
        SubtitleTrack trad = new SubtitleTrack("0", "chi", "", "1", "false");
        SubtitleTrack simp = new SubtitleTrack("1", "chi", "", "1", "false");
        SubtitlePreference pref = TrackPreferences.createSubtitlePreference(simp, Arrays.asList(trad, simp));

        // 换节目后 trackId 变化但仍为两条中文
        SubtitleTrack nextTrad = new SubtitleTrack("4", "zho", "", "1", "false");
        SubtitleTrack nextSimp = new SubtitleTrack("5", "zho", "", "1", "false");
        SubtitleTrack matched = TrackPreferences.matchSubtitle(pref, Arrays.asList(nextTrad, nextSimp));
        assertNotNull(matched, "同语言多字幕轨不应因歧义放弃");
        assertEquals("5", matched.id(), "应按 sameLangOrdinal 恢复第 2 条中文字幕");
    }

    private static void testSubtitleDisabledAndMissingLanguage() {
        SubtitleTrack eng = new SubtitleTrack("0", "eng", "", "1", "false");
        assertNull(TrackPreferences.matchSubtitle(SubtitlePreference.off(), Collections.singletonList(eng)),
                "明确关闭字幕时不应匹配任何字幕轨");

        SubtitleTrack zh = new SubtitleTrack("1", "chi", "", "1", "false");
        SubtitlePreference engPref = TrackPreferences.createSubtitlePreference(eng, Arrays.asList(eng, zh));
        assertNull(TrackPreferences.matchSubtitle(engPref, Collections.singletonList(zh)),
                "新节目无英语字幕时不应误开中文字幕");
    }
}
