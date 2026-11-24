package com.xiaomitv.dtmb;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 应用多语言管理：支持跟随电视系统语言，以及手动指定简体中文、繁體中文、English。
 * Android 13+ 同步系统 LocaleManager，Android 8-12 通过 Context 包装实现无依赖兼容。
 */
final class AppLanguage {
    static final String PREFS_NAME = "live_tv";
    static final String KEY_LANGUAGE = "app_language";

    static final String SYSTEM = "";
    static final String ZH_HANS = "zh-Hans";
    static final String ZH_HANT = "zh-Hant";
    static final String EN = "en";

    static final String[] OPTIONS = {SYSTEM, ZH_HANS, ZH_HANT, EN};

    private AppLanguage() {}

    /**
     * 在 Activity.attachBaseContext 中调用，为 Android 8-12 注入手动选择的语言配置。
     */
    static Context wrap(Context base) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return base;
        }
        String tag = readPrefTag(base);
        if (tag.isEmpty()) return base;

        Locale locale = Locale.forLanguageTag(tag);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));
        return base.createConfigurationContext(config);
    }

    /**
     * 获取当前已保存的语言选项（"" 表示跟随系统）。
     */
    static String savedTag(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) {
                LocaleList locales = manager.getApplicationLocales();
                if (locales.isEmpty()) return SYSTEM;
                return resolveSupportedTag(locales.get(0));
            }
        }
        return readPrefTag(context);
    }

    /**
     * 应用并持久化语言选择，必要时重建 Activity 立即刷新界面。
     */
    static void apply(Activity activity, String rawTag) {
        String tag = normalizeOption(rawTag);
        String current = savedTag(activity);
        if (tag.equals(current)) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, tag).apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = activity.getSystemService(LocaleManager.class);
            if (manager != null) {
                LocaleList target = tag.isEmpty()
                        ? LocaleList.getEmptyLocaleList()
                        : LocaleList.forLanguageTags(tag);
                manager.setApplicationLocales(target);
                return;
            }
        }
        activity.recreate();
    }

    /**
     * 返回当前生效的 Locale，供日期格式和轨道语言显示使用。
     */
    static Locale activeLocale(Context context) {
        LocaleList locales = context.getResources().getConfiguration().getLocales();
        return locales.isEmpty() ? Locale.getDefault() : locales.get(0);
    }

    /**
     * 返回用于设置项副标题的当前语言说明。
     */
    static String summary(Context context) {
        String saved = savedTag(context);
        if (saved.isEmpty()) {
            String resolved = resolveSupportedTag(systemLocale());
            return context.getString(R.string.language_summary_system, nativeDisplayName(resolved));
        }
        return nativeDisplayName(saved);
    }

    /**
     * 返回指定语言选项在弹窗中的展示名称（具体语言固定使用母语名，方便误切时找回）。
     */
    static String optionLabel(Context context, String tag) {
        if (SYSTEM.equals(tag)) {
            return context.getString(R.string.language_follow_system);
        }
        return nativeDisplayName(tag);
    }

    /**
     * 返回具体语言对应的母语名称。
     */
    static String nativeDisplayName(String tag) {
        return switch (tag) {
            case ZH_HANS -> "简体中文";
            case ZH_HANT -> "繁體中文";
            default -> "English";
        };
    }

    /**
     * 将存储或输入的语言标识归一化为受支持的选项值（"" / "zh-Hans" / "zh-Hant" / "en"）。
     */
    static String normalizeOption(String raw) {
        if (raw == null) return SYSTEM;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return SYSTEM;
        return resolveSupportedTag(Locale.forLanguageTag(trimmed.replace('_', '-')));
    }

    /**
     * 将任意 Locale 映射为本应用实际支持的三种语言之一。
     */
    static String resolveSupportedTag(Locale locale) {
        if (locale == null) return EN;
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        if (!"zh".equals(language)) return EN;

        String script = locale.getScript();
        if ("Hant".equalsIgnoreCase(script)) return ZH_HANT;
        if ("Hans".equalsIgnoreCase(script)) return ZH_HANS;

        String country = locale.getCountry().toUpperCase(Locale.ROOT);
        if ("TW".equals(country) || "HK".equals(country) || "MO".equals(country)) {
            return ZH_HANT;
        }
        return ZH_HANS;
    }

    private static String readPrefTag(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeOption(prefs.getString(KEY_LANGUAGE, SYSTEM));
    }

    private static Locale systemLocale() {
        LocaleList locales = Resources.getSystem().getConfiguration().getLocales();
        return locales.isEmpty() ? Locale.getDefault() : locales.get(0);
    }
}
