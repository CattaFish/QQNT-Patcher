package com.tencent.qqnt.patch.modules.misc;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class AccountBackupModule implements IPatchModule {

    private static final String TAG = "AccountBackup";
    public static final String FEATURE_ID = "account_backup";

    private static final String KEY_INCLUDE_RISK = "zzz_account_backup_include_risk";
    private static final String KEY_OBFUSCATE_ENABLED = "zzz_account_backup_obfuscate";
    private static final String KEY_OBFUSCATE_SECRET = "zzz_account_backup_obf_secret";

    private static final String DEFAULT_SECRET = "QQNT_Patcher_AccountBackup_2026";
    private static final byte[] MAGIC_ZZB1 = new byte[]{'Z', 'Z', 'B', '1'};
    private static final byte MODE_BYTE_AES = 0x01;

    private static final Pattern UIN_FILE_PATTERN = Pattern.compile("^u_(\\d+)_t$");

    @Override
    public String getId() {
        return FEATURE_ID;
    }

    @Override
    public String getName() {
        return "账号备份管理";
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
    public boolean showInSettings() {
        return false;
    }

    @Override
    public boolean hasConfig() {
        return false;
    }

    @Override
    public List<Object> getSubSettingItems(ClassLoader cl, Activity activity, Runnable onRefresh) {
        return Collections.emptyList();
    }

    public static Object createSettingView(ClassLoader cl, Activity activity, Runnable onRefresh) {
        Context ctx = activity != null ? activity.getApplicationContext() : AppContext.get();
        int count = ctx != null ? listBackupZips(getBackupDir(ctx)).size() : 0;
        return NativeSettingHelper.createClickable(
                cl, "账号备份管理", count + " 个备份", true, false,
                v -> showMainDialog(activity, onRefresh)
        );
    }

    public static Object createObfuscateConfigView(ClassLoader cl, Activity activity, Runnable onRefresh) {
        if (!isObfuscateEnabled()) {
            return null;
        }
        String keyText = getCustomSecret().isEmpty() ? "默认" : "已自定义";
        return NativeSettingHelper.createClickable(
                cl, "自定义加密密钥", keyText, true, false,
                v -> showObfuscateConfigDialog(activity, onRefresh)
        );
    }

    public static boolean isObfuscateEnabled() {
        return !"0".equals(ConfigManager.getString(KEY_OBFUSCATE_ENABLED, "1"));
    }

    public static void setObfuscateEnabled(boolean enabled) {
        ConfigManager.setString(KEY_OBFUSCATE_ENABLED, enabled ? "1" : "0");
    }

    private static String getCustomSecret() {
        return ConfigManager.getString(KEY_OBFUSCATE_SECRET, "").trim();
    }

    private static String getActiveSecret() {
        String custom = getCustomSecret();
        return custom.isEmpty() ? DEFAULT_SECRET : custom;
    }

    public static void showObfuscateConfigDialog(Activity activity, Runnable onRefresh) {
        if (activity == null || activity.isFinishing()) return;

        SettingDialogBuilder dlg = SettingDialogBuilder.build(activity, "自定义加密密钥");
        dlg.setSubTitle("加密方式: AES-256");
        dlg.addInput("密钥 (留空使用内置默认)", getCustomSecret(), true, false, null);

        dlg.setPositiveButton("保存", d -> {
            String newSecret = dlg.getInputValue(0).trim();
            ConfigManager.setString(KEY_OBFUSCATE_SECRET, newSecret);
            d.dismiss();
            ToastHelper.show(activity, newSecret.isEmpty() ? "已恢复默认密钥" : "已保存自定义密钥");
            if (onRefresh != null) onRefresh.run();
        });

        dlg.setNegativeButton("取消", null);
        dlg.show();
    }

    public static void showMainDialog(Activity activity, Runnable onRefresh) {
        if (activity == null || activity.isFinishing()) return;

        Context context = activity.getApplicationContext();
        File backupDir = getBackupDir(context);
        String dirPath = backupDir.getAbsolutePath();
        List<File> zipFiles = listBackupZips(backupDir);
        String uin = resolveCurrentUin(context);

        SettingDialogBuilder dlg = SettingDialogBuilder.build(activity, "账号备份管理");
        if (!uin.isEmpty()) {
            dlg.setSubTitle("当前账号: " + uin);
        }

        dlg.addTip("目录: " + dirPath + " (点击复制)", v -> copyToClipboard(activity, dirPath));

        if (zipFiles.isEmpty()) {
            dlg.addTip("暂无备份", null);
            dlg.setPositiveButton("新建备份", d -> {
                d.dismiss();
                showBackupDialog(activity, onRefresh);
            });
            dlg.setNegativeButton("关闭", null);
            dlg.show();
            return;
        }

        final int[] selectedIdx = new int[]{0};
        final boolean[] clearAllData = new boolean[]{false};

        List<SettingDialogBuilder.OptionCard> cards = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (File f : zipFiles) {
            String uinInfo = inspectBackupUinsSummary(f);
            String desc = (uinInfo.isEmpty() ? "" : uinInfo + "  ")
                    + formatFileSize(f.length()) + "  "
                    + sdf.format(new Date(f.lastModified()));
            cards.add(new SettingDialogBuilder.OptionCard(f.getName(), desc));
        }

        dlg.addRadioCards(cards, 0, index -> selectedIdx[0] = index);

        dlg.addSwitch(
                "清空应用数据",
                "同步异常或需彻底重置时开启",
                false,
                checked -> clearAllData[0] = checked
        );

        final Dialog[] mainDialogRef = new Dialog[1];
        dlg.addCustomView(buildActionRow(
                activity,
                dlg,
                v -> {
                    if (mainDialogRef[0] != null) mainDialogRef[0].dismiss();
                    showBackupDialog(activity, onRefresh);
                },
                v -> {
                    int idx = selectedIdx[0];
                    if (idx >= 0 && idx < zipFiles.size()) {
                        File target = zipFiles.get(idx);
                        if (mainDialogRef[0] != null) mainDialogRef[0].dismiss();
                        showDeleteConfirmDialog(activity, target, onRefresh);
                    }
                }
        ));

        dlg.setPositiveButton("恢复", d -> {
            int idx = selectedIdx[0];
            if (idx >= 0 && idx < zipFiles.size()) {
                File target = zipFiles.get(idx);
                boolean shouldClearAll = clearAllData[0];
                d.dismiss();
                showRestoreConfirmDialog(activity, context, target, shouldClearAll, zipFiles, uin, onRefresh);
            }
        });

        dlg.setNegativeButton("关闭", null);
        mainDialogRef[0] = dlg.show();
    }

    private static View buildActionRow(
            Activity activity,
            SettingDialogBuilder dlg,
            View.OnClickListener onNewBackup,
            View.OnClickListener onDeleteSelected
    ) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = SettingDialogBuilder.dp2px(activity, 8);
        row.setLayoutParams(rowLp);

        TextView btnNew = createCardButton(activity, dlg, "新建备份", dlg.accentColor, onNewBackup);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.rightMargin = SettingDialogBuilder.dp2px(activity, 6);
        row.addView(btnNew, leftLp);

        TextView btnDel = createCardButton(activity, dlg, "删除选中", dlg.dangerColor, onDeleteSelected);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.leftMargin = SettingDialogBuilder.dp2px(activity, 6);
        row.addView(btnDel, rightLp);

        return row;
    }

    private static TextView createCardButton(
            Activity activity,
            SettingDialogBuilder dlg,
            String text,
            int textColor,
            View.OnClickListener listener
    ) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        int padV = SettingDialogBuilder.dp2px(activity, 11);
        int padH = SettingDialogBuilder.dp2px(activity, 8);
        tv.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dlg.cardBgColor);
        bg.setCornerRadius(SettingDialogBuilder.dp2px(activity, 10));
        bg.setStroke(SettingDialogBuilder.dp2px(activity, 1), dlg.strokeColor);
        tv.setBackground(bg);

        tv.setOnClickListener(listener);
        return tv;
    }

    private static void showBackupDialog(Activity activity, Runnable onRefresh) {
        Context context = activity.getApplicationContext();
        String uin = resolveCurrentUin(context);
        File backupDir = getBackupDir(context);
        String dirPath = backupDir.getAbsolutePath();
        String timeSuffix = new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date());
        String defaultName = (uin.isEmpty() ? "qq" : uin) + "_" + timeSuffix;

        SettingDialogBuilder dlg = SettingDialogBuilder.build(activity, "新建备份");
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

        dlg.setPositiveButton("保存", d -> {
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
                        showMainDialog(activity, onRefresh);
                    } else {
                        ToastHelper.show(activity, "备份失败: " + err);
                    }
                });
            }).start();
        });

        dlg.setNegativeButton("取消", d -> {
            d.dismiss();
            showMainDialog(activity, onRefresh);
        });
        dlg.show();
    }

    private static void showDeleteConfirmDialog(Activity activity, File zipFile, Runnable onRefresh) {
        SettingDialogBuilder dlg = SettingDialogBuilder.build(activity, "删除备份");
        dlg.setSubTitle("确定删除 " + zipFile.getName() + "？");

        dlg.setPositiveButton("删除", d -> {
            boolean ok = zipFile.delete();
            ToastHelper.show(activity, ok ? "已删除" : "删除失败");
            d.dismiss();
            if (onRefresh != null) onRefresh.run();
            showMainDialog(activity, onRefresh);
        });

        dlg.setNegativeButton("取消", d -> {
            d.dismiss();
            showMainDialog(activity, onRefresh);
        });
        dlg.show();
    }

    private static void showRestoreConfirmDialog(
            Activity activity,
            Context context,
            File zipFile,
            boolean shouldClearAll,
            List<File> allZips,
            String currentUin,
            Runnable onRefresh
    ) {
        SettingDialogBuilder dlg = SettingDialogBuilder.build(activity, "确认恢复");
        dlg.setSubTitle("恢复 " + zipFile.getName() + (shouldClearAll ? " (并清空数据)" : "") + " 并重启 QQ？");

        final Dialog[] confirmDlgRef = new Dialog[1];
        if (!currentUin.isEmpty() && !hasAnyBackupForUin(allZips, currentUin)) {
            dlg.addNoticeBanner(
                    "当前账号尚未备份",
                    "账号 " + currentUin + " 无本地备份，继续恢复将覆盖登录状态",
                    "立即备份",
                    v -> {
                        if (confirmDlgRef[0] != null) confirmDlgRef[0].dismiss();
                        showBackupDialog(activity, onRefresh);
                    }
            );
        }

        dlg.setPositiveButton("确定", d -> {
            d.dismiss();
            if (shouldClearAll) {
                ConfigManager.setString(KEY_INCLUDE_RISK, isIncludeRiskEnabled() ? "1" : "0");
            }
            ToastHelper.show(activity, "正在恢复并重启...");
            new Thread(() -> {
                String err = restoreFromZipAndRestart(context, zipFile, shouldClearAll);
                if (err != null) {
                    AppContext.runOnUIThread(() -> ToastHelper.show(activity, "恢复失败: " + err));
                }
            }).start();
        });

        dlg.setNegativeButton("取消", d -> {
            d.dismiss();
            showMainDialog(activity, onRefresh);
        });
        confirmDlgRef[0] = dlg.show();
    }

    private static boolean hasAnyBackupForUin(List<File> allZips, String targetUin) {
        if (allZips == null || targetUin == null || targetUin.isEmpty()) return true;
        String marker = "user/u_" + targetUin + "_t";
        for (File f : allZips) {
            try {
                byte[] zipBytes = decodePackageToPlainZip(f);
                if (zipBytes == null) continue;
                try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String n = entry.getName();
                        if (n != null && n.contains(marker)) {
                            return true;
                        }
                        zis.closeEntry();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static String inspectBackupUinsSummary(File f) {
        try {
            byte[] zipBytes = decodePackageToPlainZip(f);
            if (zipBytes == null) {
                return "密钥不匹配";
            }
            Set<String> uins = new LinkedHashSet<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String n = entry.getName();
                    if (n != null) {
                        int slash = n.lastIndexOf('/');
                        String base = slash >= 0 ? n.substring(slash + 1) : n;
                        Matcher m = UIN_FILE_PATTERN.matcher(base);
                        if (m.matches()) {
                            uins.add(m.group(1));
                        }
                    }
                    zis.closeEntry();
                }
            }
            if (uins.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String u : uins) {
                if (sb.length() > 0) sb.append(",");
                sb.append(u);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
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

        File tmpFile = new File(outZip.getParentFile(), outZip.getName() + ".tmp");
        try {
            ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(zipBaos))) {
                for (File f : targets) {
                    addFileOrDirToZip(zos, dataRoot, f);
                }
            }
            byte[] plainZip = zipBaos.toByteArray();
            byte[] outputBytes = isObfuscateEnabled() ? encodeZipBytes(plainZip) : plainZip;

            if (tmpFile.exists()) tmpFile.delete();
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(outputBytes);
            }
            if (outZip.exists()) outZip.delete();
            if (!tmpFile.renameTo(outZip)) return "保存失败";
            PLog.i(TAG, "备份成功(encrypted=" + isObfuscateEnabled() + "): " + outZip.getAbsolutePath());
            return null;
        } catch (Throwable t) {
            PLog.e(TAG, "备份异常", t);
            if (tmpFile.exists()) tmpFile.delete();
            return t.getMessage();
        }
    }

    private static byte[] encodeZipBytes(byte[] plainZip) throws Exception {
        String secret = getActiveSecret();
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        byte[] keyBytes = sha256(secret.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plainZip);

        ByteArrayOutputStream out = new ByteArrayOutputStream(4 + 1 + 16 + encrypted.length);
        out.write(MAGIC_ZZB1);
        out.write(MODE_BYTE_AES);
        out.write(iv);
        out.write(encrypted);
        return out.toByteArray();
    }

    private static byte[] decodePackageToPlainZip(File file) {
        try {
            byte[] raw = readAllBytes(file);
            if (raw.length < 4) return null;

            if (isZipHeader(raw)) {
                return raw;
            }

            if (raw.length > 21
                    && raw[0] == MAGIC_ZZB1[0]
                    && raw[1] == MAGIC_ZZB1[1]
                    && raw[2] == MAGIC_ZZB1[2]
                    && raw[3] == MAGIC_ZZB1[3]
                    && raw[4] == MODE_BYTE_AES) {
                byte[] iv = new byte[16];
                System.arraycopy(raw, 5, iv, 0, 16);
                byte[] payload = new byte[raw.length - 21];
                System.arraycopy(raw, 21, payload, 0, payload.length);

                String activeSecret = getActiveSecret();
                byte[] decoded = tryDecryptAes(iv, payload, activeSecret);
                if (decoded != null && isZipHeader(decoded)) {
                    return decoded;
                }

                if (!DEFAULT_SECRET.equals(activeSecret)) {
                    decoded = tryDecryptAes(iv, payload, DEFAULT_SECRET);
                    if (decoded != null && isZipHeader(decoded)) {
                        return decoded;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] tryDecryptAes(byte[] iv, byte[] payload, String secret) {
        try {
            byte[] keyBytes = sha256(secret.getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(payload);
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] sha256(byte[] input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(input);
    }

    private static boolean isZipHeader(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    private static byte[] readAllBytes(File file) throws Exception {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = bis.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            return baos.toByteArray();
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

    private static String restoreFromZipAndRestart(Context context, File zipFile, boolean clearAllData) {
        if (!zipFile.exists() || !zipFile.isFile()) return "文件不存在";

        byte[] plainZip = decodePackageToPlainZip(zipFile);
        if (plainZip == null) return "加密密钥不匹配或备份已损坏";
        if (!validatePlainZip(plainZip)) return "无效的备份压缩包";

        File filesDir = context.getFilesDir();
        if (filesDir == null) return "files 目录异常";
        File dataRoot = filesDir.getParentFile();
        if (dataRoot == null) return "数据根目录异常";

        try {
            stopAllSelfServices(context);
            killOtherProcesses(context);

            if (clearAllData) {
                cleanAllQQData(filesDir, dataRoot);
            } else {
                cleanAuthDataOnly(filesDir, dataRoot);
            }
            if (!filesDir.exists()) filesDir.mkdirs();

            byte[] buf = new byte[8192];
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(plainZip))) {
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

            PLog.i(TAG, "恢复完成(clearAll=" + clearAllData + ")，重启应用: " + zipFile.getName());
            scheduleRestartAndKillAll(context);
            return null;
        } catch (Throwable t) {
            PLog.e(TAG, "恢复异常", t);
            return t.getMessage();
        }
    }

    private static boolean validatePlainZip(byte[] plainZip) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(plainZip))) {
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
            String pkg = context.getPackageName();
            List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(200);
            if (services == null) return;
            for (ActivityManager.RunningServiceInfo rsi : services) {
                boolean matchUid = (rsi.uid == myUid);
                boolean matchPkg = (rsi.service != null && pkg.equals(rsi.service.getPackageName()));
                if ((matchUid || matchPkg) && rsi.service != null) {
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
            String pkg = context.getPackageName();
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pid == myPid) continue;
                boolean sameUid = (p.uid == myUid);
                boolean samePkg = (p.processName != null && p.processName.startsWith(pkg));
                if (sameUid || samePkg) {
                    try {
                        Process.sendSignal(p.pid, Process.SIGNAL_KILL);
                        Process.killProcess(p.pid);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "清理子进程异常: " + t.getMessage());
        }
    }

    private static void cleanAuthDataOnly(File filesDir, File dataRoot) {
        String[] dirsToClear = new String[]{
                "nt_wtlogin", "user", "uid", "qm", "5463306EE50FE3AA",
                "com.tencent.qimei.sdk.QimeiSDK", "imei", "wlogin_device.dat",
                "nt_openid", "kc", "msfCore"
        };
        for (String name : dirsToClear) {
            deleteRecursively(new File(filesDir, name));
        }

        File[] rootFiles = filesDir.listFiles();
        if (rootFiles != null) {
            for (File f : rootFiles) {
                if (f.getName().startsWith("com.tencent.mobileqq_")) {
                    deleteRecursively(f);
                }
            }
        }

        File mmkvDir = new File(filesDir, "mmkv");
        if (mmkvDir.exists() && mmkvDir.isDirectory()) {
            File[] mmkvFiles = mmkvDir.listFiles();
            if (mmkvFiles != null) {
                for (File mf : mmkvFiles) {
                    String n = mf.getName();
                    if (n.startsWith("msf_mmkv_file")
                            || n.startsWith("wtlogin_mmkv_file")
                            || n.startsWith("qq_uin_uid_map")
                            || n.startsWith("qsec_mmkv_configurations")) {
                        mf.delete();
                    }
                }
            }
        }

        File dbDir = new File(dataRoot, "databases");
        if (dbDir.exists() && dbDir.isDirectory()) {
            File[] dbFiles = dbDir.listFiles();
            if (dbFiles != null) {
                for (File df : dbFiles) {
                    String n = df.getName();
                    if (n.startsWith("tk_file")
                            || n.startsWith("name_file")
                            || n.matches("^\\d+\\.db.*$")) {
                        df.delete();
                    }
                }
            }
        }
    }

    private static void cleanAllQQData(File filesDir, File dataRoot) {
        File[] filesChildren = filesDir.listFiles();
        if (filesChildren != null) {
            for (File child : filesChildren) {
                deleteRecursively(child);
            }
        }

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

    private static void scheduleRestartAndKillAll(Context context) {
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
                    long triggerAt = SystemClock.elapsedRealtime() + 250;
                    try {
                        if (Build.VERSION.SDK_INT >= 23) {
                            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                        } else if (Build.VERSION.SDK_INT >= 19) {
                            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                        } else {
                            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                        }
                    } catch (Throwable ignored) {
                        am.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pi);
                    }
                }
            }
        } catch (Throwable t) {
            PLog.w(TAG, "调度重启异常: " + t.getMessage());
        }

        stopAllSelfServices(context);
        killOtherProcesses(context);

        int myPid = Process.myPid();
        Process.sendSignal(myPid, Process.SIGNAL_KILL);
        Process.killProcess(myPid);
        Runtime.getRuntime().halt(0);
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