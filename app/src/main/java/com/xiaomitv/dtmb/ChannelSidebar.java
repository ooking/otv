package com.xiaomitv.dtmb;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xiaomitv.dtmb.TvListings.Channel;
import com.xiaomitv.dtmb.TvListings.Program;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

final class ChannelSidebar {
    final LinearLayout view;
    private final Context context;
    private final TvStyle style;
    private final TvScreen.Actions actions;
    private final DateFormat timeFormat;

    // 顶部控制胶囊行 (图 2 胶囊条)
    private final LinearLayout controlsRow;
    private final Button filterBtn;
    private final Button guideBtn;
    private final Button optionsBtn;

    // 中部横向电视频道走马灯 (图 2 底部横向频道卡片)
    private final HorizontalScrollView scroll;
    private final LinearLayout cardsContainer;

    // 提示及空状态
    private final TextView emptyText;

    private List<Channel> channels = Collections.emptyList();
    private Set<String> favorites = Collections.emptySet();
    private Function<Channel, Program> nowPlaying = channel -> null;
    private long playingId = -1;
    private long selectedId = -1;

    ChannelSidebar(Context context, TvStyle style, TvScreen.Actions actions) {
        this.context = context;
        this.style = style;
        this.actions = actions;
        this.timeFormat = android.text.format.DateFormat.getTimeFormat(context);

        // 底部整体浮层面板：深色毛玻璃，位于屏幕下部
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(bottomGlassSurface());
        view.setPadding(style.dp(28), style.dp(18), style.dp(28), style.dp(20));

        // 1. 控制胶囊行
        controlsRow = new LinearLayout(context);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, -2);
        controlsParams.bottomMargin = style.dp(14);
        view.addView(controlsRow, controlsParams);

        filterBtn = style.capsuleButton("全部频道", actions::filter);
        guideBtn = style.capsuleButton("节目指南 (EPG)", actions::openGuide);
        optionsBtn = style.capsuleButton("设置选项", actions::options);

        addCapsule(controlsRow, filterBtn);
        addCapsule(controlsRow, guideBtn);
        addCapsule(controlsRow, optionsBtn);

        // 胶囊行右侧：金色 DTMB GUIDE 品牌标识与操作提示
        View spacer = new View(context);
        controlsRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView hint = style.text(13, TvStyle.MUTED, false);
        hint.setText("← / → 选台   OK 确认播放   ↑ 功能菜单   返回 关闭");
        hint.setPadding(0, 0, style.dp(14), 0);
        controlsRow.addView(hint);

        TextView brand = style.text(15, TvStyle.GOLD_GUIDE, true);
        brand.setText("DTMB GUIDE");
        controlsRow.addView(brand);

        // 2. 空状态提示
        emptyText = style.text(16, TvStyle.MUTED, false);
        emptyText.setPadding(style.dp(8), style.dp(16), style.dp(8), style.dp(16));
        emptyText.setVisibility(View.GONE);
        view.addView(emptyText);

        // 3. 横向电视频道卡片走马灯
        scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        cardsContainer = new LinearLayout(context);
        cardsContainer.setOrientation(LinearLayout.HORIZONTAL);
        cardsContainer.setClipChildren(false);
        cardsContainer.setClipToPadding(false);
        scroll.addView(cardsContainer, new LinearLayout.LayoutParams(-2, -2));
        view.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        // 按键导航绑定
        filterBtn.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                focusCurrentCard();
                return true;
            }
            return false;
        });
        guideBtn.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                focusCurrentCard();
                return true;
            }
            return false;
        });
        optionsBtn.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                focusCurrentCard();
                return true;
            }
            return false;
        });
    }

    private void addCapsule(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = style.dp(10);
        parent.addView(button, lp);
    }

    private GradientDrawable bottomGlassSurface() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xf21a1c24, 0xf9101116});
        drawable.setCornerRadii(new float[]{
                style.dp(24), style.dp(24),
                style.dp(24), style.dp(24),
                0, 0,
                0, 0
        });
        drawable.setStroke(style.dp(1), 0x33ffffff);
        return drawable;
    }

    void update(List<Channel> channels, Set<String> favorites, long playingId, boolean onlyFavorites,
                String message, Function<Channel, Program> nowPlaying) {
        this.channels = channels;
        this.favorites = favorites;
        this.playingId = playingId;
        this.nowPlaying = nowPlaying;

        filterBtn.setText(onlyFavorites ? "频道: 收藏 ★" : "频道: 全部");
        emptyText.setText(message);
        emptyText.setVisibility(channels.isEmpty() ? View.VISIBLE : View.GONE);
        scroll.setVisibility(channels.isEmpty() ? View.GONE : View.VISIBLE);

        buildCards();
    }

    private void buildCards() {
        cardsContainer.removeAllViews();
        if (channels.isEmpty()) return;

        int cardWidth = style.dp(196);
        int cardHeight = style.dp(102);

        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            Program program = nowPlaying.apply(channel);
            boolean isCurrentPlaying = (playingId == channel.id());

            // 单个卡片容器
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setClickable(true);
            card.setPadding(style.dp(12), style.dp(10), style.dp(12), style.dp(10));
            style.attachElevation(card, style.dp(6));

            // 1. 顶部行：台号徽章 + 频道名 + 播放状态点
            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            // 电台序号：不固定 3 位，有数字直接用数字
            String numStr = channel.number().isEmpty() ? String.valueOf(channel.id()) : channel.number();
            TextView numBadge = style.text(12, 0xffa0a4b5, true);
            numBadge.setText(numStr);
            numBadge.setPadding(style.dp(6), style.dp(1), style.dp(6), style.dp(2));
            numBadge.setBackground(style.surface(0x33ffffff, 6, Color.TRANSPARENT));
            topRow.addView(numBadge);

            TextView nameView = style.text(14, 0xffffffff, true);
            nameView.setText(channel.label());
            style.trim(nameView, 1);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
            nameLp.leftMargin = style.dp(8);
            topRow.addView(nameView, nameLp);

            // 播放中小标
            TextView liveBadge = style.text(11, TvStyle.RED_LIVE, true);
            liveBadge.setText("● 播放中");
            liveBadge.setVisibility(isCurrentPlaying ? View.VISIBLE : View.GONE);
            topRow.addView(liveBadge);

            card.addView(topRow);

            // 2. 中部分割线
            View divider = new View(context);
            divider.setBackgroundColor(0x22ffffff);
            LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, style.dp(1));
            divParams.setMargins(0, style.dp(8), 0, style.dp(6));
            card.addView(divider, divParams);

            // 3. 底部：播出时间与节目名
            TextView timeView = style.text(11, 0xff9094a6, false);
            style.trim(timeView, 1);
            if (program != null) {
                timeView.setText(timeFormat.format(new Date(program.start())) + "~");
            } else {
                timeView.setText("当前时段");
            }
            card.addView(timeView);

            TextView progView = style.text(13, 0xffdcdfe8, true);
            style.trim(progView, 1);
            progView.setPadding(0, style.dp(2), 0, 0);
            if (program != null) {
                progView.setText(program.title());
            } else {
                progView.setText(isCurrentPlaying ? "正在播出" : "暂无节目资料");
            }
            card.addView(progView);

            // 动态焦点样式切换函数（彻底解决白底白字问题，确保高对比度）
            Runnable applyStyle = () -> {
                boolean hasFocus = card.isFocused();
                if (hasFocus) {
                    // 聚焦态：纯白底色，深黑色文字
                    card.setBackground(style.surface(0xffffffff, 12, Color.TRANSPARENT));
                    numBadge.setBackground(style.surface(0x1a000000, 6, Color.TRANSPARENT));
                    numBadge.setTextColor(0xff4b5563);
                    nameView.setTextColor(0xff121316); // 纯黑粗体电台名！彻底解决看不见问题
                    divider.setBackgroundColor(0x1a000000);
                    timeView.setTextColor(0xff6b7280);
                    progView.setTextColor(0xff121316); // 纯黑粗体节目名！
                } else {
                    // 未聚焦态：深灰半透，白色及浅灰文字
                    card.setBackground(style.surface(0xdf222530, 12, 0x33ffffff));
                    numBadge.setBackground(style.surface(0x33ffffff, 6, Color.TRANSPARENT));
                    numBadge.setTextColor(0xffa0a4b5);
                    nameView.setTextColor(0xffffffff);
                    divider.setBackgroundColor(0x22ffffff);
                    timeView.setTextColor(0xff9094a6);
                    progView.setTextColor(0xffdcdfe8);
                }
            };

            // 应用初始样式
            applyStyle.run();

            // 点击与按键切台
            final Channel currentChannel = channel;
            card.setOnClickListener(v -> actions.select(currentChannel));
            card.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
                    guideBtn.requestFocus();
                    return true;
                }
                return false;
            });

            card.setOnFocusChangeListener((v, hasFocus) -> {
                applyStyle.run();
                if (hasFocus) {
                    selectedId = currentChannel.id();
                    scroll.smoothScrollTo(v.getLeft() - style.dp(40), 0);
                }
            });

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(cardWidth, cardHeight);
            cardParams.rightMargin = style.dp(12);
            cardsContainer.addView(card, cardParams);
        }
    }

    void saveSelection() {
        // 当前选中 ID 已在 setOnFocusChangeListener 中记录
    }

    void restoreFocus(View previousFocus, boolean onlyFavorites) {
        if (previousFocus != null && previousFocus.requestFocus()) {
            return;
        }
        focusInitial(onlyFavorites);
    }

    void focusInitial(boolean onlyFavorites) {
        if (!focusCurrentCard()) {
            filterBtn.requestFocus();
        }
    }

    private boolean focusCurrentCard() {
        if (channels.isEmpty() || cardsContainer.getChildCount() == 0) return false;
        int targetIndex = 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id() == (selectedId != -1 ? selectedId : playingId)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < cardsContainer.getChildCount()) {
            View card = cardsContainer.getChildAt(targetIndex);
            card.requestFocus();
            scroll.smoothScrollTo(card.getLeft() - style.dp(40), 0);
            return true;
        }
        return false;
    }
}
