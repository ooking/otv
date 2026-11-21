package com.xiaomitv.dtmb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
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
    private final TextView number;
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
        setGravity(Gravity.TOP);
        setPadding(style.dp(25), style.dp(20), style.dp(25), style.dp(20));
        setBackground(style.glassSurface());
        setVisibility(GONE);

        number = style.text(52, TvStyle.TEXT, false);
        number.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        number.setIncludeFontPadding(false);
        style.trim(number, 1);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(-2, -2);
        numberParams.rightMargin = style.dp(22);
        number.setMaxWidth(style.dp(108));
        addView(number, numberParams);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(VERTICAL);
        addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        title = style.text(30, TvStyle.TEXT, true);
        style.trim(title, 2);
        body.addView(title);

        LinearLayout meta = new LinearLayout(activity);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, style.dp(4), 0, 0);
        body.addView(meta);
        metadata = style.text(17, TvStyle.MUTED, true);
        style.trim(metadata, 1);
        metadata.setMaxWidth(style.dp(activity.getResources().getConfiguration().screenWidthDp * 0.43f));
        meta.addView(metadata, new LinearLayout.LayoutParams(-2, -2));
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(TvStyle.FOCUS));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(TvStyle.EDGE));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(style.dp(86), style.dp(5));
        progressParams.leftMargin = style.dp(14);
        meta.addView(progress, progressParams);
        badges = style.text(13, TvStyle.TEXT, true);
        style.trim(badges, 1);
        badges.setPadding(style.dp(10), style.dp(4), style.dp(10), style.dp(4));
        badges.setBackground(style.surface(TvStyle.GAP, 10, TvStyle.EDGE));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.leftMargin = style.dp(12);
        meta.addView(badges, badgeParams);

        synopsis = style.text(18, TvStyle.MUTED, false);
        synopsis.setPadding(0, style.dp(8), 0, 0);
        style.trim(synopsis, 2);
        body.addView(synopsis);
        footer = style.text(15, TvStyle.TEXT, true);
        footer.setPadding(0, style.dp(8), 0, 0);
        style.trim(footer, 2);
        body.addView(footer);
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
        number.setText(channel.number());
        number.setVisibility(channel.number().isEmpty() ? GONE : VISIBLE);
        title.setText(program == null ? channel.name() : program.title());
        metadata.setText(program == null ? "暂无当前节目资料"
                : channel.name() + "   " + time.format(new Date(program.start()))
                        + "–" + time.format(new Date(program.end())));
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

        String nextLabel = next == null ? "" : "接下来  " + next.title();
        String action = switching ? "正在切换频道…" : description.isEmpty() ? "" : "←  节目介绍";
        footer.setText(nextLabel.isEmpty() || action.isEmpty() ? action + nextLabel : action + "     " + nextLabel);
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
        String when = startDay + "  " + time.format(start) + "–"
                + (endDay.equals(startDay) ? "" : endDay + "  ") + time.format(end);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(true);
        TextView content = style.text(19, TvStyle.TEXT, false);
        int spacing = style.dp(24);
        content.setPadding(spacing, style.dp(8), spacing, style.dp(14));
        String description = program.description();
        content.setText(channel.label() + "\n" + when
                + (description.isEmpty() ? "" : "\n\n" + description));
        scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle(program.title()).setView(scroll)
                .setPositiveButton("返回", null).setOnDismissListener(dialog -> renewTimeout()).show();
    }

    void close() {
        handler.removeCallbacks(dismiss);
        setVisibility(GONE);
        channel = null;
        program = null;
    }

    private void renewTimeout() {
        handler.removeCallbacks(dismiss);
        if (temporary && !switching && getVisibility() == VISIBLE) handler.postDelayed(dismiss, 3_000);
    }
}
