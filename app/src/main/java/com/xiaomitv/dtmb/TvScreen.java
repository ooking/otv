package com.xiaomitv.dtmb;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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

import java.util.ArrayDeque;
import java.util.Collections;
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
    final TvStyle style;
    private final FrameLayout root;
    private final ChannelSidebar sidebar;
    private final GuideGrid guide;
    private final LinearLayout options;
    private final ListView optionsList;
    private final OptionsAdapter optionRows = new OptionsAdapter();
    private final View transition;
    private final ProgramOverlay banner;
    private final TextView status;
    private final Deque<Layer> history = new ArrayDeque<>();
    private List<Option> optionItems = Collections.emptyList();
    private View sidebarFocus;
    private int optionSelection;

    TvScreen(Activity activity, Handler handler, Actions actions) {
        this.activity = activity;
        style = new TvStyle(activity);
        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        video = new TvView(activity);
        video.setFocusable(false);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));

        transition = new View(activity);
        transition.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        transition.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff30343c, TvStyle.GRAPHITE}));
        transition.setVisibility(View.GONE);
        root.addView(transition, new FrameLayout.LayoutParams(-1, -1));

        banner = new ProgramOverlay(activity, handler, style);
        root.addView(banner, inset(new FrameLayout.LayoutParams(-1, -2, Gravity.TOP)));
        status = style.text(17, TvStyle.TEXT, true);
        status.setPadding(style.dp(16), style.dp(9), style.dp(16), style.dp(9));
        status.setGravity(Gravity.CENTER);
        status.setBackground(style.surface(TvStyle.PANEL, 14, TvStyle.EDGE));
        root.addView(status, inset(new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)));
        status.setVisibility(View.GONE);

        sidebar = new ChannelSidebar(activity, style, actions);
        root.addView(sidebar.view, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        sidebar.view.setVisibility(View.GONE);

        guide = new GuideGrid(activity, style, actions);
        root.addView(guide, new FrameLayout.LayoutParams(-1, -1));
        guide.setVisibility(View.GONE);

        options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setBackground(optionsDrawerSurface());
        options.setPadding(style.dp(24), style.dp(22), style.dp(24), style.dp(20));

        TextView optTitle = style.text(22, TvStyle.TEXT, true);
        optTitle.setText("设置与选项");
        options.addView(optTitle);

        TextView optSub = style.text(13, TvStyle.MUTED, false);
        optSub.setText("按 OK 键进入设置 · 返回键关闭");
        optSub.setPadding(0, style.dp(4), 0, style.dp(12));
        options.addView(optSub);

        View optDivider = new View(activity);
        optDivider.setBackgroundColor(0x22ffffff);
        options.addView(optDivider, new LinearLayout.LayoutParams(-1, style.dp(1)));

        optionsList = style.list();
        optionsList.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        optionsList.setDividerHeight(style.dp(8));
        optionsList.setAdapter(optionRows);
        optionsList.setOnItemClickListener((parent, view, position, id) -> optionItems.get(position).action().run());
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(-1, 0, 1);
        optionsParams.topMargin = style.dp(14);
        options.addView(optionsList, optionsParams);

        TextView optFooter = style.text(12, 0xff707485, false);
        optFooter.setText("OTV · DTMB 播放核心");
        optFooter.setGravity(Gravity.CENTER);
        optFooter.setPadding(0, style.dp(10), 0, 0);
        options.addView(optFooter);

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
            sidebar.focusInitial();
            if (ValueAnimator.areAnimatorsEnabled()) {
                sidebar.view.setTranslationY(style.dp(24));
                sidebar.view.setAlpha(0f);
                sidebar.view.animate().translationY(0f).alpha(1f).setDuration(180)
                        .withEndAction(() -> root.requestTransparentRegion(sidebar.view))
                        .start();
            }
        }
    }

    void showGuide(List<Channel> channels, long playingId, boolean onlyFavorites,
                   Function<Channel, List<Program>> schedules) {
        if (push(Layer.GUIDE)) guide.show(channels, playingId, onlyFavorites, schedules);
    }

    void updateGuide(List<Channel> channels, long playingId, boolean onlyFavorites,
                     Function<Channel, List<Program>> schedules) {
        if (guideOpen()) guide.update(channels, playingId, onlyFavorites, schedules);
    }

    void closeGuide() {
        if (history.peek() == Layer.GUIDE) back();
        else history.remove(Layer.GUIDE);
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
        sidebar.update(channels, favorites, playingId, onlyFavorites, message, nowPlaying);
    }

    boolean back() {
        if (history.isEmpty()) {
            if (!hasProgramOverlay()) return false;
            clearBanner();
            return true;
        }
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

    void showProgram(Channel channel, Program now, Program next, String badges,
                     boolean temporary, boolean switching) {
        if (!panelOpen()) banner.show(channel, now, next, badges, temporary, switching);
    }

    void updateBanner(Channel channel, Program now, Program next, String badges) {
        if (hasProgramOverlay()) banner.update(channel, now, next, badges);
    }

    boolean hasProgramOverlay() {
        return banner.getVisibility() == View.VISIBLE;
    }

    void openProgramDescription() {
        banner.openDetails();
    }

    void clearBanner() {
        banner.close();
    }

    void beginTransition() {
        transition.animate().cancel();
        transition.setAlpha(1f);
        transition.setVisibility(View.VISIBLE);
    }

    void revealVideo() {
        if (transition.getVisibility() != View.VISIBLE) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            clearTransition();
            return;
        }
        transition.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> transition.setVisibility(View.GONE)).start();
    }

    void clearTransition() {
        transition.animate().cancel();
        transition.setVisibility(View.GONE);
    }

    void programDetails(Channel channel, Program program) {
        banner.showDetails(channel, program);
    }

    void status(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void finish() {
        clearBanner();
        clearTransition();
        sidebar.view.animate().cancel();
    }

    private boolean push(Layer layer) {
        Layer previous = history.peek();
        if (layer == previous) return false;
        clearBanner();
        if (previous == Layer.CHANNELS) {
            sidebarFocus = sidebar.view.findFocus();
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
            case CHANNELS -> sidebar.restoreFocus(sidebarFocus);
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
            view.setTranslationY(0f);
        }
        view.setVisibility(View.GONE);
    }

    private void focusOptions() {
        optionsList.requestFocus();
        optionsList.setSelection(Math.max(0, Math.min(optionSelection, optionItems.size() - 1)));
    }

    private int panelWidth(float fraction, int maxWidthDp) {
        int width = activity.getResources().getConfiguration().screenWidthDp;
        return style.dp(Math.min(maxWidthDp, Math.max(246, width * fraction)));
    }

    private FrameLayout.LayoutParams inset(FrameLayout.LayoutParams params) {
        params.setMargins(style.insetX(), style.insetY(), style.insetX(), style.insetY());
        return params;
    }

    private GradientDrawable optionsDrawerSurface() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xf51c1e28, 0xf8111218});
        drawable.setCornerRadius(style.dp(24));
        drawable.setStroke(style.dp(1), 0x33ffffff);
        return drawable;
    }

    private final class OptionsAdapter extends BaseAdapter {
        @Override public int getCount() { return optionItems.size(); }
        @Override public Option getItem(int position) { return optionItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            TextView title;
            TextView arrow;
            if (recycled instanceof LinearLayout layout) {
                row = layout;
                title = (TextView) row.getChildAt(0);
                arrow = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(style.dp(18), 0, style.dp(16), 0);
                row.setLayoutParams(new AbsListView.LayoutParams(-1, style.dp(54)));
                style.focusSurface(row);

                title = style.text(16, TvStyle.TEXT, true);
                title.setTextColor(style.focusText(TvStyle.TEXT));
                title.setDuplicateParentStateEnabled(true);
                style.trim(title, 1);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
                row.addView(title, titleLp);

                arrow = style.text(18, TvStyle.MUTED, false);
                arrow.setTextColor(style.focusText(TvStyle.MUTED));
                arrow.setText("›");
                arrow.setDuplicateParentStateEnabled(true);
                row.addView(arrow);
            }
            title.setText(getItem(position).label());
            return row;
        }
    }
}
