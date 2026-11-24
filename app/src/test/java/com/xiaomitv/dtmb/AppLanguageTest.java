package com.xiaomitv.dtmb;

import java.util.Locale;

public final class AppLanguageTest {

    public static void main(String[] args) {
        testResolveSupportedTag();
        testNormalizeOption();
        testNativeDisplayName();
        System.out.println("所有 AppLanguage 测试用例全部通过！");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError(message + " - 期望: " + expected + ", 实际: " + actual);
    }

    private static void testResolveSupportedTag() {
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.resolveSupportedTag(Locale.SIMPLIFIED_CHINESE),
                "zh_CN 应映射为简体中文");
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.resolveSupportedTag(Locale.forLanguageTag("zh-SG")),
                "zh-SG 应映射为简体中文");
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.resolveSupportedTag(Locale.forLanguageTag("zh-Hans-HK")),
                "显式 Hans 脚本应映射为简体中文");
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.resolveSupportedTag(Locale.forLanguageTag("zh")),
                "裸 zh 应映射为简体中文");

        assertEquals(AppLanguage.ZH_HANT, AppLanguage.resolveSupportedTag(Locale.TRADITIONAL_CHINESE),
                "zh_TW 应映射为繁體中文");
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.resolveSupportedTag(Locale.forLanguageTag("zh-HK")),
                "zh-HK 应映射为繁體中文");
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.resolveSupportedTag(Locale.forLanguageTag("zh-MO")),
                "zh-MO 应映射为繁體中文");
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.resolveSupportedTag(Locale.forLanguageTag("zh-Hant-CN")),
                "显式 Hant 脚本应映射为繁體中文");

        assertEquals(AppLanguage.EN, AppLanguage.resolveSupportedTag(Locale.US),
                "en_US 应映射为英文");
        assertEquals(AppLanguage.EN, AppLanguage.resolveSupportedTag(Locale.JAPAN),
                "未支持语种应回退为英文");
        assertEquals(AppLanguage.EN, AppLanguage.resolveSupportedTag(null),
                "null Locale 应回退为英文");
    }

    private static void testNormalizeOption() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalizeOption(null), "null 应归一化为跟随系统");
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalizeOption(""), "空串应归一化为跟随系统");
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalizeOption("   "), "空白串应归一化为跟随系统");
        assertEquals(AppLanguage.ZH_HANS, AppLanguage.normalizeOption("zh_CN"), "zh_CN 应归一化为 zh-Hans");
        assertEquals(AppLanguage.ZH_HANT, AppLanguage.normalizeOption("zh-HK"), "zh-HK 应归一化为 zh-Hant");
        assertEquals(AppLanguage.EN, AppLanguage.normalizeOption("en-GB"), "en-GB 应归一化为 en");
    }

    private static void testNativeDisplayName() {
        assertEquals("简体中文", AppLanguage.nativeDisplayName(AppLanguage.ZH_HANS), "简体母语名应正确");
        assertEquals("繁體中文", AppLanguage.nativeDisplayName(AppLanguage.ZH_HANT), "繁体母语名应正确");
        assertEquals("English", AppLanguage.nativeDisplayName(AppLanguage.EN), "英文母语名应正确");
    }
}
