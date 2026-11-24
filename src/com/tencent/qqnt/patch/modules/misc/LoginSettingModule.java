package com.tencent.qqnt.patch.modules.misc;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.PatchBootstrap;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.ui.ZzzSettingFragment;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ThemeHelper;

import java.util.ArrayList;
import java.util.List;

public class LoginSettingModule implements IPatchModule {

    private static final String TAG = "LoginSetting";
    public static final String FEATURE_ID = "login_setting";
    private static final int ROW_CONTAINER_ID = 0x7f0a47b7;
    private static final String TAG_ZZZ_BUTTON = "zzz_login_bottom_setting_btn";
    private static final String TAG_ZZZ_ICON = "zzz_login_bottom_setting_icon";
    private static final String TAG_ZZZ_LABEL = "zzz_login_bottom_setting_label";

    @Override
    public String getId() {
        return FEATURE_ID;
    }

    @Override
    public String getName() {
        return "登录页设置入口";
    }

    @Override
    public String getCategory() {
        return CATEGORY_MISC;
    }

    @Override
    public String getSubName() {
        return "在登录页底部功能栏显示 Zzz 设置按钮";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    public static boolean isModuleActive() {
        return FeatureConfig.has(FEATURE_ID) && ConfigManager.isModuleEnabled(FEATURE_ID, true);
    }

    public static void onLayoutBottomActions(Object actionsViewObj) {
        if (!(actionsViewObj instanceof ViewGroup)) return;
        ViewGroup actionsView = (ViewGroup) actionsViewObj;

        try {
            LinearLayout rowContainer = findButtonsRowContainer(actionsView);
            if (rowContainer == null) return;

            View existingBtn = rowContainer.findViewWithTag(TAG_ZZZ_BUTTON);
            if (!isModuleActive()) {
                if (existingBtn != null) {
                    rowContainer.removeView(existingBtn);
                }
                return;
            }

            List<ViewGroup> visibleNativeSlots = new ArrayList<>();
            List<View> spacerViews = new ArrayList<>();

            int childCount = rowContainer.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = rowContainer.getChildAt(i);
                if (child == null || TAG_ZZZ_BUTTON.equals(child.getTag())) continue;
                if (isButtonSlot(child)) {
                    if (child.getVisibility() == View.VISIBLE) {
                        visibleNativeSlots.add((ViewGroup) child);
                    }
                } else {
                    spacerViews.add(child);
                }
            }

            if (visibleNativeSlots.isEmpty()) return;

            Context ctx = actionsView.getContext();
            int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
            float density = ctx.getResources().getDisplayMetrics().density;
            int base360Px = (int) (360f * density + 0.5f);
            float scale = (screenWidth > 0 && base360Px > 0)
                    ? Math.min(1.0f, (float) screenWidth / (float) base360Px)
                    : 1.0f;

            ViewGroup sampleSlot = visibleNativeSlots.get(0);
            ImageView sampleIv = findFirstChildOfType(sampleSlot, ImageView.class);
            TextView sampleTv = findFirstChildOfType(sampleSlot, TextView.class);

            LinearLayout zzzBtn = (existingBtn instanceof LinearLayout)
                    ? (LinearLayout) existingBtn
                    : createZzzButton(ctx, actionsView, sampleIv, sampleTv);

            if (zzzBtn.getParent() == null) {
                rowContainer.addView(zzzBtn);
            }

            for (View spacer : spacerViews) {
                spacer.setVisibility(View.GONE);
            }

            int totalCount = visibleNativeSlots.size() + 1;
            float sidePaddingDp = (totalCount >= 5) ? 10f : (totalCount == 4 ? 20f : 36f);
            int sidePaddingPx = dp2px(ctx, sidePaddingDp * scale);
            rowContainer.setGravity(Gravity.CENTER_HORIZONTAL);
            rowContainer.setPadding(sidePaddingPx, 0, sidePaddingPx, 0);

            List<View> allVisibleButtons = new ArrayList<>(visibleNativeSlots);
            allVisibleButtons.add(zzzBtn);
            for (View btn : allVisibleButtons) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                );
                btn.setLayoutParams(lp);
                if (btn instanceof LinearLayout) {
                    ((LinearLayout) btn).setGravity(Gravity.CENTER_HORIZONTAL);
                }
            }

            updateZzzButtonMetrics(ctx, zzzBtn, scale, sampleIv, sampleTv);
        } catch (Throwable t) {
            PLog.w(TAG, "注入登录页 Zzz 按钮异常: " + t.getMessage());
        }
    }

    private static LinearLayout createZzzButton(Context ctx, View hostView, ImageView sampleIv, TextView sampleTv) {
        LinearLayout container = new LinearLayout(ctx);
        container.setTag(TAG_ZZZ_BUTTON);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView iv = new ImageView(ctx);
        iv.setTag(TAG_ZZZ_ICON);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(new ZLineIconDrawable(resolveNativeIconColor(sampleIv)));
        container.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView tv = new TextView(ctx);
        tv.setTag(TAG_ZZZ_LABEL);
        tv.setText("Zzz");
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        if (sampleTv != null && sampleTv.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams smp = (ViewGroup.MarginLayoutParams) sampleTv.getLayoutParams();
            tvLp.topMargin = smp.topMargin;
            tvLp.bottomMargin = smp.bottomMargin;
        } else {
            tvLp.topMargin = dp2px(ctx, 6f);
        }
        container.addView(tv, tvLp);

        View.OnClickListener clickListener = v -> {
            try {
                PatchBootstrap.ensureStarted();
                Activity act = resolveActivity(hostView.getContext());
                Context targetCtx = (act != null) ? act : hostView.getContext();
                ZzzSettingFragment.startCore(targetCtx);
            } catch (Throwable t) {
                PLog.e(TAG, "登录页打开 Zzz 设置异常", t);
            }
        };
        container.setOnClickListener(clickListener);
        iv.setOnClickListener(clickListener);

        syncNativeStyle(ctx, iv, tv, sampleIv, sampleTv);
        return container;
    }

    private static void updateZzzButtonMetrics(Context ctx, LinearLayout zzzBtn, float scale,
                                               ImageView sampleIv, TextView sampleTv) {
        ImageView iv = zzzBtn.findViewWithTag(TAG_ZZZ_ICON);
        TextView tv = zzzBtn.findViewWithTag(TAG_ZZZ_LABEL);

        if (iv != null) {
            int circleSize = dp2px(ctx, 48f * scale);
            int iconPad = dp2px(ctx, 14f * scale);
            ViewGroup.LayoutParams ivLp = iv.getLayoutParams();
            if (ivLp == null) {
                ivLp = new LinearLayout.LayoutParams(circleSize, circleSize);
            } else {
                ivLp.width = circleSize;
                ivLp.height = circleSize;
            }
            iv.setLayoutParams(ivLp);
            iv.setPadding(iconPad, iconPad, iconPad, iconPad);
        }

        if (tv != null) {
            tv.setTextSize(12f * scale);
        }

        syncNativeStyle(ctx, iv, tv, sampleIv, sampleTv);
    }

    private static void syncNativeStyle(Context ctx, ImageView iv, TextView tv,
                                        ImageView sampleIv, TextView sampleTv) {
        if (iv != null) {
            iv.setBackground(buildCircleBackground(ctx, sampleIv));

            Drawable iconDrawable = iv.getDrawable();
            if (iconDrawable instanceof ZLineIconDrawable) {
                ((ZLineIconDrawable) iconDrawable).setColor(resolveNativeIconColor(sampleIv));
            }
        }

        if (tv != null) {
            if (sampleTv != null) {
                ColorStateList colors = sampleTv.getTextColors();
                if (colors != null) {
                    tv.setTextColor(colors);
                }
                tv.setTypeface(sampleTv.getTypeface());
            } else {
                tv.setTextColor(Color.parseColor("#888888"));
            }
        }
    }

    /**
     * 精确构建 1dp 细描边圆形背景，避免 clone Drawable 引发 Android 密度二次放大导致边框加粗
     */
    private static Drawable buildCircleBackground(Context ctx, ImageView sampleIv) {
        int strokePx = Math.max(1, dp2px(ctx, 1f));
        boolean isNight = isNightModeSafe();
        int borderColor = resolveNativeBorderColor(ctx, sampleIv, isNight);
        int pressedFill = isNight ? Color.parseColor("#1FFFFFFF") : Color.parseColor("#14000000");

        GradientDrawable normalGd = new GradientDrawable();
        normalGd.setShape(GradientDrawable.OVAL);
        normalGd.setStroke(strokePx, borderColor);

        GradientDrawable pressedGd = new GradientDrawable();
        pressedGd.setShape(GradientDrawable.OVAL);
        pressedGd.setColor(pressedFill);
        pressedGd.setStroke(strokePx, borderColor);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressedGd);
        sld.addState(new int[0], normalGd);
        return sld;
    }

    private static int resolveNativeBorderColor(Context ctx, ImageView sampleIv, boolean isNight) {
        if (sampleIv != null) {
            try {
                Drawable bg = sampleIv.getBackground();
                if (bg != null) {
                    int size = Math.max(24, dp2px(ctx, 48f));
                    Rect oldBounds = new Rect(bg.getBounds());
                    Bitmap probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(probe);
                    bg.setBounds(0, 0, size, size);
                    bg.draw(c);
                    bg.setBounds(oldBounds);

                    int bestPx = 0;
                    int bestAlpha = 0;
                    int midX = size / 2;
                    int maxScanY = Math.min(size, dp2px(ctx, 5f) + 2);
                    for (int y = 0; y < maxScanY; y++) {
                        int px = probe.getPixel(midX, y);
                        int a = Color.alpha(px);
                        if (a > bestAlpha) {
                            bestAlpha = a;
                            bestPx = px;
                        }
                    }
                    probe.recycle();
                    if (bestAlpha > 0) {
                        return bestPx;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return isNight ? Color.parseColor("#29FFFFFF") : Color.parseColor("#1F000000");
    }

    private static int resolveNativeIconColor(ImageView sampleIv) {
        if (sampleIv != null) {
            try {
                ColorStateList tint = sampleIv.getImageTintList();
                if (tint != null) {
                    return tint.getColorForState(sampleIv.getDrawableState(), tint.getDefaultColor());
                }
            } catch (Throwable ignored) {}

            try {
                Drawable d = sampleIv.getDrawable();
                if (d != null) {
                    Rect oldBounds = new Rect(d.getBounds());
                    Bitmap probe = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(probe);
                    d.setBounds(0, 0, 24, 24);
                    d.draw(c);
                    d.setBounds(oldBounds);

                    int bestPx = 0;
                    int bestAlpha = 0;
                    for (int y = 0; y < 24; y++) {
                        for (int x = 0; x < 24; x++) {
                            int px = probe.getPixel(x, y);
                            int a = Color.alpha(px);
                            if (a > bestAlpha) {
                                bestAlpha = a;
                                bestPx = px;
                            }
                        }
                    }
                    probe.recycle();
                    if (bestAlpha >= 120) {
                        return bestPx;
                    }
                }
            } catch (Throwable ignored) {}
        }

        return isNightModeSafe() ? Color.parseColor("#EBEBF5") : Color.parseColor("#1D1D1F");
    }

    private static boolean isNightModeSafe() {
        try {
            return ThemeHelper.isNightMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static class ZLineIconDrawable extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        ZLineIconDrawable(int color) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(color);
        }

        void setColor(int color) {
            if (mPaint.getColor() != color) {
                mPaint.setColor(color);
                invalidateSelf();
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) return;

            float size = Math.min(w, h);
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float halfW = size * 0.24f;
            float halfH = size * 0.29f;

            // 细描边 (20dp 视口下约 1.36dp)，贴合 QQ 原生矢量线框粗细
            mPaint.setStrokeWidth(Math.max(1.5f, size * 0.068f));

            mPath.reset();
            mPath.moveTo(cx - halfW, cy - halfH);
            mPath.lineTo(cx + halfW, cy - halfH);
            mPath.lineTo(cx - halfW, cy + halfH);
            mPath.lineTo(cx + halfW, cy + halfH);
            canvas.drawPath(mPath, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static LinearLayout findButtonsRowContainer(ViewGroup root) {
        View byId = root.findViewById(ROW_CONTAINER_ID);
        if (byId instanceof LinearLayout && countSlotChildren((LinearLayout) byId) >= 1) {
            return (LinearLayout) byId;
        }
        return findRowContainerRecursive(root);
    }

    private static LinearLayout findRowContainerRecursive(ViewGroup vg) {
        if (vg instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) vg;
            if (ll.getOrientation() == LinearLayout.HORIZONTAL && countSlotChildren(ll) >= 2) {
                return ll;
            }
        }
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof ViewGroup) {
                LinearLayout hit = findRowContainerRecursive((ViewGroup) child);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static int countSlotChildren(LinearLayout ll) {
        int slots = 0;
        int count = ll.getChildCount();
        for (int i = 0; i < count; i++) {
            if (isButtonSlot(ll.getChildAt(i))) slots++;
        }
        return slots;
    }

    private static boolean isButtonSlot(View v) {
        if (!(v instanceof ViewGroup) || TAG_ZZZ_BUTTON.equals(v.getTag())) {
            return false;
        }
        ViewGroup vg = (ViewGroup) v;
        return findFirstChildOfType(vg, ImageView.class) != null
                && findFirstChildOfType(vg, TextView.class) != null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findFirstChildOfType(ViewGroup vg, Class<T> targetClass) {
        if (vg == null) return null;
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (targetClass.isInstance(child)) {
                return (T) child;
            }
            if (child instanceof ViewGroup) {
                T nested = findFirstChildOfType((ViewGroup) child, targetClass);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static Activity resolveActivity(Context ctx) {
        Context cur = ctx;
        while (cur instanceof ContextWrapper) {
            if (cur instanceof Activity) return (Activity) cur;
            cur = ((ContextWrapper) cur).getBaseContext();
        }
        return AppContext.getCurrentActivity();
    }

    private static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}