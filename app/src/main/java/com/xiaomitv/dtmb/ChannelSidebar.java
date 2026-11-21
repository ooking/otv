package com.xiaomitv.dtmb;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.xiaomitv.dtmb.TvListings.Channel;
import com.xiaomitv.dtmb.TvListings.Program;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

final class ChannelSidebar {
    final LinearLayout view;
    private final Context context;
    private final TvStyle style;
    private final ListView list;
    private final Button filter;
    private final Button guide;
    private final Button options;
    private final TextView empty;
    private final ChannelAdapter rows = new ChannelAdapter();
    private List<Channel> channels = Collections.emptyList();
    private Set<String> favorites = Collections.emptySet();
    private Function<Channel, Program> nowPlaying = channel -> null;
    private long playingId = -1;
    private long selectedId = -1;
    private int selectedTop;

    ChannelSidebar(Context context, TvStyle style, TvScreen.Actions actions) {
        this.context = context;
        this.style = style;
        view = style.panel(16, 18);
        TextView title = style.title("频道");
        view.addView(title);

        LinearLayout controls = new LinearLayout(context);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, style.dp(42));
        controlsParams.setMargins(0, style.dp(12), 0, style.dp(10));
        view.addView(controls, controlsParams);
        filter = style.button("全部", actions::filter);
        guide = style.button("节目指南", actions::openGuide);
        options = style.button("选项", actions::options);
        addControl(controls, filter, 1, 0);
        addControl(controls, guide, 1.45f, 6);
        addControl(controls, options, 1, 6);

        empty = style.text(17, TvStyle.MUTED, false);
        empty.setPadding(style.dp(6), style.dp(12), style.dp(6), 0);
        view.addView(empty);

        list = style.list();
        list.setId(View.generateViewId());
        filter.setNextFocusRightId(guide.getId());
        guide.setNextFocusLeftId(filter.getId());
        guide.setNextFocusRightId(options.getId());
        options.setNextFocusLeftId(guide.getId());
        list.setNextFocusUpId(filter.getId());
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, row, position, id) -> actions.select(channels.get(position)));
        list.setOnKeyListener((row, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            boolean atTop = key == KeyEvent.KEYCODE_DPAD_UP && list.getSelectedItemPosition() == 0;
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && !atTop) return false;
            saveSelection();
            filter.requestFocus();
            return true;
        });
        for (Button button : new Button[]{filter, guide, options}) {
            button.setNextFocusDownId(list.getId());
            button.setOnKeyListener((control, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_DOWN || event.getAction() != KeyEvent.ACTION_DOWN
                        || channels.isEmpty()) return false;
                focusSaved();
                return true;
            });
        }
        view.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = style.text(14, TvStyle.MUTED, false);
        hint.setText("←  筛选     确认  切台     返回  收起");
        hint.setPadding(style.dp(6), style.dp(8), 0, 0);
        view.addView(hint);
    }

    void update(List<Channel> channels, Set<String> favorites, long playingId, boolean onlyFavorites,
                String message, Function<Channel, Program> nowPlaying) {
        saveSelection();
        this.channels = channels;
        this.favorites = favorites;
        this.playingId = playingId;
        this.nowPlaying = nowPlaying;
        filter.setText(onlyFavorites ? "收藏" : "全部");
        filter.setContentDescription(onlyFavorites ? "当前显示收藏频道，确认显示全部"
                : "当前显示全部频道，确认只看收藏");
        empty.setText(message);
        empty.setVisibility(channels.isEmpty() ? View.VISIBLE : View.GONE);
        boolean hadListFocus = list.hasFocus();
        int first = list.getFirstVisiblePosition();
        int last = list.getLastVisiblePosition();
        list.setVisibility(channels.isEmpty() ? View.GONE : View.VISIBLE);
        rows.notifyDataSetChanged();
        if (hadListFocus) {
            if (channels.isEmpty()) {
                (onlyFavorites ? filter : options).requestFocus();
            } else {
                int selected = indexOf(selectedId);
                if (selected < 0 || selected != list.getSelectedItemPosition()
                        || selected < first || selected > last) {
                    list.setSelection(selected < 0 ? initialPosition() : selected);
                }
            }
        }
    }

    void focusInitial(boolean onlyFavorites) {
        if (channels.isEmpty()) {
            (onlyFavorites ? filter : options).requestFocus();
        } else {
            focusList(initialPosition());
        }
    }

    void saveSelection() {
        if (!list.hasFocus()) return;
        long id = list.getSelectedItemId();
        if (id != ListView.INVALID_ROW_ID) {
            selectedId = id;
            View row = list.getSelectedView();
            if (row != null) selectedTop = Math.max(0, row.getTop());
        }
    }

    void restoreFocus(View target, boolean onlyFavorites) {
        if (target == list && !channels.isEmpty()) {
            focusSaved();
        } else if (target == null || !target.requestFocus()) {
            focusInitial(onlyFavorites);
        }
    }

    private void focusSaved() {
        int position = indexOf(selectedId);
        if (position < 0) {
            focusList(initialPosition());
        } else {
            list.requestFocus();
            list.setSelectionFromTop(position, selectedTop);
        }
    }

    private void focusList(int position) {
        list.requestFocus();
        list.setSelection(Math.max(0, position));
    }

    private int initialPosition() {
        return Math.max(0, indexOf(playingId));
    }

    private int indexOf(long id) {
        for (int index = 0; index < channels.size(); index++) {
            if (channels.get(index).id() == id) return index;
        }
        return -1;
    }

    private void addControl(LinearLayout controls, Button button, float weight, int margin) {
        button.setId(View.generateViewId());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.leftMargin = style.dp(margin);
        controls.addView(button, params);
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return channels.size(); }
        @Override public Channel getItem(int position) { return channels.get(position); }
        @Override public long getItemId(int position) { return getItem(position).id(); }
        @Override public boolean hasStableIds() { return true; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            ChannelRow row = recycled instanceof ChannelRow ? (ChannelRow) recycled : new ChannelRow();
            Channel channel = getItem(position);
            row.bind(channel, nowPlaying.apply(channel), channel.id() == playingId,
                    favorites.contains(Long.toString(channel.id())));
            return row;
        }
    }

    private final class ChannelRow extends LinearLayout {
        private final TextView badge;
        private final TextView title;
        private final TextView program;

        ChannelRow() {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(style.dp(4), style.dp(3), style.dp(4), style.dp(3));
            setMinimumHeight(style.dp(62));
            LinearLayout tile = new LinearLayout(context);
            tile.setDuplicateParentStateEnabled(true);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(style.dp(10), style.dp(6), style.dp(10), style.dp(6));
            style.focusSurface(tile);
            addView(tile, new LinearLayout.LayoutParams(-1, -1));

            badge = style.text(14, TvStyle.LIVE, true);
            badge.setDuplicateParentStateEnabled(true);
            badge.setTextColor(style.focusText(TvStyle.LIVE));
            badge.setGravity(Gravity.CENTER);
            tile.addView(badge, new LinearLayout.LayoutParams(style.dp(34), -1));

            LinearLayout titles = new LinearLayout(context);
            titles.setDuplicateParentStateEnabled(true);
            titles.setOrientation(VERTICAL);
            tile.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
            title = rowText(titles, 19, TvStyle.TEXT, true);
            program = rowText(titles, 14, TvStyle.MUTED, false);
        }

        void bind(Channel channel, Program current, boolean playing, boolean favorite) {
            String marks = (playing ? "▶" : "") + (favorite ? "★" : "");
            String name = channel.label();
            String show = current == null ? "暂无节目资料" : current.title();
            badge.setText(marks);
            title.setText(name);
            program.setText(show);
            setContentDescription(name + (playing ? "，正在观看" : "")
                    + (favorite ? "，已收藏" : "") + "，" + show);
        }

        private TextView rowText(LinearLayout parent, int size, int color, boolean medium) {
            TextView view = style.text(size, color, medium);
            view.setDuplicateParentStateEnabled(true);
            view.setTextColor(style.focusText(color));
            style.trim(view, 1);
            parent.addView(view);
            return view;
        }
    }
}
