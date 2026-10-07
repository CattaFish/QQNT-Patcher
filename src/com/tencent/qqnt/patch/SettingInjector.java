package com.tencent.qqnt.patch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.View;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class SettingInjector {
    private static final String TAG = "QQ_DEBUG";
    private static final String TITLE_ZZZ = "Zzz";
    private static final String TITLE_SCRIPTS = "动态脚本";

    interface ViewBindCallback {
        void onBind(View view);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void inject(Context context, List resultList, String itemClassName) {
        if (context == null || resultList == null || resultList.isEmpty() || itemClassName == null) {
            return;
        }

        // 如果 setting 特性未激活，直接不挂载任何设置入口
        if (!FeatureConfig.has("setting")) {
            return;
        }

        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> itemClass = cl.loadClass(itemClassName);
            Class<?> func0Class = cl.loadClass("kotlin.jvm.functions.Function0");
            Class<?> func1Class = cl.loadClass("kotlin.jvm.functions.Function1");
            Object unitInstance = getKotlinUnitInstance(cl);

            // =========================================================
            // 1. Zzz 项
            // =========================================================
            CharSequence finalZzzTitle = createTitleWithIcon(context, "zzz_icon.png", TITLE_ZZZ, 28f);
            Object zzzItem = newInstanceSmart(itemClass, new Object[]{context, 10, finalZzzTitle, 0, null});
            if (zzzItem != null) {
                Object clickProxy = Proxy.newProxyInstance(cl, new Class[]{func0Class}, (proxy, method, args) -> {
                    if ("invoke".equals(method.getName())) {
                        ZzzSettingFragment.startCore(context);
                    }
                    return unitInstance;
                });
                bindItemAction(itemClass, zzzItem, func0Class, clickProxy);

                bindItemView(cl, itemClass, zzzItem, func1Class, unitInstance, view -> {
                    boolean hasNew = ConfigManager.hasNewVersion();
                    QUIBadgeHelper.attachNativeBadge(view, ConfigManager.VERSION, hasNew, true);
                });
            }

            // =========================================================
            // 2. 动态脚本项 (受 FeatureConfig.has("script") 保护)
            // =========================================================
            Object scriptItem = null;
            if (FeatureConfig.has("script")) {
                CharSequence finalScriptTitle = createTitleWithIcon(context, "script_icon.png", TITLE_SCRIPTS, 22f);
                scriptItem = newInstanceSmart(itemClass, new Object[]{context, 11, finalScriptTitle, 0, null});
                if (scriptItem != null) {
                    Object clickProxy = Proxy.newProxyInstance(cl, new Class[]{func0Class}, (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            ZzzSettingFragment.startPlugins(context);
                        }
                        return unitInstance;
                    });
                    bindItemAction(itemClass, scriptItem, func0Class, clickProxy);

                    bindItemView(cl, itemClass, scriptItem, func1Class, unitInstance, view -> {
                        QUIBadgeHelper.attachNativeBadge(view, "", false, true);
                    });
                }
            }

            // =========================================================
            // 3. 组合卡片并注入主设置页 (脚本被排除时优雅单卡片显示)
            // =========================================================
            List<Object> combinedItems = new ArrayList<>();
            if (zzzItem != null) combinedItems.add(zzzItem);
            if (scriptItem != null) combinedItems.add(scriptItem);

            if (combinedItems.isEmpty()) return;

            Object firstGroup = resultList.get(0);
            Class<?> groupClass = firstGroup.getClass();

            Object[] preferredGroupArgs = new Object[]{
                    combinedItems,
                    "",
                    "",
                    0,
                    null
            };
            Object myGroup = newInstanceSmart(groupClass, preferredGroupArgs);

            if (myGroup != null) {
                if (resultList.size() >= 2) {
                    resultList.add(1, myGroup);
                } else {
                    resultList.add(myGroup);
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "设置中心注入异常", t);
        }
    }

    private static CharSequence createTitleWithIcon(Context context, String assetName, String title, float iconDp) {
        try {
            Bitmap rawBitmap = PatchAssetHelper.getBitmap(context, assetName);
            if (rawBitmap != null) {
                float density = context.getResources().getDisplayMetrics().density;
                int iconSize = (int) (iconDp * density + 0.5f);
                Bitmap scaledBitmap = Bitmap.createScaledBitmap(rawBitmap, iconSize, iconSize, true);
                Drawable drawable = new BitmapDrawable(context.getResources(), scaledBitmap);
                drawable.setBounds(0, 0, iconSize, iconSize);

                SpannableString sp = new SpannableString("   " + title);
                sp.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return sp;
            }
        } catch (Throwable ignored) {}
        return title;
    }

    private static void bindItemAction(Class<?> itemClass, Object item, Class<?> func0Class, Object proxy) {
        for (Method m : itemClass.getMethods()) {
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 1 && pts[0] == func0Class && m.getReturnType() == void.class) {
                try {
                    m.invoke(item, proxy);
                } catch (Throwable ignored) {}
                break;
            }
        }
    }

    private static void bindItemView(ClassLoader cl, Class<?> itemClass, Object item, Class<?> func1Class, Object unitInstance, ViewBindCallback callback) {
        try {
            Object viewProxy = Proxy.newProxyInstance(cl, new Class[]{func1Class}, (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && args != null && args.length == 1 && (args[0] instanceof View)) {
                    callback.onBind((View) args[0]);
                }
                return unitInstance;
            });
            for (Method m : itemClass.getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == func1Class && m.getReturnType() == void.class) {
                    m.invoke(item, viewProxy);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Object newInstanceSmart(Class<?> clazz, Object[] preferredArgs) {
        if (clazz == null) return null;
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        for (Constructor<?> c : constructors) {
            try {
                c.setAccessible(true);
                Class<?>[] paramTypes = c.getParameterTypes();
                Object[] args = new Object[paramTypes.length];

                for (int i = 0; i < paramTypes.length; i++) {
                    Class<?> pt = paramTypes[i];
                    if (i < preferredArgs.length && preferredArgs[i] != null && pt.isAssignableFrom(preferredArgs[i].getClass())) {
                        args[i] = preferredArgs[i];
                    } else if (pt == int.class || pt == Integer.class) {
                        args[i] = (i < preferredArgs.length && preferredArgs[i] instanceof Number)
                                ? ((Number) preferredArgs[i]).intValue() : 0;
                    } else if (pt == boolean.class || pt == Boolean.class) {
                        args[i] = (i < preferredArgs.length && preferredArgs[i] instanceof Boolean)
                                ? (Boolean) preferredArgs[i] : false;
                    } else if (pt == long.class || pt == Long.class) {
                        args[i] = 0L;
                    } else if (pt == float.class || pt == Float.class) {
                        args[i] = 0.0f;
                    } else if (pt == double.class || pt == Double.class) {
                        args[i] = 0.0d;
                    } else if (pt == byte.class || pt == Byte.class) {
                        args[i] = (byte) 0;
                    } else if (pt == short.class || pt == Short.class) {
                        args[i] = (short) 0;
                    } else if (pt == char.class || pt == Character.class) {
                        args[i] = ' ';
                    } else {
                        args[i] = (i < preferredArgs.length) ? preferredArgs[i] : null;
                    }
                }

                return c.newInstance(args);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object getKotlinUnitInstance(ClassLoader cl) {
        try {
            Class<?> unitClass = cl.loadClass("kotlin.Unit");
            Field field = unitClass.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
