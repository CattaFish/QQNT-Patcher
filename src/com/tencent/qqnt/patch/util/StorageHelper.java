package com.tencent.qqnt.patch.util;

import android.content.Context;
import android.os.Environment;
import com.tencent.qqnt.patch.AppContext;

import java.io.File;

public class StorageHelper {

    public static File getZzzDir(Context context) {
        Context ctx = (context != null) ? context : AppContext.get();
        File mediaDir = null;
        if (ctx != null) {
            try {
                File[] mediaDirs = ctx.getExternalMediaDirs();
                if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                    mediaDir = mediaDirs[0];
                }
            } catch (Throwable ignored) {}
            if (mediaDir == null) {
                mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/" + ctx.getPackageName());
            }
        } else {
            mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/com.tencent.mobileqq");
        }
        File zzzDir = new File(mediaDir, "zzz");
        if (!zzzDir.exists()) {
            zzzDir.mkdirs();
        }
        return zzzDir;
    }

    public static File getZzzSubDir(Context context, String subDirName) {
        return getZzzSubDir(context, subDirName, true);
    }

    public static File getZzzSubDir(Context context, String subDirName, boolean autoCreate) {
        File dir = new File(getZzzDir(context), subDirName);
        if (autoCreate && !dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getZzzFile(Context context, String fileName) {
        return new File(getZzzDir(context), fileName);
    }
}