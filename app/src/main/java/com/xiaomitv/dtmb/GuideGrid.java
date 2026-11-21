package com.xiaomitv.dtmb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xiaomitv.dtmb.GuideTimeline.Slot;
import com.xiaomitv.dtmb.TvListings.Channel;
import com.xiaomitv.dtmb.TvListings.Program;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static com.xiaomitv.dtmb.GuideTimeline.HOUR;
import static com.xiaomitv.dtmb.GuideTimeline.TICK;
import static com.xiaomitv.dtmb.GuideTimeline.WINDOW;

final class GuideGrid extends LinearLayout {
    private static final String NO_PROGRAM = "暂无节目资料";

    private final TvStyle style;
    private final TvScreen.Actions actions;
    private final DateFormat clockFormat = new SimpleDateFormat("yyyy/MM/dd(E) HH:mm", Locale.getDefault());
    private final DateFormat dateFormat = new SimpleDateFormat("M/dd (E)", Locale.getDefault());
    private final DateFormat hourFormat = new SimpleDateFormat("HH時", Locale.getDefault());
    private final DateFormat timeFormat;

    // 顶部 Header 控件
    private final TextView groupPill;
    private final TextView dateSwitcher;
    private final TextView heroTitle;
    private final TextView heroMeta;
    private final TextView heroLiveTag;
    private final TextView heroSynopsis;
    private final TextView clockView;

    // 中间节目表矩阵
    private final MatrixView matrix;

    // 底部状态栏
    private final TextView bottomChannelLabel;
    private final TextView bottomHint;

    private List<Channel> channels = Collections.emptyList();
    private Function<Channel, List<Program>> schedules = channel -> Collections.emptyList();
    private long playingId;
    private int selectedChannelIndex = 0;
    private long cursor;
    private long windowStart;
    private long earliest;
    private long latest;
    private long now;

    // 矩阵绘制参数
    private final int rulerWidth;
    private final int columnWidth;
    private final int headerHeight;
    private final int hourHeight;

    GuideGrid(Context context, TvStyle style, TvScreen.Actions actions) {
        super(context);
        this.style = style;
        this.actions = actions;
        setOrientation(VERTICAL);
        setBackgroundColor(TvStyle.BG_DARK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        timeFormat = android.text.format.DateFormat.getTimeFormat(context);

        rulerWidth = style.dp(52);
        columnWidth = style.dp(168);
        headerHeight = style.dp(44);
        hourHeight = style.dp(150);

        // 1. 构建顶部 Header 区域（图 3 风格）
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setPadding(style.dp(20), style.dp(14), style.dp(20), style.dp(12));
        header.setBackgroundColor(0xf5171920);

        // 左侧：分类胶囊与日期切换
        LinearLayout leftGroup = new LinearLayout(context);
        leftGroup.setOrientation(VERTICAL);
        leftGroup.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        groupPill = style.text(13, TvStyle.TEXT, true);
        groupPill.setText("全部频道");
        groupPill.setPadding(style.dp(12), style.dp(5), style.dp(12), style.dp(5));
        groupPill.setBackground(style.surface(TvStyle.PILL_DEFAULT, 12, TvStyle.TILE_BORDER));
        leftGroup.addView(groupPill);

        dateSwitcher = style.text(14, TvStyle.MUTED, true);
        dateSwitcher.setPadding(0, style.dp(6), 0, 0);
        leftGroup.addView(dateSwitcher);
        header.addView(leftGroup, new LinearLayout.LayoutParams(style.dp(130), -2));

        // 中间：当前选中节目的详细看板 (Hero Details)
        LinearLayout centerHero = new LinearLayout(context);
        centerHero.setOrientation(VERTICAL);
        centerHero.setPadding(style.dp(16), 0, style.dp(16), 0);

        heroTitle = style.text(22, TvStyle.TEXT, true);
        style.trim(heroTitle, 1);
        centerHero.addView(heroTitle);

        LinearLayout metaRow = new LinearLayout(context);
        metaRow.setOrientation(HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, style.dp(4), 0, style.dp(4));

        heroMeta = style.text(14, TvStyle.MUTED, true);
        metaRow.addView(heroMeta);

        heroLiveTag = style.text(12, Color.WHITE, true);
        heroLiveTag.setText("放送中");
        heroLiveTag.setPadding(style.dp(8), style.dp(2), style.dp(8), style.dp(2));
        heroLiveTag.setBackground(style.surface(TvStyle.RED_LIVE, 6, Color.TRANSPARENT));
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-2, -2);
        tagParams.leftMargin = style.dp(10);
        metaRow.addView(heroLiveTag, tagParams);
        centerHero.addView(metaRow);

        heroSynopsis = style.text(14, 0xffa0a4b5, false);
        style.trim(heroSynopsis, 2);
        centerHero.addView(heroSynopsis);
        header.addView(centerHero, new LinearLayout.LayoutParams(0, -2, 1f));

        // 右侧：金色标牌与系统时钟
        LinearLayout rightGroup = new LinearLayout(context);
        rightGroup.setOrientation(VERTICAL);
        rightGroup.setGravity(Gravity.END | Gravity.TOP);

        TextView brand = style.text(18, TvStyle.GOLD_GUIDE, true);
        brand.setText("DTMB GUIDE");
        rightGroup.addView(brand);

        clockView = style.text(13, TvStyle.MUTED, false);
        clockView.setPadding(0, style.dp(4), 0, 0);
        rightGroup.addView(clockView);
        header.addView(rightGroup, new LinearLayout.LayoutParams(style.dp(180), -2));

        addView(header, new LayoutParams(-1, -2));

        // 2. 中间：矩阵视图
        matrix = new MatrixView(context);
        addView(matrix, new LayoutParams(-1, 0, 1f));

        // 3. 底部：状态与操作提示栏
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(style.dp(20), style.dp(9), style.dp(20), style.dp(9));
        footer.setBackgroundColor(0xff121318);

        bottomChannelLabel = style.text(13, TvStyle.MUTED, false);
        bottomChannelLabel.setVisibility(GONE); // 移除冗余重复的频道名，保持界面清爽

        bottomHint = style.text(13, 0xffa0a4b5, false);
        bottomHint.setText("← / → 切换频道   ·   ↑ / ↓ 浏览时段   ·   OK 观看/详情   ·   返回 退出");
        footer.addView(bottomHint);
        addView(footer, new LayoutParams(-1, -2));
    }

    void show(List<Channel> channels, long playingId, Function<Channel, List<Program>> schedules) {
        now = System.currentTimeMillis();
        cursor = now;
        windowStart = currentHour();
        earliest = windowStart - 2 * HOUR;
        this.channels = Collections.emptyList();
        update(channels, playingId, schedules);
        requestFocus();
    }

    void update(List<Channel> channels, long playingId, Function<Channel, List<Program>> schedules) {
        long selectedId = this.channels.isEmpty() ? playingId : this.channels.get(selectedChannelIndex).id();
        this.channels = channels;
        this.playingId = playingId;
        this.schedules = schedules;
        now = System.currentTimeMillis();

        if (channels.isEmpty()) {
            renderHeader();
            matrix.invalidate();
            return;
        }

        latest = windowStart + WINDOW;
        for (Channel channel : channels) {
            List<Program> schedule = schedules.apply(channel);
            if (!schedule.isEmpty()) {
                long end = schedule.get(schedule.size() - 1).end();
                latest = Math.max(latest, end);
            }
        }

        cursor = Math.max(earliest, Math.min(cursor, latest - 1));
        int index = channelIndex(selectedId);
        selectedChannelIndex = index >= 0 ? index : Math.max(0, channelIndex(playingId));

        renderHeader();
        matrix.ensureVisible();
        matrix.invalidate();
    }

    void restoreFocus() {
        requestFocus();
        matrix.ensureVisible();
        matrix.invalidate();
    }

    boolean handleKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveVertical(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveHorizontal(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    activate();
                    return true;
                }
            }
        }
        return false;
    }

    private void moveVertical(int direction) {
        if (channels.isEmpty()) return;
        Channel channel = channels.get(selectedChannelIndex);
        Slot slot = slotAt(selectedChannelIndex, cursor);
        if (direction > 0) {
            cursor = slot.program() == null ? Math.min(cursor + TICK, slot.end()) : slot.end();
        } else {
            cursor = slot.program() == null ? Math.max(cursor - TICK, slot.start() - 1) : slot.start() - 1;
        }
        cursor = Math.max(earliest, Math.min(cursor, latest - 1));
        while (cursor < windowStart) windowStart -= HOUR;
        while (cursor >= windowStart + WINDOW) windowStart += HOUR;

        renderHeader();
        matrix.ensureVisible();
        matrix.invalidate();
    }

    private void moveHorizontal(int direction) {
        if (channels.isEmpty()) return;
        selectedChannelIndex = (selectedChannelIndex + direction + channels.size()) % channels.size();
        renderHeader();
        matrix.ensureVisible();
        matrix.invalidate();
    }

    private void activate() {
        if (channels.isEmpty()) return;
        Channel channel = channels.get(selectedChannelIndex);
        Slot slot = slotAt(selectedChannelIndex, cursor);
        Program program = slot.program();
        long currentTime = System.currentTimeMillis();
        if (program != null && program.start() <= currentTime && program.end() > currentTime) {
            actions.select(channel);
        } else if (program != null) {
            actions.programDetails(channel, program);
        } else {
            actions.select(channel);
        }
    }

    private void renderHeader() {
        if (channels.isEmpty()) {
            heroTitle.setText(NO_PROGRAM);
            heroMeta.setText("");
            heroLiveTag.setVisibility(GONE);
            heroSynopsis.setText("");
            bottomChannelLabel.setText("");
            return;
        }

        Channel channel = channels.get(selectedChannelIndex);
        Slot slot = slotAt(selectedChannelIndex, cursor);
        Program program = slot.program();

        dateSwitcher.setText("◀ " + dateFormat.format(new Date(cursor)) + " ▶");
        clockView.setText(clockFormat.format(new Date(System.currentTimeMillis())));

        if (program != null) {
            heroTitle.setText(program.title());
            long minutes = Math.max(1, (program.end() - program.start()) / 60000L);
            String timeSpan = channel.name() + "   " + timeFormat.format(new Date(program.start())) + " ~ "
                    + timeFormat.format(new Date(program.end())) + " (" + minutes + "分钟)";
            heroMeta.setText(timeSpan);
            boolean isLive = now >= program.start() && now < program.end();
            heroLiveTag.setVisibility(isLive ? VISIBLE : GONE);
            String desc = program.description();
            heroSynopsis.setText(TextUtils.isEmpty(desc) ? "暂无详细内容介绍。" : desc);
        } else {
            heroTitle.setText(NO_PROGRAM);
            heroMeta.setText(channel.name() + "   " + timeFormat.format(new Date(slot.start())) + " ~ "
                    + timeFormat.format(new Date(slot.end())));
            heroLiveTag.setVisibility(GONE);
            heroSynopsis.setText("此时间段暂未获取到电台的节目单广播或网络 EPG 资料。");
        }

        String statusStr = (playingId == channel.id() ? "正在播出  " : "") + channel.label();
        bottomChannelLabel.setText(statusStr);
    }

    private Slot slotAt(int channelIndex, long time) {
        return GuideTimeline.slotAt(schedules.apply(channels.get(channelIndex)), time, earliest, latest);
    }

    private int channelIndex(long id) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id() == id) return i;
        }
        return -1;
    }

    private long currentHour() {
        ZonedDateTime local = ZonedDateTime.now();
        return local.withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli();
    }

    // 绘制矩阵的主体 View
    private final class MatrixView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private int scrollX = 0;
        private int scrollY = 0;

        MatrixView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void ensureVisible() {
            if (channels.isEmpty()) return;
            // 确保横向可见
            int targetX = selectedChannelIndex * columnWidth;
            int viewWidth = getWidth() - rulerWidth;
            if (viewWidth > 0) {
                if (targetX < scrollX) {
                    scrollX = targetX;
                } else if (targetX + columnWidth > scrollX + viewWidth) {
                    scrollX = targetX + columnWidth - viewWidth;
                }
            }

            // 确保纵向可见
            float relativeTime = (float) (cursor - windowStart) / HOUR;
            int targetY = Math.round(relativeTime * hourHeight);
            int viewHeight = getHeight() - headerHeight;
            if (viewHeight > 0) {
                if (targetY < scrollY) {
                    scrollY = Math.max(0, targetY - style.dp(20));
                } else if (targetY + style.dp(60) > scrollY + viewHeight) {
                    scrollY = targetY + style.dp(60) - viewHeight;
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (channels.isEmpty() || w <= 0 || h <= 0) return;

            int saveCount = canvas.save();

            // 1. 绘制主体内容区域（受滚动影响）
            canvas.save();
            canvas.clipRect(rulerWidth, headerHeight, w, h);
            canvas.translate(rulerWidth - scrollX, headerHeight - scrollY);

            // 绘制横向网格分割线（整点分割线）
            int hoursCount = (int) (WINDOW / HOUR) + 4;
            paint.setColor(0xff222530);
            paint.setStrokeWidth(style.dp(1));
            for (int i = -2; i <= hoursCount; i++) {
                float y = i * hourHeight;
                canvas.drawLine(0, y, channels.size() * columnWidth, y, paint);
            }

            // 绘制各频道的节目卡片
            for (int c = 0; c < channels.size(); c++) {
                int colLeft = c * columnWidth;
                int colRight = colLeft + columnWidth;
                // 仅绘制视口内列
                if (colRight < scrollX || colLeft > scrollX + (w - rulerWidth)) continue;

                Channel channel = channels.get(c);
                List<Slot> slots = GuideTimeline.slotsForWindow(schedules.apply(channel),
                        windowStart - 2 * HOUR, windowStart + WINDOW + 2 * HOUR);

                for (Slot slot : slots) {
                    float topY = (float) (slot.start() - windowStart) / HOUR * hourHeight;
                    float bottomY = (float) (slot.end() - windowStart) / HOUR * hourHeight;
                    if (bottomY < scrollY || topY > scrollY + (h - headerHeight)) continue;

                    boolean isFocused = (c == selectedChannelIndex && cursor >= slot.start() && cursor < slot.end());
                    drawProgramCell(canvas, slot, colLeft, colRight, topY, bottomY, isFocused);
                }

                // 列间垂直分割线
                paint.setColor(0xff222530);
                canvas.drawLine(colRight, -2 * hourHeight, colRight, (hoursCount + 2) * hourHeight, paint);
            }
            canvas.restore();

            // 2. 绘制固定左侧时间标尺（只受纵向滚动影响，突出当前时间对应小时）
            canvas.save();
            canvas.clipRect(0, headerHeight, rulerWidth, h);
            paint.setColor(0xff14161d);
            canvas.drawRect(0, headerHeight, rulerWidth, h, paint);

            canvas.translate(0, headerHeight - scrollY);
            for (int i = -2; i <= hoursCount; i++) {
                long t = windowStart + i * HOUR;
                float y = i * hourHeight;
                boolean isCurrentHour = (now >= t && now < t + HOUR);

                if (isCurrentHour) {
                    // 突出高亮当前时间所在小时：高光药丸底色 + 深黑加粗文字
                    float badgeMargin = style.dp(4);
                    rect.set(badgeMargin, y + style.dp(4), rulerWidth - badgeMargin, y + style.dp(30));
                    paint.setColor(0xffffffff);
                    canvas.drawRoundRect(rect, style.dp(13), style.dp(13), paint);

                    textPaint.setColor(0xff121316);
                    textPaint.setTextSize(style.dp(12));
                    textPaint.setTypeface(Typeface.create("sans-serif-bold", Typeface.BOLD));
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(hourFormat.format(new Date(t)), rulerWidth / 2f, y + style.dp(22), textPaint);
                    textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                } else {
                    // 普通时间刻度文本
                    textPaint.setColor(TvStyle.MUTED);
                    textPaint.setTextSize(style.dp(12));
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(hourFormat.format(new Date(t)), rulerWidth / 2f, y + style.dp(20), textPaint);
                }

                // 刻度线
                paint.setColor(0xff2a2d3a);
                paint.setStrokeWidth(style.dp(1));
                canvas.drawLine(0, y, rulerWidth, y, paint);
            }
            canvas.restore();

            // 3. 绘制固定上方频道列表头（只受横向滚动影响）
            canvas.save();
            canvas.clipRect(rulerWidth, 0, w, headerHeight);
            paint.setColor(0xff181a22);
            canvas.drawRect(rulerWidth, 0, w, headerHeight, paint);

            canvas.translate(rulerWidth - scrollX, 0);
            for (int c = 0; c < channels.size(); c++) {
                int colLeft = c * columnWidth;
                int colRight = colLeft + columnWidth;
                if (colRight < scrollX || colLeft > scrollX + (w - rulerWidth)) continue;

                boolean colSelected = (c == selectedChannelIndex);
                drawChannelHeader(canvas, channels.get(c), colLeft, colRight, colSelected);
            }
            canvas.restore();

            // 4. 左上角交叉空白块
            paint.setColor(0xff14161d);
            canvas.drawRect(0, 0, rulerWidth, headerHeight, paint);
            textPaint.setColor(TvStyle.MUTED);
            textPaint.setTextSize(style.dp(12));
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("时间", rulerWidth / 2f, headerHeight / 2f + style.dp(4), textPaint);

            // 边框分割线
            paint.setColor(0xff282b36);
            paint.setStrokeWidth(style.dp(1));
            canvas.drawLine(0, headerHeight, w, headerHeight, paint);
            canvas.drawLine(rulerWidth, 0, rulerWidth, h, paint);

            canvas.restoreToCount(saveCount);
        }

        private void drawChannelHeader(Canvas canvas, Channel channel, float left, float right, boolean selected) {
            float pad = style.dp(4);
            rect.set(left + pad, pad, right - pad, headerHeight - pad);

            // 背景
            paint.setColor(selected ? 0xff282b36 : 0xff1e2029);
            canvas.drawRoundRect(rect, style.dp(6), style.dp(6), paint);

            // 频道号小徽标
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(selected ? TvStyle.FOCUS : TvStyle.MUTED);
            textPaint.setTextSize(style.dp(11));
            String num = channel.number().isEmpty() ? String.valueOf(channel.id()) : channel.number();
            canvas.drawText(num, left + style.dp(10), headerHeight / 2f + style.dp(4), textPaint);

            // 频道名称
            textPaint.setColor(selected ? TvStyle.FOCUS : TvStyle.TEXT);
            textPaint.setTextSize(style.dp(13));
            float textLeft = left + style.dp(36);
            float maxTextWidth = right - textLeft - style.dp(8);
            if (maxTextWidth > 0) {
                String clipped = TextUtils.ellipsize(channel.name(), textPaint, maxTextWidth,
                        TextUtils.TruncateAt.END).toString();
                canvas.drawText(clipped, textLeft, headerHeight / 2f + style.dp(4), textPaint);
            }
        }

        private void drawProgramCell(Canvas canvas, Slot slot, float left, float right,
                                     float top, float bottom, boolean isFocused) {
            float margin = style.dp(1.5f);
            rect.set(left + margin, top + margin, right - margin, bottom - margin);

            if (rect.height() < style.dp(6)) return;

            // 背景绘制
            if (isFocused) {
                // 聚焦态：高亮白底或纯白外边框高亮
                paint.setColor(0xffffffff);
                canvas.drawRoundRect(rect, style.dp(6), style.dp(6), paint);
            } else {
                paint.setColor(slot.program() == null ? 0xff16171e : 0xff1f212a);
                canvas.drawRoundRect(rect, style.dp(4), style.dp(4), paint);
            }

            // 绘制内容：左侧起始分钟、标题、角标
            float contentLeft = rect.left + style.dp(6);
            float contentRight = rect.right - style.dp(6);
            float currentY = rect.top + style.dp(14);

            if (currentY > rect.bottom - style.dp(4)) return;

            // 起始分钟标记
            ZonedDateTime startDt = ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(slot.start()), java.time.ZoneId.systemDefault());
            String minuteStr = String.format(Locale.getDefault(), "%02d", startDt.getMinute());

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(isFocused ? 0xff000000 : TvStyle.MUTED);
            textPaint.setTextSize(style.dp(11));
            canvas.drawText(minuteStr, contentLeft, currentY, textPaint);

            // 标题
            float titleLeft = contentLeft + style.dp(18);
            float titleWidth = contentRight - titleLeft;
            if (titleWidth > 0) {
                textPaint.setColor(isFocused ? 0xff000000 : TvStyle.TEXT);
                textPaint.setTextSize(style.dp(13));
                String title = slot.program() == null ? NO_PROGRAM : slot.program().title();
                String clipped = TextUtils.ellipsize(title, textPaint, titleWidth,
                        TextUtils.TruncateAt.END).toString();
                canvas.drawText(clipped, titleLeft, currentY, textPaint);
            }

            // 如果高度足够，多行展示简介或状态标签
            if (rect.height() >= style.dp(44)) {
                currentY += style.dp(16);
                if (currentY <= rect.bottom - style.dp(6)) {
                    boolean isLive = now >= slot.start() && now < slot.end();
                    if (isLive) {
                        textPaint.setColor(isFocused ? 0xffc62828 : TvStyle.RED_LIVE);
                        textPaint.setTextSize(style.dp(10));
                        canvas.drawText("[放送中]", contentLeft, currentY, textPaint);
                    }
                }
            }
        }
    }
}
