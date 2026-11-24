package com.xiaomitv.dtmb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xiaomitv.dtmb.TvListings.Channel;
import com.xiaomitv.dtmb.TvListings.Program;

import java.text.DateFormat;
import java.util.Date;

final class ProgramOverlay extends LinearLayout {
    private final Activity activity;
    private final Handler handler;
    private final TvStyle style;
    private final DateFormat time;
    private final TextView channelBadge;
    private final TextView title;
    private final TextView metadata;
    private final ProgressBar progress;
    private final TextView badges;
    private final TextView synopsis;
    private final TextView footer;
    private final Runnable dismiss = this::close;
    private Channel channel;
    private Program program;
    private boolean temporary;
    private boolean switching;

    ProgramOverlay(Activity activity, Handler handler, TvStyle style) {
        super(activity);
        this.activity = activity;
        this.handler = handler;
        this.style = style;
        time = android.text.format.DateFormat.getTimeFormat(activity);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(style.dp(24), style.dp(16), style.dp(24), style.dp(16));
        setBackground(topGlassSurface());
        setVisibility(GONE);

        // 1. 左侧：台标/频道号高对比度徽章 (图 2 风格)
        channelBadge = style.text(18, 0xff000000, true);
        channelBadge.setBackground(style.surface(0xffffffff, 6, Color.TRANSPARENT));
        channelBadge.setPadding(style.dp(10), style.dp(4), style.dp(10), style.dp(4));
        channelBadge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.rightMargin = style.dp(16);
        addView(channelBadge, badgeParams);

        // 2. 中间：节目大标题、副标题与进度条
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(VERTICAL);
        addView(body, new LinearLayout.LayoutParams(0, -2, 1));

        title = style.text(24, TvStyle.TEXT, true);
        style.trim(title, 1);
        body.addView(title);

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, style.dp(4), 0, 0);
        body.addView(meta);

        metadata = style.text(14, TvStyle.MUTED, true);
        style.trim(metadata, 1);
        meta.addView(metadata, new LinearLayout.LayoutParams(-2, -2));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(0xff3b82f6)); // 蓝色进度条
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x33ffffff));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(style.dp(96), style.dp(4));
        progressParams.leftMargin = style.dp(14);
        meta.addView(progress, progressParams);

        badges = style.text(12, 0xffffffff, true);
        style.trim(badges, 1);
        badges.setPadding(style.dp(6), style.dp(2), style.dp(6), style.dp(2));
        badges.setBackground(style.surface(TvStyle.PILL_DEFAULT, 6, 0x44ffffff));
        LinearLayout.LayoutParams badgesParam = new LinearLayout.LayoutParams(-2, -2);
        badgesParam.leftMargin = style.dp(10);
        meta.addView(badges, badgesParam);

        synopsis = style.text(14, 0xffa0a4b5, false);
        synopsis.setPadding(0, style.dp(6), 0, 0);
        style.trim(synopsis, 2);
        body.addView(synopsis);

        footer = style.text(13, TvStyle.MUTED, true);
        footer.setPadding(0, style.dp(4), 0, 0);
        style.trim(footer, 1);
        body.addView(footer);
    }

    private GradientDrawable topGlassSurface() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xf5161820, 0xeb101115});
        drawable.setCornerRadii(new float[]{
                0, 0,
                0, 0,
                style.dp(20), style.dp(20),
                style.dp(20), style.dp(20)
        });
        drawable.setStroke(style.dp(1), 0x33ffffff);
        return drawable;
    }

    void show(Channel channel, Program program, Program next, String labels,
              boolean temporary, boolean switching) {
        this.temporary = temporary;
        this.switching = switching;
        update(channel, program, next, labels);
        setVisibility(VISIBLE);
        renewTimeout();
    }

    void update(Channel channel, Program program, Program next, String labels) {
        this.channel = channel;
        this.program = program;

        String numberStr = channel.number().isEmpty()
                ? String.valueOf(channel.id())
                : channel.number();
        channelBadge.setText(numberStr);

        title.setText(program == null ? channel.name() : program.title());
        metadata.setText(program == null ? channel.name()
                : channel.name() + "   " + time.format(new Date(program.start()))
                + " ~ " + time.format(new Date(program.end())));

        progress.setVisibility(program == null ? GONE : VISIBLE);
        if (program != null) {
            long elapsed = System.currentTimeMillis() - program.start();
            progress.setProgress((int) Math.max(0, Math.min(1000,
                    elapsed * 1000.0 / (program.end() - program.start()))));
        }

        badges.setText(labels);
        badges.setVisibility(labels.isEmpty() ? GONE : VISIBLE);

        String description = program == null ? "" : program.description();
        synopsis.setText(description);
        synopsis.setVisibility(description.isEmpty() ? GONE : VISIBLE);

        String nextLabel = next == null ? "" : activity.getString(R.string.overlay_next_program, next.title());
        String action = switching ? activity.getString(R.string.overlay_tuning)
                : description.isEmpty() ? "" : activity.getString(R.string.overlay_more_info);
        footer.setText(nextLabel.isEmpty() || action.isEmpty() ? action + nextLabel : action + "   |   " + nextLabel);
        footer.setVisibility(footer.length() == 0 ? GONE : VISIBLE);
    }

    void openDetails() {
        if (!switching && channel != null && program != null && !program.description().isEmpty()) {
            handler.removeCallbacks(dismiss);
            showDetails(channel, program);
        }
    }

    void showDetails(Channel channel, Program program) {
        DateFormat date = android.text.format.DateFormat.getMediumDateFormat(activity);
        Date start = new Date(program.start());
        Date end = new Date(program.end());
        String startDay = date.format(start);
        String endDay = date.format(end);
        String when = startDay + "  " + time.format(start) + " ~ "
                + (endDay.equals(startDay) ? "" : endDay + "  ") + time.format(end);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(true);
        TextView content = style.text(18, TvStyle.TEXT, false);
        int spacing = style.dp(24);
        content.setPadding(spacing, style.dp(12), spacing, style.dp(16));
        String description = program.description();
        content.setText(channel.label() + "\n" + when
                + (description.isEmpty() ? "" : "\n\n" + description));
        scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle(program.title()).setView(scroll)
                .setPositiveButton(R.string.action_back, null).setOnDismissListener(dialog -> renewTimeout()).show();
    }

    void close() {
        handler.removeCallbacks(dismiss);
        setVisibility(GONE);
        channel = null;
        program = null;
    }

    private void renewTimeout() {
        handler.removeCallbacks(dismiss);
        if (temporary && !switching && getVisibility() == VISIBLE) handler.postDelayed(dismiss, 3_500);
    }
}
