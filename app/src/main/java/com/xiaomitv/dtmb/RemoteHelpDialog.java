package com.xiaomitv.dtmb;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 小米电视标配蓝牙语音遥控器可视化按键帮助弹窗。
 * 中央采用矢量渐变拟物重绘小米标配遥控器，两侧排布 7 张应用按键功能卡片并以平滑曲线箭头精准连线。
 */
final class RemoteHelpDialog extends Dialog {

    private enum KeyId {
        UP, LEFT, BACK,
        RIGHT, OK, DOWN, MENU
    }

    private record KeySpec(
            KeyId id,
            boolean leftSide,
            int row,
            String title,
            String badge,
            String slot1Label,
            String slot1Action,
            String slot2Label,
            String slot2Action
    ) { }

    private final TvStyle style;
    private final RemoteDiagramView diagramView;

    RemoteHelpDialog(Context context, TvStyle style) {
        super(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        this.style = style;

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.requestFeature(Window.FEATURE_NO_TITLE);
            window.setDimAmount(0.76f);
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(dialogSurface());
        int padH = style.dp(22);
        int padV = style.dp(16);
        root.setPadding(padH, padV, padH, padV);

        // 1. 顶部标题与操作提示栏
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleGroup = new LinearLayout(context);
        titleGroup.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = style.text(20, TvStyle.TEXT, true);
        titleView.setText(R.string.help_dialog_title);
        style.setLeadingIcon(titleView, R.drawable.ic_help, 20);
        titleGroup.addView(titleView);

        TextView subtitleView = style.text(12, TvStyle.MUTED, false);
        subtitleView.setText(R.string.help_dialog_subtitle);
        subtitleView.setPadding(0, style.dp(2), 0, 0);
        style.trim(subtitleView, 1);
        titleGroup.addView(subtitleView);

        header.addView(titleGroup, new LinearLayout.LayoutParams(0, -2, 1f));

        Button closeBtn = style.capsuleButton(context.getString(R.string.action_close), this::dismiss);
        closeBtn.setFocusable(false);
        header.addView(closeBtn, new LinearLayout.LayoutParams(-2, -2));

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // 顶部分割细线
        View divider = new View(context);
        divider.setBackgroundColor(0x22ffffff);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, style.dp(1));
        divParams.setMargins(0, style.dp(10), 0, style.dp(10));
        root.addView(divider, divParams);

        // 2. 主体遥控器图解与连线视图
        diagramView = new RemoteDiagramView(context, buildSpecs(context));
        diagramView.setFocusable(true);
        diagramView.setFocusableInTouchMode(true);
        root.addView(diagramView, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            int displayWidth = getContext().getResources().getDisplayMetrics().widthPixels;
            int displayHeight = getContext().getResources().getDisplayMetrics().heightPixels;
            int dialogWidth = Math.min(style.dp(910), (int) (displayWidth * 0.93f));
            int dialogHeight = Math.min(style.dp(460), (int) (displayHeight * 0.90f));
            window.setLayout(dialogWidth, dialogHeight);
            window.setGravity(Gravity.CENTER);
        }
        diagramView.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // 方向键与功能键按下时实时联动高亮对应卡片与引线
        if (action == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    diagramView.setActiveKey(KeyId.UP);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    diagramView.setActiveKey(KeyId.DOWN);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_INFO -> {
                    diagramView.setActiveKey(KeyId.LEFT);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE -> {
                    diagramView.setActiveKey(KeyId.RIGHT);
                    return true;
                }
                case KeyEvent.KEYCODE_MENU -> {
                    diagramView.setActiveKey(KeyId.MENU);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    diagramView.setActiveKey(KeyId.OK);
                    return true;
                }
                case KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    diagramView.setActiveKey(KeyId.BACK);
                    return true;
                }
                default -> { }
            }
        } else if (action == KeyEvent.ACTION_UP && !event.isCanceled()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> {
                    dismiss();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_INFO,
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE,
                        KeyEvent.KEYCODE_MENU -> {
                    return true;
                }
                default -> { }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private GradientDrawable dialogSurface() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xf71b1e28, 0xfb111319});
        drawable.setCornerRadius(style.dp(20));
        drawable.setStroke(style.dp(1), 0x3affffff);
        return drawable;
    }

    private static List<KeySpec> buildSpecs(Context ctx) {
        String full = ctx.getString(R.string.help_slot_fullscreen);
        String panel = ctx.getString(R.string.help_slot_panel);

        List<KeySpec> list = new ArrayList<>(7);
        // 左列 3 张（自上而下严格单调不交叉）
        list.add(new KeySpec(KeyId.UP, true, 0,
                ctx.getString(R.string.help_key_up), ctx.getString(R.string.help_badge_channel),
                full, ctx.getString(R.string.help_up_full),
                panel, ctx.getString(R.string.help_up_panel)));
        list.add(new KeySpec(KeyId.LEFT, true, 1,
                ctx.getString(R.string.help_key_left), ctx.getString(R.string.help_badge_info),
                full, ctx.getString(R.string.help_left_full),
                panel, ctx.getString(R.string.help_left_panel)));
        list.add(new KeySpec(KeyId.BACK, true, 2,
                ctx.getString(R.string.help_key_back), ctx.getString(R.string.help_badge_nav),
                panel, ctx.getString(R.string.help_back_panel),
                full, ctx.getString(R.string.help_back_full)));

        // 右列 4 张（自上而下严格单调不交叉）
        list.add(new KeySpec(KeyId.RIGHT, false, 0,
                ctx.getString(R.string.help_key_right), ctx.getString(R.string.help_badge_guide),
                full, ctx.getString(R.string.help_right_full),
                panel, ctx.getString(R.string.help_right_panel)));
        list.add(new KeySpec(KeyId.OK, false, 1,
                ctx.getString(R.string.help_key_ok), ctx.getString(R.string.help_badge_core),
                full, ctx.getString(R.string.help_ok_full),
                panel, ctx.getString(R.string.help_ok_panel)));
        list.add(new KeySpec(KeyId.DOWN, false, 2,
                ctx.getString(R.string.help_key_down), ctx.getString(R.string.help_badge_channel),
                full, ctx.getString(R.string.help_down_full),
                panel, ctx.getString(R.string.help_down_panel)));
        list.add(new KeySpec(KeyId.MENU, false, 3,
                ctx.getString(R.string.help_key_menu), ctx.getString(R.string.help_badge_menu),
                full, ctx.getString(R.string.help_menu_full),
                panel, ctx.getString(R.string.help_menu_panel)));
        return list;
    }

    /**
     * 自定义矢量画布：负责绘制中央小米标配遥控器、两侧说明卡片及三次贝塞尔曲线引线。
     */
    private final class RemoteDiagramView extends View {
        private static final int ACCENT_BLUE = 0xff3b82f6;
        private static final int ACCENT_BLUE_BRIGHT = 0xff60a5fa;
        private static final int SYSTEM_ICON_MUTED = 0x5594a3b8;
        private static final int POWER_ORANGE_MUTED = 0x66e86c3a;

        private final List<KeySpec> specs;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint slotLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint slotValuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF subRect = new RectF();
        private final Path path = new Path();
        private final RectF[] cardBounds;

        private KeyId activeKey = KeyId.OK;

        RemoteDiagramView(Context context, List<KeySpec> specs) {
            super(context);
            this.specs = specs;
            this.cardBounds = new RectF[specs.size()];
            for (int i = 0; i < cardBounds.length; i++) {
                cardBounds[i] = new RectF();
            }

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);

            Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

            titlePaint.setTypeface(medium);
            titlePaint.setTextSize(style.dp(13));

            badgePaint.setTypeface(medium);
            badgePaint.setTextSize(style.dp(10));

            slotLabelPaint.setTypeface(regular);
            slotLabelPaint.setTextSize(style.dp(9.5f));

            slotValuePaint.setTypeface(medium);
            slotValuePaint.setTextSize(style.dp(11));
        }

        void setActiveKey(KeyId key) {
            if (activeKey != key) {
                activeKey = key;
                invalidate();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                float x = event.getX();
                float y = event.getY();
                for (int i = 0; i < specs.size(); i++) {
                    if (cardBounds[i].contains(x, y)) {
                        setActiveKey(specs.get(i).id());
                        return true;
                    }
                }
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float cx = w / 2f;
            float remoteH = h * 0.96f;
            float remoteW = Math.min(remoteH * 0.252f, w * 0.136f);
            float remoteTop = (h - remoteH) / 2f;
            float remoteBottom = remoteTop + remoteH;
            float remoteLeft = cx - remoteW / 2f;
            float remoteRight = cx + remoteW / 2f;

            float corridorW = Math.max(style.dp(42), w * 0.064f);
            float leftCardL = style.dp(2);
            float leftCardR = remoteLeft - corridorW;
            float rightCardL = remoteRight + corridorW;
            float rightCardR = w - style.dp(2);

            float rowGap = style.dp(10);
            int maxRows = 4;
            int leftRows = 3;
            float cardH = Math.min(style.dp(82), (h - (maxRows - 1) * rowGap) / maxRows);
            float rightStartY = (h - (maxRows * cardH + (maxRows - 1) * rowGap)) / 2f;
            float leftStartY = (h - (leftRows * cardH + (leftRows - 1) * rowGap)) / 2f;

            // 计算遥控器各物理按键几何参数
            float rSmall = remoteW * 0.118f;
            float powerY = remoteTop + remoteH * 0.068f;
            float voiceY = remoteTop + remoteH * 0.148f;

            float dpadY = remoteTop + remoteH * 0.305f;
            float rRing = remoteW * 0.415f;
            float rOk = remoteW * 0.185f;

            float funcY = remoteTop + remoteH * 0.468f;
            float rFunc = remoteW * 0.112f;
            float dxFunc = remoteW * 0.295f;
            float homeX = cx - dxFunc;
            float menuX = cx + dxFunc;

            float volTop = remoteTop + remoteH * 0.538f;
            float volBottom = remoteTop + remoteH * 0.692f;
            float volHalfW = remoteW * 0.116f;
            float volUpY = volTop + (volBottom - volTop) * 0.24f;
            float volDownY = volTop + (volBottom - volTop) * 0.76f;

            // 1. 绘制中央小米标配蓝牙语音遥控器
            drawXiaomiRemote(canvas, cx, remoteLeft, remoteTop, remoteRight, remoteBottom, remoteW,
                    rSmall, powerY, voiceY, dpadY, rRing, rOk,
                    funcY, rFunc, homeX, menuX, volTop, volBottom, volHalfW, volUpY, volDownY);

            // 2. 绘制左右两侧共 7 张应用按键说明卡片
            for (int i = 0; i < specs.size(); i++) {
                KeySpec spec = specs.get(i);
                float startY = spec.leftSide() ? leftStartY : rightStartY;
                float top = startY + spec.row() * (cardH + rowGap);
                float bottom = top + cardH;
                float left = spec.leftSide() ? leftCardL : rightCardL;
                float right = spec.leftSide() ? leftCardR : rightCardR;
                cardBounds[i].set(left, top, right, bottom);
                drawKeyCard(canvas, spec, cardBounds[i], spec.id() == activeKey);
            }

            // 3. 先绘制未激活按键的柔和引线，最后绘制激活按键的高亮蓝色引线
            KeySpec highlighted = null;
            RectF highlightedBounds = null;
            for (int i = 0; i < specs.size(); i++) {
                KeySpec spec = specs.get(i);
                if (spec.id() == activeKey) {
                    highlighted = spec;
                    highlightedBounds = cardBounds[i];
                    continue;
                }
                drawConnectorForSpec(canvas, spec, cardBounds[i], false,
                        cx, dpadY, rRing, rOk, funcY, rFunc, menuX);
            }
            if (highlighted != null) {
                drawConnectorForSpec(canvas, highlighted, highlightedBounds, true,
                        cx, dpadY, rRing, rOk, funcY, rFunc, menuX);
            }
        }

        private void drawXiaomiRemote(Canvas canvas, float cx,
                                      float left, float top, float right, float bottom, float width,
                                      float rSmall, float powerY, float voiceY,
                                      float dpadY, float rRing, float rOk,
                                      float funcY, float rFunc, float homeX, float menuX,
                                      float volTop, float volBottom, float volHalfW,
                                      float volUpY, float volDownY) {
            float bodyCorner = width * 0.165f;

            // 机身底部投影
            paint.setShader(null);
            paint.setColor(0x66000000);
            rect.set(left + style.dp(2), top + style.dp(5), right + style.dp(2), bottom + style.dp(5));
            canvas.drawRoundRect(rect, bodyCorner, bodyCorner, paint);

            // 磨砂黑微弧机身横向立体光影渐变
            paint.setShader(new LinearGradient(left, top, right, top,
                    new int[]{0xff343842, 0xff1f2229, 0xff272b33, 0xff1b1d23, 0xff2d313a},
                    new float[]{0f, 0.16f, 0.5f, 0.84f, 1f},
                    Shader.TileMode.CLAMP));
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, bodyCorner, bodyCorner, paint);

            // 机身纵向微弱顶光渐变罩层
            paint.setShader(new LinearGradient(cx, top, cx, bottom,
                    0x14ffffff, 0x26000000, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, bodyCorner, bodyCorner, paint);
            paint.setShader(null);

            // 机身边缘高光描边
            strokePaint.setStrokeWidth(style.dp(1.1f));
            strokePaint.setColor(0xff424754);
            canvas.drawRoundRect(rect, bodyCorner, bodyCorner, strokePaint);

            // (1) 顶部电源键（系统按键，弱化显示仅作外形参照）
            drawRoundButton(canvas, cx, powerY, rSmall, false);
            drawPowerIcon(canvas, cx, powerY, rSmall * 0.50f);

            // (2) 语音键（系统按键，弱化显示仅作外形参照）
            drawRoundButton(canvas, cx, voiceY, rSmall, false);
            drawMicIcon(canvas, cx, voiceY, rSmall * 0.52f);

            // (3) 四向导航大圆环与中心内凹确定键
            // 外凹槽黑圈
            paint.setColor(0xff0f1014);
            canvas.drawCircle(cx, dpadY, rRing + style.dp(1.3f), paint);

            // 方向环主体表面
            paint.setShader(new LinearGradient(cx, dpadY - rRing, cx, dpadY + rRing,
                    0xff2c3039, 0xff1a1c22, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, dpadY, rRing, paint);
            paint.setShader(null);

            strokePaint.setStrokeWidth(style.dp(0.9f));
            strokePaint.setColor(0x2affffff);
            canvas.drawCircle(cx, dpadY, rRing, strokePaint);

            // 方向环四向激活扇区光晕（当上/下/左/右处于高亮时点亮对应方位）
            drawDpadHighlight(canvas, cx, dpadY, rRing);

            // 中心确定键凹槽与内凹碗状光影（左上暗、右下柔和反光）
            paint.setColor(0xff0d0f13);
            canvas.drawCircle(cx, dpadY, rOk + style.dp(1.2f), paint);

            paint.setShader(new RadialGradient(
                    cx + rOk * 0.32f, dpadY + rOk * 0.35f, rOk * 1.35f,
                    new int[]{0xff383d48, 0xff20232b, 0xff121419},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, dpadY, rOk, paint);
            paint.setShader(null);

            strokePaint.setStrokeWidth(style.dp(activeKey == KeyId.OK ? 1.8f : 0.9f));
            strokePaint.setColor(activeKey == KeyId.OK ? ACCENT_BLUE_BRIGHT : 0x24ffffff);
            canvas.drawCircle(cx, dpadY, rOk, strokePaint);

            // (4) 主页键 / 返回键 / 菜单键（左=主页[系统]、中=返回、右=菜单）
            drawRoundButton(canvas, homeX, funcY, rFunc, false);
            drawHomeIcon(canvas, homeX, funcY, rFunc * 0.48f);

            drawRoundButton(canvas, cx, funcY, rFunc, activeKey == KeyId.BACK);
            drawBackIcon(canvas, cx, funcY, rFunc * 0.44f);

            drawRoundButton(canvas, menuX, funcY, rFunc, activeKey == KeyId.MENU);
            drawMenuIcon(canvas, menuX, funcY, rFunc * 0.45f);

            // (5) 音量加减竖向一体胶囊键（系统按键，弱化显示仅作外形参照）
            rect.set(cx - volHalfW - style.dp(1f), volTop - style.dp(1f),
                    cx + volHalfW + style.dp(1f), volBottom + style.dp(1f));
            paint.setColor(0xff0e1014);
            canvas.drawRoundRect(rect, volHalfW + style.dp(1f), volHalfW + style.dp(1f), paint);

            rect.set(cx - volHalfW, volTop, cx + volHalfW, volBottom);
            paint.setShader(new LinearGradient(cx, volTop, cx, volBottom,
                    0xff2b2f38, 0xff1c1f26, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, volHalfW, volHalfW, paint);
            paint.setShader(null);

            strokePaint.setStrokeWidth(style.dp(0.9f));
            strokePaint.setColor(0x2affffff);
            canvas.drawRoundRect(rect, volHalfW, volHalfW, strokePaint);

            // 音量 + 与 - 符号
            iconPaint.setStrokeWidth(style.dp(1.5f));
            iconPaint.setColor(SYSTEM_ICON_MUTED);
            float plusR = volHalfW * 0.36f;
            canvas.drawLine(cx - plusR, volUpY, cx + plusR, volUpY, iconPaint);
            canvas.drawLine(cx, volUpY - plusR, cx, volUpY + plusR, iconPaint);
            canvas.drawLine(cx - plusR, volDownY, cx + plusR, volDownY, iconPaint);
        }

        private void drawDpadHighlight(Canvas canvas, float cx, float cy, float rRing) {
            float dotOffset = rRing * 0.72f;
            float spotR = rRing * 0.24f;
            float hx = cx;
            float hy = cy;
            if (activeKey == KeyId.UP) hy -= dotOffset;
            else if (activeKey == KeyId.DOWN) hy += dotOffset;
            else if (activeKey == KeyId.LEFT) hx -= dotOffset;
            else if (activeKey == KeyId.RIGHT) hx += dotOffset;
            else return;

            paint.setShader(new RadialGradient(hx, hy, spotR,
                    0x663b82f6, 0x003b82f6, Shader.TileMode.CLAMP));
            canvas.drawCircle(hx, hy, spotR, paint);
            paint.setShader(null);
        }

        private void drawRoundButton(Canvas canvas, float x, float y, float r, boolean highlighted) {
            paint.setShader(null);
            paint.setColor(0xff0e1014);
            canvas.drawCircle(x, y, r + style.dp(1f), paint);

            paint.setShader(new LinearGradient(x, y - r, x, y + r,
                    highlighted ? 0xff2b3b55 : 0xff2b2f38,
                    highlighted ? 0xff1c2738 : 0xff1b1e24,
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, paint);
            paint.setShader(null);

            strokePaint.setStrokeWidth(style.dp(highlighted ? 1.7f : 0.85f));
            strokePaint.setColor(highlighted ? ACCENT_BLUE_BRIGHT : 0x28ffffff);
            canvas.drawCircle(x, y, r, strokePaint);
        }

        private void drawPowerIcon(Canvas canvas, float cx, float cy, float r) {
            iconPaint.setStrokeWidth(style.dp(1.45f));
            iconPaint.setColor(POWER_ORANGE_MUTED);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(rect, -58f, 296f, false, iconPaint);
            canvas.drawLine(cx, cy - r * 1.12f, cx, cy - r * 0.12f, iconPaint);
        }

        private void drawMicIcon(Canvas canvas, float cx, float cy, float r) {
            iconPaint.setStrokeWidth(style.dp(1.35f));
            iconPaint.setColor(SYSTEM_ICON_MUTED);
            float capW = r * 0.42f;
            rect.set(cx - capW, cy - r * 0.92f, cx + capW, cy + r * 0.16f);
            canvas.drawRoundRect(rect, capW, capW, iconPaint);
            rect.set(cx - r * 0.74f, cy - r * 0.46f, cx + r * 0.74f, cy + r * 0.52f);
            canvas.drawArc(rect, 0f, 180f, false, iconPaint);
            canvas.drawLine(cx, cy + r * 0.52f, cx, cy + r * 0.92f, iconPaint);
        }

        private void drawHomeIcon(Canvas canvas, float cx, float cy, float r) {
            iconPaint.setStrokeWidth(style.dp(1.4f));
            iconPaint.setColor(SYSTEM_ICON_MUTED);
            path.reset();
            path.moveTo(cx, cy - r * 0.92f);
            path.lineTo(cx + r * 0.88f, cy - r * 0.12f);
            path.lineTo(cx + r * 0.68f, cy + r * 0.82f);
            path.lineTo(cx - r * 0.68f, cy + r * 0.82f);
            path.lineTo(cx - r * 0.88f, cy - r * 0.12f);
            path.close();
            canvas.drawPath(path, iconPaint);
        }

        private void drawBackIcon(Canvas canvas, float cx, float cy, float r) {
            iconPaint.setStrokeWidth(style.dp(1.5f));
            iconPaint.setColor(0xffe6e9f0);
            path.reset();
            path.moveTo(cx + r * 0.36f, cy - r * 0.78f);
            path.lineTo(cx - r * 0.48f, cy);
            path.lineTo(cx + r * 0.36f, cy + r * 0.78f);
            canvas.drawPath(path, iconPaint);
        }

        private void drawMenuIcon(Canvas canvas, float cx, float cy, float r) {
            iconPaint.setStrokeWidth(style.dp(1.4f));
            iconPaint.setColor(0xffe6e9f0);
            float w = r * 0.78f;
            float gap = r * 0.58f;
            canvas.drawLine(cx - w, cy - gap, cx + w, cy - gap, iconPaint);
            canvas.drawLine(cx - w, cy, cx + w, cy, iconPaint);
            canvas.drawLine(cx - w, cy + gap, cx + w, cy + gap, iconPaint);
        }

        private void drawKeyCard(Canvas canvas, KeySpec spec, RectF bounds, boolean active) {
            float corner = style.dp(10);

            // 卡片底色与外框
            paint.setShader(null);
            paint.setColor(active ? 0xf21d283a : 0xde1a1d26);
            canvas.drawRoundRect(bounds, corner, corner, paint);

            strokePaint.setStrokeWidth(style.dp(active ? 1.6f : 1f));
            strokePaint.setColor(active ? ACCENT_BLUE_BRIGHT : 0x26ffffff);
            canvas.drawRoundRect(bounds, corner, corner, strokePaint);

            float padX = style.dp(10);
            float headerCenterY = bounds.top + style.dp(14);

            // 头部左侧按键小图标
            float iconCx = bounds.left + padX + style.dp(7);
            drawCardKeyMiniIcon(canvas, spec.id(), iconCx, headerCenterY, style.dp(5.5f), active);

            // 右上角场景胶囊标签
            float badgePadH = style.dp(7);
            float badgeTextW = badgePaint.measureText(spec.badge());
            float badgeW = badgeTextW + badgePadH * 2f;
            float badgeH = style.dp(16);
            float badgeRight = bounds.right - padX;
            float badgeLeft = badgeRight - badgeW;
            subRect.set(badgeLeft, headerCenterY - badgeH / 2f, badgeRight, headerCenterY + badgeH / 2f);
            paint.setColor(active ? 0x3360a5fa : 0xff262a37);
            canvas.drawRoundRect(subRect, badgeH / 2f, badgeH / 2f, paint);

            badgePaint.setColor(active ? 0xff93c5fd : 0xff94a3b8);
            Paint.FontMetrics badgeFm = badgePaint.getFontMetrics();
            float badgeBaseline = headerCenterY - (badgeFm.ascent + badgeFm.descent) / 2f;
            canvas.drawText(spec.badge(), badgeLeft + badgePadH, badgeBaseline, badgePaint);

            // 头部按键标题
            titlePaint.setColor(active ? 0xffffffff : 0xfff1f5f9);
            float titleLeft = iconCx + style.dp(11);
            float maxTitleW = Math.max(0, badgeLeft - style.dp(6) - titleLeft);
            String clippedTitle = TextUtils.ellipsize(spec.title(), titlePaint, maxTitleW,
                    TextUtils.TruncateAt.END).toString();
            Paint.FontMetrics titleFm = titlePaint.getFontMetrics();
            float titleBaseline = headerCenterY - (titleFm.ascent + titleFm.descent) / 2f;
            canvas.drawText(clippedTitle, titleLeft, titleBaseline, titlePaint);

            // 下半部动作子槽位（分栏灰底圆角槽位）
            float slotTop = bounds.top + style.dp(28);
            float slotBottom = bounds.bottom - style.dp(7);
            if (slotBottom <= slotTop + style.dp(12)) return;

            float gap = style.dp(6);
            float totalW = bounds.width() - padX * 2f;
            float eachW = (totalW - gap) / 2f;
            subRect.set(bounds.left + padX, slotTop, bounds.left + padX + eachW, slotBottom);
            drawActionSlot(canvas, subRect, spec.slot1Label(), spec.slot1Action(), active, true);

            subRect.set(bounds.left + padX + eachW + gap, slotTop, bounds.right - padX, slotBottom);
            drawActionSlot(canvas, subRect, spec.slot2Label(), spec.slot2Action(), active, false);
        }

        private void drawActionSlot(Canvas canvas, RectF slotRect, String label, String value,
                                    boolean cardActive, boolean primarySlot) {
            float r = style.dp(6);
            paint.setColor(cardActive
                    ? (primarySlot ? 0xff16243b : 0xff151e2e)
                    : 0xff13151d);
            canvas.drawRoundRect(slotRect, r, r, paint);

            float innerPad = style.dp(6);
            float maxW = Math.max(0, slotRect.width() - innerPad * 2f);
            float centerX = slotRect.centerX();

            slotLabelPaint.setTextAlign(Paint.Align.CENTER);
            slotLabelPaint.setColor(0xff7e869a);
            String clippedLabel = TextUtils.ellipsize(label, slotLabelPaint, maxW,
                    TextUtils.TruncateAt.END).toString();

            slotValuePaint.setTextAlign(Paint.Align.CENTER);
            if (primarySlot) {
                slotValuePaint.setColor(cardActive ? 0xff93c5fd : 0xff60a5fa);
            } else {
                slotValuePaint.setColor(0xffcbd5e1);
            }
            String clippedValue = TextUtils.ellipsize(value, slotValuePaint, maxW,
                    TextUtils.TruncateAt.END).toString();

            float midY = slotRect.centerY();
            canvas.drawText(clippedLabel, centerX, midY - style.dp(2.5f), slotLabelPaint);
            canvas.drawText(clippedValue, centerX, midY + style.dp(10.5f), slotValuePaint);
        }

        private void drawCardKeyMiniIcon(Canvas canvas, KeyId id, float cx, float cy,
                                         float r, boolean active) {
            int color = active ? ACCENT_BLUE_BRIGHT : 0xffcbd5e1;
            iconPaint.setColor(color);
            iconPaint.setStrokeWidth(style.dp(1.4f));
            switch (id) {
                case MENU -> drawMenuIcon(canvas, cx, cy, r * 0.85f);
                case BACK -> drawBackIcon(canvas, cx, cy, r * 0.85f);
                case OK -> {
                    canvas.drawCircle(cx, cy, r * 0.95f, iconPaint);
                    paint.setColor(color);
                    canvas.drawCircle(cx, cy, r * 0.36f, paint);
                }
                case UP -> {
                    path.reset();
                    path.moveTo(cx - r * 0.8f, cy + r * 0.35f);
                    path.lineTo(cx, cy - r * 0.45f);
                    path.lineTo(cx + r * 0.8f, cy + r * 0.35f);
                    canvas.drawPath(path, iconPaint);
                }
                case DOWN -> {
                    path.reset();
                    path.moveTo(cx - r * 0.8f, cy - r * 0.35f);
                    path.lineTo(cx, cy + r * 0.45f);
                    path.lineTo(cx + r * 0.8f, cy - r * 0.35f);
                    canvas.drawPath(path, iconPaint);
                }
                case LEFT -> {
                    path.reset();
                    path.moveTo(cx + r * 0.35f, cy - r * 0.8f);
                    path.lineTo(cx - r * 0.45f, cy);
                    path.lineTo(cx + r * 0.35f, cy + r * 0.8f);
                    canvas.drawPath(path, iconPaint);
                }
                case RIGHT -> {
                    path.reset();
                    path.moveTo(cx - r * 0.35f, cy - r * 0.8f);
                    path.lineTo(cx + r * 0.45f, cy);
                    path.lineTo(cx - r * 0.35f, cy + r * 0.8f);
                    canvas.drawPath(path, iconPaint);
                }
            }
        }

        private void drawConnectorForSpec(Canvas canvas, KeySpec spec, RectF card, boolean active,
                                          float cx, float dpadY, float rRing, float rOk,
                                          float funcY, float rFunc, float menuX) {
            float sx;
            float sy;
            switch (spec.id()) {
                case UP -> {
                    sx = cx - rRing * 0.36f;
                    sy = dpadY - rRing * 0.78f;
                }
                case LEFT -> {
                    sx = cx - rRing * 0.85f;
                    sy = dpadY;
                }
                case BACK -> {
                    sx = cx - rFunc * 0.35f;
                    sy = funcY + rFunc * 0.88f;
                }
                case RIGHT -> {
                    sx = cx + rRing * 0.85f;
                    sy = dpadY - rRing * 0.12f;
                }
                case OK -> {
                    sx = cx + rOk * 0.32f;
                    sy = dpadY + rOk * 0.08f;
                }
                case DOWN -> {
                    sx = cx + rRing * 0.36f;
                    sy = dpadY + rRing * 0.78f;
                }
                case MENU -> {
                    sx = menuX + rFunc * 0.85f;
                    sy = funcY;
                }
                default -> {
                    return;
                }
            }

            float tipMargin = style.dp(3.5f);
            float ex = spec.leftSide() ? card.right + tipMargin : card.left - tipMargin;
            float ey = card.centerY();
            float arrowLen = style.dp(active ? 6.5f : 5f);
            float arrowHalfH = style.dp(active ? 3.8f : 2.9f);
            float dir = spec.leftSide() ? 1f : -1f;
            float lineEndX = ex + dir * arrowLen;

            // 三次贝塞尔平滑控制点（曲线停在箭头底边中点，避免半透明重叠）
            float spanX = lineEndX - sx;
            float c1x = sx + spanX * 0.45f;
            float c1y = sy + (spec.id() == KeyId.BACK ? style.dp(10) : 0f);
            float c2x = sx + spanX * 0.62f;
            float c2y = ey;

            path.reset();
            path.moveTo(sx, sy);
            path.cubicTo(c1x, c1y, c2x, c2y, lineEndX, ey);

            strokePaint.setStrokeWidth(style.dp(active ? 2.2f : 1.15f));
            strokePaint.setColor(active ? ACCENT_BLUE : 0x5694a3b8);
            canvas.drawPath(path, strokePaint);

            // 激活态在遥控器按键端加小圆点锚点
            if (active) {
                paint.setColor(ACCENT_BLUE_BRIGHT);
                canvas.drawCircle(sx, sy, style.dp(2.6f), paint);
            }

            // 卡片侧实心三角小箭头
            path.reset();
            path.moveTo(ex, ey);
            path.lineTo(lineEndX, ey - arrowHalfH);
            path.lineTo(lineEndX, ey + arrowHalfH);
            path.close();
            paint.setColor(active ? ACCENT_BLUE : 0x7294a3b8);
            canvas.drawPath(path, paint);
        }
    }
}
