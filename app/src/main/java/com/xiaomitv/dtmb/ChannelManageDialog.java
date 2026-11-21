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
        titleView.setText("频道管理与排序");
        titleLayout.addView(titleView);

        subtitleView = style.text(13, TvStyle.MUTED, false);
        subtitleView.setPadding(0, style.dp(3), 0, 0);
        titleLayout.addView(subtitleView);

        header.addView(titleLayout, new LinearLayout.LayoutParams(0, -2, 1f));

        // 顶部操作按钮
        Button resetOrderBtn = style.capsuleButton("恢复默认排序", this::confirmResetOrder);
        restoreHiddenBtn = style.capsuleButton("恢复全部显示", this::restoreAllHidden);
        doneBtn = style.capsuleButton("完成", this::dismiss);

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
        hintView.setText("【操作提示】↑ / ↓ 移动焦点   ← 向上移动   → 向下移动   OK 频道操作菜单   返回 保存并退出");
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
        subtitleView.setText("共 " + total + " 个频道 · " + hiddenCount + " 个已隐藏");
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
            Toast.makeText(getContext(), "已恢复显示：" + c.name(), Toast.LENGTH_SHORT).show();
        } else {
            hidden.add(idStr);
            Toast.makeText(getContext(), "已隐藏频道：" + c.name(), Toast.LENGTH_SHORT).show();
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
                .setTitle("恢复全部显示")
                .setMessage("确定要将所有已隐藏的电视台恢复显示吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    hidden.clear();
                    notifyChange();
                    Toast.makeText(getContext(), "已恢复所有电视频道显示", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmResetOrder() {
        new AlertDialog.Builder(getContext())
                .setTitle("恢复默认排序")
                .setMessage("确定要清除自定义排序，恢复为系统默认台号顺序吗？")
                .setPositiveButton("恢复默认", (dialog, which) -> {
                    if (listener != null) {
                        listener.onResetDefaultOrder();
                    }
                    dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showChannelActionMenu(int position) {
        if (position < 0 || position >= channels.size()) return;
        Channel c = channels.get(position);
        String idStr = Long.toString(c.id());
        boolean isHidden = hidden.contains(idStr);
        boolean isFav = favorites.contains(idStr);

        List<String> items = new ArrayList<>();
        items.add(isHidden ? "恢复显示此频道" : "隐藏此频道");
        if (position > 0) items.add("向上移动一位 (↑)");
        if (position < channels.size() - 1) items.add("向下移动一位 (↓)");
        if (position > 0) items.add("移至顶部");
        if (position < channels.size() - 1) items.add("移至末尾");
        items.add(isFav ? "取消收藏" : "收藏此频道");

        String[] itemArray = items.toArray(new String[0]);
        new AlertDialog.Builder(getContext())
                .setTitle(c.label())
                .setItems(itemArray, (dialog, which) -> {
                    String selected = itemArray[which];
                    if (selected.contains("隐藏此频道") || selected.contains("恢复显示此频道")) {
                        toggleHidden(position);
                    } else if (selected.contains("向上移动一位")) {
                        moveChannel(position, position - 1);
                    } else if (selected.contains("向下移动一位")) {
                        moveChannel(position, position + 1);
                    } else if (selected.contains("移至顶部")) {
                        moveChannel(position, 0);
                    } else if (selected.contains("移至末尾")) {
                        moveChannel(position, channels.size() - 1);
                    } else if (selected.contains("收藏")) {
                        toggleFavorite(position);
                    }
                })
                .setNegativeButton("返回", null)
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

                TextView playingBadge = style.text(11, 0xffffffff, true);
                playingBadge.setText("● 播放中");
                playingBadge.setBackground(style.surface(TvStyle.RED_LIVE, 4, Color.TRANSPARENT));
                playingBadge.setPadding(style.dp(6), style.dp(1), style.dp(6), style.dp(1));
                badgeContainer.addView(playingBadge);

                TextView favBadge = style.text(11, 0xff101115, true);
                favBadge.setText("★ 收藏");
                favBadge.setBackground(style.surface(TvStyle.GOLD_GUIDE, 4, Color.TRANSPARENT));
                favBadge.setPadding(style.dp(6), style.dp(1), style.dp(6), style.dp(1));
                LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(-2, -2);
                favLp.leftMargin = style.dp(6);
                badgeContainer.addView(favBadge, favLp);

                TextView hiddenBadge = style.text(11, 0xffffffff, true);
                hiddenBadge.setText("已隐藏");
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
                actionHint.setText("← 上移 / 下移 →");
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
