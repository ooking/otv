package com.xiaomitv.dtmb;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * 读取当前安装包版本号与构建类型（Release / Debug），零外部编译期依赖。
 */
final class AppVersion {
    private AppVersion() {}

    static boolean isDebug(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    static String versionTag(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (info.versionName != null && !info.versionName.isEmpty()) {
                return "v" + info.versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 理论上当前自身包名始终可读，回退到默认标识
        }
        return "v1.0.0";
    }

    static String buildType(Context context) {
        return isDebug(context) ? "Debug" : "Release";
    }

    static String badgeText(Context context) {
        return versionTag(context) + " · " + buildType(context);
    }
}
