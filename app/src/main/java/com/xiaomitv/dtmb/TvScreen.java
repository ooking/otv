package com.xiaomitv.dtmb;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.media.tv.TvView;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.xiaomitv.dtmb.TvListings.Channel;
import com.xiaomitv.dtmb.TvListings.Program;

import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

final class TvScreen {
    interface Actions {
        void select(Channel channel);
        void filter();
        void openGuide();
        void options();
        void programDetails(Channel channel, Program program);
    }

    record Option(String label, Runnable action) {}

    private enum Layer { CHANNELS, GUIDE, OPTIONS }

    final TvView video;

    private final Activity activity;
    private final Handler handler;
    private final TvStyle style;
    private final FrameLayout root;
    private final ChannelSidebar sidebar;
    private final GuideGrid guide;
    private final LinearLayout options;
    private final ListView optionsList;
    private final OptionsAdapter optionRows = new OptionsAdapter();
    private final LinearLayout banner;
    private final TextView bannerTitle;
    private final TextView nowLine;
    private final TextView nextLine;
    private final TextView status;
    private final Runnable hideBanner;
    private final Deque<Layer> history = new ArrayDeque<>();
    private final DateFormat time;
    private final DateFormat dateTime;
    private List<Option> optionItems = Collections.emptyList();
    private View sidebarFocus;
    private int optionSelection;
    private boolean onlyFavorites;

    TvScreen(Activity activity, Handler handler, Actions actions) {
        this.activity = activity;
        this.handler = handler;
        style = new TvStyle(activity);
        time = android.text.format.DateFormat.getTimeFormat(activity);
        dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        video = new TvView(activity);
        video.setFocusable(false);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));

        banner = style.panel(20, 13);
        bannerTitle = style.title("");
        style.trim(bannerTitle, 1);
        banner.addView(bannerTitle);
        nowLine = bannerLine();
        nextLine = bannerLine();
        FrameLayout.LayoutParams bannerParams = inset(
                new FrameLayout.LayoutParams(panelWidth(0.55f, 650), -2, Gravity.BOTTOM | Gravity.START));
        root.addView(banner, bannerParams);
        hideBanner = () -> banner.setVisibility(View.GONE);
        hideBanner.run();

        status = style.text(17, TvStyle.TEXT, true);
        status.setPadding(style.dp(16), style.dp(9), style.dp(16), style.dp(9));
        status.setGravity(Gravity.CENTER);
        status.setBackground(style.surface(TvStyle.PANEL, 14, TvStyle.EDGE));
        root.addView(status, inset(new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL)));
        status.setVisibility(View.GONE);

        sidebar = new ChannelSidebar(activity, style, actions);
        root.addView(sidebar.view, inset(new FrameLayout.LayoutParams(panelWidth(0.34f, 520), -1, Gravity.START)));
        sidebar.view.setVisibility(View.GONE);

        guide = new GuideGrid(activity, style, actions);
        root.addView(guide, new FrameLayout.LayoutParams(-1, -1));
        guide.setVisibility(View.GONE);

        options = style.panel(18, 18);
        options.addView(style.title("选项"));
        optionsList = style.list();
        optionsList.setAdapter(optionRows);
        optionsList.setOnItemClickListener((parent, view, position, id) -> optionItems.get(position).action().run());
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(-1, 0, 1);
        optionsParams.topMargin = style.dp(12);
        options.addView(optionsList, optionsParams);
        root.addView(options, inset(new FrameLayout.LayoutParams(panelWidth(0.36f, 530), -1, Gravity.END)));
        options.setVisibility(View.GONE);

        activity.setContentView(root);
        root.requestFocus();
    }

    boolean panelOpen() {
        return !history.isEmpty();
    }

    boolean guideOpen() {
        return history.contains(Layer.GUIDE);
    }

    boolean optionsOpen() {
        return history.peek() == Layer.OPTIONS;
    }

    boolean handleGuideKey(KeyEvent event) {
        return history.peek() == Layer.GUIDE && guide.handleKey(event);
    }

    void showChannels() {
        if (push(Layer.CHANNELS)) {
            sidebar.focusInitial(onlyFavorites);
            if (ValueAnimator.areAnimatorsEnabled()) {
                sidebar.view.setTranslationX(-style.dp(16));
                sidebar.view.setAlpha(0f);
                sidebar.view.animate().translationX(0f).alpha(1f).setDuration(180)
                        .withEndAction(() -> root.requestTransparentRegion(sidebar.view))
                        .start();
            }
        }
    }

    void showGuide(List<Channel> channels, long playingId, Function<Channel, List<Program>> schedules) {
        if (push(Layer.GUIDE)) guide.show(channels, playingId, schedules);
    }

    void updateGuide(List<Channel> channels, long playingId, Function<Channel, List<Program>> schedules) {
        if (channels.isEmpty()) {
            if (history.peek() == Layer.GUIDE) back();
            else history.remove(Layer.GUIDE);
        } else if (guideOpen()) {
            guide.update(channels, playingId, schedules);
        }
    }

    void showOptions(List<Option> items) {
        boolean opened = push(Layer.OPTIONS);
        updateOptions(items);
        if (opened) {
            optionSelection = 0;
            focusOptions();
        }
    }

    void updateOptions(List<Option> items) {
        int selected = optionsList.getSelectedItemPosition();
        optionItems = items;
        optionRows.notifyDataSetChanged();
        if (optionsOpen() && selected >= 0 && selected >= items.size()) {
            optionsList.setSelection(items.size() - 1);
        }
    }

    void channels(List<Channel> channels, Set<String> favorites, long playingId,
                  boolean onlyFavorites, String message, Function<Channel, Program> nowPlaying) {
        this.onlyFavorites = onlyFavorites;
        sidebar.update(channels, favorites, playingId, onlyFavorites, message, nowPlaying);
    }

    boolean back() {
        if (history.isEmpty()) return false;
        hide(history.pop());
        restoreTop();
        return true;
    }

    void hidePanels() {
        if (history.isEmpty()) return;
        hide(history.peek());
        history.clear();
        root.requestFocus();
    }

    void channelBanner(Channel channel, Program now, Program next) {
        if (panelOpen()) return;
        bannerTitle.setText(channel.label());
        setBannerPrograms(now, next);
        banner.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, 3_000);
    }

    void updateBanner(Program now, Program next) {
        if (banner.getVisibility() == View.VISIBLE) setBannerPrograms(now, next);
    }

    void clearBanner() {
        handler.removeCallbacks(hideBanner);
        hideBanner.run();
    }

    void programDetails(Channel channel, Program program) {
        String when = dateTime.format(new Date(program.start())) + " – " + dateTime.format(new Date(program.end()));
        new AlertDialog.Builder(activity)
                .setTitle(program.title())
                .setMessage(channel.label() + "\n" + when)
                .setPositiveButton("返回", null)
                .show();
    }

    void status(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void finish() {
        clearBanner();
        sidebar.view.animate().cancel();
    }

    private boolean push(Layer layer) {
        Layer previous = history.peek();
        if (layer == previous) return false;
        clearBanner();
        if (previous == Layer.CHANNELS) {
            sidebarFocus = sidebar.view.findFocus();
            sidebar.saveSelection();
        } else if (previous == Layer.OPTIONS) {
            optionSelection = optionsList.getSelectedItemPosition();
        }
        if (previous != null) hide(previous);
        if (history.contains(layer)) {
            while (history.peek() != layer) history.pop();
            restoreTop();
            return false;
        }
        history.push(layer);
        viewFor(layer).setVisibility(View.VISIBLE);
        return true;
    }

    private void restoreTop() {
        Layer top = history.peek();
        if (top == null) {
            root.requestFocus();
            return;
        }
        viewFor(top).setVisibility(View.VISIBLE);
        switch (top) {
            case CHANNELS -> sidebar.restoreFocus(sidebarFocus, onlyFavorites);
            case GUIDE -> guide.restoreFocus();
            case OPTIONS -> focusOptions();
        }
    }

    private View viewFor(Layer layer) {
        return switch (layer) {
            case CHANNELS -> sidebar.view;
            case GUIDE -> guide;
            case OPTIONS -> options;
        };
    }

    private void hide(Layer layer) {
        View view = viewFor(layer);
        if (layer == Layer.CHANNELS) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setTranslationX(0f);
        }
        view.setVisibility(View.GONE);
    }

    private void focusOptions() {
        optionsList.requestFocus();
        optionsList.setSelection(Math.max(0, Math.min(optionSelection, optionItems.size() - 1)));
    }

    private TextView bannerLine() {
        TextView line = style.text(17, TvStyle.MUTED, false);
        style.trim(line, 1);
        banner.addView(line);
        return line;
    }

    private void setBannerPrograms(Program now, Program next) {
        nowLine.setText(now == null ? "正在播  暂无节目资料" : programLine("正在播", now));
        nextLine.setText(next == null ? "接下来  暂无节目资料" : programLine("接下来", next));
    }

    private String programLine(String label, Program program) {
        return label + "  " + time.format(new Date(program.start())) + "–" + time.format(new Date(program.end()))
                + "  " + program.title();
    }

    private int panelWidth(float fraction, int maxWidthDp) {
        int width = activity.getResources().getConfiguration().screenWidthDp;
        return style.dp(Math.min(maxWidthDp, Math.max(246, width * fraction)));
    }

    private FrameLayout.LayoutParams inset(FrameLayout.LayoutParams params) {
        params.setMargins(style.insetX(), style.insetY(), style.insetX(), style.insetY());
        return params;
    }

    private final class OptionsAdapter extends BaseAdapter {
        @Override public int getCount() { return optionItems.size(); }
        @Override public Option getItem(int position) { return optionItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            TextView item;
            if (recycled instanceof TextView text) {
                item = text;
            } else {
                item = style.text(18, TvStyle.TEXT, true);
                item.setTextColor(style.focusText(TvStyle.TEXT));
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(style.dp(14), 0, style.dp(14), 0);
                item.setLayoutParams(new AbsListView.LayoutParams(-1, style.dp(52)));
                style.trim(item, 2);
                style.focusSurface(item);
            }
            item.setText(getItem(position).label());
            return item;
        }
    }
}
