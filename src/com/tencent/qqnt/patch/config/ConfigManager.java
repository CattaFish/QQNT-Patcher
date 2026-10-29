package com.tencent.qqnt.patch.config;

import android.content.Context;
import android.content.SharedPreferences;
import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.ModuleManager;
import com.tencent.qqnt.patch.plugin.PluginManager;
import com.tencent.qqnt.patch.util.PLog;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {

    public static final String VERSION = String.valueOf("v0.2.0");
    public static final String GITHUB_REPO = "Cattafish/QQNT-Patcher";
    public static final String TG_CHANNEL_URL = "https://t.me/ZcraftMod";
    public static final String GITHUB_REPO_URL = "https://github.com/" + GITHUB_REPO;
    public static final String UPDATE_API_URL = "https://qqnt-patcher.zcraft.dpdns.org";

    private static final String PREF_NAME = "zzz_patcher_config";

    private static final String FLAG_DEBUG_LOG_ON     = "zzz_debug_log_on";
    private static final String FLAG_HAS_NEW_VERSION  = "zzz_has_new_version";
    private static final String PREFIX_PLUGIN_ON      = "zzz_plugin_on_";

    public static final String KEY_PIC_SUMMARY_URL       = "zzz_pic_summary_url";
    public static final String KEY_PIC_SUMMARY_KEY       = "zzz_pic_summary_key";
    public static final String KEY_PIC_SUMMARY_USE_LOCAL = "zzz_pic_summary_use_local";

    private static final Map<String, Boolean> sFlagCache = new ConcurrentHashMap<>();
    private static volatile boolean sCacheLoaded = false;
    private static boolean sColdStartChecked = false;

    private static final Map<String, String> ID_ALIASES = new HashMap<>();
    static {
        ID_ALIASES.put("tablet", "tablet_mode");
        ID_ALIASES.put("tablet_mode", "tablet");
        ID_ALIASES.put("multi_window", "disable_split_screen_limit");
        ID_ALIASES.put("disable_split_screen_limit", "multi_window");
        ID_ALIASES.put("browser", "browser_mitigation");
        ID_ALIASES.put("browser_mitigation", "browser");
        ID_ALIASES.put("meow", "meow_helper");
        ID_ALIASES.put("meow_helper", "meow");
        ID_ALIASES.put("block_at_all", "block_at_all_notify");
        ID_ALIASES.put("block_at_all_notify", "block_at_all");
        ID_ALIASES.put("group_file", "show_file_download_count");
        ID_ALIASES.put("show_file_download_count", "group_file");
    }

    private static SharedPreferences getPreferences() {
        Context ctx = AppContext.get();
        if (ctx == null) return null;
        try {
            return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureCacheLoaded() {
        if (sCacheLoaded) return;
        synchronized (sFlagCache) {
            if (sCacheLoaded) return;
            SharedPreferences sp = getPreferences();
            if (sp != null) {
                Map<String, ?> all = sp.getAll();
                if (all != null) {
                    for (Map.Entry<String, ?> entry : all.entrySet()) {
                        if (entry.getValue() instanceof Boolean) {
                            sFlagCache.put(entry.getKey(), (Boolean) entry.getValue());
                        }
                    }
                }
                sCacheLoaded = true;
            }
        }
    }

    public static synchronized void triggerColdStartUpdateCheck() {
        if (sColdStartChecked) return;
        sColdStartChecked = true;

        ensureCacheLoaded();
        PLog.i("Core", "QQNT-Patcher " + VERSION + " 引擎冷启动初始化完成");

        UpdateHelper.checkUpdateSilent();

        try {
            Context ctx = AppContext.get();
            if (ctx != null) {
                AppContext.init(ctx);
                FeatureConfig.ensureLoaded();
                ModuleManager.initAll(ctx);
                
                if (FeatureConfig.has("script")) {
                    PluginManager.init(ctx);
                } else {
                    PLog.i("Core", "构建清单未包含 [script]，已跳过动态脚本引擎载入");
                }

                cleanLegacyTmpFilesAsync(ctx);
            }
        } catch (Throwable t) {
            PLog.e("Core", "启动引擎异常", t);
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
                                    PLog.i("Core", "已自动清理历史残留临时文件: " + f.getName() + " (" + mb + "MB)");
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    public static boolean isModuleEnabled(String moduleId, boolean defValue) {
        String flagOff = "zzz_mod_off_" + moduleId;
        String flagOn = "zzz_mod_on_" + moduleId;
        String alias = ID_ALIASES.get(moduleId);

        if (defValue) {
            if (hasFlag(flagOff)) return false;
            if (alias != null && hasFlag("zzz_mod_off_" + alias)) return false;
            return true;
        } else {
            if (hasFlag(flagOn)) return true;
            if (alias != null && hasFlag("zzz_mod_on_" + alias)) return true;
            return false;
        }
    }

    public static void setModuleEnabled(String moduleId, boolean enabled) {
        setFlag("zzz_mod_off_" + moduleId, !enabled);
        setFlag("zzz_mod_on_" + moduleId, enabled);
        String alias = ID_ALIASES.get(moduleId);
        if (alias != null) {
            setFlag("zzz_mod_off_" + alias, !enabled);
            setFlag("zzz_mod_on_" + alias, enabled);
        }
        PLog.i("Config", "模块 [" + moduleId + "] 状态更新为: " + (enabled ? "开启" : "关闭"));
    }

    public static boolean isFloatingBallEnabled() { return isModuleEnabled("floating_ball", false); }
    public static void setFloatingBallEnabled(boolean enabled) { setModuleEnabled("floating_ball", enabled); }
    public static boolean isAntiRevokeEnabled() { return isModuleEnabled("anti_revoke", false); }
    public static void setAntiRevokeEnabled(boolean enabled) { setModuleEnabled("anti_revoke", enabled); }
    public static boolean isFlashPicDecryptEnabled() { return isModuleEnabled("flash_pic", false); }
    public static void setFlashPicDecryptEnabled(boolean enabled) { setModuleEnabled("flash_pic", enabled); }
    public static boolean isMeowEnabled() { return isModuleEnabled("meow", false); }
    public static void setMeowEnabled(boolean enabled) { setModuleEnabled("meow", enabled); }

    public static boolean isPluginEnabled(String pluginId) {
        if (pluginId == null || pluginId.isEmpty()) return false;
        return hasFlag(PREFIX_PLUGIN_ON + pluginId);
    }

    public static void setPluginEnabled(String pluginId, boolean enabled) {
        if (pluginId == null || pluginId.isEmpty()) return;
        setFlag(PREFIX_PLUGIN_ON + pluginId, enabled);
    }

    public static boolean hasNewVersion() { return hasFlag(FLAG_HAS_NEW_VERSION); }
    public static void setHasNewVersion(boolean hasNew) { setFlag(FLAG_HAS_NEW_VERSION, hasNew); }
    public static boolean isDebugLogEnabled() { return hasFlag(FLAG_DEBUG_LOG_ON); }
    public static void setDebugLogEnabled(boolean enabled) {
        setFlag(FLAG_DEBUG_LOG_ON, enabled);
        PLog.i("Config", "调试日志输出已" + (enabled ? "开启" : "关闭"));
    }

    public static boolean hasFlag(String flagName) {
        ensureCacheLoaded();
        Boolean cached = sFlagCache.get(flagName);
        if (cached != null) return cached;

        SharedPreferences sp = getPreferences();
        if (sp == null) return false;
        boolean val = sp.getBoolean(flagName, false);
        sFlagCache.put(flagName, val);
        return val;
    }

    public static void setFlag(String flagName, boolean present) {
        sFlagCache.put(flagName, present);
        SharedPreferences sp = getPreferences();
        if (sp != null) {
            sp.edit().putBoolean(flagName, present).apply();
        }
    }

    public static String getString(String key, String defValue) {
        SharedPreferences sp = getPreferences();
        return sp != null ? sp.getString(key, defValue) : defValue;
    }

    public static void setString(String key, String value) {
        SharedPreferences sp = getPreferences();
        if (sp != null) {
            sp.edit().putString(key, value).apply();
        }
    }

    public static String getPicSummaryUrl() { return getString(KEY_PIC_SUMMARY_URL, ""); }
    public static void setPicSummaryUrl(String url) { setString(KEY_PIC_SUMMARY_URL, url); }
    public static String getPicSummaryKey() { return getString(KEY_PIC_SUMMARY_KEY, ""); }
    public static void setPicSummaryKey(String key) { setString(KEY_PIC_SUMMARY_KEY, key); }
    public static boolean isPicSummaryUseLocal() { return hasFlag(KEY_PIC_SUMMARY_USE_LOCAL); }
    public static void setPicSummaryUseLocal(boolean useLocal) { setFlag(KEY_PIC_SUMMARY_USE_LOCAL, useLocal); }
}
