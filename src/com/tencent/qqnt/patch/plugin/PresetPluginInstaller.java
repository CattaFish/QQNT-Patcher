package com.tencent.qqnt.patch.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import com.tencent.qqnt.patch.ConfigManager;
import com.tencent.qqnt.patch.PLog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PresetPluginInstaller {

    private static final String TAG = "PresetPlugin";
    private static final String ASSET_NAME = "preset_plugins.zip";
    private static final String PREF_NAME = "zzz_preset_plugin_pref";
    private static final String KEY_INSTALLED_CRC = "installed_zip_crc";
    private static final String FLAG_CONFIGURED_PREFIX = "zzz_preset_configured_";

    /**
     * 在引擎初始化时调用，自动释放并安装预设脚本
     */
    public static synchronized void install(Context context) {
        if (context == null) return;

        // 1. 检查 assets/preset_plugins.zip 是否存在
        boolean hasAsset = false;
        try (InputStream is = com.tencent.qqnt.patch.PatchAssetHelper.openStream(context, ASSET_NAME)) {
            if (is != null) hasAsset = true;
        } catch (Throwable ignored) {
            hasAsset = false;
        }

        if (!hasAsset) return;

        File targetPluginsDir = PluginManager.getPluginsStorageDir(context);
        if (!targetPluginsDir.exists()) {
            targetPluginsDir.mkdirs();
        }

        // 2. 校验 CRC32 避免每次冷启动重复覆盖
        long currentCrc = calculateAssetCrc(context, ASSET_NAME);
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long recordedCrc = sp.getLong(KEY_INSTALLED_CRC, -1L);

        File[] existingPlugins = targetPluginsDir.listFiles();
        boolean hasExistingPlugins = (existingPlugins != null && existingPlugins.length > 0);

        if (currentCrc != -1L && currentCrc == recordedCrc && hasExistingPlugins) {
            PLog.d(TAG, "预设脚本包已安装 (CRC=" + currentCrc + ")，跳过重复解压");
            return;
        }

        PLog.i(TAG, "检测到预设脚本包更新，开始自动解压安装...");

        // 3. 准备临时解压目录
        File cacheDir = context.getCacheDir();
        if (cacheDir == null) cacheDir = context.getFilesDir();
        File tempExtractDir = new File(cacheDir, "preset_temp_" + System.currentTimeMillis());
        if (tempExtractDir.exists()) deleteRecursively(tempExtractDir);
        tempExtractDir.mkdirs();

        try {
            // A. 第一层解压：释放 assets/preset_plugins.zip
            try (InputStream is = com.tencent.qqnt.patch.PatchAssetHelper.openStream(context, ASSET_NAME)) {
                unzip(is, tempExtractDir);
            }

            // B. 递归展开所有内层嵌套的 .zip 文件 (用户直接放入的 zip 压缩包)
            expandAllNestedZips(tempExtractDir);

            // C. 深度遍历，寻找所有包含 main.java 的目录作为独立插件
            List<File> pluginDirs = new ArrayList<>();
            findPluginDirectories(tempExtractDir, pluginDirs);

            if (pluginDirs.isEmpty()) {
                PLog.w(TAG, "预设脚本包中未发现包含 main.java 的有效插件");
            } else {
                int count = 0;
                for (File pDir : pluginDirs) {
                    String pluginId = resolvePluginId(pDir);
                    File destDir = new File(targetPluginsDir, pluginId);

                    // 复制插件目录至正式外部存储 plugins 目录
                    copyDirectory(pDir, destDir);
                    count++;

                    // 若该插件从未被配置过，默认设为激活开启状态
                    String cfgFlag = FLAG_CONFIGURED_PREFIX + pluginId;
                    if (!ConfigManager.hasFlag(cfgFlag)) {
                        ConfigManager.setPluginEnabled(pluginId, true);
                        ConfigManager.setFlag(cfgFlag, true);
                        PLog.i(TAG, "预设脚本 [" + pluginId + "] 首次释放，已自动激活开启");
                    } else {
                        PLog.i(TAG, "预设脚本 [" + pluginId + "] 资源已更新");
                    }
                }
                PLog.i(TAG, "预设脚本安装完成，共装载 " + count + " 个插件");
            }

            // 4. 记录本次安装的 CRC
            sp.edit().putLong(KEY_INSTALLED_CRC, currentCrc).apply();

        } catch (Throwable t) {
            PLog.e(TAG, "解压预设脚本包异常", t);
        } finally {
            // 5. 无论成败彻底清理临时文件
            deleteRecursively(tempExtractDir);
        }
    }

    private static long calculateAssetCrc(Context context, String assetName) {
        try (InputStream is = com.tencent.qqnt.patch.PatchAssetHelper.openStream(context, assetName)) {
            CRC32 crc = new CRC32();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                crc.update(buf, 0, len);
            }
            return crc.getValue();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static void unzip(InputStream is, File targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // 规避 Zip Slip 安全漏洞
                if (name.contains("..")) continue;

                File outFile = new File(targetDir, name);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, count);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void expandAllNestedZips(File dir) {
        boolean foundZip;
        do {
            foundZip = false;
            List<File> zipFiles = new ArrayList<>();
            collectZipFiles(dir, zipFiles);

            for (File zipFile : zipFiles) {
                foundZip = true;
                String baseName = zipFile.getName();
                if (baseName.toLowerCase().endsWith(".zip")) {
                    baseName = baseName.substring(0, baseName.length() - 4);
                }
                File outSubDir = new File(zipFile.getParentFile(), baseName);
                outSubDir.mkdirs();

                try (FileInputStream fis = new FileInputStream(zipFile)) {
                    unzip(fis, outSubDir);
                } catch (Throwable t) {
                    PLog.e(TAG, "解压嵌套 zip 失败: " + zipFile.getName(), t);
                }
                zipFile.delete();
            }
        } while (foundZip);
    }

    private static void collectZipFiles(File dir, List<File> zipFiles) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectZipFiles(f, zipFiles);
            } else if (f.getName().toLowerCase().endsWith(".zip")) {
                zipFiles.add(f);
            }
        }
    }

    private static void findPluginDirectories(File currentDir, List<File> result) {
        if (!currentDir.isDirectory()) return;

        File mainJava = new File(currentDir, "main.java");
        if (mainJava.exists() && mainJava.isFile()) {
            result.add(currentDir);
            return;
        }

        File[] subFiles = currentDir.listFiles();
        if (subFiles != null) {
            for (File sub : subFiles) {
                if (sub.isDirectory()) {
                    findPluginDirectories(sub, result);
                }
            }
        }
    }

    private static String resolvePluginId(File pluginDir) {
        File propFile = new File(pluginDir, "info.prop");
        if (propFile.exists()) {
            try (InputStreamReader isr = new InputStreamReader(new FileInputStream(propFile), StandardCharsets.UTF_8)) {
                Properties prop = new Properties();
                prop.load(isr);
                String id = prop.getProperty("pluginId");
                if (id != null && !id.trim().isEmpty()) {
                    return id.trim();
                }
            } catch (Throwable ignored) {}
        }
        String name = pluginDir.getName();
        if (name == null || name.isEmpty() || name.startsWith("preset_temp_")) {
            return "PresetPlugin_" + System.currentTimeMillis();
        }
        return name;
    }

    private static void copyDirectory(File sourceLocation, File targetLocation) throws IOException {
        if (sourceLocation.isDirectory()) {
            if (!targetLocation.exists()) {
                targetLocation.mkdirs();
            }
            String[] children = sourceLocation.list();
            if (children != null) {
                for (String child : children) {
                    copyDirectory(new File(sourceLocation, child), new File(targetLocation, child));
                }
            }
        } else {
            try (InputStream in = new FileInputStream(sourceLocation);
                 OutputStream out = new FileOutputStream(targetLocation)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
        }
    }

    private static void deleteRecursively(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        fileOrDir.delete();
    }
}