package com.xiaomitv.dtmb;

import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

final class ChannelSidebar {
    final LinearLayout view;
    private final Context context;
    private final TvStyle style;
    private final TvScreen.Actions actions;
    private final DateFormat timeFormat;

    // 顶部控制胶囊行
    private final LinearLayout controlsRow;
    private final Button filterBtn;
    private final Button guideBtn;
    private final Button optionsBtn;

    // 中部横向频道卡片走马灯 (图 2 风格)
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

        // 底栏整体面板：半透明深色毛玻璃，位于屏幕下部
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(bottomGlassSurface());
        view.setPadding(style.dp(24), style.dp(16), style.dp(24), style.dp(16));

        // 1. 控制胶囊行 (图 2 胶囊条)
        controlsRow = new LinearLayout(context);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, -2);
        controlsParams.bottomMargin = style.dp(12);
        view.addView(controlsRow, controlsParams);

        filterBtn = style.capsuleButton("全部", actions::filter);
        guideBtn = style.capsuleButton("节目指南", actions::openGuide);
        optionsBtn = style.capsuleButton("选项", actions::options);

        addCapsule(controlsRow, filterBtn);
        addCapsule(controlsRow, guideBtn);
        addCapsule(controlsRow, optionsBtn);

        // 胶囊行右侧：金色 DTMB GUIDE 品牌标识
        View spacer = new View(context);
        controlsRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView brand = style.text(15, TvStyle.GOLD_GUIDE, true);
        brand.setText("DTMB GUIDE");
        controlsRow.addView(brand);

        // 2. 空状态提示
        emptyText = style.text(16, TvStyle.MUTED, false);
        emptyText.setPadding(style.dp(8), style.dp(16), style.dp(8), style.dp(16));
        emptyText.setVisibility(View.GONE);
        view.addView(emptyText);

        // 3. 横向电视频道走马灯 (图 2 底部横向频道卡片)
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

        // 遥控器上下导航绑定
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
                new int[]{0xf2181a22, 0xf8101115});
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

        filterBtn.setText(onlyFavorites ? "频道: 收藏 ▼" : "频道: 全部 ▼");
        emptyText.setText(message);
        emptyText.setVisibility(channels.isEmpty() ? View.VISIBLE : View.GONE);
        scroll.setVisibility(channels.isEmpty() ? View.GONE : View.VISIBLE);

        buildCards();
    }

    private void buildCards() {
        cardsContainer.removeAllViews();
        if (channels.isEmpty()) return;

        int cardWidth = style.dp(186);
        int cardHeight = style.dp(98);

        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            Program program = nowPlaying.apply(channel);

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setClickable(true);
            card.setPadding(style.dp(12), style.dp(10), style.dp(12), style.dp(10));

            // 卡片背景：未聚焦深色，聚焦时变为纯白高亮底色 (图 2 风格)
            card.setBackground(cardStateBackground());
            style.attachElevation(card, style.dp(6));

            // 1. 顶部：台号与频道名称
            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView numView = style.text(12, 0xff8c90a0, true);
            numView.setText(String.format(Locale.getDefault(), "%03d", channel.id() % 1000));
            numView.setTextColor(style.focusText(0xff8c90a0));
            topRow.addView(numView);

            TextView nameView = style.text(14, TvStyle.TEXT, true);
            nameView.setText(channel.label());
            nameView.setTextColor(style.focusText(TvStyle.TEXT));
            style.trim(nameView, 1);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
            nameLp.leftMargin = style.dp(8);
            topRow.addView(nameView, nameLp);

            card.addView(topRow);

            // 2. 中部：当前时间段
            TextView timeView = style.text(12, 0xff8c90a0, false);
            timeView.setTextColor(style.focusText(0xff8c90a0));
            style.trim(timeView, 1);
            timeView.setPadding(0, style.dp(6), 0, 0);
            if (program != null) {
                timeView.setText(timeFormat.format(new Date(program.start())) + "~");
            } else {
                timeView.setText("当前时段");
            }
            card.addView(timeView);

            // 3. 底部：正在播出的节目名
            TextView progView = style.text(13, 0xffdcdfe8, true);
            progView.setTextColor(style.focusText(0xff121316));
            style.trim(progView, 1);
            progView.setPadding(0, style.dp(3), 0, 0);
            if (program != null) {
                progView.setText(program.title());
            } else {
                progView.setText(playingId == channel.id() ? "正在播出" : "暂无节目资料");
            }
            card.addView(progView);

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

    private StateListDrawable cardStateBackground() {
        StateListDrawable drawable = new StateListDrawable();
        // 聚焦时：纯白高亮卡片 (白底黑字)
        drawable.addState(new int[]{android.R.attr.state_focused}, style.surface(TvStyle.FOCUS, 12, Color.TRANSPARENT));
        drawable.addState(new int[]{android.R.attr.state_selected}, style.surface(TvStyle.FOCUS, 12, Color.TRANSPARENT));
        // 平常时：深色半透卡片
        drawable.addState(new int[]{}, style.surface(0xe0222530, 12, 0x33ffffff));
        return drawable;
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
