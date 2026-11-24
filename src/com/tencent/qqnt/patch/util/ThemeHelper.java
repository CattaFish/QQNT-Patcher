package com.tencent.qqnt.patch.util;

import android.content.Context;
import android.content.res.Configuration;
import com.tencent.qqnt.patch.AppContext;

import java.lang.reflect.Method;

public class ThemeHelper {

    public static boolean isNightMode() {
        try {
            Object runtime = AppContext.getAppRuntime();
            if (runtime != null) {
                Class<?> themeUtilClz = Class.forName("com.tencent.mobileqq.theme.ThemeUtil");
                Method m = themeUtilClz.getMethod(
                        "isNowThemeIsNight",
                        Class.forName("mqq.app.AppRuntime"),
                        boolean.class,
                        String.class
                );
                Object result = m.invoke(null, runtime, false, null);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            }
        } catch (Throwable ignored) {}

        try {
            Context ctx = AppContext.get();
            if (ctx != null && ctx.getResources() != null && ctx.getResources().getConfiguration() != null) {
                int uiMode = ctx.getResources().getConfiguration().uiMode;
                return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            }
        } catch (Throwable ignored) {}

        return false;
    }
}