package com.tencent.qqnt.patch;

import android.content.Context;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.config.UpdateHelper;
import com.tencent.qqnt.patch.plugin.PluginManager;
import com.tencent.qqnt.patch.util.CustomIconHelper;
import com.tencent.qqnt.patch.util.PLog;

import java.io.File;

public class PatchBootstrap {

    private static final String TAG = "Core";
    private static volatile boolean sStarted = false;

    public static void ensureStarted() {
        if (sStarted) return;
        synchronized (PatchBootstrap.class) {
            if (sStarted) return;
            sStarted = true;

            ConfigManager.ensureCacheLoaded();
            PLog.i(TAG, "QQNT-Patcher " + ConfigManager.VERSION + " 引擎冷启动初始化完成");

            UpdateHelper.checkUpdateSilent();

            try {
                Context ctx = AppContext.get();
                if (ctx != null) {
                    AppContext.init(ctx);
                    FeatureConfig.ensureLoaded();

                    if (FeatureConfig.has("custom_icon")) {
                        CustomIconHelper.ensureValidLauncherAlias(ctx);
                    }

                    ModuleManager.initAll(ctx);

                    if (FeatureConfig.has("script")) {
                        PluginManager.init(ctx);
                    } else {
                        PLog.i(TAG, "构建清单未包含 [script]，已跳过动态脚本引擎载入");
                    }

                    cleanLegacyTmpFilesAsync(ctx);
                }
            } catch (Throwable t) {
                PLog.e(TAG, "启动引擎异常", t);
            }
        }
    }

    private static void cleanLegacyTmpFilesAsync(Context context) {
        new Thread(() -> {
            try {
                Thread.sleep(15000);
                File filesDir = context.getFilesDir();
                File dataDir = filesDir != null ? filesDir.getParentFile() : null;
                if (dataDir != null && dataDir.exists()) {
                    File[] files = dataDir.listFiles();
                    if (files != null) {
                        long now = System.currentTimeMillis();
                        for (File f : files) {
                            if (f.isFile() && f.getName().contains(".apk.tmp.") && (now - f.lastModified() > 30000)) {
                                long mb = f.length() / (1024 * 1024);
                                if (f.delete()) {
                                    PLog.i(TAG, "已自动清理历史残留临时文件: " + f.getName() + " (" + mb + "MB)");
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }).start();
    }
}