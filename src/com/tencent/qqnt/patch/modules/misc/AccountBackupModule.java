package com.tencent.qqnt.patch.modules.misc;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;

import com.tencent.qqnt.patch.AppContext;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.config.ConfigManager;
import com.tencent.qqnt.patch.plugin.MsgSender;
import com.tencent.qqnt.patch.ui.NativeSettingHelper;
import com.tencent.qqnt.patch.ui.SettingDialogBuilder;
import com.tencent.qqnt.patch.util.PLog;
import com.tencent.qqnt.patch.util.ToastHelper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class AccountBackupModule implements IPatchModule {

    private static final String TAG = "AccountBackup";
    public static final String FEATURE_ID = "account_backup";
    private static final String KEY_INCLUDE_RISK = "zzz_account_backup_include_risk";
    private static final Pattern UIN_FILE_PATTERN = Pattern.compile("^u_(\\d+)_t$");

    @Override
    public String getId() {
        return FEATURE_ID;
    }

    @Override
    public String getName() {
        return "账号凭证备份";
    }

    @Override
    public String getCategory() {
        return CATEGORY_MISC;
    }

    @Override
    public String getSubName() {
        return "本地登录状态备份与切换";
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public boolean hasConfig() {
        return true;
    }

    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        Context ctx = activity != null ? activity.getApplicationContext() : AppContext.get();
        String uin = ctx != null ? resolveCurrentUin(ctx) : "";
        int count = ctx != null ? listBackupZips(getBackupDir(ctx)).size() : 0;

        List<Object> items = new ArrayList<>();
        items.add(NativeSettingHelper.createClickable(
                cl, "  ↳ 备份当前账号", uin.isEmpty() ? "备份" : uin, true, false,
                v -> {
                    if (!checkModuleEnabled(activity)) return;
                    showBackupDialog(activity, onRefresh);
                }
        ));
        items.add(NativeSettingHelper.createClickable(
                cl, "  ↳ 管理已存备份", count + " 个", true, false,
                v -> {
                    if (!checkModuleEnabled(activity)) return;
                    showManageDialog(activity, onRefresh);
                }
        ));
        return items;
    }

    @Override
    public void onConfigClick(Activity activity, Runnable onSaved) {
        if (!checkModuleEnabled(activity)) return;
        showManageDialog(activity, onSaved);
    }

    private boolean checkModuleEnabled(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        if (!isEnabled()) {
            ToastHelper.show(activity, "请先开启开关");
            return false;
        }
        return true;
    }

    private void showBackupDialog(Activity activity, Runnable onRefresh) {
        Context context = activity.getApplicationContext();
        String uin = resolveCurrentUin(context);
        File backupDir = getBackupDir(context);
        String dirPath = backupDir.getAbsolutePath();
        String timeSuffix = new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date());
        String defaultName = (uin.isEmpty() ? "qq" : uin) + "_" + timeSuffix;

        SettingDialogBuilder dlg = SettingDialogBuilder.build(activity, "备份当前账号");
        if (!uin.isEmpty()) {
            dlg.setSubTitle("当前账号: " + uin);
        }

        dlg.addInput("文件名", defaultName, true, false, null);

        dlg.addSwitch(
                "附带设备参数",
                "含 QIMEI 与安全配置",
                isIncludeRiskEnabled(),
                checked -> ConfigManager.setString(KEY_INCLUDE_RISK, checked ? "1" : "0")
        );

        dlg.addTip("路径: " + dirPath + " (点击复制)", v -> copyToClipboard(activity, dirPath));

        dlg.setPositiveButton("备份", d -> {
            String name = dlg.getInputValue(0).trim();
            if (name.isEmpty()) name = defaultName;
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                name = name + ".zip";
            }

            File outZip = new File(backupDir, name);
            boolean includeRisk = isIncludeRiskEnabled();
            d.dismiss();

            new Thread(() -> {
                String err = exportBackupZip(context, outZip, includeRisk);
                AppContext.runOnUIThread(() -> {
                    if (err == null) {
                        ToastHelper.show(activity, "已保存: " + outZip.getName());
                        if (onRefresh != null) onRefresh.run();
                    } else {
                        ToastHelper.show(activity, "备份失败: " + err);
                    }
                });
            }).start();
        });

        dlg.setNegativeButton("取消", null);
        dlg.show();
    }

    private void showManageDialog(Activity activity, Runnable onRefresh) {
        Context context = activity.getApplicationContext();
        File backupDir = getBackupDir(context);
        String dirPath = backupDir.getAbsolutePath();
        List<File> zipFiles = listBackupZips(backupDir);
        String uin = resolveCurrentUin(context);

        SettingDialogBuilder dlg = SettingDialogBuilder.build(activity, "已存备份");
        if (!uin.isEmpty()) {
            dlg.setSubTitle("当前账号: " + uin);
        }

        dlg.addTip("目录: " + dirPath + " (点击复制)", v -> copyToClipboard(activity, dirPath));

        if (zipFiles.isEmpty()) {
            dlg.addTip("暂无备份文件", null);
            dlg.setNegativeButton("关闭", null);
            dlg.show();
            return;
        }

        final int[] selectedIdx = new int[]{0};
        List<SettingDialogBuilder.OptionCard> cards = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (File f : zipFiles) {
            cards.add(new SettingDialogBuilder.OptionCard(
                    f.getName(),
                    formatFileSize(f.length()) + "  " + sdf.format(new Date(f.lastModified()))
            ));
        }

        dlg.addRadioCards(cards, 0, index -> selectedIdx[0] = index);

        dlg.setPositiveButton("恢复", d -> {
            int idx = selectedIdx[0];
            if (idx >= 0 && idx < zipFiles.size()) {
                File target = zipFiles.get(idx);
                d.dismiss();
                showRestoreConfirmDialog(activity, context, target);
            }
        });

        dlg.setNeutralButton("删除", d -> {
            int idx = selectedIdx[0];
            if (idx >= 0 && idx < zipFiles.size()) {
                File target = zipFiles.get(idx);
                boolean ok = target.delete();
                ToastHelper.show(activity, ok ? "已删除" : "删除失败");
                d.dismiss();
                if (onRefresh != null) onRefresh.run();
                showManageDialog(activity, onRefresh);
            }
        });

        dlg.setNegativeButton("取消", null);
        dlg.show();
    }

    private void showRestoreConfirmDialog(Activity activity, Context context, File zipFile) {
        SettingDialogBuilder dlg = SettingDialogBuilder.build(activity, "恢复备份");
        dlg.setSubTitle("确定恢复 " + zipFile.getName() + " 并重启 QQ？");

        dlg.setPositiveButton("确定", d -> {
            d.dismiss();
            // 确保标记写入当前 ConfigManager 所在的 SP 文件，防止清理 shared_prefs 时误删模块开关
            ConfigManager.setString(KEY_INCLUDE_RISK, isIncludeRiskEnabled() ? "1" : "0");
            new Thread(() -> {
                String err = restoreFromZipAndRestart(context, zipFile);
                if (err != null) {
                    AppContext.runOnUIThread(() -> ToastHelper.show(activity, "恢复失败: " + err));
                }
            }).start();
        });

        dlg.setNegativeButton("取消", null);
        dlg.show();
    }

    private static void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("path", text));
                ToastHelper.show(context, "已复制路径");
            }
        } catch (Throwable t) {
            ToastHelper.show(context, "复制失败");
        }
    }

    private static String exportBackupZip(Context context, File outZip, boolean includeRisk) {
        File filesDir = context.getFilesDir();
        if (filesDir == null || !filesDir.exists()) return "files 目录不存在";
        File dataRoot = filesDir.getParentFile();
        if (dataRoot == null) return "目录结构异常";

        List<File> targets = collectBackupTargets(filesDir, includeRisk);
        if (targets.isEmpty()) return "未找到登录凭证";

        File tmpZip = new File(outZip.getParentFile(), outZip.getName() + ".tmp");
        try {
            if (tmpZip.exists()) tmpZip.delete();
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmpZip)))) {
                for (File f : targets) {
                    addFileOrDirToZip(zos, dataRoot, f);
                }
            }
            if (outZip.exists()) outZip.delete();
            if (!tmpZip.renameTo(outZip)) return "保存失败";
            PLog.i(TAG, "备份成功: " + outZip.getAbsolutePath());
            return null;
        } catch (Throwable t) {
            PLog.e(TAG, "备份异常", t);
            if (tmpZip.exists()) tmpZip.delete();
            return t.getMessage();
        }
    }

    private static List<File> collectBackupTargets(File filesDir, boolean includeRisk) {
        List<File> list = new ArrayList<>();
        addIfExists(list, new File(filesDir, "nt_wtlogin"));
        addIfExists(list, new File(filesDir, "user"));
        addIfExists(list, new File(filesDir, "uid"));

        File mmkvDir = new File(filesDir, "mmkv");
        if (mmkvDir.exists() && mmkvDir.isDirectory()) {
            File[] mmkvFiles = mmkvDir.listFiles();
            if (mmkvFiles != null) {
                for (File mf : mmkvFiles) {
                    String n = mf.getName();
                    if (n.startsWith("msf_mmkv_file")
                            || n.startsWith("qq_uin_uid_map")
                            || (includeRisk && n.startsWith("qsec_mmkv_configurations"))) {
                        list.add(mf);
                    }
                }
            }
        }

        if (includeRisk) {
            addIfExists(list, new File(filesDir, "qm"));
            addIfExists(list, new File(filesDir, "5463306EE50FE3AA"));
            addIfExists(list, new File(filesDir, "com.tencent.qimei.sdk.QimeiSDK"));
            addIfExists(list, new File(filesDir, "imei"));
        }
        return list;
    }

    private static void addIfExists(List<File> list, File f) {
        if (f != null && f.exists()) list.add(f);
    }

    private static void addFileOrDirToZip(ZipOutputStream zos, File baseDir, File target) throws Exception {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    addFileOrDirToZip(zos, baseDir, child);
                }
            }
            return;
        }

        String relPath = getRelativePath(baseDir, target);
        zos.putNextEntry(new ZipEntry(relPath));
        byte[] buf = new byte[8192];
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(target))) {
            int len;
            while ((len = bis.read(buf)) != -1) {
                zos.write(buf, 0, len);
            }
        }
        zos.closeEntry();
    }

    private static String getRelativePath(File baseDir, File target) {
        String basePath = baseDir.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (!basePath.endsWith(File.separator)) basePath = basePath + File.separator;
        String rel = targetPath.startsWith(basePath) ? targetPath.substring(basePath.length()) : target.getName();
        return rel.replace(File.separatorChar, '/');
    }

    private static String restoreFromZipAndRestart(Context context, File zipFile) {
        if (!zipFile.exists() || !zipFile.isFile()) return "文件不存在";
        if (!validateBackupZip(zipFile)) return "无效的备份压缩包";

        File filesDir = context.getFilesDir();
        if (filesDir == null) return "files 目录异常";
        File dataRoot = filesDir.getParentFile();
        if (dataRoot == null) return "数据根目录异常";

        try {
            // 1. 先停掉所有后台服务并杀掉子进程 (:MSF 等)
            stopAllSelfServices(context);
            killOtherProcesses(context);

            // 2. 彻底清空旧账号所有运行时目录 (对齐 Termux 清理效果，仅保留 Patcher 配置文件)
            cleanAllQQData(filesDir, dataRoot);
            if (!filesDir.exists()) filesDir.mkdirs();

            // 3. 解压写入新凭证
            byte[] buf = new byte[8192];
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null || name.isEmpty() || name.contains("..")) {
                        zis.closeEntry();
                        continue;
                    }
                    while (name.startsWith("/")) name = name.substring(1);
                    if (name.startsWith("./")) name = name.substring(2);

                    File dest = name.startsWith("files/") ? new File(dataRoot, name) : new File(filesDir, name);
                    if (entry.isDirectory()) {
                        dest.mkdirs();
                    } else {
                        File parent = dest.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        if (dest.exists()) dest.delete();
                        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dest))) {
                            int len;
                            while ((len = zis.read(buf)) != -1) {
                                bos.write(buf, 0, len);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }

            PLog.i(TAG, "恢复完成，重启应用: " + zipFile.getName());
            scheduleRestartAndKillSelf(context);
            return null;
        } catch (Throwable t) {
            PLog.e(TAG, "恢复异常", t);
            return t.getMessage();
        }
    }

    private static boolean validateBackupZip(File zipFile) {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName();
                if (n != null && !n.contains("..") && (n.contains("nt_wtlogin/") || n.contains("user/"))) {
                    return true;
                }
                zis.closeEntry();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @SuppressWarnings("deprecation")
    private static void stopAllSelfServices(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            int myUid = Process.myUid();
            List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(100);
            if (services == null) return;
            for (ActivityManager.RunningServiceInfo rsi : services) {
                if (rsi.uid == myUid && rsi.service != null) {
                    try {
                        context.stopService(new Intent().setComponent(rsi.service));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "停止服务异常: " + t.getMessage());
        }
    }

    private static void killOtherProcesses(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            int myPid = Process.myPid();
            int myUid = Process.myUid();
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pid != myPid && p.uid == myUid) {
                    Process.killProcess(p.pid);
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "清理子进程异常: " + t.getMessage());
        }
    }

    private static void cleanAllQQData(File filesDir, File dataRoot) {
        // 1. 清空整个内部 files 目录
        File[] filesChildren = filesDir.listFiles();
        if (filesChildren != null) {
            for (File child : filesChildren) {
                deleteRecursively(child);
            }
        }

        // 2. 清空 databases、cache、code_cache 以及 app_* 目录
        deleteRecursively(new File(dataRoot, "databases"));
        deleteRecursively(new File(dataRoot, "cache"));
        deleteRecursively(new File(dataRoot, "code_cache"));

        File[] rootChildren = dataRoot.listFiles();
        if (rootChildren != null) {
            for (File child : rootChildren) {
                if (child.getName().startsWith("app_")) {
                    deleteRecursively(child);
                }
            }
        }

        // 3. 清理 shared_prefs 中非 Patcher 配置文件
        File spDir = new File(dataRoot, "shared_prefs");
        if (spDir.exists() && spDir.isDirectory()) {
            File[] spFiles = spDir.listFiles();
            if (spFiles != null) {
                for (File sp : spFiles) {
                    if (!isPatcherConfigFile(sp)) {
                        sp.delete();
                    }
                }
            }
        }
    }

    private static boolean isPatcherConfigFile(File spFile) {
        String name = spFile.getName().toLowerCase(Locale.ROOT);
        if (name.contains("zzz") || name.contains("patch")) {
            return true;
        }
        try (FileInputStream fis = new FileInputStream(spFile);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = fis.read(buf)) != -1) {
                baos.write(buf, 0, len);
                if (baos.size() > 256 * 1024) break;
            }
            String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            return content.contains(KEY_INCLUDE_RISK) || content.contains("zzz_");
        } catch (Throwable ignored) {}
        return false;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static void scheduleRestartAndKillSelf(Context context) {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) {
                    flags |= 0x04000000;
                }
                PendingIntent pi = PendingIntent.getActivity(context, 9527, launchIntent, flags);
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (am != null) {
                    am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 450, pi);
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "调度重启异常: " + t.getMessage());
        }

        stopAllSelfServices(context);
        killOtherProcesses(context);
        Process.killProcess(Process.myPid());
        System.exit(0);
    }

    private static boolean isIncludeRiskEnabled() {
        return !"0".equals(ConfigManager.getString(KEY_INCLUDE_RISK, "1"));
    }

    private static String resolveCurrentUin(Context context) {
        try {
            String uin = MsgSender.getMyUin();
            if (uin != null && !uin.trim().isEmpty() && !"0".equals(uin.trim())) {
                return uin.trim();
            }
        } catch (Throwable ignored) {}

        try {
            File userDir = new File(context.getFilesDir(), "user");
            if (userDir.exists() && userDir.isDirectory()) {
                File[] files = userDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        Matcher m = UIN_FILE_PATTERN.matcher(f.getName());
                        if (m.matches()) {
                            return m.group(1);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static File getBackupDir(Context context) {
        File mediaDir = null;
        if (context != null) {
            try {
                File[] mediaDirs = context.getExternalMediaDirs();
                if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                    mediaDir = mediaDirs[0];
                }
            } catch (Throwable ignored) {}
            if (mediaDir == null) {
                mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/" + context.getPackageName());
            }
        } else {
            mediaDir = new File(Environment.getExternalStorageDirectory(), "Android/media/com.tencent.mobileqq");
        }
        File dir = new File(new File(mediaDir, "zzz"), "accounts");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static List<File> listBackupZips(File backupDir) {
        File[] files = backupDir.listFiles();
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> list = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip") && !f.getName().startsWith(".")) {
                list.add(f);
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
    }
}