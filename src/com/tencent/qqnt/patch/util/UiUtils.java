package com.tencent.qqnt.patch.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

public class UiUtils {

    public static int dp2px(Context c, float dp) {
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static boolean copyToClipboard(Context context, String label, String text) {
        if (context == null || text == null) return false;
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label != null ? label : "text", text));
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}