package com.tencent.qqnt.patch.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PatchAssetHelper {

    private static final Map<String, Bitmap> sBitmapCache = new ConcurrentHashMap<>();
    private static volatile String sCachedRealApkPath = null;
    private static final Object sZipLock = new Object();
    private static volatile ZipFile sCachedApkZip = null;

    public static InputStream openStream(Context context, String assetName) {
        if (context == null || assetName == null || assetName.isEmpty()) return null;

        try {
            return context.getAssets().open(assetName);
        } catch (Throwable ignored) {}

        try {
            ZipFile zip = getApkZipFile(context);
            if (zip != null) {
                String entryPath = assetName.startsWith("assets/") ? assetName : ("assets/" + assetName);
                synchronized (sZipLock) {
                    ZipEntry entry = zip.getEntry(entryPath);
                    if (entry != null) {
                        long size = entry.getSize();
                        int initSize = (size > 0 && size < Integer.MAX_VALUE) ? (int) size : 8192;
                        try (InputStream is = zip.getInputStream(entry);
                             ByteArrayOutputStream bos = new ByteArrayOutputStream(initSize)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                bos.write(buf, 0, n);
                            }
                            return new ByteArrayInputStream(bos.toByteArray());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            PLog.w("AssetHelper", "穿透读取资产 [" + assetName + "] 异常: " + t.getMessage());
        }
        return null;
    }

    private static ZipFile getApkZipFile(Context context) {
        ZipFile zf = sCachedApkZip;
        if (zf != null) return zf;
        synchronized (sZipLock) {
            zf = sCachedApkZip;
            if (zf != null) return zf;
            try {
                String apkPath = getRealApkPath(context);
                if (apkPath != null) {
                    String bypassPath = apkPath.contains("/base.apk")
                            ? apkPath.replace("/base.apk", "/./base.apk")
                            : apkPath;
                    File f = new File(bypassPath);
                    if (!f.exists()) f = new File(apkPath);
                    zf = new ZipFile(f);
                    sCachedApkZip = zf;
                }
            } catch (Throwable t) {
                PLog.w("AssetHelper", "打开宿主 APK ZipFile 异常: " + t.getMessage());
            }
            return zf;
        }
    }

    public static Bitmap getBitmap(Context context, String assetName) {
        if (assetName == null) return null;
        Bitmap cached = sBitmapCache.get(assetName);
        if (cached != null && !cached.isRecycled()) return cached;

        try (InputStream is = openStream(context, assetName)) {
            if (is != null) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) {
                    sBitmapCache.put(assetName, bmp);
                    return bmp;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String getRealApkPath(Context context) {
        if (sCachedRealApkPath != null) return sCachedRealApkPath;
        try {
            sCachedRealApkPath = context.getPackageResourcePath();
        } catch (Throwable ignored) {}
        return sCachedRealApkPath;
    }
}