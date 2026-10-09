package com.tencent.qqnt.patch.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class ToastHelper {
    private static Toast sLastToast = null;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    public static void show(Context context, CharSequence text) {
        if (context == null || text == null) return;

        Runnable task = () -> {
            try {
                if (sLastToast != null) {
                    try {
                        sLastToast.cancel();
                    } catch (Throwable ignored) {}
                }
                Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                Toast toast = Toast.makeText(appCtx, text, Toast.LENGTH_SHORT);
                sLastToast = toast;
                toast.show();
            } catch (Throwable ignored) {}
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            sMainHandler.post(task);
        }
    }
}
