package com.xiaomitv.dtmb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

final class XiaomiAvSettings {
    private static final String PACKAGE = "com.xiaomi.mitv.settings";
    private static final ComponentName SERVICE = new ComponentName(PACKAGE, PACKAGE + ".TvSettingService");
    private final Activity activity;
    private final Runnable onOpened;

    XiaomiAvSettings(Activity activity, Runnable onOpened) {
        this.activity = activity;
        this.onOpened = onOpened;
    }

    boolean available() {
        ResolveInfo resolved = activity.getPackageManager().resolveService(
                new Intent().setComponent(SERVICE), 0);
        if (resolved == null || resolved.serviceInfo == null) return false;
        ServiceInfo info = resolved.serviceInfo;
        return info.exported && (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (info.permission == null || activity.checkSelfPermission(info.permission)
                == PackageManager.PERMISSION_GRANTED);
    }

    void openPicture() {
        open(activity.getString(R.string.option_picture_settings), "DISPLAY_PARAMETER");
    }

    void openSound() {
        // Use the same model capability as the stock DTMB player when exposed by the firmware.
        String soundTitle = activity.getString(R.string.option_sound_settings);
        try {
            Class<?> config = Class.forName("mitv.common.ConfigurationManager");
            Object manager = config.getMethod("getInstance").invoke(null);
            String capability = (String) config.getMethod("queryTvConfigByKey", String.class)
                    .invoke(manager, "dolby_effect_supported");
            if ("YES".equalsIgnoreCase(capability) || "NO".equalsIgnoreCase(capability)) {
                open(soundTitle, "YES".equalsIgnoreCase(capability) ? "DOLBY_POPUP" : "SOUND_EFFECT_POPUP");
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Log.d("XiaomiAvSettings", "Dolby capability unavailable", exception);
        }
        new AlertDialog.Builder(activity).setTitle(soundTitle)
                .setItems(new String[]{
                        activity.getString(R.string.xiaomi_av_standard),
                        activity.getString(R.string.xiaomi_av_dolby)
                }, (dialog, index) ->
                        open(soundTitle, index == 0 ? "SOUND_EFFECT_POPUP" : "DOLBY_POPUP"))
                .setNegativeButton(R.string.action_cancel, null).show();
    }

    private void open(String title, String action) {
        Intent intent = new Intent(PACKAGE + "." + action).setComponent(SERVICE)
                .putExtra("from", "tvPlayer");
        try {
            if (activity.startService(intent) != null) {
                onOpened.run();
                return;
            }
        } catch (SecurityException | IllegalStateException exception) {
            Log.w("XiaomiAvSettings", "Cannot start " + action, exception);
        }
        new AlertDialog.Builder(activity).setTitle(title)
                .setMessage(activity.getString(R.string.xiaomi_av_unavailable, title))
                .setPositiveButton(R.string.action_close, null).show();
    }
}
