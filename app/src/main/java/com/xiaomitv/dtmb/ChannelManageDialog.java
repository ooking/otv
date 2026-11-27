package com.xiaomitv.dtmb;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.xiaomitv.dtmb.TvListings.Channel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ChannelManageDialog extends Dialog {
    interface Listener {
        void onChannelsUpdated(List<Channel> newOrder, Set<String> hiddenChannels, Set<String> favorites);
        void onResetDefaultOrder();
    }

    private final TvStyle style;
    private final Listener listener;
    private final List<Channel> channels = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private final Set<String> favorites = new HashSet<>();
    private final long currentPlayingId;

    private final ChannelAdapter adapter;
    private final ListView listView;
    private final TextView subtitleView;
    private final Button restoreHiddenBtn;
    private final Button doneBtn;

    ChannelManageDialog(Context context, TvStyle style, List<Channel> allChannels,
                        Set<String> hiddenChannels, Set<String> favoriteChannels,
                        long playingId, Listener listener) {
        super(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        this.style = style;
        this.listener = listener;
        this.channels.addAll(allChannels);
        this.hidden.addAll(hiddenChannels);
        this.favorites.addAll(favoriteChannels);
        this.currentPlayingId = playingId;

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.requestFeature(Window.FEATURE_NO_TITLE);
        }

        // 外层主面板
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(dialogSurface());
        int paddingH = style.dp(24);
        int paddingV = style.dp(20);
        root.setPadding(paddingH, paddingV, paddingH, paddingV);

        // 1. 顶部标题栏
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleLayout = new LinearLayout(context);
        titleLayout.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = style.text(22, 0xffffffff, true);
        titleView.setText(R.string.channel_mgr_title);
        style.setLeadingIcon(titleView, R.drawable.ic_channels, 20);
        titleLayout.addView(titleView);

        subtitleView = style.text(13, TvStyle.MUTED, false);
        subtitleView.setPadding(0, style.dp(3), 0, 0);
        titleLayout.addView(subtitleView);

        header.addView(titleLayout, new LinearLayout.LayoutParams(0, -2, 1f));

        // 顶部操作按钮
        Button resetOrderBtn = style.capsuleButton(context.getString(R.string.channel_mgr_reset_order), this::confirmResetOrder);
        restoreHiddenBtn = style.capsuleButton(context.getString(R.string.channel_mgr_restore_all), this::restoreAllHidden);
        doneBtn = style.capsuleButton(context.getString(R.string.action_done), this::dismiss);

        addCapsule(header, resetOrderBtn);
        addCapsule(header, restoreHiddenBtn);
        addCapsule(header, doneBtn);

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // 分割线
        View divider = new View(context);
        divider.setBackgroundColor(0x22ffffff);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, style.dp(1));
        divParams.setMargins(0, style.dp(12), 0, style.dp(8));
        root.addView(divider, divParams);

        // 操作指引提示
        TextView hintView = style.text(12, 0xff9ba1b0, false);
        hintView.setText(R.string.channel_mgr_hint);
        hintView.setPadding(0, 0, 0, style.dp(8));
        root.addView(hintView);

        // 2. 频道列表
        listView = style.list();
        listView.setId(View.generateViewId());
        resetOrderBtn.setNextFocusDownId(listView.getId());
        restoreHiddenBtn.setNextFocusDownId(listView.getId());
        doneBtn.setNextFocusDownId(listView.getId());
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(style.dp(6));
        adapter = new ChannelAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, row, position, id) -> showChannelActionMenu(position));
        listView.setOnKeyListener((view, keyCode, event) -> handleListKey(keyCode, event));

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(listView, listParams);

        setContentView(root);

        updateSubtitle();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            int displayWidth = getContext().getResources().getDisplayMetrics().widthPixels;
            int displayHeight = getContext().getResources().getDisplayMetrics().heightPixels;
            int dialogWidth = Math.min(style.dp(720), (int) (displayWidth * 0.85f));
            int dialogHeight = Math.min(style.dp(520), (int) (displayHeight * 0.85f));
            window.setLayout(dialogWidth, dialogHeight);
            window.setGravity(Gravity.CENTER);
        }

        // 初始高亮当前播放频道或第 1 项
        int targetIndex = 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id() == currentPlayingId) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < channels.size()) {
            final int pos = targetIndex;
            listView.post(() -> {
                listView.requestFocus();
                listView.setSelection(pos);
            });
        }
    }

    private boolean handleListKey(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false;
        }
        int position = listView.getSelectedItemPosition();
        if (position == ListView.INVALID_POSITION) return false;

        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            moveChannel(position, position + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
        }
        return true;
    }

    private void addCapsule(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = style.dp(8);
        parent.addView(button, lp);
    }

    private GradientDrawable dialogSurface() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xf51c1e26, 0xf9121318});
        drawable.setCornerRadius(style.dp(16));
        drawable.setStroke(style.dp(1), 0x44ffffff);
        return drawable;
    }

    private void updateSubtitle() {
        int total = channels.size();
        int hiddenCount = 0;
        for (Channel c : channels) {
            if (hidden.contains(Long.toString(c.id()))) {
                hiddenCount++;
            }
        }
        subtitleView.setText(getContext().getString(R.string.channel_mgr_subtitle, total, hiddenCount));
        if (hiddenCount == 0 && restoreHiddenBtn.hasFocus()) doneBtn.requestFocus();
        restoreHiddenBtn.setVisibility(hiddenCount > 0 ? View.VISIBLE : View.GONE);
    }

    private void notifyChange() {
        adapter.notifyDataSetChanged();
        updateSubtitle();
        if (listener != null) {
            listener.onChannelsUpdated(channels, hidden, favorites);
        }
    }

    private void moveChannel(int from, int to) {
        if (from < 0 || from >= channels.size() || to < 0 || to >= channels.size() || from == to) {
            return;
        }
        Channel c = channels.remove(from);
        channels.add(to, c);
        notifyChange();
        listView.setSelection(to);
    }

    private void toggleHidden(int position) {
        if (position < 0 || position >= channels.size()) return;
        Channel c = channels.get(position);
        String idStr = Long.toString(c.id());
        if (hidden.contains(idStr)) {
            hidden.remove(idStr);
            Toast.makeText(getContext(), getContext().getString(R.string.channel_mgr_toast_restored, c.name()),
                    Toast.LENGTH_SHORT).show();
        } else {
            hidden.add(idStr);
            Toast.makeText(getContext(), getContext().getString(R.string.channel_mgr_toast_hidden, c.name()),
                    Toast.LENGTH_SHORT).show();
        }
        notifyChange();
    }

    private void toggleFavorite(int position) {
        if (position < 0 || position >= channels.size()) return;
        Channel c = channels.get(position);
        String idStr = Long.toString(c.id());
        if (favorites.contains(idStr)) {
            favorites.remove(idStr);
        } else {
            favorites.add(idStr);
        }
        notifyChange();
    }

    private void restoreAllHidden() {
        if (hidden.isEmpty()) return;
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.channel_mgr_restore_all_title)
                .setMessage(R.string.channel_mgr_restore_all_msg)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    hidden.clear();
                    notifyChange();
                    Toast.makeText(getContext(), R.string.channel_mgr_toast_all_restored, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmResetOrder() {
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.channel_mgr_reset_title)
                .setMessage(R.string.channel_mgr_reset_msg)
                .setPositiveButton(R.string.channel_mgr_reset_confirm, (dialog, which) -> {
                    if (listener != null) {
                        listener.onResetDefaultOrder();
                    }
                    dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showChannelActionMenu(int position) {
        if (position < 0 || position >= channels.size()) return;
        Channel c = channels.get(position);
        String idStr = Long.toString(c.id());
        boolean isHidden = hidden.contains(idStr);
        boolean isFav = favorites.contains(idStr);

        Context ctx = getContext();
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        items.add(ctx.getString(isHidden ? R.string.channel_mgr_action_unhide : R.string.channel_mgr_action_hide));
        actions.add(() -> toggleHidden(position));
        if (position > 0) {
            items.add(ctx.getString(R.string.channel_mgr_action_move_up));
            actions.add(() -> moveChannel(position, position - 1));
        }
        if (position < channels.size() - 1) {
            items.add(ctx.getString(R.string.channel_mgr_action_move_down));
            actions.add(() -> moveChannel(position, position + 1));
        }
        if (position > 0) {
            items.add(ctx.getString(R.string.channel_mgr_action_move_top));
            actions.add(() -> moveChannel(position, 0));
        }
        if (position < channels.size() - 1) {
            items.add(ctx.getString(R.string.channel_mgr_action_move_bottom));
            actions.add(() -> moveChannel(position, channels.size() - 1));
        }
        items.add(ctx.getString(isFav ? R.string.channel_mgr_action_unfavorite : R.string.channel_mgr_action_favorite));
        actions.add(() -> toggleFavorite(position));

        String[] itemArray = items.toArray(new String[0]);
        new AlertDialog.Builder(ctx)
                .setTitle(c.label())
                .setItems(itemArray, (dialog, which) -> actions.get(which).run())
                .setNegativeButton(R.string.action_back, null)
                .show();
    }

    private final class ChannelAdapter extends BaseAdapter {
        private final ColorStateList visibleChannelText = style.focusText(Color.WHITE);
        private final ColorStateList mutedChannelText = style.focusText(TvStyle.MUTED);

        @Override
        public int getCount() {
            return channels.size();
        }

        @Override
        public Object getItem(int position) {
            return channels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return channels.get(position).id();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8));
                StateListDrawable background = new StateListDrawable();
                background.addState(new int[]{android.R.attr.state_selected},
                        style.surface(TvStyle.FOCUS, 8, Color.TRANSPARENT));
                background.addState(new int[]{android.R.attr.state_activated},
                        style.surface(0x66181920, 8, 0x22ffffff));
                background.addState(new int[0], style.surface(0xd0222530, 8, 0x33ffffff));
                row.setBackground(background);

                // 序号徽章
                TextView numView = style.text(12, 0xffa0a4b5, true);
                numView.setPadding(style.dp(6), style.dp(2), style.dp(6), style.dp(2));
                numView.setGravity(Gravity.CENTER);
                numView.setMinWidth(style.dp(36));
                numView.setDuplicateParentStateEnabled(true);
                numView.setTextColor(style.focusText(TvStyle.MUTED));
                StateListDrawable numberBackground = new StateListDrawable();
                numberBackground.addState(new int[]{android.R.attr.state_selected},
                        style.surface(0x1f000000, 4, Color.TRANSPARENT));
                numberBackground.addState(new int[0], style.surface(0x33ffffff, 4, Color.TRANSPARENT));
                numView.setBackground(numberBackground);
                row.addView(numView);

                // 频道名称
                TextView nameView = style.text(14, 0xffffffff, true);
                nameView.setDuplicateParentStateEnabled(true);
                nameView.setPadding(style.dp(10), 0, style.dp(8), 0);
                style.trim(nameView, 1);
                LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
                row.addView(nameView, nameLp);

                // 徽章容器（隐藏状态、收藏、播放中）
                LinearLayout badgeContainer = new LinearLayout(getContext());
                badgeContainer.setOrientation(LinearLayout.HORIZONTAL);
                badgeContainer.setGravity(Gravity.CENTER_VERTICAL);

                TextView playingBadge = style.text(11, TvStyle.LIVE_TEXT, true);
                playingBadge.setText(R.string.badge_playing);
                style.styleSelectableLiveBadge(playingBadge);
                badgeContainer.addView(playingBadge);

                TextView favBadge = style.text(11, 0xff101115, true);
                favBadge.setText(R.string.channel_mgr_badge_fav);
                favBadge.setBackground(style.surface(TvStyle.GOLD_GUIDE, 4, Color.TRANSPARENT));
                favBadge.setPadding(style.dp(6), style.dp(1), style.dp(6), style.dp(1));
                LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(-2, -2);
                favLp.leftMargin = style.dp(6);
                badgeContainer.addView(favBadge, favLp);

                TextView hiddenBadge = style.text(11, 0xffffffff, true);
                hiddenBadge.setText(R.string.channel_mgr_badge_hidden);
                hiddenBadge.setBackground(style.surface(0xffb91c1c, 4, Color.TRANSPARENT));
                hiddenBadge.setPadding(style.dp(6), style.dp(1), style.dp(6), style.dp(1));
                LinearLayout.LayoutParams hiddenLp = new LinearLayout.LayoutParams(-2, -2);
                hiddenLp.leftMargin = style.dp(6);
                badgeContainer.addView(hiddenBadge, hiddenLp);

                row.addView(badgeContainer);

                // 快捷提示标签
                TextView actionHint = style.text(11, 0xff717684, false);
                actionHint.setDuplicateParentStateEnabled(true);
                actionHint.setTextColor(style.focusText(0xff717684));
                actionHint.setText(R.string.channel_mgr_row_hint);
                actionHint.setPadding(style.dp(12), 0, 0, 0);
                row.addView(actionHint);

                holder = new ViewHolder(numView, nameView, playingBadge, favBadge, hiddenBadge);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            Channel channel = channels.get(position);
            String idStr = Long.toString(channel.id());
            boolean isHidden = hidden.contains(idStr);
            boolean isFav = favorites.contains(idStr);
            boolean isPlaying = (channel.id() == currentPlayingId);

            // 格式化序号：01, 02...
            String orderStr = String.format("%02d", position + 1);
            holder.numView.setText(orderStr);
            holder.nameView.setText(channel.label());
            holder.nameView.setTextColor(isHidden ? mutedChannelText : visibleChannelText);
            convertView.setActivated(isHidden);

            holder.playingBadge.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
            holder.favBadge.setVisibility(isFav ? View.VISIBLE : View.GONE);
            holder.hiddenBadge.setVisibility(isHidden ? View.VISIBLE : View.GONE);

            return convertView;
        }
    }

    private static final class ViewHolder {
        final TextView numView;
        final TextView nameView;
        final TextView playingBadge;
        final TextView favBadge;
        final TextView hiddenBadge;
        ViewHolder(TextView numView, TextView nameView, TextView playingBadge,
                   TextView favBadge, TextView hiddenBadge) {
            this.numView = numView;
            this.nameView = nameView;
            this.playingBadge = playingBadge;
            this.favBadge = favBadge;
            this.hiddenBadge = hiddenBadge;
        }
    }
}
