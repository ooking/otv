package com.xiaomitv.dtmb;

import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

final class TvStyle {
    // 现代深色电视主题配色
    static final int BG_DARK = 0xff101115;
    static final int GRAPHITE = 0xff14151a;
    static final int PANEL = 0xf01a1c23;
    static final int TILE = 0xff22242c;
    static final int TILE_BORDER = 0xff353844;
    static final int GAP = 0xff17181f;
    static final int EDGE = 0xff323540;
    static final int TEXT = 0xfff5f6fa;
    static final int MUTED = 0xff8c90a0;
    static final int FOCUS = 0xffffffff;
    static final int FOCUS_TEXT = 0xff101115;
    static final int RED_LIVE = 0xffd32f2f;
    static final int GOLD_GUIDE = 0xfff59e0b;
    static final int PILL_DEFAULT = 0xff282b35;

    private static final int[] FOCUSED = {android.R.attr.state_focused};
    private static final int[] SELECTED = {android.R.attr.state_selected};
    private static final int[] DISABLED = {-android.R.attr.state_enabled};
    private static final int[] DEFAULT = {};

    private final Context context;

    TvStyle(Context context) {
        this.context = context;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    int insetX() {
        return dp(Math.max(24, context.getResources().getConfiguration().screenWidthDp * 0.036f));
    }

    int insetY() {
        return dp(Math.max(18, context.getResources().getConfiguration().screenHeightDp * 0.045f));
    }

    TextView text(int size, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    TextView title(String label) {
        TextView view = text(26, TEXT, true);
        view.setText(label);
        return view;
    }

    void trim(TextView view, int lines) {
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
    }

    GradientDrawable surface(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    GradientDrawable glassSurface() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xf520222a, 0xf514151a});
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), 0xff383b48);
        return drawable;
    }

    LinearLayout panel(int horizontalPadding, int verticalPadding) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(glassSurface());
        panel.setPadding(dp(horizontalPadding), dp(verticalPadding), dp(horizontalPadding),
                dp(verticalPadding));
        return panel;
    }

    ListView list() {
        ListView list = new ListView(context);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        return list;
    }

    Button button(String label, Runnable onClick) {
        return capsuleButton(label, onClick);
    }

    Button capsuleButton(String label, Runnable onClick) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(14), dp(6), dp(14), dp(6));
        trim(button, 1);
        button.setTextColor(focusText(TEXT));
        capsuleSurface(button);
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    Button capsuleButton(String label, int iconRes, Runnable onClick) {
        Button button = capsuleButton(label, onClick);
        setLeadingIcon(button, iconRes, 16);
        return button;
    }

    void setLeadingIcon(TextView view, int iconRes, int sizeDp) {
        Drawable icon = context.getDrawable(iconRes).mutate();
        int size = dp(sizeDp);
        icon.setBounds(0, 0, size, size);
        view.setCompoundDrawablePadding(dp(8));
        view.setCompoundDrawablesRelative(icon, null, null, null);
        view.setCompoundDrawableTintList(view.getTextColors());
    }

    ColorStateList focusText(int restingColor) {
        return new ColorStateList(new int[][]{DISABLED, FOCUSED, SELECTED, DEFAULT},
                new int[]{MUTED, FOCUS_TEXT, FOCUS_TEXT, restingColor});
    }

    void capsuleSurface(View view) {
        StateListDrawable background = new StateListDrawable();
        background.addState(FOCUSED, surface(FOCUS, 18, Color.TRANSPARENT));
        background.addState(SELECTED, surface(FOCUS, 18, Color.TRANSPARENT));
        background.addState(DEFAULT, surface(PILL_DEFAULT, 18, 0x33ffffff));
        view.setBackground(background);
        attachElevation(view, dp(3));
    }

    void focusSurface(View view) {
        StateListDrawable background = new StateListDrawable();
        background.addState(FOCUSED, surface(FOCUS, 12, Color.TRANSPARENT));
        background.addState(SELECTED, surface(FOCUS, 12, Color.TRANSPARENT));
        background.addState(DEFAULT, surface(TILE, 12, TILE_BORDER));
        view.setBackground(background);
        attachElevation(view, dp(4));
    }

    void attachElevation(View view, int height) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            view.setStateListAnimator(null);
            return;
        }
        StateListAnimator animator = new StateListAnimator();
        animator.addState(FOCUSED, elevation(view, height));
        animator.addState(SELECTED, elevation(view, height));
        animator.addState(DEFAULT, elevation(view, 0));
        view.setStateListAnimator(animator);
    }

    private ObjectAnimator elevation(View view, int value) {
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Z, value).setDuration(120);
    }
}
