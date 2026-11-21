package com.xiaomitv.dtmb;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Channels;
import android.media.tv.TvContract.Programs;
import android.os.CancellationSignal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TvListings {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final String INPUT_ID = "com.mediatek.tvinput/.tuner.TunerInputService/HW0";

    record Channel(long id, String number, String name) {
        String guideKey() {
            return id + ":" + name;
        }

        String label() {
            return number.isEmpty() ? name : number + "  " + name;
        }

        long sortNumber() {
            int end = 0;
            while (end < number.length() && number.charAt(end) >= '0'
                    && number.charAt(end) <= '9') {
                end++;
            }
            try {
                return end == 0 ? Long.MAX_VALUE : Long.parseLong(number.substring(0, end));
            } catch (NumberFormatException ignored) {
                return Long.MAX_VALUE;
            }
        }
    }

    record Program(String title, long start, long end) {}

    private final ContentResolver resolver;

    TvListings(ContentResolver resolver) {
        this.resolver = resolver;
    }

    List<Channel> channels(CancellationSignal signal) {
        List<Channel> dtmb = new ArrayList<>();
        List<Channel> tunerChannels = new ArrayList<>();
        String[] columns = {Channels._ID, Channels.COLUMN_DISPLAY_NUMBER,
                Channels.COLUMN_DISPLAY_NAME, Channels.COLUMN_TYPE, Channels.COLUMN_SERVICE_TYPE};
        try (Cursor cursor = resolver.query(TvContract.buildChannelsUriForInput(INPUT_ID),
                columns, null, null, null, signal)) {
            if (cursor == null) throw new IllegalStateException("TV provider unavailable");
            while (cursor.moveToNext()) {
                String service = cursor.getString(4);
                if (Channels.SERVICE_TYPE_AUDIO.equals(service)) continue;

                long id = cursor.getLong(0);
                Channel channel = new Channel(id, value(cursor.getString(1), ""),
                        value(cursor.getString(2), "频道 " + id));
                tunerChannels.add(channel);
                String type = cursor.getString(3);
                if (Channels.TYPE_DTMB.equals(type)) {
                    dtmb.add(channel);
                }
            }
        }
        List<Channel> channels = dtmb.isEmpty() ? tunerChannels : dtmb;
        channels.sort(Comparator.comparingLong(Channel::sortNumber)
                .thenComparing(Channel::number).thenComparing(Channel::name)
                .thenComparingLong(Channel::id));
        return channels;
    }

    Map<String, List<Program>> programs(List<Channel> channels, long now, long until,
                                        CancellationSignal signal) {
        Map<Long, String> keys = new HashMap<>();
        for (Channel channel : channels) keys.put(channel.id(), channel.guideKey());
        Map<String, List<Program>> programs = new HashMap<>();
        String[] columns = {Programs.COLUMN_CHANNEL_ID, Programs.COLUMN_TITLE,
                Programs.COLUMN_START_TIME_UTC_MILLIS,
                Programs.COLUMN_END_TIME_UTC_MILLIS};
        try (Cursor cursor = resolver.query(Programs.CONTENT_URI, columns, null, null, null, signal)) {
            if (cursor == null) throw new IllegalStateException("TV provider unavailable");
            while (cursor.moveToNext()) {
                signal.throwIfCanceled();
                String key = keys.get(cursor.getLong(0));
                if (key == null) continue;
                long start = cursor.getLong(2);
                long end = cursor.getLong(3);
                String title = cursor.getString(1);
                if (title != null && !title.trim().isEmpty()
                        && start < until && end > now && end > start) {
                    programs.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new Program(title.trim(), start, end));
                }
            }
        }
        return programs;
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
