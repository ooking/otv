package com.xiaomitv.dtmb;

import com.xiaomitv.dtmb.TvListings.Program;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class GuideTimeline {
    static final long HOUR = TimeUnit.HOURS.toMillis(1);
    static final long TICK = TimeUnit.MINUTES.toMillis(30);
    // 每次展示 4 个小时的时段窗口，支持跨天和跨时段切换
    static final long WINDOW = 4 * HOUR;

    record Slot(long start, long end, Program program) {}

    private GuideTimeline() {}

    // 获取在特定时间点包含的 Slot
    static Slot slotAt(List<Program> schedule, long time, long start, long end) {
        int left = firstEndingAfter(schedule, time);
        Program next = left < schedule.size() ? schedule.get(left) : null;
        if (next != null && next.start() <= time) {
            return new Slot(Math.max(start, next.start()), Math.min(end, next.end()), next);
        }
        long gapStart = left == 0 ? start : Math.max(start, schedule.get(left - 1).end());
        long gapEnd = next == null ? end : Math.min(end, next.start());
        return new Slot(gapStart, gapEnd, null);
    }

    // 获取指定时间窗口 [windowStart, windowEnd] 内该频道的所有 Slot 序列（包含空白 Slot）
    static List<Slot> slotsForWindow(List<Program> schedule, long windowStart, long windowEnd) {
        List<Slot> slots = new ArrayList<>();
        long time = windowStart;
        while (time < windowEnd) {
            Slot slot = slotAt(schedule, time, windowStart, windowEnd);
            long slotEnd = Math.min(slot.end(), windowEnd);
            if (slotEnd <= time) {
                slotEnd = Math.min(time + TICK, windowEnd);
            }
            slots.add(new Slot(time, slotEnd, slot.program()));
            time = slotEnd;
        }
        return slots;
    }

    static Program nextProgram(List<Program> schedule, long time, long end) {
        int index = firstEndingAfter(schedule, time);
        if (index < schedule.size() && schedule.get(index).start() <= time) index++;
        if (index == schedule.size()) return null;
        Program next = schedule.get(index);
        return next.start() < end ? next : null;
    }

    private static int firstEndingAfter(List<Program> schedule, long time) {
        int left = 0;
        int right = schedule.size();
        while (left < right) {
            int middle = (left + right) >>> 1;
            if (schedule.get(middle).end() <= time) {
                left = middle + 1;
            } else {
                right = middle;
            }
        }
        return left;
    }
}
