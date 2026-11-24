package com.tencent.qqnt.patch;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

public class AppContext {
    private static volatile Context sContext = null;
    private static volatile WeakReference<Activity> sCurrentActivity = null;
    private static volatile boolean sLifecycleRegistered = false;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    public static Context get() {
        if (sContext != null) return sContext;
        synchronized (AppContext.class) {
            if (sContext != null) return sContext;
            try {
                Class<?> appClass = Class.forName("com.tencent.qphone.base.util.BaseApplication");
                sContext = (Context) appClass.getMethod("getContext").invoke(null);
            } catch (Throwable ignored) {}
            if (sContext == null) {
                try {
                    Class<?> atClass = Class.forName("android.app.ActivityThread");
                    Object app = atClass.getMethod("currentApplication").invoke(null);
                    if (app instanceof Context) sContext = (Context) app;
                } catch (Throwable ignored) {}
            }
        }
        return sContext;
    }

    public static void init(Context context) {
        if (context != null && sContext == null) {
            sContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        }
        registerLifecycle();
    }

    public static void registerLifecycle() {
        if (sLifecycleRegistered) return;
        Context ctx = get();
        if (ctx instanceof Application) {
            try {
                ((Application) ctx).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityCreated(Activity a, Bundle b) {}
                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityResumed(Activity a) {
                        sCurrentActivity = new WeakReference<>(a);
                    }
                    @Override public void onActivityPaused(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    @Override public void onActivityDestroyed(Activity a) {
                        if (sCurrentActivity != null && sCurrentActivity.get() == a) {
                            sCurrentActivity = null;
                        }
                    }
                });
                sLifecycleRegistered = true;
            } catch (Throwable ignored) {}
        }
    }
    
    public static String getCurrentUin() {
        try {
            Object runtime = getAppRuntime();
            if (runtime != null) {
                try {
                    Object uin = runtime.getClass().getMethod("getCurrentAccountUin").invoke(runtime);
                    if (uin != null && !String.valueOf(uin).isEmpty()) {
                        return String.valueOf(uin);
                    }
                } catch (NoSuchMethodException ignored) {}
                Object uin2 = runtime.getClass().getMethod("getAccount").invoke(runtime);
                if (uin2 != null) return String.valueOf(uin2);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static Activity getCurrentActivity() {
        if (sCurrentActivity != null && sCurrentActivity.get() != null) {
            Activity act = sCurrentActivity.get();
            if (!act.isFinishing() && !act.isDestroyed()) {
                return act;
            }
        }
        // 备用兜底 1：QBaseActivity.sTopActivity
        try {
            Class<?> qBaseClz = Class.forName("com.tencent.mobileqq.app.QBaseActivity");
            Field topField = qBaseClz.getDeclaredField("sTopActivity");
            topField.setAccessible(true);
            Object top = topField.get(null);
            if (top instanceof Activity) {
                Activity act = (Activity) top;
                sCurrentActivity = new WeakReference<>(act);
                return act;
            }
        } catch (Throwable ignored) {}

        // 备用兜底 2：ActivityThread.mActivities
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Field actField = atClass.getDeclaredField("mActivities");
            actField.setAccessible(true);
            Map<?, ?> acts = (Map<?, ?>) actField.get(at);
            if (acts != null) {
                for (Object record : acts.values()) {
                    if (record == null) continue;
                    Class<?> rClz = record.getClass();
                    Field pausedField = null;
                    try {
                        pausedField = rClz.getDeclaredField("paused");
                    } catch (Throwable e) {
                        pausedField = rClz.getDeclaredField("mPaused");
                    }
                    pausedField.setAccessible(true);
                    if (!pausedField.getBoolean(record)) {
                        Field aField = rClz.getDeclaredField("activity");
                        aField.setAccessible(true);
                        Activity act = (Activity) aField.get(record);
                        if (act != null) {
                            sCurrentActivity = new WeakReference<>(act);
                            return act;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Object getAppRuntime() {
        try {
            Class<?> mobileQQClz = Class.forName("mqq.app.MobileQQ");
            Object mobileQQ = mobileQQClz.getMethod("getMobileQQ").invoke(null);
            if (mobileQQ != null) {
                return mobileQQ.getClass().getMethod("peekAppRuntime").invoke(mobileQQ);
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> baseAppClz = Class.forName("com.tencent.common.app.BaseApplicationImpl");
            Object app = baseAppClz.getMethod("getApplication").invoke(null);
            if (app != null) {
                return app.getClass().getMethod("peekAppRuntime").invoke(app);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void runOnUIThread(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            sMainHandler.post(r);
        }
    }
}
