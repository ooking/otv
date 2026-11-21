package com.xiaomitv.dtmb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.xiaomitv.dtmb.GuideTimeline.Slot;
import com.xiaomitv.dtmb.TvListings.Channel;
import com.xiaomitv.dtmb.TvListings.Program;

import java.text.DateFormat;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

import static com.xiaomitv.dtmb.GuideTimeline.TICK;
import static com.xiaomitv.dtmb.GuideTimeline.WINDOW;

final class GuideGrid extends LinearLayout {
    private static final String NO_PROGRAM = "暂无节目资料";

    private final TvStyle style;
    private final TvScreen.Actions actions;
    private final ListView rows;
    private final TextView date;
    private final TextView description;
    private final View scale;
    private final Rows adapter = new Rows();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final DateFormat timeFormat;
    private final DateFormat dateFormat;
    private final int channelWidth;
    private final int rowHeight;
    private List<Channel> channels = Collections.emptyList();
    private Function<Channel, List<Program>> schedules = channel -> Collections.emptyList();
    private long playingId;
    private int selected;
    private long cursor;
    private long windowStart;
    private long earliest;
    private long latest;
    private long now;

    GuideGrid(Context context, TvStyle style, TvScreen.Actions actions) {
        super(context);
        this.style = style;
        this.actions = actions;
        channelWidth = style.dp(Math.max(128, context.getResources().getConfiguration().screenWidthDp * 0.19f));
        rowHeight = style.dp(51);
        timeFormat = android.text.format.DateFormat.getTimeFormat(context);
        dateFormat = android.text.format.DateFormat.getMediumDateFormat(context);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setOrientation(VERTICAL);
        setBackgroundColor(TvStyle.GRAPHITE);
        setPadding(style.insetX(), style.insetY(), style.insetX(), style.insetY());

        LinearLayout header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(style.title("节目指南"), new LinearLayout.LayoutParams(0, -2, 1));
        date = style.text(17, TvStyle.MUTED, true);
        header.addView(date);
        addView(header);

        description = style.text(18, TvStyle.TEXT, true);
        description.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        style.trim(description, 2);
        description.setPadding(0, style.dp(8), 0, style.dp(8));
        description.setMinHeight(style.dp(63));
        addView(description);

        scale = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                drawText(canvas, "频道", style.dp(10), baseline(getHeight()), channelWidth,
                        15, TvStyle.MUTED);
                for (int tick = 0; tick < 4; tick++) {
                    float x = timeX(windowStart + tick * TICK, getWidth());
                    long time = windowStart + tick * TICK;
                    String label = formatTime(time);
                    boolean currentTick = time <= now && now < time + TICK;
                    float availableWidth = (getWidth() - channelWidth) / 4f;
                    if (currentTick) {
                        text.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
                        float width = Math.min(availableWidth, text.measureText(label) + style.dp(20));
                        paint.setColor(TvStyle.FOCUS);
                        canvas.drawRoundRect(x, style.dp(3), x + width, getHeight() - style.dp(3),
                                style.dp(14), style.dp(14), paint);
                    }
                    drawText(canvas, label, x + style.dp(currentTick ? 10 : 7),
                            baseline(getHeight()), availableWidth - style.dp(10),
                            15, currentTick ? TvStyle.FOCUS_TEXT : TvStyle.MUTED);
                }
            }
        };
        addView(scale, new LinearLayout.LayoutParams(-1, style.dp(34)));

        rows = style.list();
        rows.setAdapter(adapter);
        rows.setOnItemClickListener((parent, view, position, id) -> {
            selected = position;
            activate();
        });
        addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView hint = style.text(14, TvStyle.MUTED, false);
        hint.setText("← / →  时间     ↑ / ↓  频道     确认  观看或查看详情     返回  关闭");
        hint.setPadding(0, style.dp(8), 0, 0);
        addView(hint);
    }

    void show(List<Channel> channels, long playingId, Function<Channel, List<Program>> schedules) {
        now = System.currentTimeMillis();
        cursor = now;
        windowStart = halfHour();
        earliest = windowStart;
        this.channels = Collections.emptyList();
        update(channels, playingId, schedules);
        restoreFocus();
    }

    void update(List<Channel> channels, long playingId, Function<Channel, List<Program>> schedules) {
        long selectedId = this.channels.isEmpty() ? playingId : this.channels.get(selected).id();
        boolean lineupChanged = !this.channels.equals(channels);
        this.channels = channels;
        this.playingId = playingId;
        this.schedules = schedules;
        now = System.currentTimeMillis();
        if (channels.isEmpty()) {
            adapter.notifyDataSetChanged();
            return;
        }

        // Keep the visible window anchored during refresh, even if a programme expires.
        latest = Math.min(now + GuideCache.WINDOW,
                Math.max(windowStart + WINDOW, halfHour() + WINDOW));
        for (Channel channel : channels) {
            List<Program> schedule = schedules.apply(channel);
            if (!schedule.isEmpty()) {
                long end = schedule.get(schedule.size() - 1).end();
                latest = Math.max(latest, Math.min(end, now + GuideCache.WINDOW));
            }
        }
        cursor = Math.max(earliest, Math.min(cursor, latest - 1));
        int index = channelIndex(selectedId);
        selected = index >= 0 ? index : Math.max(0, channelIndex(playingId));
        if (lineupChanged) adapter.notifyDataSetChanged();
        render();
        if (lineupChanged) rows.setSelection(selected);
    }

    void restoreFocus() {
        rows.requestFocus();
        invalidateRows();
    }

    boolean handleKey(KeyEvent event) {
        int key = event.getKeyCode();
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) activate();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !channels.isEmpty()) move(key);
                return true;
            }
            default -> { return false; }
        }
    }

    private void move(int key) {
        int oldSelection = selected;
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int step = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
            selected = Math.max(0, Math.min(selected + step, channels.size() - 1));
        } else {
            Slot slot = slotAt(selected, cursor);
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                long next = slot.program() == null ? Math.min(slot.end(), cursor + TICK) : slot.end();
                cursor = Math.min(latest - 1, next);
            } else {
                long previous = slot.program() == null ? Math.max(slot.start() - 1, cursor - TICK)
                        : slot.start() - 1;
                cursor = Math.max(earliest, previous);
            }
            while (cursor < windowStart) windowStart -= WINDOW;
            while (cursor >= windowStart + WINDOW) windowStart += WINDOW;
        }
        if (selected != oldSelection) revealSelectedRow();
        render();
    }

    private void revealSelectedRow() {
        int first = rows.getFirstVisiblePosition();
        int index = selected - first;
        View row = index >= 0 && index < rows.getChildCount() ? rows.getChildAt(index) : null;
        if (row == null || row.getTop() < 0 || row.getBottom() > rows.getHeight()) {
            int top = selected < first ? 0 : Math.max(0, rows.getHeight() - rowHeight);
            rows.setSelectionFromTop(selected, top);
        }
    }

    private void activate() {
        if (channels.isEmpty()) return;
        Channel channel = channels.get(selected);
        Program program = slotAt(selected, cursor).program();
        long currentTime = System.currentTimeMillis();
        if (program != null && program.start() <= currentTime && program.end() > currentTime) {
            actions.select(channel);
        } else if (program != null) {
            actions.programDetails(channel, program);
        } else {
            List<Program> schedule = schedules.apply(channel);
            if (schedule.isEmpty() || schedule.get(schedule.size() - 1).end() <= currentTime
                    || schedule.get(0).start() >= currentTime + GuideCache.WINDOW) {
                actions.select(channel);
            }
        }
    }

    private void render() {
        if (channels.isEmpty()) return;
        date.setText(dateFormat.format(new Date(cursor)));
        Channel channel = channels.get(selected);
        Program program = slotAt(selected, cursor).program();
        String detail = program == null ? NO_PROGRAM
                : formatTime(program.start()) + "–" + formatTime(program.end()) + "  " + program.title();
        description.setText(channel.label() + "  ·  " + detail);
        scale.invalidate();
        invalidateRows();
        invalidate();
    }

    private void invalidateRows() {
        int first = rows.getFirstVisiblePosition();
        for (int index = 0; index < rows.getChildCount(); index++) {
            if (rows.getChildAt(index) instanceof Row row && first + index < channels.size()) {
                row.bind(first + index);
            }
        }
    }

    private Slot slotAt(int index, long time) {
        return GuideTimeline.slotAt(schedules.apply(channels.get(index)), time, earliest, latest);
    }

    private int channelIndex(long id) {
        for (int index = 0; index < channels.size(); index++) {
            if (channels.get(index).id() == id) return index;
        }
        return -1;
    }

    private long halfHour() {
        ZonedDateTime local = ZonedDateTime.now();
        return local.withMinute(local.getMinute() / 30 * 30).withSecond(0).withNano(0)
                .toInstant().toEpochMilli();
    }

    private float timeX(long time, int width) {
        return channelWidth + (float) (time - windowStart) / WINDOW * (width - channelWidth);
    }

    private String formatTime(long time) {
        return timeFormat.format(new Date(time));
    }

    private float baseline(float height) {
        return height / 2f + style.dp(6);
    }

    private void drawText(Canvas canvas, String label, float left, float baseline,
                          float width, int size, int color) {
        if (width <= 0) return;
        text.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
        text.setColor(color);
        String clipped = TextUtils.ellipsize(label, text, width, TextUtils.TruncateAt.END).toString();
        canvas.drawText(clipped, left, baseline, text);
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return channels.size(); }
        @Override public Channel getItem(int position) { return channels.get(position); }
        @Override public long getItemId(int position) { return getItem(position).id(); }
        @Override public boolean hasStableIds() { return true; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Row row = recycled instanceof Row ? (Row) recycled : new Row();
            row.bind(position);
            return row;
        }
    }

    private final class Row extends View {
        private int index;

        Row() {
            super(GuideGrid.this.getContext());
            setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, rowHeight));
        }

        void bind(int index) {
            this.index = index;
            Channel channel = channels.get(index);
            Program program = slotAt(index, cursor).program();
            String label = channel.label() + "，" + (program == null ? NO_PROGRAM : program.title());
            if (!TextUtils.equals(label, getContentDescription())) setContentDescription(label);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (index >= channels.size() || getWidth() <= channelWidth) return;
            Channel channel = channels.get(index);
            String channelLabel = (playingId == channel.id() ? "▶  " : "") + channel.label();
            drawText(canvas, channelLabel, style.dp(10), baseline(getHeight()),
                    channelWidth - style.dp(18), 17, index == selected ? TvStyle.TEXT : TvStyle.MUTED);
            long visibleEnd = Math.min(latest, windowStart + WINDOW);
            List<Program> schedule = schedules.apply(channel);
            long time = windowStart;
            while (time < visibleEnd) {
                Slot slot = GuideTimeline.slotAt(schedule, time, earliest, latest);
                long end = Math.min(slot.end(), visibleEnd);
                float startX = timeX(time, getWidth());
                float endX = timeX(end, getWidth());
                drawSlot(canvas, slot, startX, endX, index == selected && cursor >= time && cursor < end);
                time = end;
            }
        }

        private void drawSlot(Canvas canvas, Slot slot, float left, float right, boolean focused) {
            float spacing = Math.min(style.dp(2), (right - left) / 4);
            left += spacing;
            right -= spacing;
            float top = style.dp(3);
            float bottom = getHeight() - top;
            float radius = style.dp(8);

            paint.setColor(focused ? TvStyle.FOCUS
                    : slot.program() == null ? TvStyle.GAP : TvStyle.TILE);
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
            float padding = style.dp(9);
            drawText(canvas, slot.program() == null ? NO_PROGRAM : slot.program().title(), left + padding,
                    baseline(getHeight()), right - left - 2 * padding, 16,
                    focused ? TvStyle.FOCUS_TEXT : slot.program() == null ? TvStyle.MUTED : TvStyle.TEXT);
        }
    }
}
