package com.xiaomitv.dtmb;

import android.content.SharedPreferences;

import com.xiaomitv.dtmb.TvListings.Channel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ChannelManager {
    static final String PREF_HIDDEN_CHANNELS = "hidden_channels";
    static final String PREF_CHANNEL_ORDER = "channel_order";

    record Result(List<Channel> allSorted, List<Channel> visible) {}

    static Set<String> loadHidden(SharedPreferences prefs) {
        if (prefs == null) return new HashSet<>();
        return new HashSet<>(prefs.getStringSet(PREF_HIDDEN_CHANNELS, Collections.emptySet()));
    }

    static void saveHidden(SharedPreferences prefs, Set<String> hidden) {
        if (prefs == null) return;
        prefs.edit().putStringSet(PREF_HIDDEN_CHANNELS, new HashSet<>(hidden)).apply();
    }

    static List<Long> loadOrder(SharedPreferences prefs) {
        if (prefs == null) return Collections.emptyList();
        String raw = prefs.getString(PREF_CHANNEL_ORDER, "");
        return parseOrder(raw);
    }

    static void saveOrder(SharedPreferences prefs, List<Long> order) {
        if (prefs == null) return;
        if (order == null || order.isEmpty()) {
            prefs.edit().remove(PREF_CHANNEL_ORDER).apply();
            return;
        }
        prefs.edit().putString(PREF_CHANNEL_ORDER, serializeOrder(order)).apply();
    }

    static List<Long> parseOrder(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        String[] parts = raw.split(",");
        List<Long> order = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                order.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
            }
        }
        return Collections.unmodifiableList(order);
    }

    static String serializeOrder(List<Long> order) {
        if (order == null || order.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(order.get(i));
        }
        return builder.toString();
    }

    static Result apply(List<Channel> rawChannels, List<Long> customOrder, Set<String> hidden) {
        if (rawChannels == null || rawChannels.isEmpty()) {
            return new Result(Collections.emptyList(), Collections.emptyList());
        }

        List<Channel> allSorted;
        if (customOrder == null || customOrder.isEmpty()) {
            allSorted = new ArrayList<>(rawChannels);
        } else {
            Map<Long, Channel> channelMap = new HashMap<>(rawChannels.size());
            for (Channel c : rawChannels) {
                channelMap.put(c.id(), c);
            }

            allSorted = new ArrayList<>(rawChannels.size());
            // 先按保存的顺序添加
            for (Long id : customOrder) {
                Channel channel = channelMap.remove(id);
                if (channel != null) {
                    allSorted.add(channel);
                }
            }
            // 将剩余未在自定义排序中的频道（例如新搜出来的频道）追加到末尾
            for (Channel c : rawChannels) {
                if (channelMap.containsKey(c.id())) {
                    allSorted.add(c);
                }
            }
        }

        List<Channel> visible = new ArrayList<>(allSorted.size());
        for (Channel c : allSorted) {
            if (hidden == null || !hidden.contains(Long.toString(c.id()))) {
                visible.add(c);
            }
        }

        return new Result(Collections.unmodifiableList(allSorted), Collections.unmodifiableList(visible));
    }

    static List<Long> extractIds(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) return Collections.emptyList();
        List<Long> ids = new ArrayList<>(channels.size());
        for (Channel c : channels) {
            ids.add(c.id());
        }
        return Collections.unmodifiableList(ids);
    }
}
