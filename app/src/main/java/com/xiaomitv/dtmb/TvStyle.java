package com.xiaomitv.dtmb;

import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
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
    static final int GRAPHITE = 0xff18191c;
    static final int PANEL = 0xee292b30;
    static final int TILE = 0xff303238;
    static final int GAP = 0xff202125;
    static final int EDGE = 0xff464850;
    static final int TEXT = 0xfff7f7fa;
    static final int MUTED = 0xffc0c1c9;
    static final int FOCUS = 0xfff2f2f6;
    static final int FOCUS_TEXT = 0xff18191c;
    static final int LIVE = 0xffd4e3ff;

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
        TextView view = text(30, TEXT, true);
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
                new int[]{0xf2393b42, 0xf226282d});
        drawable.setCornerRadius(dp(28));
        drawable.setStroke(dp(1), 0xff50525c);
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
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        trim(button, 1);
        button.setTextColor(focusText(TEXT));
        focusSurface(button);
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    ColorStateList focusText(int restingColor) {
        return new ColorStateList(new int[][]{DISABLED, FOCUSED, SELECTED, DEFAULT},
                new int[]{MUTED, FOCUS_TEXT, FOCUS_TEXT, restingColor});
    }

    void focusSurface(View view) {
        StateListDrawable background = new StateListDrawable();
        background.addState(FOCUSED, surface(FOCUS, 18, Color.TRANSPARENT));
        background.addState(SELECTED, surface(FOCUS, 18, Color.TRANSPARENT));
        background.addState(DEFAULT, surface(0x267a7c88, 18, Color.TRANSPARENT));
        view.setBackground(background);

        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            view.setStateListAnimator(null);
            return;
        }
        StateListAnimator animator = new StateListAnimator();
        animator.addState(FOCUSED, elevation(view, dp(4)));
        animator.addState(SELECTED, elevation(view, dp(4)));
        animator.addState(DEFAULT, elevation(view, 0));
        view.setStateListAnimator(animator);
    }

    private ObjectAnimator elevation(View view, int value) {
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Z, value).setDuration(120);
    }
}
