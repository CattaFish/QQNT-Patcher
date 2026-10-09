package com.tencent.qqnt.patch.ui;

import android.view.View;
import android.widget.CompoundButton;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

public class NativeSettingHelper {

    public static Object createGroup(ClassLoader cl, CharSequence topTitle, CharSequence bottomFooter, List<Object> items) {
        try {
            Class<?> itemBaseClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.a");
            Object itemArray = Array.newInstance(itemBaseClass, items.size());
            for (int i = 0; i < items.size(); i++) {
                Array.set(itemArray, i, items.get(i));
            }

            Class<?> groupClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.Group");
            Constructor<?> ctor = groupClass.getConstructor(
                    CharSequence.class,
                    CharSequence.class,
                    itemArray.getClass()
            );
            return ctor.newInstance(
                    topTitle != null ? topTitle : "",
                    bottomFooter != null ? bottomFooter : "",
                    itemArray
            );
        } catch (Throwable t) {
            PLog.e("UI", "createGroup 失败", t);
            return null;
        }
    }

    public static void applyGroupsToAdapter(Object adapter, List<Object> groups, ClassLoader cl) {
        if (adapter == null || groups == null || cl == null) return;
        try {
            Class<?> groupClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.Group");
            Object groupArray = Array.newInstance(groupClass, groups.size());
            for (int i = 0; i < groups.size(); i++) {
                Array.set(groupArray, i, groups.get(i));
            }

            for (Method m : adapter.getClass().getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0].isArray() &&
                    pts[0].getComponentType().getName().endsWith("Group")) {
                    m.invoke(adapter, new Object[]{groupArray});
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static Object createTextItem(ClassLoader cl, CharSequence title, CharSequence rightText) {
        try {
            Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Object left = newInstanceSmart(xbdClass, new Object[]{title});

            Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
            Object right = newInstanceSmart(xcgClass, new Object[]{rightText != null ? rightText : "", false, false});

            return assembleSingleLineRow(cl, left, right, null);
        } catch (Throwable t) {
            PLog.e("UI", "createTextItem 失败", t);
            return null;
        }
    }

    public static Object createSwitch(ClassLoader cl, CharSequence title, boolean isChecked, CompoundButton.OnCheckedChangeListener listener) {
        return createSwitch(cl, title, null, isChecked, listener);
    }

    public static Object createSwitch(ClassLoader cl, CharSequence title, CharSequence subTitle, boolean isChecked, CompoundButton.OnCheckedChangeListener listener) {
        try {
            if (subTitle != null && subTitle.length() > 0) {
                try {
                    Class<?> cClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.c");
                    Class<?> cafClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.c$a$f");
                    Class<?> cbcClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.c$b$c");

                    Object left = newInstanceSmart(cafClass, new Object[]{title, subTitle});
                    Object right = newInstanceSmart(cbcClass, new Object[]{isChecked, listener, true});

                    if (right != null && listener != null) {
                        try {
                            Method gMethod = cbcClass.getMethod("g", CompoundButton.OnCheckedChangeListener.class);
                            gMethod.invoke(right, listener);
                        } catch (Throwable ignored) {}
                    }

                    Object doubleLineRow = newInstanceSmart(cClass, new Object[]{left, right});
                    if (doubleLineRow != null) {
                        return doubleLineRow;
                    }
                } catch (Throwable t) {
                    PLog.w("UI", "双行组件加载异常，降级单行: " + t.getMessage());
                }
            }

            Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Object left = newInstanceSmart(xbdClass, new Object[]{title});

            Class<?> xcfClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$f");
            Object right = newInstanceSmart(xcfClass, new Object[]{isChecked, listener, true});

            return assembleSingleLineRow(cl, left, right, null);
        } catch (Throwable t) {
            PLog.e("UI", "createSwitch 失败", t);
            return null;
        }
    }

    public static Object createClickable(ClassLoader cl, CharSequence title, String rightText, boolean showArrow, boolean showRedDot, View.OnClickListener clickListener) {
        try {
            Class<?> xbdClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b$d");
            Object left = newInstanceSmart(xbdClass, new Object[]{title});

            Class<?> xcgClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c$g");
            Object right = newInstanceSmart(xcgClass, new Object[]{rightText != null ? rightText : "", showArrow, showRedDot});

            if (right != null && showRedDot) {
                try {
                    Method gMethod = xcgClass.getMethod("g", boolean.class);
                    gMethod.invoke(right, true);
                } catch (Throwable ignored) {}
            }

            Object rowItem = assembleSingleLineRow(cl, left, right, clickListener);

            if (showRedDot && rowItem != null) {
                try {
                    Class<?> gClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.g");
                    Object gProxy = Proxy.newProxyInstance(cl, new Class<?>[]{gClass}, (proxy, method, args) -> {
                        if ("G".equals(method.getName()) && args != null && args.length == 1 && (args[0] instanceof View)) {
                            QUIBadgeHelper.attachNativeBadge((View) args[0], rightText, true, showArrow);
                        }
                        return null;
                    });
                    for (Method m : rowItem.getClass().getMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if ("w".equals(m.getName()) && pts.length == 1 && pts[0] == gClass) {
                            m.invoke(rowItem, gProxy);
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            return rowItem;
        } catch (Throwable t) {
            PLog.e("UI", "createClickable 失败", t);
            return null;
        }
    }

    private static Object assembleSingleLineRow(ClassLoader cl, Object left, Object right, View.OnClickListener clickListener) throws Exception {
        Class<?> xbClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$b");
        Class<?> xcClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x$c");
        Class<?> xClass = cl.loadClass("com.tencent.mobileqq.widget.listitem.x");
        Constructor<?> xCtor = xClass.getConstructor(xbClass, xcClass);

        Object rowItem = xCtor.newInstance(left, right);

        if (clickListener != null) {
            for (Method m : rowItem.getClass().getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == View.OnClickListener.class) {
                    m.invoke(rowItem, clickListener);
                    break;
                }
            }
        }
        return rowItem;
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
                    } else {
                        args[i] = (i < preferredArgs.length) ? preferredArgs[i] : null;
                    }
                }
                return c.newInstance(args);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
