package com.tencent.qqnt.patch.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class QUIBadgeHelper {

    public static final int QUI_BADGE_ID = 0x7f0a5eb2;

    public static void attachNativeBadge(View root, String rightText, boolean showRedDot, boolean showArrow) {
        if (!(root instanceof ViewGroup)) return;
        try {
            ViewGroup vg = (ViewGroup) root;

            TextView rightTv = findRightTextView(vg);
            if (rightTv != null) {
                if (rightText != null && !rightText.isEmpty()) {
                    rightTv.setText(rightText);
                    rightTv.setVisibility(View.VISIBLE);
                    rightTv.setAlpha(1.0f);
                } else {
                    rightTv.setVisibility(View.GONE);
                }
            }

            ImageView arrowIv = findArrowImageView(vg);
            if (arrowIv != null) {
                arrowIv.setVisibility(showArrow ? View.VISIBLE : View.GONE);
            }

            View quiBadge = getOrCreateNativeQUIBadge(root);
            if (quiBadge != null) {
                if (showRedDot) {
                    boolean invoked = false;
                    try {
                        Method m1 = quiBadge.getClass().getMethod("setRedDot");
                        m1.invoke(quiBadge);
                        invoked = true;
                    } catch (Throwable ignored) {}

                    if (!invoked) {
                        try {
                            Method m2 = quiBadge.getClass().getMethod("setRedDot", boolean.class);
                            m2.invoke(quiBadge, true);
                        } catch (Throwable ignored) {}
                    }
                    quiBadge.setVisibility(View.VISIBLE);
                } else {
                    quiBadge.setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static View getOrCreateNativeQUIBadge(View root) {
        if (root == null) return null;
        View badge = root.findViewById(QUI_BADGE_ID);
        if (badge != null && badge.getClass().getName().contains("QUIBadge")) {
            return badge;
        }
        try {
            Class<?> rootClass = root.getClass();
            for (Field f : rootClass.getDeclaredFields()) {
                f.setAccessible(true);
                Object binding = f.get(root);
                if (binding != null && binding.getClass().getName().contains("RightBinding")) {
                    for (Method m : binding.getClass().getDeclaredMethods()) {
                        m.setAccessible(true);
                        if (m.getParameterTypes().length == 0) {
                            Class<?> returnType = m.getReturnType();
                            if (returnType.getName().contains("QUIBadge") || returnType == View.class) {
                                Object result = m.invoke(binding);
                                if (result instanceof View && result.getClass().getName().contains("QUIBadge")) {
                                    return (View) result;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (root instanceof ViewGroup) {
            return findChildByClassName((ViewGroup) root, "QUIBadge");
        }
        return null;
    }

    private static View findChildByClassName(ViewGroup vg, String classNameKeyword) {
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child != null && child.getClass().getName().contains(classNameKeyword)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View res = findChildByClassName((ViewGroup) child, classNameKeyword);
                if (res != null) return res;
            }
        }
        return null;
    }

    private static TextView findRightTextView(ViewGroup vg) {
        int count = vg.getChildCount();
        TextView lastTv = null;
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                CharSequence cs = ((TextView) child).getText();
                if (cs != null && !cs.toString().contains("Zzz") && !cs.toString().contains("动态脚本") && !cs.toString().contains("检查更新")) {
                    lastTv = (TextView) child;
                }
            } else if (child instanceof ViewGroup) {
                TextView res = findRightTextView((ViewGroup) child);
                if (res != null) lastTv = res;
            }
        }
        return lastTv;
    }

    private static ImageView findArrowImageView(ViewGroup vg) {
        int count = vg.getChildCount();
        ImageView lastIv = null;
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof ImageView) {
                lastIv = (ImageView) child;
            } else if (child instanceof ViewGroup) {
                ImageView res = findArrowImageView((ViewGroup) child);
                if (res != null) lastIv = res;
            }
        }
        return lastIv;
    }
}
